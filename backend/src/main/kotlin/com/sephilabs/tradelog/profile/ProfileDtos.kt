// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class ProfileRequest(
    val kind: ProfileKind,

    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too.long")
    val name: String,

    @field:Size(max = 1000, message = "validation.too.long")
    val strategyNote: String? = null,
)

data class ProfileDto(
    val id: UUID,
    val kind: ProfileKind,
    val name: String,
    val strategyNote: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(p: Profile) = ProfileDto(p.id, p.kind, p.name, p.strategyNote, p.createdAt)
    }
}
