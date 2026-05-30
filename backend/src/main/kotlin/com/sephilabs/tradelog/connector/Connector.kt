// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.datasource.SourceKind
import java.io.InputStream
import java.time.Instant

/**
 * A data source connector. Strategy pattern: one implementation per [SourceKind]. Adding a new
 * source is a new implementation registered as a Spring bean — no change to the sync core.
 */
interface Connector {
    val kind: SourceKind

    /** Maps the source's own symbol naming (BTCUSDT, BTC-USDT, Quantfury naming) to canonical BASE/QUOTE. */
    fun normalizeSymbol(raw: String): Symbol
}

/** Signed-REST API source pulled incrementally (Bitunix, BingX). */
interface ApiConnector : Connector {
    /**
     * Fetch closed positions newer than [cursor]. On first connect [cursor] is empty and the
     * connector should backfill as far back as the API allows, bounded below by [backfillFrom]
     * when non-null.
     */
    fun fetchClosedPositions(
        credentials: ExchangeCredentials,
        cursor: SyncCursor,
        backfillFrom: Instant?,
    ): SyncBatch
}

/** File-import source with no API (Quantfury PDF "Trading History Report"). */
interface FileImportConnector : Connector {
    fun parse(input: InputStream): List<PositionRecord>
}
