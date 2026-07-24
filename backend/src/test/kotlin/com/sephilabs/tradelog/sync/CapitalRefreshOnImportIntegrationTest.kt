// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.capital.AdjustmentEntryInput
import com.sephilabs.tradelog.capital.CapitalHistoryService
import com.sephilabs.tradelog.capital.SaveAdjustmentsRequest
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.datasource.CreateDataSourceRequest
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A sync/import that lands new trades must refresh the stored AUTO capital snapshots right away —
 * the chart reads those rows verbatim, so without this they stay stale until the hourly job. MANUAL
 * anchors are the only immutable dates and must survive the refresh untouched.
 */
class CapitalRefreshOnImportIntegrationTest @Autowired constructor(
    private val syncService: SyncService,
    private val history: CapitalHistoryService,
    private val users: UserRepository,
    private val profiles: ProfileRepository,
    private val dataSourceService: DataSourceService,
    private val dataSources: DataSourceRepository,
) : IntegrationTestBase() {

    private fun newProfile(): Pair<UUID, DataSource> {
        val user = users.save(
            User(email = "ref${System.nanoTime()}@example.com", passwordHash = "x", timeZone = "Europe/Madrid"),
        )
        val profile = profiles.save(Profile(userId = user.id, kind = ProfileKind.PERSONAL, name = "Main"))
        val dsId = dataSourceService.create(profile.id, CreateDataSourceRequest(SourceKind.JOURNAL_CSV, "journal")).id
        return profile.id to dataSources.findById(dsId).orElseThrow()
    }

    private fun record(externalId: String, closedAt: Instant, pnl: String, exchange: String = "Bitunix") = PositionRecord(
        externalId = externalId,
        symbol = Symbol("BTC", "USDT"),
        side = PositionSide.LONG,
        openedAt = closedAt.minusSeconds(3600),
        closedAt = closedAt,
        qty = BigDecimal.ONE,
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("110"),
        realizedPnl = BigDecimal(pnl),
        exchange = exchange,
    )

    private fun storedValueOn(profileId: UUID, date: LocalDate) =
        history.snapshotSeries(profileId, date, date).days.single().values.single()

    @Test
    fun `a file import immediately refreshes the stored AUTO series and leaves the anchor immutable`() {
        val (profileId, ds) = newProfile()
        // Anchor July 1 = 1000 (Madrid). The recompute materializes daily AUTO rows through today.
        history.saveAdjustments(
            profileId,
            SaveAdjustmentsRequest(LocalDate.of(2026, 7, 1), listOf(AdjustmentEntryInput("Bitunix", BigDecimal("1000")))),
        )
        // No trades yet: the start of July 2 carries the anchor forward flat.
        assertThat(storedValueOn(profileId, LocalDate.of(2026, 7, 2)).amount).isEqualByComparingTo("1000")

        // A +250 trade closed on July 1 lands through the real import path — no job, no manual recompute.
        syncService.importFile(
            ds,
            listOf(record("ext-1", Instant.parse("2026-07-01T10:00:00Z"), "250")),
            SyncTrigger.UPLOAD,
        )

        // The stored July-2 AUTO row now reflects the new trade (start-of-day carries July 1's PnL).
        assertThat(storedValueOn(profileId, LocalDate.of(2026, 7, 2)).amount).isEqualByComparingTo("1250")

        // The July-1 anchor is the only immutable date: still MANUAL, still 1000.
        val anchorDay = storedValueOn(profileId, LocalDate.of(2026, 7, 1))
        assertThat(anchorDay.manual).isTrue
        assertThat(anchorDay.amount).isEqualByComparingTo("1000")
        assertThat(history.listAdjustments(profileId).single().amount).isEqualByComparingTo("1000")
    }

    @Test
    fun `an import that changes no trades does not touch the series`() {
        val (profileId, ds) = newProfile()
        history.saveAdjustments(
            profileId,
            SaveAdjustmentsRequest(LocalDate.of(2026, 7, 1), listOf(AdjustmentEntryInput("Bitunix", BigDecimal("1000")))),
        )
        // An empty batch inserts/updates nothing; the guard skips the recompute and the series is unchanged.
        val run = syncService.importFile(ds, emptyList(), SyncTrigger.UPLOAD)
        assertThat(run.inserted + run.updated).isEqualTo(0)
        assertThat(storedValueOn(profileId, LocalDate.of(2026, 7, 2)).amount).isEqualByComparingTo("1000")
    }
}
