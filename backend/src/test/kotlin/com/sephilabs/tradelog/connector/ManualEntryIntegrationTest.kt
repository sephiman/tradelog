// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.Position
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import com.sephilabs.tradelog.taxonomy.TagRequest
import com.sephilabs.tradelog.taxonomy.TaxonomyService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

class ManualEntryIntegrationTest @Autowired constructor(
    private val service: ManualEntryService,
    private val positions: PositionRepository,
    private val dataSources: DataSourceRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
    private val taxonomy: TaxonomyService,
) : IntegrationTestBase() {

    private class Fixture(val userId: UUID, val profileId: UUID, val dataSourceId: UUID?)

    /** [label] null leaves the profile with no journal source, so the first trade has to create one. */
    private fun fixture(label: String? = "Manual"): Fixture {
        val u = users.save(User(email = "manual${System.nanoTime()}@example.com", passwordHash = "x"))
        val p = profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
        val ds = label?.let { dataSources.save(DataSource(profileId = p, kind = SourceKind.JOURNAL_CSV, label = it)).id }
        return Fixture(u.id, p, ds)
    }

    private fun request(
        symbol: String = "SOL-USDT",
        side: PositionSide = PositionSide.LONG,
        openedAt: Instant = Instant.parse("2026-07-14T09:20:00Z"),
        closedAt: Instant = Instant.parse("2026-08-02T18:05:00Z"),
        qty: BigDecimal = BigDecimal.ONE,
        entryPrice: BigDecimal = BigDecimal("500"),
        exitPrice: BigDecimal = BigDecimal("541.30"),
        realizedPnl: BigDecimal? = null,
        fees: BigDecimal = BigDecimal("2.10"),
        funding: BigDecimal = BigDecimal("0.80"),
        exchange: String? = "BingX Strategy",
        note: String? = "Futures grid, 40 grids",
        tagId: UUID? = null,
        tagGroupId: UUID? = null,
    ) = ManualPositionRequest(
        symbol, side, openedAt, closedAt, qty, entryPrice, exitPrice,
        realizedPnl, fees, funding, exchange, note, tagId, tagGroupId,
    )

    @Test
    fun `adds a hand-entered trade, derives pnl from prices and marks it editable`() {
        val f = fixture()

        val dto = service.create(f.profileId, f.userId, request())

        assertThat(dto.symbolBase).isEqualTo("SOL")
        assertThat(dto.symbolQuote).isEqualTo("USDT")
        assertThat(dto.exchange).isEqualTo("BingX Strategy")
        assertThat(dto.editable).isTrue()
        // Gross realized comes from the leg prices: (541.30 − 500) × 1; net takes off fees + funding.
        assertThat(dto.realizedPnl.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("41.30")
        assertThat(dto.netPnl.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("38.40")

        val stored = positions.findAll().first { it.dataSourceId == f.dataSourceId!! }
        assertThat(stored.externalId).startsWith(Position.MANUAL_EXTERNAL_ID_PREFIX)
        assertThat(stored.note).isEqualTo("Futures grid, 40 grids")
        assertThat(stored.raw).contains("manual")
    }

    @Test
    fun `an explicit realized pnl wins over the derived one`() {
        val f = fixture()

        val dto = service.create(
            f.profileId, f.userId,
            request(realizedPnl = BigDecimal("44.20"), fees = BigDecimal("2.10"), funding = BigDecimal("0.80")),
        )

        assertThat(dto.realizedPnl.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("44.20")
        assertThat(dto.netPnl.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("41.30")
    }

    @Test
    fun `two identical submissions are two trades, never a silent overwrite`() {
        val f = fixture()

        service.create(f.profileId, f.userId, request())
        service.create(f.profileId, f.userId, request())

        assertThat(positions.countByDataSourceIdAndDeletedAtIsNull(f.dataSourceId!!)).isEqualTo(2)
    }

    @Test
    fun `blank exchange falls back to the data source label`() {
        val f = fixture(label = "Hand-kept")

        val dto = service.create(f.profileId, f.userId, request(exchange = null))

        assertThat(dto.exchange).isEqualTo("Hand-kept")
    }

    @Test
    fun `the venue spelling folds onto the one already in use`() {
        val f = fixture()

        service.create(f.profileId, f.userId, request(exchange = "BingX Strategy"))
        val second = service.create(f.profileId, f.userId, request(exchange = "bingx strategy"))

        assertThat(second.exchange).isEqualTo("BingX Strategy")
        assertThat(positions.findDistinctExchanges(f.profileId)).containsExactly("BingX Strategy")
    }

    @Test
    fun `tagging on insert marks the trade, and clearing it on edit removes the tag`() {
        val f = fixture()
        taxonomy.ensureSeeded(f.userId)
        val origen = taxonomy.listGroups(f.userId).first { it.code == "origen" }
        val tag = taxonomy.createTag(f.userId, origen.id, TagRequest("Grid bot"))

        val created = service.create(
            f.profileId, f.userId,
            request(tagId = tag.id, tagGroupId = origen.id),
        )
        assertThat(created.tags.map { it.tagName }).containsExactly("Grid bot")

        val cleared = service.update(
            f.profileId, f.userId, created.id,
            request(tagId = null, tagGroupId = origen.id),
        )
        assertThat(cleared.tags).isEmpty()
    }

    @Test
    fun `editing rewrites the trade in place, note included`() {
        val f = fixture()
        val created = service.create(f.profileId, f.userId, request())

        val edited = service.update(
            f.profileId, f.userId, created.id,
            request(
                symbol = "BTC-USDT",
                side = PositionSide.SHORT,
                qty = BigDecimal("2"),
                entryPrice = BigDecimal("60000"),
                exitPrice = BigDecimal("59000"),
                fees = BigDecimal("10"),
                funding = BigDecimal.ZERO,
                exchange = "Bitunix",
                note = "closed early",
            ),
        )

        assertThat(edited.id).isEqualTo(created.id)
        assertThat(edited.symbolBase).isEqualTo("BTC")
        assertThat(edited.side).isEqualTo(PositionSide.SHORT)
        assertThat(edited.exchange).isEqualTo("Bitunix")
        assertThat(edited.note).isEqualTo("closed early")
        // Short: (entry − exit) × qty = (60000 − 59000) × 2 = 2000 gross, 1990 net after the 10 fee.
        assertThat(edited.realizedPnl.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("2000.00")
        assertThat(edited.netPnl.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("1990.00")
        assertThat(positions.countByDataSourceIdAndDeletedAtIsNull(f.dataSourceId!!)).isEqualTo(1)
    }

    @Test
    fun `a synced trade cannot be edited`() {
        val f = fixture()
        val synced = positions.save(
            Position(
                profileId = f.profileId,
                dataSourceId = f.dataSourceId!!,
                source = SourceKind.JOURNAL_CSV,
                externalId = "csv-abc123-0",
                symbolBase = "ETH",
                symbolQuote = "USDT",
                side = PositionSide.LONG,
                openedAt = Instant.parse("2026-07-01T00:00:00Z"),
                closedAt = Instant.parse("2026-07-02T00:00:00Z"),
                qty = BigDecimal.ONE,
                entryPrice = BigDecimal("3000"),
                exitPrice = BigDecimal("3100"),
                realizedPnl = BigDecimal("100"),
                netPnl = BigDecimal("100"),
                fees = BigDecimal.ZERO,
                funding = BigDecimal.ZERO,
            )
        )

        assertThatThrownBy { service.update(f.profileId, f.userId, synced.id, request()) }
            .isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "POSITION_NOT_MANUAL")
    }

    @Test
    fun `a close before the open is refused`() {
        val f = fixture()

        assertThatThrownBy {
            service.create(
                f.profileId, f.userId,
                request(
                    openedAt = Instant.parse("2026-08-02T18:05:00Z"),
                    closedAt = Instant.parse("2026-07-14T09:20:00Z"),
                ),
            )
        }
            .isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "MANUAL_CLOSED_BEFORE_OPENED")
    }

    @Test
    fun `the first hand-entered trade creates the journal source it lives on`() {
        val f = fixture(label = null)
        dataSources.save(DataSource(profileId = f.profileId, kind = SourceKind.BINGX, label = "BingX"))

        service.create(f.profileId, f.userId, request())
        service.create(f.profileId, f.userId, request(symbol = "BTC-USDT"))

        val journals = dataSources.findAllByProfileIdOrderByCreatedAtAsc(f.profileId)
            .filter { it.kind == SourceKind.JOURNAL_CSV }
        assertThat(journals).singleElement().extracting("label").isEqualTo("Manual")
        assertThat(positions.countByDataSourceIdAndDeletedAtIsNull(journals.first().id)).isEqualTo(2)
    }

    @Test
    fun `an existing journal source is reused rather than a second one created`() {
        val f = fixture(label = "Dead exchanges")

        service.create(f.profileId, f.userId, request())

        assertThat(dataSources.findAllByProfileIdOrderByCreatedAtAsc(f.profileId)).singleElement()
            .extracting("id").isEqualTo(f.dataSourceId)
    }
}
