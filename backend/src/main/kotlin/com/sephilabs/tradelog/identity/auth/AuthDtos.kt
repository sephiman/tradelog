// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class LoginRequest(
    val email: String,
    val password: String,
)

data class RegisterRequest(
    @field:Email(message = "validation.email.invalid")
    val email: String,

    @field:Size(min = 8, message = "validation.password.length")
    val password: String,

    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val locale: String = "en",
)

data class PasswordChangeRequest(
    @field:NotBlank(message = "validation.required")
    val currentPassword: String,

    @field:Size(min = 8, message = "validation.password.length")
    val newPassword: String,
)

data class MeUpdateRequest(
    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val locale: String? = null,
    val timeZone: String? = null,
)

data class MeResponse(
    val id: UUID,
    val email: String,
    val locale: String,
    val timeZone: String,
)
