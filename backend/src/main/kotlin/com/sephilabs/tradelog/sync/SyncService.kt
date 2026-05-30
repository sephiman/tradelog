// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.ConnectorRegistry
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Orchestrates one synchronisation of a data source (template method): rate-limit → start run →
 * fetch from the connector (network, no DB transaction) → idempotent upsert + cursor advance + run
 * finalisation (transactional, in [SyncStore]). Credential/permission failures move the source to
 * ERROR with an actionable i18n code rather than throwing.
 */
@Service
class SyncService(
    private val registry: ConnectorRegistry,
    private val dataSources: DataSourceRepository,
    private val dataSourceService: DataSourceService,
    private val store: SyncStore,
    private val rateLimiter: ExchangeRateLimiter,
    private val props: AppProperties,
    private val metrics: AppMetrics,
) {
    private val log = LoggerFactory.getLogger(SyncService::class.java)

    /** Manual sync of one owned data source. API sources only; Quantfury is synced via PDF upload. */
    fun syncDataSource(profileId: UUID, dataSourceId: UUID, trigger: SyncTrigger): SyncRunDto {
        val ds = dataSources.findByIdAndProfileId(dataSourceId, profileId)
            ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")
        if (!ds.kind.isApi) throw AppException.badRequest("DATA_SOURCE_NOT_SYNCABLE")
        return syncApiSource(ds, trigger)
    }

    /** Core API sync used by both manual and on-login triggers. Throws only on rate limiting (429). */
    fun syncApiSource(ds: DataSource, trigger: SyncTrigger): SyncRunDto {
        if (!rateLimiter.tryAcquire(ds.kind)) throw AppException.tooManyRequests("SYNC_RATE_LIMITED")
        val connector = registry.api(ds.kind)
        val run = store.startRun(ds.id, trigger)
        log.info("Sync starting: source={} kind={} trigger={} run={}", ds.id, ds.kind, trigger, run.id)
        return try {
            val creds = dataSourceService.credentialsOf(ds)
            val cursor = dataSourceService.cursorOf(ds)
            val batch = connector.fetchClosedPositions(creds, cursor, backfillFloor())
            val finished = store.completeApiSuccess(ds.id, run.id, batch.records, batch.nextCursor)
            metrics.syncRun(ds.kind.name, trigger.name, "success")
            metrics.positionsUpserted(ds.kind.name, finished.inserted + finished.updated)
            log.info("Sync success: source={} kind={} inserted={} updated={}", ds.id, ds.kind, finished.inserted, finished.updated)
            SyncRunDto.of(finished)
        } catch (e: Exception) {
            val code = (e as? AppException)?.code ?: "SYNC_FAILED"
            val detail = e.message?.takeIf { it.isNotBlank() && it != code } ?: code
            log.warn("Sync failed: source={} kind={} code={} detail={}", ds.id, ds.kind, code, detail, e)
            val finished = store.completeError(ds.id, run.id, code, detail)
            metrics.syncRun(ds.kind.name, trigger.name, "error")
            SyncRunDto.of(finished)
        }
    }

    /** Persist a batch parsed from a file source (Quantfury PDF). */
    fun importFile(ds: DataSource, records: List<PositionRecord>, trigger: SyncTrigger): SyncRunDto {
        val run = store.startRun(ds.id, trigger)
        return try {
            val finished = store.completeFileSuccess(ds.id, run.id, records)
            metrics.syncRun(ds.kind.name, trigger.name, "success")
            metrics.positionsUpserted(ds.kind.name, finished.inserted + finished.updated)
            log.info("Import success: source={} kind={} inserted={} updated={}", ds.id, ds.kind, finished.inserted, finished.updated)
            SyncRunDto.of(finished)
        } catch (e: Exception) {
            val code = (e as? AppException)?.code ?: "IMPORT_FAILED"
            val detail = e.message?.takeIf { it.isNotBlank() && it != code } ?: code
            log.warn("Import failed: source={} kind={} code={} detail={}", ds.id, ds.kind, code, detail, e)
            val finished = store.completeError(ds.id, run.id, code, detail)
            metrics.syncRun(ds.kind.name, trigger.name, "error")
            SyncRunDto.of(finished)
        }
    }

    private fun backfillFloor(): Instant? {
        val days = props.sync.maxBackfillDays
        return if (days > 0) Instant.now().minus(days, ChronoUnit.DAYS) else null
    }
}
