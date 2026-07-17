// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.datasource.CreateDataSourceRequest
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.Position
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Exercises the capital-history rules end to end against a real database. The user's zone is
 * Europe/Madrid (UTC+2 in summer), so day boundaries deliberately do NOT coincide with UTC.
 * Fixed dates in early July 2026 are used; assertions never depend on what "today" is beyond
 * it being at/after those dates.
 */
class CapitalHistoryIntegrationTest @Autowired constructor(
    private val history: CapitalHistoryService,
    private val capitalService: CapitalService,
    private val users: UserRepository,
    private val profiles: ProfileRepository,
    private val positions: PositionRepository,
    private val dataSourceService: DataSourceService,
    private val snapshots: CapitalSnapshotRepository,
) : IntegrationTestBase() {

    private fun newProfile(): Pair<Profile, UUID> {
        val user = users.save(
            User(email = "cap${System.nanoTime()}@example.com", passwordHash = "x", timeZone = "Europe/Madrid"),
        )
        val profile = profiles.save(Profile(userId = user.id, kind = ProfileKind.PERSONAL, name = "Main"))
        val ds = dataSourceService.create(profile.id, CreateDataSourceRequest(SourceKind.JOURNAL_CSV, "journal"))
        return profile to ds.id
    }

    private fun trade(profileId: UUID, dataSourceId: UUID, closedAt: Instant, netPnl: String, exchange: String = "Bitunix") {
        positions.save(
            Position(
                profileId = profileId, dataSourceId = dataSourceId, source = SourceKind.JOURNAL_CSV,
                externalId = "t${System.nanoTime()}", symbolBase = "BTC", symbolQuote = "USDT",
                side = PositionSide.LONG, openedAt = closedAt.minusSeconds(3600), closedAt = closedAt,
                qty = BigDecimal.ONE, entryPrice = BigDecimal("100"), exitPrice = BigDecimal("110"),
                realizedPnl = BigDecimal(netPnl), netPnl = BigDecimal(netPnl),
                fees = BigDecimal.ZERO, funding = BigDecimal.ZERO, pnlCurrency = "USDT", exchange = exchange,
            ),
        )
    }

    private fun anchor(profileId: UUID, date: LocalDate, exchange: String, amount: String) {
        history.saveAdjustments(
            profileId,
            SaveAdjustmentsRequest(date, listOf(AdjustmentEntryInput(exchange, BigDecimal(amount)))),
        )
    }

    @Test
    fun `estimate carries the latest anchor forward and applies the hard cut`() {
        val (profile, dsId) = newProfile()
        // Closed before the first anchor's day starts (Madrid): must never count.
        trade(profile.id, dsId, Instant.parse("2026-06-29T10:00:00Z"), "999")
        anchor(profile.id, LocalDate.of(2026, 7, 1), "Bitunix", "1000")
        trade(profile.id, dsId, Instant.parse("2026-07-02T10:00:00Z"), "500")
        // A later anchor settles everything before it — the +500 stops counting.
        anchor(profile.id, LocalDate.of(2026, 7, 5), "Bitunix", "2000")
        trade(profile.id, dsId, Instant.parse("2026-07-06T10:00:00Z"), "100")
        // Another exchange without any anchor: no estimate for it.
        trade(profile.id, dsId, Instant.parse("2026-07-06T11:00:00Z"), "77", exchange = "BingX")

        val estimates = history.estimateNow(profile.id)
        assertThat(estimates).hasSize(1)
        with(estimates.single()) {
            assertThat(exchange).isEqualTo("Bitunix")
            assertThat(amount).isEqualByComparingTo("2100")
            assertThat(anchorDate).isEqualTo(LocalDate.of(2026, 7, 5))
            assertThat(anchorAmount).isEqualByComparingTo("2000")
        }

        val overview = capitalService.overview(profile.id)
        assertThat(overview.hasAnchors).isTrue
        assertThat(overview.total).isEqualByComparingTo("2100")
        assertThat(overview.timeZone).isEqualTo("Europe/Madrid")
        assertThat(overview.entries.first { it.exchange == "BingX" }.amount).isNull()
    }

    @Test
    fun `daily snapshots backfill from the anchor and a manual edit re-bases the days after it`() {
        val (profile, dsId) = newProfile()
        trade(profile.id, dsId, Instant.parse("2026-07-01T10:00:00Z"), "100")
        trade(profile.id, dsId, Instant.parse("2026-07-03T10:00:00Z"), "-30")
        anchor(profile.id, LocalDate.of(2026, 7, 1), "Bitunix", "1000")

        fun valueOn(date: LocalDate): SnapshotValueDto =
            history.snapshotSeries(profile.id, date, date).days.single().values.single()

        // Start-of-day semantics: July 2 carries July 1's PnL; July 3 is unchanged; July 4 carries the loss.
        assertThat(valueOn(LocalDate.of(2026, 7, 1)).manual).isTrue
        assertThat(valueOn(LocalDate.of(2026, 7, 2)).amount).isEqualByComparingTo("1100")
        assertThat(valueOn(LocalDate.of(2026, 7, 2)).manual).isFalse
        assertThat(valueOn(LocalDate.of(2026, 7, 3)).amount).isEqualByComparingTo("1100")
        assertThat(valueOn(LocalDate.of(2026, 7, 4)).amount).isEqualByComparingTo("1070")

        // Manually editing a snapshot day turns it into an anchor and re-bases what follows.
        anchor(profile.id, LocalDate.of(2026, 7, 3), "Bitunix", "5000")
        assertThat(valueOn(LocalDate.of(2026, 7, 3)).manual).isTrue
        assertThat(valueOn(LocalDate.of(2026, 7, 4)).amount).isEqualByComparingTo("4970")
        assertThat(valueOn(LocalDate.of(2026, 7, 4)).manual).isFalse

        // The job's recompute never overwrites the manual value.
        history.recomputeAutoSnapshots(profile.id)
        assertThat(valueOn(LocalDate.of(2026, 7, 3)).amount).isEqualByComparingTo("5000")
        assertThat(valueOn(LocalDate.of(2026, 7, 3)).manual).isTrue

        // Removing the manual value reverts the day to auto-carried.
        history.saveAdjustments(
            profile.id,
            SaveAdjustmentsRequest(LocalDate.of(2026, 7, 3), listOf(AdjustmentEntryInput("Bitunix", null))),
        )
        assertThat(valueOn(LocalDate.of(2026, 7, 3)).amount).isEqualByComparingTo("1100")
        assertThat(valueOn(LocalDate.of(2026, 7, 3)).manual).isFalse
        assertThat(valueOn(LocalDate.of(2026, 7, 4)).amount).isEqualByComparingTo("1070")
    }

    @Test
    fun `weekly cadence materializes Mondays only`() {
        val (profile, _) = newProfile()
        capitalService.updateSettings(
            profile.id,
            UpdateCapitalSettingsRequest(RiskPercentsInput(BigDecimal.ONE, BigDecimal("2")), SnapshotFrequency.WEEKLY),
        )
        anchor(profile.id, LocalDate.of(2026, 7, 1), "Bitunix", "1000") // a Wednesday

        val series = history.snapshotSeries(profile.id, null, null)
        val autoDates = series.days.filter { day -> day.values.none { it.manual } }.map { it.date }
        assertThat(autoDates).isNotEmpty
        assertThat(autoDates).allMatch { it.dayOfWeek == DayOfWeek.MONDAY }
        assertThat(autoDates).contains(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13))
        assertThat(series.days.first().date).isEqualTo(LocalDate.of(2026, 7, 1)) // the anchor itself

        // The on-demand backfill is idempotent: a second run writes, refreshes and drops nothing.
        assertThat(history.recomputeAutoSnapshots(profile.id)).isEqualTo(RecomputeResult(0, 0, 0))
    }

    @Test
    fun `roi divides period PnL after the cut by the period-start capital`() {
        val (profile, dsId) = newProfile()
        anchor(profile.id, LocalDate.of(2026, 7, 1), "Bitunix", "1000")
        trade(profile.id, dsId, Instant.parse("2026-07-02T10:00:00Z"), "100")
        anchor(profile.id, LocalDate.of(2026, 7, 5), "Bitunix", "2000")
        trade(profile.id, dsId, Instant.parse("2026-07-06T10:00:00Z"), "50")
        // A BingX trade with no BingX anchor: out of both sides of the ratio.
        trade(profile.id, dsId, Instant.parse("2026-07-06T11:00:00Z"), "9999", exchange = "BingX")

        // Period = July 4-10 in Madrid time.
        val from = Instant.parse("2026-07-03T22:00:00Z")
        val to = Instant.parse("2026-07-10T21:59:59Z")
        val roi = history.roi(profile.id, from, to, null)

        // Denominator: capital at the start of July 4 = 1000 + 100. Numerator: the July-5 anchor
        // truncates the period's trades to those closed at/after it — only the +50 counts.
        assertThat(roi.startCapital).isEqualByComparingTo("1100")
        assertThat(roi.netPnl).isEqualByComparingTo("50")
        assertThat(roi.cutDate).isEqualTo(LocalDate.of(2026, 7, 5))
        assertThat(roi.roi).isEqualByComparingTo(BigDecimal("50").divide(BigDecimal("1100"), 8, RoundingMode.HALF_EVEN))

        // Filtering to the anchored exchange gives the same figures.
        val scoped = history.roi(profile.id, from, to, "Bitunix")
        assertThat(scoped.roi).isEqualByComparingTo(roi.roi)

        // All-time: denominator is the FIRST anchor; the cut still truncates the numerator.
        val allTime = history.roi(profile.id, null, null, null)
        assertThat(allTime.startCapital).isEqualByComparingTo("1000")
        assertThat(allTime.netPnl).isEqualByComparingTo("50")

        // No snapshot or adjustment at/before the period start → unavailable.
        val early = history.roi(profile.id, Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-15T00:00:00Z"), null)
        assertThat(early.roi).isNull()
        assertThat(early.startCapital).isNull()

        // An exchange with no anchors at all → unavailable.
        assertThat(history.roi(profile.id, from, to, "BingX").roi).isNull()
    }

    @Test
    fun `adjustment lifecycle - patch moves the anchor and delete drops orphaned autos`() {
        val (profile, dsId) = newProfile()
        trade(profile.id, dsId, Instant.parse("2026-07-02T10:00:00Z"), "100")
        anchor(profile.id, LocalDate.of(2026, 7, 1), "Bitunix", "1000")
        val adjustment = history.listAdjustments(profile.id).single()

        // Moving the anchor to a later date re-bases the series there.
        history.patchAdjustment(
            profile.id, adjustment.id,
            PatchAdjustmentRequest(date = LocalDate.of(2026, 7, 3), amount = BigDecimal("1500")),
        )
        val series = history.snapshotSeries(profile.id, null, null)
        assertThat(series.days.first().date).isEqualTo(LocalDate.of(2026, 7, 3))
        assertThat(series.days.first().values.single().amount).isEqualByComparingTo("1500")
        // The +100 of July 2 was settled into the moved anchor — days after it carry 1500 flat.
        assertThat(
            history.snapshotSeries(profile.id, LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 4))
                .days.single().values.single().amount,
        ).isEqualByComparingTo("1500")

        // Future dates are rejected.
        assertThatThrownBy {
            history.patchAdjustment(profile.id, adjustment.id, PatchAdjustmentRequest(date = LocalDate.now().plusDays(3)))
        }.hasMessageContaining("INVALID_PARAMETER")

        // Deleting the only anchor removes the whole derived series.
        history.deleteAdjustment(profile.id, adjustment.id)
        assertThat(history.listAdjustments(profile.id)).isEmpty()
        assertThat(snapshots.findAllByProfileIdOrderBySnapshotDateAscExchangeAsc(profile.id)).isEmpty()
        assertThat(history.estimateNow(profile.id)).isEmpty()
        assertThat(history.roi(profile.id, null, null, null).roi).isNull()
    }
}
