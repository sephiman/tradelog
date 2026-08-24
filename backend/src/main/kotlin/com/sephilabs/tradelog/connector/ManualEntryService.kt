// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.Position
import com.sephilabs.tradelog.position.PositionDto
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.position.PositionService
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.sync.RunStatus
import com.sephilabs.tradelog.sync.SyncService
import com.sephilabs.tradelog.sync.SyncTrigger
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** One hand-entered closed trade — the Journal CSV's columns as a form, for what no API serves (grid bots, dead venues). */
data class ManualPositionRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 40, message = "validation.too.long")
    val symbol: String,
    val side: PositionSide,
    val openedAt: Instant,
    val closedAt: Instant,
    @field:Positive(message = "validation.invalid")
    val qty: BigDecimal = BigDecimal.ONE,
    @field:Positive(message = "validation.invalid")
    val entryPrice: BigDecimal,
    @field:PositiveOrZero(message = "validation.invalid")
    val exitPrice: BigDecimal,
    /** Null means "derive from the leg prices", the same rule the CSV and Quantfury already follow. */
    val realizedPnl: BigDecimal? = null,
    // Signed: a fee rebate and received funding are both negative, and both raise the net.
    val fees: BigDecimal = BigDecimal.ZERO,
    val funding: BigDecimal = BigDecimal.ZERO,
    @field:Size(max = 64, message = "validation.too.long")
    val exchange: String? = null,
    @field:Size(max = 4000, message = "validation.too.long")
    val note: String? = null,
    /** Applied on insert, so a grid-bot run is marked as one without a second trip. */
    val tagId: UUID? = null,
    val tagGroupId: UUID? = null,
)

/** Manual-entry counterpart of [FileImportService]: user input in, canonical record out, same sync pipeline. */
@Service
class ManualEntryService(
    private val dataSources: DataSourceRepository,
    private val positions: PositionRepository,
    private val syncService: SyncService,
    private val positionService: PositionService,
) {

    fun create(profileId: UUID, userId: UUID, req: ManualPositionRequest): PositionDto {
        validate(req)
        val ds = manualSource(profileId)

        // Random id, never a natural key: two genuinely identical trades must both be kept, and a
        // double-submitted form is visible in the list and deletable, unlike a silent update.
        val record = toRecord(req, "${Position.MANUAL_EXTERNAL_ID_PREFIX}${UUID.randomUUID()}")
        store(ds, record)
        val saved = positions.findByDataSourceIdAndExternalId(ds.id, record.externalId)
            ?: throw AppException.badRequest("IMPORT_FAILED")

        applyTag(userId, profileId, saved.id, req)
        return positionService.get(profileId, saved.id).position
    }

    /**
     * Rewrites a hand-entered trade in place. Only those: every other row is re-derived from its
     * source, so an edit would be silently reverted by the next sync or re-import.
     */
    fun update(profileId: UUID, userId: UUID, positionId: UUID, req: ManualPositionRequest): PositionDto {
        val existing = positions.findByIdAndProfileIdAndDeletedAtIsNull(positionId, profileId)
            ?: throw AppException.notFound("POSITION_NOT_FOUND")
        if (!existing.isManual) throw AppException.badRequest("POSITION_NOT_MANUAL")
        val ds = dataSources.findByIdAndProfileId(existing.dataSourceId, profileId)
            ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")
        validate(req)

        // Keeping the external id makes this the upsert's update path, so the money math, the venue
        // resolution and the capital refresh are the same code that writes every other trade.
        store(ds, toRecord(req, existing.externalId))
        // The shared upsert deliberately never touches notes; on a hand-entered row it is editable.
        positionService.updateNote(profileId, positionId, req.note)
        applyTag(userId, profileId, positionId, req)
        return positionService.get(profileId, positionId).position
    }

    /**
     * Where hand-entered trades live: the profile's own Journal CSV source, created on first use.
     * Never asked for — the venue that matters for capital and ROI is the trade's `exchange`, and a
     * Journal CSV source is the one lane no sync or re-import can overwrite.
     */
    private fun manualSource(profileId: UUID): DataSource =
        dataSources.findAllByProfileIdOrderByCreatedAtAsc(profileId).firstOrNull { it.kind == SourceKind.JOURNAL_CSV }
            ?: dataSources.save(DataSource(profileId = profileId, kind = SourceKind.JOURNAL_CSV, label = DEFAULT_LABEL))

    /** Goes through the import pipeline so a hand-entered trade gets the same upsert, audit run and capital refresh a file import does. */
    private fun store(ds: DataSource, record: PositionRecord) {
        val run = syncService.importFile(ds, listOf(record), SyncTrigger.MANUAL)
        if (run.status != RunStatus.SUCCESS) throw AppException.badRequest(run.errorCode ?: "IMPORT_FAILED")
    }

    private fun validate(req: ManualPositionRequest) {
        if (req.closedAt.isBefore(req.openedAt)) throw AppException.badRequest("MANUAL_CLOSED_BEFORE_OPENED")
        if (req.tagId != null && req.tagGroupId == null) throw AppException.badRequest("TAG_GROUP_MISMATCH")
    }

    /** A null [ManualPositionRequest.tagId] with a group clears that group, which is how an edit removes a tag. */
    private fun applyTag(userId: UUID, profileId: UUID, positionId: UUID, req: ManualPositionRequest) {
        val groupId = req.tagGroupId ?: return
        if (req.tagId != null) positionService.setTag(userId, profileId, positionId, groupId, req.tagId)
        else positionService.clearTag(profileId, positionId, groupId)
    }

    private fun toRecord(req: ManualPositionRequest, externalId: String): PositionRecord {
        val symbol = Symbols.split(req.symbol)
        val realized = req.realizedPnl
            ?: PositionReconstructor.realizedFromPrices(req.side, req.entryPrice, req.exitPrice, req.qty)
        return PositionRecord(
            externalId = externalId,
            symbol = symbol,
            side = req.side,
            openedAt = req.openedAt,
            closedAt = req.closedAt,
            qty = req.qty,
            entryPrice = req.entryPrice,
            exitPrice = req.exitPrice,
            realizedPnl = realized,
            fees = req.fees,
            funding = req.funding,
            exchange = req.exchange?.trim()?.takeIf { it.isNotEmpty() },
            note = req.note,
            raw = RAW_MANUAL,
        )
    }

    private companion object {
        /** Label of the source created on first use; the user is free to rename it afterwards. */
        const val DEFAULT_LABEL = "Manual"

        /** Keeps hand-entered rows distinguishable from the CSV rows sharing their data source. */
        const val RAW_MANUAL = """{"entry":"manual"}"""
    }
}
