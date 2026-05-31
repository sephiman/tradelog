// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.connector.FillRecord
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.FillAction
import com.sephilabs.tradelog.position.FillSide
import com.sephilabs.tradelog.position.PositionFillRepository
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.position.PositionTag
import com.sephilabs.tradelog.position.PositionTagId
import com.sephilabs.tradelog.position.PositionTagRepository
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import com.sephilabs.tradelog.taxonomy.Tag
import com.sephilabs.tradelog.taxonomy.TagGroup
import com.sephilabs.tradelog.taxonomy.TagGroupRepository
import com.sephilabs.tradelog.taxonomy.TagRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PositionUpsertIntegrationTest @Autowired constructor(
    private val upsert: PositionUpsertService,
    private val positions: PositionRepository,
    private val fills: PositionFillRepository,
    private val positionTags: PositionTagRepository,
    private val profiles: ProfileRepository,
    private val dataSources: DataSourceRepository,
    private val users: UserRepository,
    private val tagGroups: TagGroupRepository,
    private val tags: TagRepository,
) : IntegrationTestBase() {

    private lateinit var profileId: UUID
    private lateinit var dataSourceId: UUID

    private fun setup() {
        val u = users.save(User(email = "up${System.nanoTime()}@example.com", passwordHash = "x"))
        profileId = profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
        dataSourceId = dataSources.save(DataSource(profileId = profileId, kind = SourceKind.BITUNIX, label = "l${System.nanoTime()}")).id
    }

    private fun record(externalId: String, pnl: String, exit: String, legs: Int) = PositionRecord(
        externalId = externalId,
        symbol = Symbol("ETH", "USDT"),
        side = PositionSide.LONG,
        openedAt = Instant.parse("2026-01-01T00:00:00Z"),
        closedAt = Instant.parse("2026-01-01T01:00:00Z"),
        qty = BigDecimal("1"),
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal(exit),
        realizedPnl = BigDecimal(pnl),
        fills = (1..legs).map { FillRecord(it - 1, if (it == 1) FillAction.OPEN else FillAction.CLOSE, if (it == 1) FillSide.BUY else FillSide.SELL, Instant.parse("2026-01-01T00:0$it:00Z"), BigDecimal("100"), BigDecimal("1")) },
    )

    @Test
    fun `upsert inserts once and is idempotent on (data source, external id)`() {
        setup()
        val c1 = upsert.upsert(dataSourceId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(record("ext-1", "10", "110", 2)))
        assertThat(c1.inserted).isEqualTo(1)
        assertThat(c1.updated).isEqualTo(0)

        // Re-run an overlapping window with the SAME external id but changed values + fewer fills.
        val c2 = upsert.upsert(dataSourceId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(record("ext-1", "-5", "95", 1)))
        assertThat(c2.inserted).isEqualTo(0)
        assertThat(c2.updated).isEqualTo(1)

        assertThat(positions.countByDataSourceId(dataSourceId)).isEqualTo(1)
        val p = positions.findByDataSourceIdAndExternalId(dataSourceId, "ext-1")!!
        assertThat(p.realizedPnl).isEqualByComparingTo("-5")
        assertThat(p.exitPrice).isEqualByComparingTo("95")
        // Fills were replaced (2 -> 1), not appended.
        assertThat(fills.findAllByPositionIdOrderBySeqAsc(p.id)).hasSize(1)
    }

    @Test
    fun `re-sync preserves the user's note and tag`() {
        setup()
        upsert.upsert(dataSourceId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(record("ext-keep", "10", "110", 2)))
        val p = positions.findByDataSourceIdAndExternalId(dataSourceId, "ext-keep")!!

        // user annotations
        p.note = "my analysis"
        positions.save(p)
        val u = users.findById(profiles.findById(profileId).orElseThrow().userId).orElseThrow()
        val group = tagGroups.save(TagGroup(userId = u.id, code = "origen", name = "Origen"))
        val tag = tags.save(Tag(groupId = group.id, code = "discrecional", name = "Discrecional"))
        positionTags.save(PositionTag(PositionTagId(p.id, group.id), tag.id))

        // re-sync the same position
        upsert.upsert(dataSourceId, profileId, SourceKind.BITUNIX, "Bitunix", listOf(record("ext-keep", "12", "112", 2)))

        val reloaded = positions.findByDataSourceIdAndExternalId(dataSourceId, "ext-keep")!!
        assertThat(reloaded.note).isEqualTo("my analysis")
        assertThat(reloaded.realizedPnl).isEqualByComparingTo("12") // source field updated
        assertThat(positionTags.findByIdPositionIdAndIdGroupId(p.id, group.id)?.tagId).isEqualTo(tag.id)
    }
}
