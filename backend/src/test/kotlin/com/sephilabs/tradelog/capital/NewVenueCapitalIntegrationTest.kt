// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.datasource.CreateDataSourceRequest
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.datasource.Venues
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.PositionService
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import com.sephilabs.tradelog.sync.PositionUpsertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every exchange must turn up wherever exchanges do, via both routes: the data source's venue label
 * (capital columns, before any sync) and the venue written onto each position (the Exchange filter).
 */
class NewVenueCapitalIntegrationTest @Autowired constructor(
    private val capitalService: CapitalService,
    private val history: CapitalHistoryService,
    private val positionService: PositionService,
    private val upsert: PositionUpsertService,
    private val dataSourceService: DataSourceService,
    private val users: UserRepository,
    private val profiles: ProfileRepository,
) : IntegrationTestBase() {

    private val exchangeKinds = SourceKind.entries.filter { it.isApi }

    private fun newProfile(): UUID {
        val user = users.save(
            User(email = "venue${System.nanoTime()}@example.com", passwordHash = "x", timeZone = "Europe/Madrid"),
        )
        return profiles.save(Profile(userId = user.id, kind = ProfileKind.PERSONAL, name = "Main")).id
    }

    private fun connect(profileId: UUID, kind: SourceKind): UUID = dataSourceService.create(
        profileId,
        CreateDataSourceRequest(kind, "src-${kind.name}", "k", "s", passphrase = "p"),
    ).id

    /** A synced closed position, exactly as a connector would hand it to the upsert. */
    private fun syncOne(profileId: UUID, dataSourceId: UUID, kind: SourceKind, closedAt: Instant, pnl: String) {
        upsert.upsert(
            dataSourceId, profileId, kind, "src-${kind.name}",
            listOf(
                PositionRecord(
                    externalId = "ext-${kind.name}",
                    symbol = Symbol("BTC", "USDT"),
                    side = PositionSide.LONG,
                    openedAt = closedAt.minusSeconds(3600),
                    closedAt = closedAt,
                    qty = BigDecimal("0.1"),
                    entryPrice = BigDecimal("60000"),
                    exitPrice = BigDecimal("61000"),
                    realizedPnl = BigDecimal(pnl),
                ),
            ),
        )
    }

    @Test
    fun `connecting an exchange makes it anchorable before a single trade has synced`() {
        val profileId = newProfile()
        // Capital has to be assertable the moment the key is added — the user knows their balance then,
        // and the first anchor is what every ROI figure is denominated by.
        exchangeKinds.forEach { connect(profileId, it) }

        val known = capitalService.overview(profileId).knownExchanges

        assertThat(known).containsAll(exchangeKinds.mapNotNull { it.venueLabel })
        assertThat(known).contains("Binance", "Bybit", "OKX", "Bitget", "Kraken Futures", "Gate.io", "MEXC", "KuCoin")
    }

    @Test
    fun `an anchored active exchange is counted in the capital total and is never dormant`() {
        val profileId = newProfile()
        exchangeKinds.forEach { connect(profileId, it) }
        // 1,000 on each of the eleven exchanges.
        exchangeKinds.mapNotNull { it.venueLabel }.forEach { venue ->
            history.saveAdjustments(
                profileId,
                SaveAdjustmentsRequest(LocalDate.of(2026, 7, 1), listOf(AdjustmentEntryInput(venue, BigDecimal("1000")))),
            )
        }

        val overview = capitalService.overview(profileId)

        val anchored = overview.entries.filter { it.amount != null }
        assertThat(anchored).hasSize(exchangeKinds.size)
        // A live exchange is never dormant, so none of these drops out of the capital & risk block.
        assertThat(anchored).allMatch { !it.dormant }
        assertThat(overview.total).isEqualByComparingTo(BigDecimal(1000 * exchangeKinds.size))
        assertThat(overview.hasAnchors).isTrue
    }

    @Test
    fun `a synced trade puts its venue in the Exchange filter and carries into that venue's capital`() {
        val profileId = newProfile()
        exchangeKinds.forEach { kind ->
            val dsId = connect(profileId, kind)
            syncOne(profileId, dsId, kind, Instant.parse("2026-07-02T10:00:00Z"), "100")
        }

        // The filter is built from the venue written onto each position, not from the data sources — so
        // this is the route that would break if a kind had no venue label.
        assertThat(positionService.exchanges(profileId))
            .containsAll(exchangeKinds.mapNotNull { it.venueLabel })

        // Anchor one venue before the trade and the trade's PnL must carry forward onto it.
        history.saveAdjustments(
            profileId,
            SaveAdjustmentsRequest(LocalDate.of(2026, 7, 1), listOf(AdjustmentEntryInput("OKX", BigDecimal("1000")))),
        )
        val okx = history.estimateNow(profileId).single { it.exchange == "OKX" }
        assertThat(okx.amount).isEqualByComparingTo("1100") // 1000 anchored + the 100 closed after it
        assertThat(capitalService.overview(profileId).entries.single { it.exchange == "OKX" }.dormant).isFalse
    }

    @Test
    fun `a hand-typed or CSV spelling folds onto the connector's own venue name`() {
        // Otherwise a Journal CSV row saying "okx" would become a second venue with its own balance,
        // splitting one exchange's capital and ROI in two — which is exactly what happened with BingX.
        assertThat(Venues.canonical("okx")).isEqualTo("OKX")
        assertThat(Venues.canonical("gate.io")).isEqualTo("Gate.io")
        assertThat(Venues.canonical("gateio")).isEqualTo("Gate.io")
        assertThat(Venues.canonical("BYBIT")).isEqualTo("Bybit")
        assertThat(Venues.canonical("kucoin")).isEqualTo("KuCoin")
        assertThat(Venues.canonical("bit get")).isEqualTo("Bitget")
        assertThat(Venues.canonical("krakenfutures")).isEqualTo("Kraken Futures")
    }
}
