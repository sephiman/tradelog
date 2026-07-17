// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/profiles/{profileId}/capital")
class CapitalController(
    private val service: CapitalService,
    private val history: CapitalHistoryService,
) {

    @GetMapping
    fun overview(@PathVariable profileId: UUID): CapitalOverviewDto = service.overview(profileId)

    @PutMapping
    fun updateSettings(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: UpdateCapitalSettingsRequest,
    ): CapitalOverviewDto = service.updateSettings(profileId, body)

    @GetMapping("/adjustments")
    fun adjustments(@PathVariable profileId: UUID): List<AdjustmentDto> = history.listAdjustments(profileId)

    @PostMapping("/adjustments")
    fun saveAdjustments(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: SaveAdjustmentsRequest,
    ): List<AdjustmentDto> = history.saveAdjustments(profileId, body)

    @PatchMapping("/adjustments/{id}")
    fun patchAdjustment(
        @PathVariable profileId: UUID,
        @PathVariable id: UUID,
        @RequestBody body: PatchAdjustmentRequest,
    ): List<AdjustmentDto> = history.patchAdjustment(profileId, id, body)

    @DeleteMapping("/adjustments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAdjustment(@PathVariable profileId: UUID, @PathVariable id: UUID) {
        history.deleteAdjustment(profileId, id)
    }

    /**
     * On-demand backfill: materializes the AUTO series at the profile's configured frequency from
     * the first adjustment through today (and refreshes stale values), without waiting for the
     * scheduled job. Manual values are never touched.
     */
    @PostMapping("/snapshots/backfill")
    fun backfillSnapshots(@PathVariable profileId: UUID): RecomputeResult =
        history.recomputeAutoSnapshots(profileId)

    @GetMapping("/snapshots")
    fun snapshots(
        @PathVariable profileId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): SnapshotSeriesDto = history.snapshotSeries(profileId, from, to)

    @DeleteMapping("/snapshots/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteSnapshotDay(
        @PathVariable profileId: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) {
        history.deleteSnapshotDay(profileId, date)
    }

    @GetMapping("/roi")
    fun roi(
        @PathVariable profileId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) exchange: String?,
    ): RoiDto = history.roi(profileId, from, to, exchange?.takeIf { it.isNotBlank() && it != "ALL" })
}
