// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.identity.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Every endpoint answers 404 when the instance has no AI provider, as if the feature did not exist;
 *  the profile-scoped ownership check comes from ProfileAccessInterceptor. */
@RestController
@RequestMapping("/api/profiles/{profileId}/ai-analysis")
class AiAnalysisController(
    private val service: AiAnalysisService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(@PathVariable profileId: UUID): List<AiAnalysisRunDto> = service.list(profileId)

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        @PathVariable profileId: UUID,
        @RequestBody body: AiAnalysisStartRequest,
    ): AiAnalysisRunDto = service.start(profileId, currentUser.requireUser(), body)

    @DeleteMapping("/{runId}")
    fun delete(@PathVariable profileId: UUID, @PathVariable runId: UUID) {
        service.delete(profileId, runId)
    }
}
