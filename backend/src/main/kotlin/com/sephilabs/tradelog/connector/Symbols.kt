// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

/** Splits an exchange symbol into canonical BASE/QUOTE. */
object Symbols {

    // Longest-first so USDT matches before USD.
    private val KNOWN_QUOTES = listOf("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "USD", "BTC", "ETH", "EUR")

    /**
     * Handles separated (`BTC-USDT`, `BTC/USDT`, `BTC_USDT`) and concatenated (`BTCUSDT`) forms.
     * Falls back to (whole, "USDT") if no known quote can be identified.
     */
    fun split(raw: String): Symbol {
        val s = raw.trim().uppercase()
        for (sep in charArrayOf('-', '/', '_')) {
            val i = s.indexOf(sep)
            if (i > 0 && i < s.length - 1) {
                return Symbol(s.substring(0, i), s.substring(i + 1))
            }
        }
        for (q in KNOWN_QUOTES) {
            if (s.length > q.length && s.endsWith(q)) {
                return Symbol(s.substring(0, s.length - q.length), q)
            }
        }
        return Symbol(s, "USDT")
    }
}
