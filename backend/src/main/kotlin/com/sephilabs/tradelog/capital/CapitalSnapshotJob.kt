// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Materializes AUTO capital snapshots for every profile that has at least one anchor: fills the
 * profile's cadence days (daily/weekly/monthly, per its settings) and refreshes stale AUTO values
 * as newly synced trades land. Manual values always win — the recompute never touches them.
 *
 * Runs hourly by default: day boundaries are per-user time zones, so a user's "new day" can start
 * at any server hour. The recompute is idempotent and cheap (one profile-scoped query per run).
 */
@Component
class CapitalSnapshotJob(
    private val snapshots: CapitalSnapshotRepository,
    private val history: CapitalHistoryService,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.capital.snapshot.cron:0 20 * * * *}")
    fun run() {
        if (!props.capital.snapshot.enabled) return
        val profileIds = snapshots.findDistinctProfileIdsBySource(SnapshotSource.MANUAL)
        for (profileId in profileIds) {
            runCatching { history.recomputeAutoSnapshots(profileId) }
                .onFailure { log.warn("Capital snapshot recompute failed for profile {}", profileId, it) }
        }
    }
}
