// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.ConnectorRegistry
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.sync.SyncRunDto
import com.sephilabs.tradelog.sync.SyncService
import com.sephilabs.tradelog.sync.SyncTrigger
import org.springframework.stereotype.Service
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PreviewPositionDto(
    val symbol: String,
    val side: String,
    val openedAt: Instant,
    val closedAt: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val qty: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val realizedPnl: BigDecimal,
)

data class QuantfuryPreviewDto(
    val totalPositions: Int,
    val dateFrom: Instant?,
    val dateTo: Instant?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val sumRealizedPnl: BigDecimal,
    val symbols: List<String>,
    val sample: List<PreviewPositionDto>,
)

/** Two-phase (preview/execute) import of the Quantfury PDF into a QUANTFURY data source. */
@Service
class QuantfuryImportService(
    private val dataSources: DataSourceRepository,
    private val registry: ConnectorRegistry,
    private val syncService: SyncService,
) {

    fun preview(profileId: UUID, dataSourceId: UUID, input: InputStream): QuantfuryPreviewDto {
        val ds = loadFileSource(profileId, dataSourceId)
        val records = registry.file(ds.kind).parse(input)
        return summarize(records)
    }

    fun execute(profileId: UUID, dataSourceId: UUID, input: InputStream): SyncRunDto {
        val ds = loadFileSource(profileId, dataSourceId)
        val records = registry.file(ds.kind).parse(input)
        return syncService.importFile(ds, records, SyncTrigger.UPLOAD)
    }

    private fun summarize(records: List<PositionRecord>): QuantfuryPreviewDto {
        val symbols = records.map { "${it.symbol.base}/${it.symbol.quote}" }.distinct().sorted()
        return QuantfuryPreviewDto(
            totalPositions = records.size,
            dateFrom = records.minOfOrNull { it.openedAt },
            dateTo = records.maxOfOrNull { it.closedAt },
            sumRealizedPnl = records.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realizedPnl) },
            symbols = symbols,
            sample = records.sortedByDescending { it.closedAt }.take(SAMPLE_SIZE).map {
                PreviewPositionDto("${it.symbol.base}/${it.symbol.quote}", it.side.name, it.openedAt, it.closedAt, it.qty, it.realizedPnl)
            },
        )
    }

    private fun loadFileSource(profileId: UUID, dataSourceId: UUID) =
        dataSources.findByIdAndProfileId(dataSourceId, profileId)
            ?.also { if (it.kind.isApi) throw AppException.badRequest("DATA_SOURCE_NOT_FILE") }
            ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")

    private companion object {
        const val SAMPLE_SIZE = 25
    }
}
