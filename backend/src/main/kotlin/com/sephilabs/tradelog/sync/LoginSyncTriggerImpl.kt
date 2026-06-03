// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceStatus
import com.sephilabs.tradelog.profile.ProfileRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Asynchronously syncs the user's ACTIVE API data sources after login. Runs on the bounded
 * `syncExecutor`, never blocking the login response; each source is synced independently so one
 * failure (or a rate-limit) does not stop the others. No pacing — the user is waiting on fresh data.
 */
@Component
class LoginSyncTriggerImpl(
    private val profiles: ProfileRepository,
    private val dataSources: DataSourceRepository,
    private val syncService: SyncService,
) : LoginSyncTrigger {

    @Async("syncExecutor")
    override fun onLogin(userId: UUID) {
        val profileIds = profiles.findAllByUserIdOrderByCreatedAtAsc(userId).map { it.id }
        if (profileIds.isEmpty()) return
        val sources = dataSources.findAllByProfileIdInAndStatus(profileIds, DataSourceStatus.ACTIVE)
            .filter { it.kind.isApi }
        syncService.syncEach(sources, SyncTrigger.LOGIN)
    }
}
