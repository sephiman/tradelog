// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.taxonomy

import com.sephilabs.tradelog.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Per-user annotation taxonomy (shared across the user's profiles). */
@RestController
@RequestMapping("/api/taxonomy")
class TaxonomyController(
    private val service: TaxonomyService,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/groups")
    fun listGroups(): List<TagGroupDto> = service.listGroups(currentUser.requireUser().id)

    @PostMapping("/groups")
    fun createGroup(@Valid @RequestBody body: TagGroupRequest): ResponseEntity<TagGroupDto> =
        ResponseEntity.status(201).body(service.createGroup(currentUser.requireUser().id, body))

    @PutMapping("/groups/{groupId}")
    fun updateGroup(@PathVariable groupId: UUID, @Valid @RequestBody body: TagGroupRequest): TagGroupDto =
        service.updateGroup(currentUser.requireUser().id, groupId, body)

    @DeleteMapping("/groups/{groupId}")
    fun deleteGroup(@PathVariable groupId: UUID): ResponseEntity<Void> {
        service.deleteGroup(currentUser.requireUser().id, groupId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/groups/{groupId}/tags")
    fun createTag(@PathVariable groupId: UUID, @Valid @RequestBody body: TagRequest): ResponseEntity<TagDto> =
        ResponseEntity.status(201).body(service.createTag(currentUser.requireUser().id, groupId, body))

    @PutMapping("/groups/{groupId}/tags/{tagId}")
    fun updateTag(@PathVariable groupId: UUID, @PathVariable tagId: UUID, @Valid @RequestBody body: TagRequest): TagDto =
        service.updateTag(currentUser.requireUser().id, groupId, tagId, body)

    @DeleteMapping("/groups/{groupId}/tags/{tagId}")
    fun deleteTag(@PathVariable groupId: UUID, @PathVariable tagId: UUID): ResponseEntity<Void> {
        service.deleteTag(currentUser.requireUser().id, groupId, tagId)
        return ResponseEntity.noContent().build()
    }
}
