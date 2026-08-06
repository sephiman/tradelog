// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import java.time.Instant

/**
 * The set of supported data source connectors. Adding a new exchange/import source means
 * adding a value here plus a Connector implementation — no change to the position core.
 */
enum class SourceKind {
    BITUNIX,
    BINGX,
    BITMART,
    BINANCE_FUTURES,
    BYBIT,
    OKX,
    BITGET,

    /** Kraken's separate futures platform. Not "Kraken": a spot history is a different balance. */
    KRAKEN_FUTURES,
    GATEIO_FUTURES,
    MEXC_FUTURES,
    KUCOIN_FUTURES,
    QUANTFURY,

    /** Manual closed-position CSV in tradelog's canonical format (dead exchanges, hand-kept journals). */
    JOURNAL_CSV;

    /** True for REST API sources that hold encrypted credentials and a sync cursor. */
    val isApi: Boolean get() = this != QUANTFURY && this != JOURNAL_CSV

    /** Venues needing a third credential: the passphrase chosen when the API key was created. */
    val requiresPassphrase: Boolean get() = this == OKX || this == BITGET || this == KUCOIN_FUTURES

    /** When the venue stops serving its API, for an exchange that has announced it is closing. */
    val retiredAt: Instant?
        get() = if (this == BITMART) BITMART_CLOSES_AT else null

    /** True once [retiredAt] has passed, i.e. the venue is gone and must no longer be called. */
    fun isRetired(now: Instant = Instant.now()): Boolean =
        retiredAt?.let { !now.isBefore(it) } == true

    /**
     * The trading venue this source represents, when it is the venue itself. Null for [JOURNAL_CSV],
     * whose venue is per-row data (a dead exchange) or, failing that, the data source label.
     */
    val venueLabel: String?
        get() = when (this) {
            BITUNIX -> "Bitunix"
            BINGX -> "BingX"
            BITMART -> "BitMart"
            BINANCE_FUTURES -> "Binance"
            BYBIT -> "Bybit"
            OKX -> "OKX"
            BITGET -> "Bitget"
            KRAKEN_FUTURES -> "Kraken Futures"
            GATEIO_FUTURES -> "Gate.io"
            MEXC_FUTURES -> "MEXC"
            KUCOIN_FUTURES -> "KuCoin"
            QUANTFURY -> "Quantfury"
            JOURNAL_CSV -> null
        }

    companion object {
        /** BitMart closes on 2026-08-26. UTC midnight, so the freeze is time-zone independent. */
        val BITMART_CLOSES_AT: Instant = Instant.parse("2026-08-26T00:00:00Z")
    }
}
