// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.sephilabs.tradelog.common.TimestampedEntity
import com.sephilabs.tradelog.datasource.SourceKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PositionSide { LONG, SHORT }

/**
 * What the row records. A [GRID_BOT] run is hundreds of matched orders with no single entry price,
 * exit price or quantity, so those are null on it and its PnL is supplied directly.
 */
enum class PositionKind { TRADE, GRID_BOT }

/**
 * A canonical, flat-to-flat closed position: from net exposure leaving zero until it returns to
 * zero. Scaling in/out within that lifecycle is a single position. Realized PnL, fees and funding
 * are kept separate and summable (USDT for now).
 */
@Entity
@Table(name = "positions")
class Position(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false, updatable = false)
    var profileId: UUID,

    @Column(name = "data_source_id", nullable = false, updatable = false)
    var dataSourceId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16, updatable = false)
    var source: SourceKind,

    @Column(name = "external_id", nullable = false, length = 160, updatable = false)
    var externalId: String,

    @Column(name = "symbol_base", nullable = false, length = 32)
    var symbolBase: String,

    @Column(name = "symbol_quote", nullable = false, length = 16)
    var symbolQuote: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    var side: PositionSide,

    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant,

    @Column(name = "closed_at", nullable = false)
    var closedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    var kind: PositionKind = PositionKind.TRADE,

    /** Null on a grid-bot run: no single quantity exists, and a synthesised one would be a lie. */
    @Column(name = "qty", precision = 38, scale = 18)
    var qty: BigDecimal? = null,

    @Column(name = "entry_price", precision = 38, scale = 18)
    var entryPrice: BigDecimal? = null,

    @Column(name = "exit_price", precision = 38, scale = 18)
    var exitPrice: BigDecimal? = null,

    /** GROSS realized PnL — price movement only, before fees and funding. */
    @Column(name = "realized_pnl", nullable = false, precision = 38, scale = 8)
    var realizedPnl: BigDecimal,

    /** Net profit = [realizedPnl] − [fees] − [funding]. The bottom line actually kept. */
    @Column(name = "net_pnl", nullable = false, precision = 38, scale = 8)
    var netPnl: BigDecimal,

    @Column(name = "fees", nullable = false, precision = 38, scale = 8)
    var fees: BigDecimal,

    @Column(name = "funding", nullable = false, precision = 38, scale = 8)
    var funding: BigDecimal,

    @Column(name = "pnl_currency", nullable = false, length = 8)
    var pnlCurrency: String = "USDT",

    /** Traded notional, both legs. Null = derive it from [qty] × ([entryPrice] + [exitPrice]). */
    @Column(name = "volume", precision = 38, scale = 8)
    var volume: BigDecimal? = null,

    /** Informative only, as the venue showed it on the closed grid; never feeds analytics. */
    @Column(name = "leverage", precision = 10, scale = 2)
    var leverage: BigDecimal? = null,

    @Column(name = "investment", precision = 38, scale = 8)
    var investment: BigDecimal? = null,

    /** Trading venue (e.g. Bitunix, BingX, Quantfury, or a CSV-supplied dead exchange like FTX). */
    @Column(name = "exchange", length = 64)
    var exchange: String? = null,

    /** Free-text user annotation. */
    @Column(name = "note")
    var note: String? = null,

    /** Raw source payload (JSON string) kept for audit/reprocessing. */
    @Column(name = "raw")
    var raw: String? = null,

    /**
     * Soft-delete marker. When set, the position is hidden from every read path but kept in the table
     * so the sync dedup lookup still finds it and re-sync skips it instead of re-inserting a duplicate.
     */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) : TimestampedEntity() {

    /** Hand-entered rows are the only ones no importer re-derives, so the only ones safe to edit in place. */
    @get:Transient
    val isManual: Boolean get() = externalId.startsWith(MANUAL_EXTERNAL_ID_PREFIX)

    companion object {
        const val MANUAL_EXTERNAL_ID_PREFIX = "manual-"
    }
}
