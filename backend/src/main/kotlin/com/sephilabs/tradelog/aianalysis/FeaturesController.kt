// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Instance capabilities for signed-in users. False means the feature does not exist here: nothing
 *  related to it is rendered or requested by the SPA. */
data class InstanceFeaturesDto(
    /** One flag for every AI feature: the instance has a provider. */
    val ai: Boolean,
)

@RestController
@RequestMapping("/api/features")
class FeaturesController(private val availability: AiAvailability) {

    @GetMapping
    fun features(): InstanceFeaturesDto = InstanceFeaturesDto(ai = availability.enabled)
}
