// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.identity.auth.CurrentUser
import com.sephilabs.tradelog.position.PositionDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * No path names a data source: hand-entered records resolve to the profile's own journal lane. A
 * grid-bot run gets its own paths rather than a flag, because the two shapes barely overlap — a
 * grid has no leg prices and its PnL may arrive net.
 */
@RestController
@RequestMapping("/api/profiles/{profileId}/positions")
class ManualEntryController(
    private val service: ManualEntryService,
    private val currentUser: CurrentUser,
) {

    @PostMapping
    fun create(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: ManualPositionRequest,
    ): ResponseEntity<PositionDto> = created(profileId, body)

    @PutMapping("/{positionId}")
    fun update(
        @PathVariable profileId: UUID,
        @PathVariable positionId: UUID,
        @Valid @RequestBody body: ManualPositionRequest,
    ): PositionDto =
        service.update(profileId, currentUser.requireUser().id, positionId, body)

    @PostMapping("/grid-runs")
    fun createGridRun(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: GridRunRequest,
    ): ResponseEntity<PositionDto> = created(profileId, body)

    @PutMapping("/grid-runs/{positionId}")
    fun updateGridRun(
        @PathVariable profileId: UUID,
        @PathVariable positionId: UUID,
        @Valid @RequestBody body: GridRunRequest,
    ): PositionDto =
        service.update(profileId, currentUser.requireUser().id, positionId, body)

    private fun created(profileId: UUID, entry: ManualEntry): ResponseEntity<PositionDto> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(service.create(profileId, currentUser.requireUser().id, entry))
}
