// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

/**
 * The set of supported data source connectors. Adding a new exchange/import source means
 * adding a value here plus a Connector implementation — no change to the position core.
 */
enum class SourceKind {
    BITUNIX,
    BINGX,
    QUANTFURY;

    /** True for signed-REST API sources that hold encrypted credentials and a sync cursor. */
    val isApi: Boolean get() = this == BITUNIX || this == BINGX
}
