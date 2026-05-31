// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

/**
 * The set of supported data source connectors. Adding a new exchange/import source means
 * adding a value here plus a Connector implementation — no change to the position core.
 */
enum class SourceKind {
    BITUNIX,
    BINGX,
    QUANTFURY,

    /** Manual closed-position CSV in tradelog's canonical format (dead exchanges, hand-kept journals). */
    JOURNAL_CSV;

    /** True for signed-REST API sources that hold encrypted credentials and a sync cursor. */
    val isApi: Boolean get() = this == BITUNIX || this == BINGX

    /**
     * The trading venue this source represents, when it is the venue itself. Null for [JOURNAL_CSV],
     * whose venue is per-row data (a dead exchange) or, failing that, the data source label.
     */
    val venueLabel: String?
        get() = when (this) {
            BITUNIX -> "Bitunix"
            BINGX -> "BingX"
            QUANTFURY -> "Quantfury"
            JOURNAL_CSV -> null
        }
}
