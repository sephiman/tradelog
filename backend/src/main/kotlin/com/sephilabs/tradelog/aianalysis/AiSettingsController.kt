// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Every endpoint answers 404 when the instance has no AI provider, as if the feature did not exist. */
@RestController
@RequestMapping("/api/ai")
class AiSettingsController(
    private val settings: AiSettingsService,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/settings")
    fun get(): AiSettingsDto = settings.get(currentUser.requireUser().id)

    @PutMapping("/settings")
    fun update(@Valid @RequestBody body: AiSettingsRequest): AiSettingsDto =
        settings.update(currentUser.requireUser().id, body.enabled)
}
