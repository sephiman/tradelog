// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/profiles")
class ProfileController(
    private val service: ProfileService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(): List<ProfileDto> = service.list(currentUser.requireUser().id)

    @PostMapping
    fun create(@Valid @RequestBody body: ProfileRequest): ResponseEntity<ProfileDto> =
        ResponseEntity.status(201).body(service.create(currentUser.requireUser().id, body))

    @GetMapping("/{profileId}")
    fun get(@PathVariable profileId: UUID): ProfileDto =
        service.get(currentUser.requireUser().id, profileId)

    @PutMapping("/{profileId}")
    fun update(@PathVariable profileId: UUID, @Valid @RequestBody body: ProfileRequest): ProfileDto =
        service.update(currentUser.requireUser().id, profileId, body)

    @DeleteMapping("/{profileId}")
    fun delete(@PathVariable profileId: UUID): ResponseEntity<Void> {
        service.delete(currentUser.requireUser().id, profileId)
        return ResponseEntity.noContent().build()
    }
}
