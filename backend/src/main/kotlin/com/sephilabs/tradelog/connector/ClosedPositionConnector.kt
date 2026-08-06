// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Base for venues serving already-closed positions: each row maps 1:1, with the exchange's own PnL.
 * Requests reach back `app.sync.overlap-days` before the watermark because several of these endpoints
 * filter by OPEN time, which would hide a position opened before the last sync and closed after it.
 */
abstract class ClosedPositionConnector(
    endpoint: AppProperties.ExchangeEndpoint,
    mapper: ObjectMapper,
    private val props: AppProperties,
) : SignedRestConnector(endpoint, mapper) {

    /** Every row from [since] (null = as far back as the venue goes). Paging is the subclass's. */
    protected abstract fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode>

    /** Map one row to a canonical position, or say why it could not be mapped. */
    protected abstract fun mapPosition(row: JsonNode): Mapped<PositionRecord>

    final override fun fetchClosedPositions(
        credentials: ExchangeCredentials,
        cursor: SyncCursor,
        backfillFrom: Instant?,
    ): SyncBatch {
        val watermark = cursor.lastClosedAt
        val since = if (watermark != null) {
            val overlapped = watermark.minus(props.sync.overlapDays, ChronoUnit.DAYS)
            backfillFrom?.let { maxOf(overlapped, it) } ?: overlapped
        } else {
            backfillFrom
        }

        val rows = fetchRows(credentials, since)
        val skips = SkipTally()
        val mapped = mutableListOf<PositionRecord>()
        for (row in rows) skips.keep(row, mapPosition(row), mapped)
        skips.report(log, venue, "(since=$since)", rows.size)

        // Positions already ingested are re-scanned by the overlap but must not be re-emitted; the
        // watermark is the true boundary, and the exchange's own filter is only an approximation of it.
        val records = mapped.filter { watermark == null || it.closedAt.isAfter(watermark) }
        val maxClosed = records.maxOfOrNull { it.closedAt } ?: watermark
        log.info(
            "{} fetch: {} row(s) -> {} closed position(s) (since={}, cursor={})",
            venue, rows.size, records.size, since, watermark,
        )
        return SyncBatch(records, SyncCursor(lastClosedAt = maxClosed, lastExternalId = records.lastOrNull()?.externalId))
    }
}
