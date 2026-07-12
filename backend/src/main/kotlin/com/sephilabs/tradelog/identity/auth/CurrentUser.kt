// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST
import org.springframework.web.context.request.RequestContextHolder

@Component
class CurrentUser(private val users: UserRepository) {

    fun requireUser(): User {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw AppException.unauthorized()
        val email = when (val principal = auth.principal) {
            is UserDetails -> principal.username
            is String -> principal
            else -> throw AppException.unauthorized()
        }
        // The MDC filter, the profile interceptor and the controller all resolve the user on a
        // typical request — cache the row per request so it is one SELECT, not three or four.
        val attrs = RequestContextHolder.getRequestAttributes()
        (attrs?.getAttribute(ATTR_USER, SCOPE_REQUEST) as? User)
            ?.takeIf { it.email.equals(email, ignoreCase = true) }
            ?.let { return it }
        val user = users.findByEmailIgnoreCase(email) ?: throw AppException.unauthorized()
        attrs?.setAttribute(ATTR_USER, user, SCOPE_REQUEST)
        return user
    }

    private companion object {
        const val ATTR_USER = "tl.currentUser"
    }
}
