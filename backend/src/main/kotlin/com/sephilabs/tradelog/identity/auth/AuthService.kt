// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.observability.AppMetrics
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Service
class AuthService(
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val metrics: AppMetrics,
    private val props: AppProperties,
) {

    @Transactional
    fun register(request: RegisterRequest): User {
        val mode = props.registration.mode
        if (mode != AppProperties.RegistrationMode.OPEN) {
            // Invitation flow is a later phase; only OPEN self-registration is supported for now.
            metrics.registration(mode.name, "disabled")
            throw AppException.forbidden("REGISTRATION_DISABLED")
        }
        if (users.existsByEmailIgnoreCase(request.email)) {
            metrics.registration(mode.name, "failed_email_taken")
            throw AppException.conflict("EMAIL_ALREADY_REGISTERED")
        }
        val user = User(
            email = request.email.lowercase(),
            passwordHash = encoder.encode(request.password)!!,
            locale = request.locale,
        )
        users.save(user)
        metrics.registration(mode.name, "success")
        return user
    }

    @Transactional
    fun recordLogin(userId: UUID) {
        loadManaged(userId).lastLoginAt = Instant.now()
    }

    @Transactional
    fun changePassword(userId: UUID, current: String, newPassword: String) {
        val user = loadManaged(userId)
        if (!encoder.matches(current, user.passwordHash)) {
            throw AppException.badRequest("PASSWORD_MISMATCH")
        }
        user.passwordHash = encoder.encode(newPassword)!!
    }

    /** Partial profile update: applies only the provided fields. A bad time zone is rejected. */
    @Transactional
    fun updateProfile(userId: UUID, locale: String?, timeZone: String?): User {
        timeZone?.let {
            if (it !in ZoneId.getAvailableZoneIds()) throw AppException.badRequest("INVALID_PARAMETER", "timeZone")
        }
        val user = loadManaged(userId)
        locale?.let { user.locale = it }
        timeZone?.let { user.timeZone = it }
        return user
    }

    private fun loadManaged(userId: UUID): User =
        users.findById(userId).orElseThrow { AppException.unauthorized() }
}
