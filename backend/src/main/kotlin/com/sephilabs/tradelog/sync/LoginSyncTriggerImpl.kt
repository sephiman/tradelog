// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceStatus
import com.sephilabs.tradelog.profile.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Asynchronously syncs the user's ACTIVE API data sources after login. Runs on the bounded
 * `syncExecutor`, never blocking the login response; each source is synced independently so one
 * failure (or a rate-limit) does not stop the others.
 */
@Component
class LoginSyncTriggerImpl(
    private val profiles: ProfileRepository,
    private val dataSources: DataSourceRepository,
    private val syncService: SyncService,
) : LoginSyncTrigger {

    private val log = LoggerFactory.getLogger(LoginSyncTriggerImpl::class.java)

    @Async("syncExecutor")
    override fun onLogin(userId: UUID) {
        val profileIds = profiles.findAllByUserIdOrderByCreatedAtAsc(userId).map { it.id }
        if (profileIds.isEmpty()) return
        val sources = dataSources.findAllByProfileIdInAndStatus(profileIds, DataSourceStatus.ACTIVE)
            .filter { it.kind.isApi }
        for (ds in sources) {
            try {
                syncService.syncApiSource(ds, SyncTrigger.LOGIN)
            } catch (e: AppException) {
                // Rate-limited or transient — skip this source; it will retry on the next login or manual sync.
                log.info("Login sync skipped source {} ({}): {}", ds.id, ds.kind, e.code)
            } catch (e: Exception) {
                log.warn("Login sync error for source {} ({})", ds.id, ds.kind, e)
            }
        }
    }
}
