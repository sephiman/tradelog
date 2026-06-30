// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/profiles/{profileId}/capital")
class CapitalController(private val service: CapitalService) {

    @GetMapping
    fun get(@PathVariable profileId: UUID): CapitalSettingsDto = service.get(profileId)

    @PutMapping
    fun update(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: UpdateCapitalRequest,
    ): CapitalSettingsDto = service.update(profileId, body)
}
