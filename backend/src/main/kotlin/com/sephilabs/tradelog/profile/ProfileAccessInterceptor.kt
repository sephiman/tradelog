// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.auth.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Enforces that the authenticated user owns the `{profileId}` in the request path before any
 * profile-scoped handler runs. Profiles are private to a single user — there is no sharing — so
 * ownership is a single FK check. Handlers then take the validated id from their own path variable.
 */
@Component
class ProfileAccessInterceptor(
    private val currentUser: CurrentUser,
    private val profiles: ProfileRepository,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        @Suppress("UNCHECKED_CAST")
        val pathVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
            ?: return true
        val raw = pathVars["profileId"] ?: return true
        val profileId = try { UUID.fromString(raw) } catch (_: IllegalArgumentException) {
            throw AppException.badRequest("INVALID_PARAMETER", "profileId")
        }
        val user = currentUser.requireUser()
        profiles.findByIdAndUserId(profileId, user.id)
            ?: throw AppException.forbidden("NOT_PROFILE_OWNER")

        MDC.put("profileId", profileId.toString())
        return true
    }
}
