// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.isSyncable
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Manual sync triggers and run history for a profile's data sources. */
@RestController
@RequestMapping("/api/profiles/{profileId}")
class SyncController(
    private val syncService: SyncService,
    private val dataSources: DataSourceRepository,
    private val runs: SyncRunRepository,
) {

    /** Sync every syncable API source; ERROR ones included, since this run may be what clears the error. */
    @PostMapping("/sync")
    fun syncAll(@PathVariable profileId: UUID): List<SyncRunDto> =
        dataSources.findAllByProfileIdOrderByCreatedAtAsc(profileId)
            .filter { it.isSyncable }
            .mapNotNull { ds ->
                try {
                    syncService.syncApiSource(ds, SyncTrigger.MANUAL)
                } catch (_: AppException) {
                    null
                }
            }

    @PostMapping("/data-sources/{dataSourceId}/sync")
    fun syncOne(@PathVariable profileId: UUID, @PathVariable dataSourceId: UUID): SyncRunDto =
        syncService.syncDataSource(profileId, dataSourceId, SyncTrigger.MANUAL)

    @GetMapping("/data-sources/{dataSourceId}/sync-runs")
    fun runs(
        @PathVariable profileId: UUID,
        @PathVariable dataSourceId: UUID,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<SyncRunDto> {
        dataSources.findByIdAndProfileId(dataSourceId, profileId)
            ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")
        return runs.findAllByDataSourceIdOrderByStartedAtDesc(dataSourceId, PageRequest.of(0, limit.coerceIn(1, 50)))
            .map(SyncRunDto::of)
    }
}
