// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.sephilabs.tradelog.common.PageResponse
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/profiles/{profileId}/positions")
class PositionController(
    private val service: PositionService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun search(
        @PathVariable profileId: UUID,
        @RequestParam(required = false) symbol: String?,
        @RequestParam(required = false) side: PositionSide?,
        @RequestParam(required = false) source: SourceKind?,
        @RequestParam(required = false) exchange: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) tagId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "closed_desc") sort: String,
    ): PageResponse<PositionDto> =
        service.search(
            PositionSearchCriteria(profileId, symbol, side, source, exchange, from, to, tagId, page, size, sort)
        )

    @GetMapping("/exchanges")
    fun exchanges(@PathVariable profileId: UUID): List<String> = service.exchanges(profileId)

    @GetMapping("/{positionId}")
    fun get(@PathVariable profileId: UUID, @PathVariable positionId: UUID): PositionDetailDto =
        service.get(profileId, positionId)

    @PutMapping("/{positionId}/note")
    fun updateNote(
        @PathVariable profileId: UUID,
        @PathVariable positionId: UUID,
        @Valid @RequestBody body: NoteRequest,
    ): ResponseEntity<Void> {
        service.updateNote(profileId, positionId, body.note)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{positionId}/tags/{groupId}")
    fun setTag(
        @PathVariable profileId: UUID,
        @PathVariable positionId: UUID,
        @PathVariable groupId: UUID,
        @Valid @RequestBody body: SetTagRequest,
    ): ResponseEntity<Void> {
        service.setTag(currentUser.requireUser().id, profileId, positionId, groupId, body.tagId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{positionId}/tags/{groupId}")
    fun clearTag(
        @PathVariable profileId: UUID,
        @PathVariable positionId: UUID,
        @PathVariable groupId: UUID,
    ): ResponseEntity<Void> {
        service.clearTag(profileId, positionId, groupId)
        return ResponseEntity.noContent().build()
    }
}
