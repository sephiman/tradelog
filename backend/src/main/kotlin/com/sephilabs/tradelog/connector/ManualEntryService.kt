// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.Position
import com.sephilabs.tradelog.position.PositionDto
import com.sephilabs.tradelog.position.PositionKind
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

/**
 * What every hand-entered record carries, whatever kind it is. Both shapes map themselves to a
 * canonical [PositionRecord], so the service below never branches on the kind.
 */
sealed interface ManualEntry {
    val symbol: String
    val openedAt: Instant
    val closedAt: Instant
    val exchange: String?
    val note: String?
    val tagId: UUID?
    val tagGroupId: UUID?
    val kind: PositionKind

    fun toRecord(externalId: String): PositionRecord
}

/** One hand-entered closed trade — the Journal CSV's columns as a form, for what no API serves. */
data class ManualPositionRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 40, message = "validation.too.long")
    override val symbol: String,
    val side: PositionSide,
    override val openedAt: Instant,
    override val closedAt: Instant,
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
    override val exchange: String? = null,
    @field:Size(max = 4000, message = "validation.too.long")
    override val note: String? = null,
    /** Applied on insert, so a trade is marked with its origen without a second trip. */
    override val tagId: UUID? = null,
    override val tagGroupId: UUID? = null,
) : ManualEntry {

    override val kind get() = PositionKind.TRADE

    override fun toRecord(externalId: String) = PositionRecord(
        externalId = externalId,
        symbol = Symbols.split(symbol),
        side = side,
        openedAt = openedAt,
        closedAt = closedAt,
        qty = qty,
        entryPrice = entryPrice,
        exitPrice = exitPrice,
        realizedPnl = realizedPnl ?: PositionReconstructor.realizedFromPrices(side, entryPrice, exitPrice, qty),
        fees = fees,
        funding = funding,
        exchange = exchange?.trim()?.takeIf { it.isNotEmpty() },
        note = note,
        raw = RAW_MANUAL,
    )
}

/** Which figure of the exchange's closed-grid screen the user typed. */
enum class GridPnlBasis { NET, GROSS }

/**
 * One hand-entered grid-bot run: hundreds of matched orders with no single entry price, exit price
 * or quantity, so those stay null and the run reports its result directly — whichever of the two
 * figures the venue showed ("Realized PnL" is gross, "Total profit" is already net).
 */
data class GridRunRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 40, message = "validation.too.long")
    override val symbol: String,
    val side: PositionSide,
    /** Required, unlike on a trade: with no leg prices the venue is all that anchors capital and ROI. */
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 64, message = "validation.too.long")
    override val exchange: String,
    override val openedAt: Instant,
    override val closedAt: Instant,
    val pnl: BigDecimal,
    val pnlBasis: GridPnlBasis = GridPnlBasis.NET,
    val fees: BigDecimal = BigDecimal.ZERO,
    val funding: BigDecimal = BigDecimal.ZERO,
    /** Traded notional, both legs. Null when the volume calculator was left empty — no fake number. */
    @field:PositiveOrZero(message = "validation.invalid")
    val volume: BigDecimal? = null,
    @field:Positive(message = "validation.invalid")
    val leverage: BigDecimal? = null,
    @field:Positive(message = "validation.invalid")
    val investment: BigDecimal? = null,
    @field:Size(max = 4000, message = "validation.too.long")
    override val note: String? = null,
    override val tagId: UUID? = null,
    override val tagGroupId: UUID? = null,
) : ManualEntry {

    override val kind get() = PositionKind.GRID_BOT

    override fun toRecord(externalId: String) = PositionRecord(
        externalId = externalId,
        symbol = Symbols.split(symbol),
        side = side,
        openedAt = openedAt,
        closedAt = closedAt,
        kind = PositionKind.GRID_BOT,
        // Stored gross, always: the upsert takes the costs back off for net, so whichever figure the
        // user typed, both readings of the run agree afterwards.
        realizedPnl = if (pnlBasis == GridPnlBasis.GROSS) pnl else pnl.add(fees).add(funding),
        fees = fees,
        funding = funding,
        volume = volume,
        leverage = leverage,
        investment = investment,
        exchange = exchange.trim(),
        note = note,
        raw = RAW_GRID,
    )
}

/** Manual-entry counterpart of [FileImportService]: user input in, canonical record out, same sync pipeline. */
@Service
class ManualEntryService(
    private val dataSources: DataSourceRepository,
    private val positions: PositionRepository,
    private val syncService: SyncService,
    private val positionService: PositionService,
) {

    fun create(profileId: UUID, userId: UUID, entry: ManualEntry): PositionDto {
        validate(entry)
        val ds = manualSource(profileId)

        // Random id, never a natural key: two genuinely identical entries must both be kept, and a
        // double-submitted form is visible in the list and deletable, unlike a silent update.
        val record = entry.toRecord("${Position.MANUAL_EXTERNAL_ID_PREFIX}${UUID.randomUUID()}")
        store(ds, record)
        val saved = positions.findByDataSourceIdAndExternalId(ds.id, record.externalId)
            ?: throw AppException.badRequest("IMPORT_FAILED")

        applyTag(userId, profileId, saved.id, entry)
        return positionService.get(profileId, saved.id).position
    }

    /**
     * Rewrites a hand-entered record in place. Only those: every other row is re-derived from its
     * source, so an edit would be silently reverted by the next sync or re-import.
     */
    fun update(profileId: UUID, userId: UUID, positionId: UUID, entry: ManualEntry): PositionDto {
        val existing = positions.findByIdAndProfileIdAndDeletedAtIsNull(positionId, profileId)
            ?: throw AppException.notFound("POSITION_NOT_FOUND")
        if (!existing.isManual) throw AppException.badRequest("POSITION_NOT_MANUAL")
        // A trade and a grid run carry different fields; the wrong form would blank half of them.
        if (existing.kind != entry.kind) throw AppException.badRequest("POSITION_KIND_MISMATCH")
        val ds = dataSources.findByIdAndProfileId(existing.dataSourceId, profileId)
            ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")
        validate(entry)

        // Keeping the external id makes this the upsert's update path, so the money math, the venue
        // resolution and the capital refresh are the same code that writes every other trade.
        store(ds, entry.toRecord(existing.externalId))
        // The shared upsert deliberately never touches notes; on a hand-entered row it is editable.
        positionService.updateNote(profileId, positionId, entry.note)
        applyTag(userId, profileId, positionId, entry)
        return positionService.get(profileId, positionId).position
    }

    /**
     * Where hand-entered records live: the profile's own Journal CSV source, created on first use.
     * Never asked for — the venue that matters for capital and ROI is the entry's `exchange`, and a
     * Journal CSV source is the one lane no sync or re-import can overwrite.
     */
    private fun manualSource(profileId: UUID): DataSource =
        dataSources.findAllByProfileIdOrderByCreatedAtAsc(profileId).firstOrNull { it.kind == SourceKind.JOURNAL_CSV }
            ?: dataSources.save(DataSource(profileId = profileId, kind = SourceKind.JOURNAL_CSV, label = DEFAULT_LABEL))

    /** Goes through the import pipeline so a hand-entered record gets the same upsert, audit run and capital refresh a file import does. */
    private fun store(ds: DataSource, record: PositionRecord) {
        val run = syncService.importFile(ds, listOf(record), SyncTrigger.MANUAL)
        if (run.status != RunStatus.SUCCESS) throw AppException.badRequest(run.errorCode ?: "IMPORT_FAILED")
    }

    private fun validate(entry: ManualEntry) {
        if (entry.closedAt.isBefore(entry.openedAt)) throw AppException.badRequest("MANUAL_CLOSED_BEFORE_OPENED")
        if (entry.tagId != null && entry.tagGroupId == null) throw AppException.badRequest("TAG_GROUP_MISMATCH")
    }

    /** A null [ManualEntry.tagId] with a group clears that group, which is how an edit removes a tag. */
    private fun applyTag(userId: UUID, profileId: UUID, positionId: UUID, entry: ManualEntry) {
        val groupId = entry.tagGroupId ?: return
        val tagId = entry.tagId
        if (tagId != null) positionService.setTag(userId, profileId, positionId, groupId, tagId)
        else positionService.clearTag(profileId, positionId, groupId)
    }

    private companion object {
        /** Label of the source created on first use; the user is free to rename it afterwards. */
        const val DEFAULT_LABEL = "Manual"
    }
}

/** Keeps hand-entered rows distinguishable from the CSV rows sharing their data source. */
private const val RAW_MANUAL = """{"entry":"manual"}"""
private const val RAW_GRID = """{"entry":"grid"}"""
