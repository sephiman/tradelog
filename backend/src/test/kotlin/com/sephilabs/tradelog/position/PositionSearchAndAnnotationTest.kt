// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import com.sephilabs.tradelog.sync.PositionUpsertService
import com.sephilabs.tradelog.taxonomy.TaxonomyService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PositionSearchAndAnnotationTest @Autowired constructor(
    private val service: PositionService,
    private val upsert: PositionUpsertService,
    private val taxonomy: TaxonomyService,
    private val profiles: ProfileRepository,
    private val dataSources: DataSourceRepository,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private lateinit var userId: UUID
    private lateinit var profileId: UUID
    private lateinit var dsId: UUID

    private fun setup() {
        userId = users.save(User(email = "ps${System.nanoTime()}@example.com", passwordHash = "x")).id
        profileId = profiles.save(Profile(userId = userId, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
        dsId = dataSources.save(DataSource(profileId = profileId, kind = SourceKind.BITUNIX, label = "l${System.nanoTime()}")).id
    }

    private fun rec(ext: String, base: String, side: PositionSide) = PositionRecord(
        externalId = ext, symbol = Symbol(base, "USDT"), side = side,
        openedAt = Instant.parse("2026-02-01T00:00:00Z"), closedAt = Instant.parse("2026-02-01T01:00:00Z"),
        qty = BigDecimal("1"), entryPrice = BigDecimal("100"), exitPrice = BigDecimal("110"),
        realizedPnl = BigDecimal("10"), fees = BigDecimal.ZERO, funding = BigDecimal.ZERO,
    )

    private fun recAt(ext: String, base: String, closedAt: String, pnl: String, fees: String, funding: String) = PositionRecord(
        externalId = ext, symbol = Symbol(base, "USDT"), side = PositionSide.LONG,
        openedAt = Instant.parse("2026-02-01T00:00:00Z"), closedAt = Instant.parse(closedAt),
        qty = BigDecimal("1"), entryPrice = BigDecimal("100"), exitPrice = BigDecimal("110"),
        realizedPnl = BigDecimal(pnl), fees = BigDecimal(fees), funding = BigDecimal(funding),
    )

    @Test
    fun `search filters by symbol and side`() {
        setup()
        upsert.upsert(dsId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(rec("e", "ETH", PositionSide.LONG), rec("b", "BTC", PositionSide.SHORT)))

        assertThat(service.search(PositionSearchCriteria(profileId)).total).isEqualTo(2)
        assertThat(service.search(PositionSearchCriteria(profileId, symbolBase = "eth")).items.map { it.symbolBase }).containsExactly("ETH")
        val shorts = service.search(PositionSearchCriteria(profileId, side = PositionSide.SHORT)).items
        assertThat(shorts).hasSize(1)
        assertThat(shorts[0].symbolBase).isEqualTo("BTC")
    }

    @Test
    fun `note and single-select tag annotate a position and are searchable`() {
        setup()
        upsert.upsert(dsId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(rec("e", "ETH", PositionSide.LONG)))
        val positionId = service.search(PositionSearchCriteria(profileId)).items.first().id
        val origen = taxonomy.listGroups(userId).first { it.code == "origen" }
        val tagA = origen.tags[0]
        val tagB = origen.tags[1]

        service.updateNote(profileId, positionId, "  good entry  ")
        service.setTag(userId, profileId, positionId, origen.id, tagA.id)

        val detail = service.get(profileId, positionId)
        assertThat(detail.position.note).isEqualTo("good entry")
        assertThat(detail.position.tags.single().tagId).isEqualTo(tagA.id)

        // filter by tag
        assertThat(service.search(PositionSearchCriteria(profileId, tagId = tagA.id)).total).isEqualTo(1)

        // single-select: assigning another tag in the same group replaces it
        service.setTag(userId, profileId, positionId, origen.id, tagB.id)
        assertThat(service.get(profileId, positionId).position.tags.single().tagId).isEqualTo(tagB.id)
        assertThat(service.search(PositionSearchCriteria(profileId, tagId = tagA.id)).total).isEqualTo(0)

        // clear
        service.clearTag(profileId, positionId, origen.id)
        assertThat(service.get(profileId, positionId).position.tags).isEmpty()
    }

    @Test
    fun `closedSummary returns profile rows oldest-close-first with net components`() {
        setup()
        val early = recAt("a", "BTC", "2026-02-01T01:00:00Z", pnl = "20", fees = "2", funding = "1")
        val late = recAt("b", "ETH", "2026-02-03T01:00:00Z", pnl = "-5", fees = "1", funding = "0")
        upsert.upsert(dsId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(late, early))

        val rows = service.closedSummary(profileId)
        assertThat(rows.map { it.symbolBase }).containsExactly("BTC", "ETH") // ordered by closedAt asc
        val btc = rows.first()
        assertThat(btc.exchange).isEqualTo("Bitunix")
        assertThat(btc.source).isEqualTo(SourceKind.BITUNIX)
        assertThat(btc.realizedPnl).isEqualByComparingTo("20")
        assertThat(btc.fees).isEqualByComparingTo("2")
        assertThat(btc.funding).isEqualByComparingTo("1")
    }

    @Test
    fun `closedSummary is scoped to its profile`() {
        setup()
        upsert.upsert(dsId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(rec("e", "ETH", PositionSide.LONG)))
        val otherProfile = profiles.save(Profile(userId = userId, kind = ProfileKind.PERSONAL, name = "Q${System.nanoTime()}")).id

        assertThat(service.closedSummary(profileId)).hasSize(1)
        assertThat(service.closedSummary(otherProfile)).isEmpty()
    }

    @Test
    fun `cannot tag with another user's tag`() {
        setup()
        upsert.upsert(dsId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(rec("e", "ETH", PositionSide.LONG)))
        val positionId = service.search(PositionSearchCriteria(profileId)).items.first().id

        val otherUser = users.save(User(email = "other${System.nanoTime()}@example.com", passwordHash = "x")).id
        val otherOrigen = taxonomy.listGroups(otherUser).first { it.code == "origen" }
        val foreignTag = otherOrigen.tags[0].id

        assertThatThrownBy { service.setTag(userId, profileId, positionId, otherOrigen.id, foreignTag) }
            .isInstanceOf(AppException::class.java)
    }
}
