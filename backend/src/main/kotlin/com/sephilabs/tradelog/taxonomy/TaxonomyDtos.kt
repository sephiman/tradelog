// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.taxonomy

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class TagDto(
    val id: UUID,
    val code: String,
    val name: String,
    val sortOrder: Int,
)

data class TagGroupDto(
    val id: UUID,
    val code: String,
    val name: String,
    val sortOrder: Int,
    val tags: List<TagDto>,
)

data class TagGroupRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too.long")
    val name: String,
)

data class TagRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too.long")
    val name: String,
)
