// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder

class AuthIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val authService: AuthService,
    private val encoder: PasswordEncoder,
) : IntegrationTestBase() {

    private fun email() = "auth${System.nanoTime()}@example.com"

    @Test
    fun `register persists a user with a hashed password`() {
        val e = email()
        val user = authService.register(RegisterRequest(email = e, password = "password123", locale = "es"))

        val reloaded = users.findById(user.id).orElseThrow()
        assertThat(reloaded.email).isEqualTo(e.lowercase())
        assertThat(reloaded.locale).isEqualTo("es")
        assertThat(reloaded.timeZone).isEqualTo("UTC")
        assertThat(reloaded.passwordHash).isNotEqualTo("password123")
        assertThat(encoder.matches("password123", reloaded.passwordHash)).isTrue
    }

    @Test
    fun `register rejects a duplicate email`() {
        val e = email()
        authService.register(RegisterRequest(email = e, password = "password123"))
        assertThatThrownBy { authService.register(RegisterRequest(email = e.uppercase(), password = "password123")) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("EMAIL_ALREADY_REGISTERED")
    }

    @Test
    fun `updateProfile and changePassword and recordLogin persist`() {
        val saved = users.save(User(email = email(), passwordHash = encoder.encode("oldpass1234")!!))
        assertThat(saved.lastLoginAt).isNull()

        authService.updateProfile(saved.id, "es", "Europe/Madrid")
        authService.changePassword(saved.id, "oldpass1234", "newpass5678")
        authService.recordLogin(saved.id)

        val reloaded = users.findById(saved.id).orElseThrow()
        assertThat(reloaded.locale).isEqualTo("es")
        assertThat(reloaded.timeZone).isEqualTo("Europe/Madrid")
        assertThat(encoder.matches("newpass5678", reloaded.passwordHash)).isTrue
        assertThat(reloaded.lastLoginAt).isNotNull
    }

    @Test
    fun `updateProfile applies only provided fields and rejects an unknown time zone`() {
        val saved = users.save(User(email = email(), passwordHash = "x", locale = "en"))

        // Partial update: timeZone only, locale left untouched.
        authService.updateProfile(saved.id, null, "America/New_York")
        val afterTz = users.findById(saved.id).orElseThrow()
        assertThat(afterTz.locale).isEqualTo("en")
        assertThat(afterTz.timeZone).isEqualTo("America/New_York")

        assertThatThrownBy { authService.updateProfile(saved.id, null, "Mars/Olympus") }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("INVALID_PARAMETER")
    }

    @Test
    fun `changePassword rejects a wrong current password`() {
        val saved = users.save(User(email = email(), passwordHash = encoder.encode("right-password")!!))
        assertThatThrownBy { authService.changePassword(saved.id, "wrong-password", "newpass5678") }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("PASSWORD_MISMATCH")
    }
}
