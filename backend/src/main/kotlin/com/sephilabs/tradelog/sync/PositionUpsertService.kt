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
    fun upsert(dataSourceId: UUID, profileId: UUID, source: SourceKind, records: List<PositionRecord>): UpsertCounts {
        var inserted = 0
        var updated = 0
        for (r in records) {
            val existing = positions.findByDataSourceIdAndExternalId(dataSourceId, r.externalId)
            val position = if (existing != null) {
                applySourceFields(existing, r); updated++; existing
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
                    fees = Usdt.normalize(r.fees),
                    funding = Usdt.normalize(r.funding),
                    pnlCurrency = r.pnlCurrency,
                    raw = r.raw,
                ).also { positions.save(it); inserted++ }
            }
            replaceFills(position.id, r)
        }
        return UpsertCounts(inserted, updated)
    }

    private fun applySourceFields(p: Position, r: PositionRecord) {
        p.symbolBase = r.symbol.base
        p.symbolQuote = r.symbol.quote
        p.side = r.side
        p.openedAt = r.openedAt
        p.closedAt = r.closedAt
        p.qty = r.qty
        p.entryPrice = r.entryPrice
        p.exitPrice = r.exitPrice
        p.realizedPnl = Usdt.normalize(r.realizedPnl)
        p.fees = Usdt.normalize(r.fees)
        p.funding = Usdt.normalize(r.funding)
        p.pnlCurrency = r.pnlCurrency
        p.raw = r.raw
        // note + tags are intentionally left untouched.
    }

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
