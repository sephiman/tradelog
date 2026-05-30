// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component

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
        return users.findByEmailIgnoreCase(email) ?: throw AppException.unauthorized()
    }
}
