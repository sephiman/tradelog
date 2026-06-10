// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.common.Usdt
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.Position
import com.sephilabs.tradelog.position.PositionFill
import com.sephilabs.tradelog.position.PositionFillRepository
import com.sephilabs.tradelog.position.PositionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class UpsertCounts(val inserted: Int, val updated: Int)

/**
 * Maps canonical [PositionRecord]s to [Position] entities and upserts them idempotently, keyed by
 * (data_source_id, external_id). Re-running overlapping windows never duplicates. User annotations
 * (note + tags) are preserved across re-syncs; only source-derived fields are overwritten.
 */
@Service
class PositionUpsertService(
    private val positions: PositionRepository,
    private val fills: PositionFillRepository,
) {

    @Transactional
    fun upsert(
        dataSourceId: UUID,
        profileId: UUID,
        source: SourceKind,
        sourceLabel: String,
        records: List<PositionRecord>,
    ): UpsertCounts {
        var inserted = 0
        var updated = 0
        for (r in records) {
            val exchange = resolveExchange(r, source, sourceLabel)
            val existing = positions.findByDataSourceIdAndExternalId(dataSourceId, r.externalId)
            // A soft-deleted position stays deleted: skip it so re-sync never resurrects what the user removed.
            if (existing != null && existing.deletedAt != null) continue
            val position = if (existing != null) {
                applySourceFields(existing, r, exchange); updated++; existing
            } else {
                Position(
                    profileId = profileId,
                    dataSourceId = dataSourceId,
                    source = source,
                    externalId = r.externalId,
                    symbolBase = r.symbol.base,
                    symbolQuote = r.symbol.quote,
                    side = r.side,
                    openedAt = r.openedAt,
                    closedAt = r.closedAt,
                    qty = r.qty,
                    entryPrice = r.entryPrice,
                    exitPrice = r.exitPrice,
                    realizedPnl = Usdt.normalize(r.realizedPnl),
                    netPnl = netPnl(r),
                    fees = Usdt.normalize(r.fees),
                    funding = Usdt.normalize(r.funding),
                    pnlCurrency = r.pnlCurrency,
                    raw = r.raw,
                    note = r.note?.trim()?.takeIf { it.isNotEmpty() },
                    exchange = exchange,
                ).also { positions.save(it); inserted++ }
            }
            replaceFills(position.id, r)
        }
        return UpsertCounts(inserted, updated)
    }

    /**
     * The venue is the source itself for the live connectors; for a Journal CSV it is the row's
     * supplied exchange, falling back to the data source label when the row leaves it blank.
     */
    private fun resolveExchange(r: PositionRecord, source: SourceKind, sourceLabel: String): String? =
        r.exchange?.trim()?.takeIf { it.isNotEmpty() }
            ?: source.venueLabel
            ?: sourceLabel.trim().takeIf { it.isNotEmpty() }?.take(64)

    private fun applySourceFields(p: Position, r: PositionRecord, exchange: String?) {
        p.exchange = exchange
        p.symbolBase = r.symbol.base
        p.symbolQuote = r.symbol.quote
        p.side = r.side
        p.openedAt = r.openedAt
        p.closedAt = r.closedAt
        p.qty = r.qty
        p.entryPrice = r.entryPrice
        p.exitPrice = r.exitPrice
        p.realizedPnl = Usdt.normalize(r.realizedPnl)
        p.netPnl = netPnl(r)
        p.fees = Usdt.normalize(r.fees)
        p.funding = Usdt.normalize(r.funding)
        p.pnlCurrency = r.pnlCurrency
        p.raw = r.raw
        // note + tags are intentionally left untouched.
    }

    /**
     * Net profit, the single source of truth for "what was kept": gross realized PnL − fees − funding.
     * Connectors always supply [PositionRecord.realizedPnl] as GROSS (Bitunix backs it out from its
     * native net), so this reproduces each source's true net exactly.
     */
    private fun netPnl(r: PositionRecord): BigDecimal =
        Usdt.normalize(r.realizedPnl.subtract(r.fees).subtract(r.funding))

    private fun replaceFills(positionId: UUID, r: PositionRecord) {
        fills.deleteByPositionId(positionId)
        if (r.fills.isEmpty()) return
        for (f in r.fills) {
            fills.save(
                PositionFill(
                    positionId = positionId,
                    seq = f.seq,
                    action = f.action,
                    side = f.side,
                    ts = f.ts,
                    price = f.price,
                    qty = f.qty,
                    value = f.value?.let { Usdt.normalize(it) },
                    fee = f.fee?.let { Usdt.normalize(it) },
                )
            )
        }
    }
}
