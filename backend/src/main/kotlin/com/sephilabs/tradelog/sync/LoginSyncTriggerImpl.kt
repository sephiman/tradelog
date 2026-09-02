// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceStatus
import com.sephilabs.tradelog.profile.ProfileRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Asynchronously syncs the user's API data sources after login, errored ones included so they get a
 * chance to recover. Runs on the bounded `syncExecutor`, never blocking the login response; each
 * source is synced independently so one failure (or a rate-limit) does not stop the others. No
 * pacing — the user is waiting on fresh data.
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
        val sources = dataSources.findAllByProfileIdInAndStatusNot(profileIds, DataSourceStatus.DISABLED)
            .filter { it.kind.isApi }
        syncService.syncEach(sources, SyncTrigger.LOGIN)
    }
}
