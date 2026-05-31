// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.position.FillAction
import com.sephilabs.tradelog.position.FillSide
import com.sephilabs.tradelog.position.PositionSide
import java.math.BigDecimal
import java.time.Instant

/** Canonical BASE/QUOTE symbol after per-connector normalization. */
data class Symbol(val base: String, val quote: String)

/** One leg of a position lifecycle, as produced by a connector. */
data class FillRecord(
    val seq: Int,
    val action: FillAction,
    val side: FillSide,
    val ts: Instant,
    val price: BigDecimal,
    val qty: BigDecimal,
    val value: BigDecimal? = null,
    val fee: BigDecimal? = null,
)

/**
 * The canonical position every connector emits. The sync engine maps this to the [com.sephilabs.tradelog.position.Position]
 * entity and upserts by (data source, [externalId]). Connectors never touch JPA.
 *
 * Realized PnL, fees and funding are separate and summable; a source that doesn't report fees or
 * funding (e.g. Quantfury, whose PnL is already net) simply leaves them at zero.
 */
data class PositionRecord(
    val externalId: String,
    val symbol: Symbol,
    val side: PositionSide,
    val openedAt: Instant,
    val closedAt: Instant,
    val qty: BigDecimal,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val realizedPnl: BigDecimal,
    val fees: BigDecimal = BigDecimal.ZERO,
    val funding: BigDecimal = BigDecimal.ZERO,
    val pnlCurrency: String = "USDT",
    val fills: List<FillRecord> = emptyList(),
    val raw: String? = null,
    /** Optional note seeded on first insert only; never overwrites a user-edited note on re-import. */
    val note: String? = null,
    /** Trading venue supplied by the source (e.g. a Journal CSV's dead exchange). Null = derive from source. */
    val exchange: String? = null,
    /** 1-based source row (CSV line including the header) for file imports; null for API connectors. Lets the preview point users at flagged rows. */
    val sourceRow: Int? = null,
)

/** Read-only API credentials passed to an [ApiConnector]; decrypted only inside the sync worker. */
data class ExchangeCredentials(
    val apiKey: String,
    val apiSecret: String,
    val passphrase: String? = null,
)

/**
 * Per-source incremental sync watermark, persisted as JSON in `data_sources.cursor`.
 * [lastClosedAt] advances so overlapping windows never re-fetch from scratch; the upsert key
 * still guarantees idempotency even if windows overlap.
 */
data class SyncCursor(
    val lastClosedAt: Instant? = null,
    val lastExternalId: String? = null,
)

/** A batch pulled from an API connector plus the cursor to persist after a successful upsert. */
data class SyncBatch(
    val records: List<PositionRecord>,
    val nextCursor: SyncCursor,
)
