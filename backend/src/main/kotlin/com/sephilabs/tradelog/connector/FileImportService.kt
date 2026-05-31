// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.sync.SyncRunDto
import com.sephilabs.tradelog.sync.SyncService
import com.sephilabs.tradelog.sync.SyncTrigger
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class PreviewPositionDto(
    val symbol: String,
    val side: String,
    val openedAt: Instant,
    val closedAt: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val qty: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val realizedPnl: BigDecimal,
)

/** One parsed row implicated in a [ImportWarningDto], so the user can locate it in their file. */
data class WarningRowDto(
    /** 1-based source row (CSV line including header); null when the source has no row concept. */
    val row: Int?,
    val symbol: String,
    val side: String,
    val openedAt: Instant,
    val closedAt: Instant,
)

/** A non-fatal data-quality notice plus the exact rows that triggered it. */
data class ImportWarningDto(
    val code: String,
    val count: Int,
    val rows: List<WarningRowDto>,
)

data class FileImportPreviewDto(
    val totalPositions: Int,
    val dateFrom: Instant?,
    val dateTo: Instant?,
    /** Gross realized PnL (before fees), as stored on each position. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val sumRealizedPnl: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val sumFees: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val sumFunding: BigDecimal,
    /** Net = gross realized PnL − fees − funding. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val sumNetPnl: BigDecimal,
    val symbols: List<String>,
    val sample: List<PreviewPositionDto>,
    /** Non-fatal data-quality notices surfaced before the user commits the import. */
    val warnings: List<ImportWarningDto> = emptyList(),
)

/**
 * Two-phase (preview/execute) import for any [FileImportConnector] source (Quantfury PDF,
 * journal CSV). Source-neutral: it resolves the connector by the data source's [com.sephilabs.tradelog.datasource.SourceKind]
 * and never knows the file format itself.
 */
@Service
class FileImportService(
    private val dataSources: DataSourceRepository,
    private val registry: ConnectorRegistry,
    private val syncService: SyncService,
    private val positions: PositionRepository,
) {

    @Transactional(readOnly = true)
    fun preview(profileId: UUID, dataSourceId: UUID, input: InputStream): FileImportPreviewDto {
        val ds = loadFileSource(profileId, dataSourceId)
        val records = registry.file(ds.kind).parse(input)
        return summarize(records, profileId, dataSourceId)
    }

    fun execute(profileId: UUID, dataSourceId: UUID, input: InputStream): SyncRunDto {
        val ds = loadFileSource(profileId, dataSourceId)
        val records = registry.file(ds.kind).parse(input)
        return syncService.importFile(ds, records, SyncTrigger.UPLOAD)
    }

    private fun summarize(records: List<PositionRecord>, profileId: UUID, dataSourceId: UUID): FileImportPreviewDto {
        val symbols = records.map { "${it.symbol.base}/${it.symbol.quote}" }.distinct().sorted()
        val sumPnl = records.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realizedPnl) }
        val sumFees = records.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.fees) }
        val sumFunding = records.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.funding) }
        val backdated = records.filter { it.closedAt.isBefore(it.openedAt) }
        val existing = existingDayKeysFromOtherSources(profileId, dataSourceId)
        val overlaps = records.filter { dayKey(it.symbol.base, it.symbol.quote, it.side.name, it.openedAt, it.closedAt) in existing }
        val warnings = buildList {
            if (backdated.isNotEmpty()) add(warningOf("CLOSED_BEFORE_OPENED", backdated))
            if (overlaps.isNotEmpty()) add(warningOf("OVERLAP_SUSPECTED", overlaps))
        }
        return FileImportPreviewDto(
            totalPositions = records.size,
            dateFrom = records.minOfOrNull { it.openedAt },
            dateTo = records.maxOfOrNull { it.closedAt },
            sumRealizedPnl = sumPnl,
            sumFees = sumFees,
            sumFunding = sumFunding,
            sumNetPnl = sumPnl.subtract(sumFees).subtract(sumFunding),
            symbols = symbols,
            sample = records.sortedByDescending { it.closedAt }.take(SAMPLE_SIZE).map {
                PreviewPositionDto("${it.symbol.base}/${it.symbol.quote}", it.side.name, it.openedAt, it.closedAt, it.qty, it.realizedPnl)
            },
            warnings = warnings,
        )
    }

    private fun warningOf(code: String, rows: List<PositionRecord>) = ImportWarningDto(
        code = code,
        count = rows.size,
        rows = rows
            .sortedWith(compareBy({ it.sourceRow ?: Int.MAX_VALUE }, { it.closedAt }))
            .take(MAX_WARNING_ROWS)
            .map { WarningRowDto(it.sourceRow, "${it.symbol.base}/${it.symbol.quote}", it.side.name, it.openedAt, it.closedAt) },
    )

    /**
     * The UTC-day keys of positions already stored from *other* sources in this profile — same symbol,
     * side and open/close day. A heuristic only (the per-data-source external_id namespace means there is
     * never a real DB collision); lets the user spot rows that would double-count PnL when a journal
     * overlaps a live exchange's synced history.
     */
    private fun existingDayKeysFromOtherSources(profileId: UUID, dataSourceId: UUID): Set<String> =
        positions.findAllByProfileIdAndDataSourceIdNot(profileId, dataSourceId)
            .mapTo(HashSet()) { dayKey(it.symbolBase, it.symbolQuote, it.side.name, it.openedAt, it.closedAt) }

    private fun dayKey(base: String, quote: String, side: String, opened: Instant, closed: Instant): String {
        val o = opened.atZone(ZoneOffset.UTC).toLocalDate()
        val c = closed.atZone(ZoneOffset.UTC).toLocalDate()
        return "$base/$quote|$side|$o|$c"
    }

    private fun loadFileSource(profileId: UUID, dataSourceId: UUID) =
        dataSources.findByIdAndProfileId(dataSourceId, profileId)
            ?.also { if (it.kind.isApi) throw AppException.badRequest("DATA_SOURCE_NOT_FILE") }
            ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")

    private companion object {
        const val SAMPLE_SIZE = 25

        /** Cap rows listed per warning; the [ImportWarningDto.count] still reflects the true total. */
        const val MAX_WARNING_ROWS = 100
    }
}
