// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceStatus
import com.sephilabs.tradelog.datasource.SourceKind
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nightly sweep that keeps every ACTIVE API data source current without the user having to log in.
 * Runs sequentially on the scheduler thread and paces itself ([AppProperties.SyncSchedule.spacingMs])
 * so the trickle stays well under the per-exchange rate limit; per-source failures and rate-limits
 * are isolated by [SyncService.syncEach]. `@Scheduled` will not re-enter a run that is still in flight.
 */
@Component
class ScheduledSyncJob(
    private val dataSources: DataSourceRepository,
    private val syncService: SyncService,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(ScheduledSyncJob::class.java)

    @Scheduled(cron = "\${app.sync.schedule.cron:0 0 4 * * *}")
    fun sweep() {
        if (!props.sync.schedule.enabled) return
        val apiKinds = SourceKind.entries.filter { it.isApi }
        val sources = dataSources.findAllByStatusAndKindIn(DataSourceStatus.ACTIVE, apiKinds)
        if (sources.isEmpty()) return
        log.info("Scheduled sync sweep starting: {} active API source(s)", sources.size)
        syncService.syncEach(sources, SyncTrigger.SCHEDULED, props.sync.schedule.spacingMs)
        log.info("Scheduled sync sweep finished")
    }
}
