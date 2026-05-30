// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.analytics

import com.sephilabs.tradelog.identity.auth.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val service: AnalyticsService,
    private val currentUser: CurrentUser,
) {
    @GetMapping("/pnl-cumulative")
    fun cumulativePnl(): List<PnlSeriesDto> =
        service.cumulativePnlPerProfile(currentUser.requireUser().id)
}
