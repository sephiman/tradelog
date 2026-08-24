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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Neither path names a data source: hand-entered trades resolve to the profile's own journal lane. */
@RestController
class ManualEntryController(
    private val service: ManualEntryService,
    private val currentUser: CurrentUser,
) {

    @PostMapping("/api/profiles/{profileId}/positions")
    fun create(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: ManualPositionRequest,
    ): ResponseEntity<PositionDto> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(service.create(profileId, currentUser.requireUser().id, body))

    @PutMapping("/api/profiles/{profileId}/positions/{positionId}")
    fun update(
        @PathVariable profileId: UUID,
        @PathVariable positionId: UUID,
        @Valid @RequestBody body: ManualPositionRequest,
    ): PositionDto =
        service.update(profileId, currentUser.requireUser().id, positionId, body)
}
