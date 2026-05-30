// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.SyncCursor
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.datasource.DataSourceStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Owns the short, transactional state transitions of a sync run, keeping the (potentially slow)
 * network fetch in [SyncService] outside any database transaction.
 */
@Service
class SyncStore(
    private val dataSources: DataSourceRepository,
    private val dataSourceService: DataSourceService,
    private val upsert: PositionUpsertService,
    private val runs: SyncRunRepository,
) {

    @Transactional
    fun startRun(dataSourceId: UUID, trigger: SyncTrigger): SyncRun =
        runs.save(SyncRun(dataSourceId = dataSourceId, trigger = trigger, status = RunStatus.RUNNING))

    /** Upsert the batch, advance the cursor, and mark the source + run successful — all atomically. */
    @Transactional
    fun completeApiSuccess(dataSourceId: UUID, runId: UUID, records: List<PositionRecord>, nextCursor: SyncCursor): SyncRun {
        val ds = dataSources.findById(dataSourceId).orElseThrow()
        val counts = upsert.upsert(ds.id, ds.profileId, ds.kind, records)
        dataSourceService.writeCursor(ds, nextCursor)
        ds.lastSyncedAt = Instant.now()
        ds.status = DataSourceStatus.ACTIVE
        ds.statusDetail = null
        return finish(runId, RunStatus.SUCCESS, counts.inserted, counts.updated, null)
    }

    /** Upsert a parsed file batch and mark the source synced — used by the Quantfury PDF upload. */
    @Transactional
    fun completeFileSuccess(dataSourceId: UUID, runId: UUID, records: List<PositionRecord>): SyncRun {
        val ds = dataSources.findById(dataSourceId).orElseThrow()
        val counts = upsert.upsert(ds.id, ds.profileId, ds.kind, records)
        ds.lastSyncedAt = Instant.now()
        ds.status = DataSourceStatus.ACTIVE
        ds.statusDetail = null
        return finish(runId, RunStatus.SUCCESS, counts.inserted, counts.updated, null)
    }

    /**
     * Mark the source + run failed. [code] is the stable, i18n-able error code (stored on the run);
     * [detail] is the human-readable reason from the exchange, shown in the UI and truncated to the
     * status_detail column width. The full untruncated detail is in the logs.
     */
    @Transactional
    fun completeError(dataSourceId: UUID, runId: UUID, code: String, detail: String? = null): SyncRun {
        dataSources.findById(dataSourceId).orElse(null)?.let {
            it.status = DataSourceStatus.ERROR
            it.statusDetail = (detail ?: code).take(STATUS_DETAIL_MAX)
        }
        return finish(runId, RunStatus.ERROR, 0, 0, code)
    }

    private fun finish(runId: UUID, status: RunStatus, inserted: Int, updated: Int, code: String?): SyncRun {
        val run = runs.findById(runId).orElseThrow()
        run.status = status
        run.finishedAt = Instant.now()
        run.inserted = inserted
        run.updated = updated
        run.errorCode = code
        return run
    }

    private companion object {
        const val STATUS_DETAIL_MAX = 64 // matches the data_sources.status_detail column width
    }
}
