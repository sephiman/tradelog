// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.passwordreset

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PasswordResetRequest(
    @field:NotBlank(message = "validation.required")
    @field:Email(message = "validation.email.invalid")
    val email: String,
)

data class PasswordResetTokenRequest(
    @field:NotBlank(message = "validation.required")
    val token: String,
)

data class PasswordResetConfirmRequest(
    @field:NotBlank(message = "validation.required")
    val token: String,

    // Same floor as PasswordChangeRequest: the reset must not become a way around it.
    @field:Size(min = 8, message = "validation.password.length")
    val newPassword: String,
)
