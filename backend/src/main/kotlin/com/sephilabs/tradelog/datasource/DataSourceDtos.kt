// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Credentials payload encrypted at rest; this shape is the plaintext JSON, never returned to clients. */
data class DataSourceCredentials(
    val apiKey: String,
    val apiSecret: String,
    val passphrase: String? = null,
)

data class DataSourceDto(
    val id: UUID,
    val kind: SourceKind,
    val label: String,
    val status: DataSourceStatus,
    val statusDetail: String?,
    val hasCredentials: Boolean,
    val lastSyncedAt: Instant?,
    val syncFrom: Instant?,
    val positionCount: Long,
    val createdAt: Instant,
)

data class CreateDataSourceRequest(
    val kind: SourceKind,

    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too.long")
    val label: String,

    // Required for API kinds (BITUNIX, BINGX); ignored for QUANTFURY.
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val passphrase: String? = null,

    // Optional earliest closed-position date to backfill from (API kinds). Locked once set; null =
    // pull as far back as the exchange API serves. Deliberately absent from UpdateDataSourceRequest.
    val syncFrom: Instant? = null,
)

data class UpdateDataSourceRequest(
    @field:Size(max = 80, message = "validation.too.long")
    val label: String? = null,

    val status: DataSourceStatus? = null,

    // When both provided, rotates the stored credentials. Otherwise credentials are left unchanged.
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val passphrase: String? = null,
)
