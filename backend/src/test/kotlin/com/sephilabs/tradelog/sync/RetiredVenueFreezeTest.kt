// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.datasource.CreateDataSourceRequest
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.datasource.DataSourceStatus
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Freezing a shut-down venue: it stops being swept, and every position it imported stays. */
class RetiredVenueFreezeTest @Autowired constructor(
    private val store: SyncStore,
    private val dataSourceService: DataSourceService,
    private val dataSources: DataSourceRepository,
    private val positions: PositionRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private fun newProfile(): UUID {
        val u = users.save(User(email = "freeze${System.nanoTime()}@example.com", passwordHash = "x"))
        return profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
    }

    private fun bitmartSource(profileId: UUID): UUID =
        dataSourceService.create(profileId, CreateDataSourceRequest(SourceKind.BITMART, "bitmart", "k", "s")).id

    private fun trade(profileId: UUID, dataSourceId: UUID, pnl: String) =
        positions.save(
            Position(
                profileId = profileId, dataSourceId = dataSourceId, source = SourceKind.BITMART,
                externalId = "bm${System.nanoTime()}", symbolBase = "BTC", symbolQuote = "USDT",
                side = PositionSide.LONG,
                openedAt = Instant.parse("2026-07-01T10:00:00Z"), closedAt = Instant.parse("2026-07-01T12:00:00Z"),
                qty = BigDecimal.ONE, entryPrice = BigDecimal("100"), exitPrice = BigDecimal("110"),
                realizedPnl = BigDecimal(pnl), netPnl = BigDecimal(pnl),
                fees = BigDecimal.ZERO, funding = BigDecimal.ZERO, pnlCurrency = "USDT", exchange = "BitMart",
            ),
        )

    @Test
    fun `freezing stops the sweep from picking the source up again`() {
        val profileId = newProfile()
        val id = bitmartSource(profileId)
        val apiKinds = SourceKind.entries.filter { it.isApi }
        assertThat(dataSources.findAllByStatusAndKindIn(DataSourceStatus.ACTIVE, apiKinds).map { it.id })
            .contains(id)

        store.freezeRetired(id)

        // This is exactly the query the nightly sweep runs, so the source is now invisible to it —
        // no more calls to a dead host, no more error rows piling up.
        assertThat(dataSources.findAllByStatusAndKindIn(DataSourceStatus.ACTIVE, apiKinds).map { it.id })
            .doesNotContain(id)
    }

    @Test
    fun `freezing disables rather than errors, and says why`() {
        val id = bitmartSource(newProfile())

        store.freezeRetired(id)

        val frozen = dataSources.findById(id).orElseThrow()
        // DISABLED, not ERROR: nothing failed and there is nothing for the user to fix.
        assertThat(frozen.status).isEqualTo(DataSourceStatus.DISABLED)
        assertThat(frozen.statusDetail).isEqualTo(SyncStore.RETIRED_CODE)
    }

    @Test
    fun `freezing keeps every position, its venue and its credentials`() {
        val profileId = newProfile()
        val id = bitmartSource(profileId)
        trade(profileId, id, "40")
        trade(profileId, id, "-15")

        store.freezeRetired(id)

        val kept = positions.findAll().filter { it.dataSourceId == id }
        assertThat(kept).hasSize(2)
        assertThat(kept.map { it.exchange }).containsOnly("BitMart")
        assertThat(kept.map { it.netPnl }).usingElementComparator(BigDecimal::compareTo)
            .containsExactlyInAnyOrder(BigDecimal("40"), BigDecimal("-15"))
        // The source itself survives with its counts and keys intact — restoring it is just re-enabling.
        val dto = dataSourceService.get(profileId, id)
        assertThat(dto.positionCount).isEqualTo(2)
        assertThat(dto.hasCredentials).isTrue
    }
}
