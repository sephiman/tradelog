// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val security: Security = Security(),
    val registration: Registration = Registration(),
    val bootstrap: Bootstrap = Bootstrap(),
    val crypto: Crypto = Crypto(),
    val sync: Sync = Sync(),
    val connectors: Connectors = Connectors(),
) {
    data class Security(
        val cookieSecure: Boolean = true,
        val loginRate: LoginRate = LoginRate(),
        val importRate: ImportRate = ImportRate(),
    )

    data class LoginRate(
        val perMinute: Long = 5,
        val perHour: Long = 20,
    )

    data class ImportRate(
        val perHour: Long = 20,
    )

    data class Registration(
        val mode: RegistrationMode = RegistrationMode.OPEN,
    )

    enum class RegistrationMode { OPEN, INVITE_ONLY, CLOSED }

    data class Bootstrap(
        val adminEmail: String = "",
        val adminPassword: String = "",
    )

    data class Crypto(
        // Base64-encoded AES key (16/24/32 bytes) for encrypting exchange API credentials at rest.
        val key: String = "",
    )

    data class Sync(
        val rate: SyncRate = SyncRate(),
        val executor: SyncExecutor = SyncExecutor(),
        // 0 = backfill as far back as each exchange API allows.
        val maxBackfillDays: Long = 0,
        // Incremental fetches start this many days *before* the close-time watermark so a position
        // opened before the last sync but closed after it is still re-fetched (its opening fills are
        // needed for BingX reconstruction; Bitunix filters by open time). Upserts are idempotent, so
        // the overlap only re-scans already-synced positions. Bounds the gap to positions held open
        // longer than this; raise it if you hold positions open for longer.
        val overlapDays: Long = 30,
        val schedule: SyncSchedule = SyncSchedule(),
    )

    data class SyncSchedule(
        // Daily background sweep that keeps every ACTIVE API source current without a login.
        val enabled: Boolean = true,
        // Quartz-style cron (seconds field first); evaluated in the JVM's time zone.
        val cron: String = "0 0 4 * * *",
        // Delay between successive sources so the sweep trickles well under the per-exchange quota.
        val spacingMs: Long = 3000,
    )

    data class SyncRate(
        val bitunixPerMinute: Long = 30,
        val bingxPerMinute: Long = 30,
    )

    data class SyncExecutor(
        val corePoolSize: Int = 2,
        val maxPoolSize: Int = 4,
        val queueCapacity: Int = 100,
    )

    data class Connectors(
        val bitunix: ExchangeEndpoint = ExchangeEndpoint("https://fapi.bitunix.com"),
        val bingx: ExchangeEndpoint = ExchangeEndpoint("https://open-api.bingx.com"),
    )

    data class ExchangeEndpoint(
        val baseUrl: String = "",
        val timeoutMs: Long = 10000,
    )
}
