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
    val capital: Capital = Capital(),
    val benchmark: Benchmark = Benchmark(),
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
        // BitMart limits private contract reads to ~6 req / 2s per key; 30/min stays well under.
        val bitmartPerMinute: Long = 30,
    )

    data class SyncExecutor(
        val corePoolSize: Int = 2,
        val maxPoolSize: Int = 4,
        val queueCapacity: Int = 100,
    )

    data class Capital(
        val snapshot: CapitalSnapshotSchedule = CapitalSnapshotSchedule(),
    )

    data class CapitalSnapshotSchedule(
        // Background job that materializes AUTO capital snapshots on each profile's cadence days.
        val enabled: Boolean = true,
        // Quartz-style cron (seconds field first); hourly because day boundaries are per-user
        // time zones, so a user's "new day" can begin at any server hour. Idempotent re-runs.
        val cron: String = "0 20 * * * *",
    )

    /**
     * Benchmark price history behind the Monthly ROI comparison lines. Both providers are keyless
     * public market-data endpoints, and neither is an exchange connector: benchmarks are global
     * reference data, unrelated to any user's trades or credentials.
     */
    data class Benchmark(
        val yahoo: YahooEndpoint = YahooEndpoint(),
        val binance: BenchmarkEndpoint = BenchmarkEndpoint("https://api.binance.com", minRequestIntervalMs = 300),
        // How far back the first fill reaches. Also the floor the daily gap-fill extends the head
        // down to, so an already-filled series never re-pulls history.
        val historyLookbackDays: Long = 3650,
        // Bootstrap missing history at startup (off in tests, so no test makes provider HTTP calls).
        val backfillOnStart: Boolean = true,
        val schedule: BenchmarkSchedule = BenchmarkSchedule(),
    )

    data class BenchmarkSchedule(
        val enabled: Boolean = true,
        // Quartz-style cron (seconds field first). Nightly is ample for daily closes; it runs
        // before the sync sweep so a fresh benchmark day is in place by the time anyone looks.
        val cron: String = "0 30 3 * * *",
    )

    data class BenchmarkEndpoint(
        val baseUrl: String = "",
        val timeoutMs: Long = 10000,
        // Delay after each benchmark's fetch, keeping a full sweep well under any rate limiting.
        val minRequestIntervalMs: Long = 1000,
    )

    /** Yahoo's chart API, which mirrors across two hosts; a transport failure retries on the second. */
    data class YahooEndpoint(
        val baseUrl: String = "https://query1.finance.yahoo.com",
        val fallbackBaseUrl: String = "https://query2.finance.yahoo.com",
        val timeoutMs: Long = 10000,
        val minRequestIntervalMs: Long = 1500,
    )

    data class Connectors(
        val bitunix: ExchangeEndpoint = ExchangeEndpoint("https://fapi.bitunix.com"),
        val bingx: ExchangeEndpoint = ExchangeEndpoint("https://open-api.bingx.com"),
        val bitmart: ExchangeEndpoint = ExchangeEndpoint("https://api-cloud-v2.bitmart.com"),
    )

    data class ExchangeEndpoint(
        val baseUrl: String = "",
        val timeoutMs: Long = 10000,
    )
}
