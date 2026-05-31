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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PositionSide { LONG, SHORT }

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

    @Column(name = "qty", nullable = false, precision = 38, scale = 18)
    var qty: BigDecimal,

    @Column(name = "entry_price", nullable = false, precision = 38, scale = 18)
    var entryPrice: BigDecimal,

    @Column(name = "exit_price", nullable = false, precision = 38, scale = 18)
    var exitPrice: BigDecimal,

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

    /** Trading venue (e.g. Bitunix, BingX, Quantfury, or a CSV-supplied dead exchange like FTX). */
    @Column(name = "exchange", length = 64)
    var exchange: String? = null,

    /** Free-text user annotation. */
    @Column(name = "note")
    var note: String? = null,

    /** Raw source payload (JSON string) kept for audit/reprocessing. */
    @Column(name = "raw")
    var raw: String? = null,
) : TimestampedEntity()
