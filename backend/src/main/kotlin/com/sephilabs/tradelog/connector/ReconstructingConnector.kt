// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Base for venues exposing fills rather than closed positions. Fills are FETCHED WIDE — the whole
 * retained history every sync, because reconstruction needs a flat starting point — and EMITTED
 * NARROW, only past the cursor, because re-deriving a position whose opening fills aged out corrupts it.
 */
abstract class ReconstructingConnector(
    endpoint: AppProperties.ExchangeEndpoint,
    mapper: ObjectMapper,
) : SignedRestConnector(endpoint, mapper) {

    /** One window's fills, already mapped. Throwing is how a connector reports a window failing. */
    protected abstract fun fetchFills(
        creds: ExchangeCredentials,
        start: Instant,
        end: Instant,
    ): List<RawFill>

    /** Length of one fetch window — bounded by whatever range the venue accepts per request. */
    protected open val windowDays: Long = 7

    /** Backstop on how far back to walk; the empty-window streak normally stops the walk first. */
    protected open val backfillDays: Long = 365

    /** Safety backstop on window count, far above any real history. */
    protected open val maxWindows: Int = 320

    /** Consecutive empty windows that mean the history is exhausted (or past the venue's retention). */
    protected open val emptyWindowStreak: Int = 8

    /** Venue reports no per-fill PnL, so derive it from leg prices. Exact for linear contracts. */
    protected open val derivePnlFromPrices: Boolean = false

    /** Last chance to adjust a reconstructed position, e.g. to record a non-USDT settlement currency. */
    protected open fun adjust(record: PositionRecord): PositionRecord = record

    final override fun fetchClosedPositions(
        credentials: ExchangeCredentials,
        cursor: SyncCursor,
        backfillFrom: Instant?,
    ): SyncBatch {
        val now = Instant.now()
        val hardFloor = now.minus(backfillDays, ChronoUnit.DAYS)
        val raw = mutableListOf<RawFill>()
        var windows = 0
        var windowEnd = now
        var emptyStreak = 0
        var oldestWithData: Instant? = null

        while (windowEnd.isAfter(hardFloor) && windows < maxWindows) {
            val windowStart = maxOf(windowEnd.minus(windowDays, ChronoUnit.DAYS), hardFloor)
            val before = raw.size
            try {
                raw += fetchFills(credentials, windowStart, windowEnd)
            } catch (e: AppException) {
                // The first window failing is a real error; a later one is how venues answer
                // a request past their retention, so stop there with what we have.
                if (windows == 0) throw e
                log.warn("{} fetch stopped at {}: {}", venue, windowStart, e.message)
                break
            }
            windows++
            if (raw.size > before) {
                emptyStreak = 0
                oldestWithData = windowStart
            } else {
                emptyStreak++
            }
            if (emptyStreak >= emptyWindowStreak) {
                log.info(
                    "{} fetch: history exhausted — {} empty window(s) older than {}; oldest fills at {}",
                    venue, emptyStreak, windowStart, oldestWithData,
                )
                break
            }
            windowEnd = windowStart
            pace(windowEnd.isAfter(hardFloor) && windows < maxWindows)
        }

        val reconstructed = PositionReconstructor.reconstruct(raw, ::normalizeSymbol)
        val keepAfter = cursor.lastClosedAt
        val records = reconstructed
            .filter {
                if (keepAfter != null) it.closedAt.isAfter(keepAfter)
                else backfillFrom == null || !it.closedAt.isBefore(backfillFrom)
            }
            .map { if (derivePnlFromPrices) it.copy(realizedPnl = PositionReconstructor.realizedFromPrices(it)) else it }
            .map(::adjust)
        val maxClosed = records.maxOfOrNull { it.closedAt } ?: cursor.lastClosedAt
        log.info(
            "{} fetch: {} fills over {} window(s) -> {} reconstructed, {} new (cursor={}, sync_from={}, oldest requested={})",
            venue, raw.size, windows, reconstructed.size, records.size,
            cursor.lastClosedAt, backfillFrom, oldestWithData ?: windowEnd,
        )
        logOpenResidual(raw)
        return SyncBatch(records, SyncCursor(lastClosedAt = maxClosed, lastExternalId = records.lastOrNull()?.externalId))
    }

    /** Diagnostic for groups that don't net flat: still-open positions, or an orphaned close. */
    private fun logOpenResidual(raw: List<RawFill>) {
        if (!log.isDebugEnabled) return
        raw.groupBy { it.symbol }.forEach { (group, fills) ->
            var net = BigDecimal.ZERO
            var buys = BigDecimal.ZERO
            var sells = BigDecimal.ZERO
            for (f in fills) {
                if (f.buy) { net = net.add(f.qty); buys = buys.add(f.qty) } else { net = net.subtract(f.qty); sells = sells.add(f.qty) }
            }
            if (net.abs() > buys.max(sells).multiply(RESIDUAL_EPS)) {
                log.debug(
                    "{} open residual (still-open position, not emitted): group={} net={} (buy={} sell={}) fills={} firstTs={} lastTs={}",
                    venue, group, net, buys, sells, fills.size,
                    fills.minByOrNull { it.ts }?.ts, fills.maxByOrNull { it.ts }?.ts,
                )
            }
        }
    }

    private companion object {
        val RESIDUAL_EPS = BigDecimal("0.001")
    }
}
