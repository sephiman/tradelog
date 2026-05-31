// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.backup

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.connector.SyncCursor
import com.sephilabs.tradelog.datasource.*
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.*
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

class BackupRoundTripTest @Autowired constructor(
    private val exportService: ExportService,
    private val importService: ImportService,
    private val dataSourceService: DataSourceService,
    private val users: UserRepository,
    private val profiles: ProfileRepository,
    private val dataSources: DataSourceRepository,
    private val positions: PositionRepository,
    private val fills: PositionFillRepository,
    private val positionTags: PositionTagRepository,
    private val tagGroups: TagGroupRepository,
    private val tags: TagRepository,
) : IntegrationTestBase() {

    @Test
    fun `export then replace-import reproduces the data without secrets and keeps the cursor`() {
        val user = users.save(User(email = "backup${System.nanoTime()}@example.com", passwordHash = "x", timeZone = "Europe/Madrid"))
        val profile = profiles.save(Profile(userId = user.id, kind = ProfileKind.PERSONAL, name = "Main"))

        // Taxonomy: one group with one tag, which a position will reference.
        val group = tagGroups.save(TagGroup(userId = user.id, code = "origen", name = "Origen", sortOrder = 10))
        val signalTag = tags.save(Tag(groupId = group.id, code = "senal", name = "Señal", sortOrder = 10))

        // An API source WITH encrypted credentials and a sync cursor + watermark.
        val apiDto = dataSourceService.create(profile.id, CreateDataSourceRequest(SourceKind.BITUNIX, "main", "key-abc", "secret-xyz"))
        val apiSource = dataSources.findById(apiDto.id).orElseThrow()
        val watermark = Instant.parse("2026-03-01T00:00:00Z")
        dataSourceService.writeCursor(apiSource, SyncCursor(lastClosedAt = watermark, lastExternalId = "ext-2"))
        apiSource.lastSyncedAt = watermark
        dataSources.save(apiSource)
        // A credential-less file source, to verify its status is preserved on restore.
        dataSourceService.create(profile.id, CreateDataSourceRequest(SourceKind.JOURNAL_CSV, "old-ftx"))

        val pos = positions.save(
            Position(
                profileId = profile.id, dataSourceId = apiSource.id, source = SourceKind.BITUNIX,
                externalId = "ext-1", symbolBase = "BTC", symbolQuote = "USDT", side = PositionSide.LONG,
                openedAt = Instant.parse("2026-02-01T00:00:00Z"), closedAt = Instant.parse("2026-02-02T00:00:00Z"),
                qty = BigDecimal("0.500000000000000000"), entryPrice = BigDecimal("60000.000000000000000000"),
                exitPrice = BigDecimal("62000.000000000000000000"), realizedPnl = BigDecimal("1000.00000000"),
                netPnl = BigDecimal("990.00000000"), fees = BigDecimal("10.00000000"), funding = BigDecimal("0.00000000"),
                pnlCurrency = "USDT", exchange = "Bitunix", note = "good entry", raw = "{\"x\":1}",
            ),
        )
        fills.save(
            PositionFill(
                positionId = pos.id, seq = 1, action = FillAction.OPEN, side = FillSide.BUY,
                ts = Instant.parse("2026-02-01T00:00:00Z"), price = BigDecimal("60000.000000000000000000"),
                qty = BigDecimal("0.500000000000000000"), value = BigDecimal("30000.00000000"), fee = BigDecimal("5.00000000"),
            ),
        )
        positionTags.save(PositionTag(PositionTagId(pos.id, group.id), signalTag.id))

        // --- Export, then restore back into the same account with REPLACE semantics. ---
        val envelope = exportService.export(user)
        assertThat(envelope.profiles).hasSize(1)
        assertThat(envelope.profiles[0].dataSources).hasSize(2)
        val apiBackup = envelope.profiles[0].dataSources.first { it.kind == SourceKind.BITUNIX }
        assertThat(apiBackup.cursor).contains("ext-2")
        assertThat(apiBackup.positions).hasSize(1)
        assertThat(apiBackup.positions[0].tags).containsExactly(BackupTagRef("origen", "senal"))

        val summary = importService.replaceAll(user, envelope)
        assertThat(summary).isEqualTo(ImportSummary(profiles = 1, dataSources = 2, positions = 1, fills = 1, tags = 1))

        // --- Verify the restored graph. ---
        val restoredProfiles = profiles.findAllByUserIdOrderByCreatedAtAsc(user.id)
        assertThat(restoredProfiles).hasSize(1)
        val restoredSources = dataSources.findAllByProfileIdOrderByCreatedAtAsc(restoredProfiles[0].id)
        assertThat(restoredSources).hasSize(2)

        val restoredApi = restoredSources.first { it.kind == SourceKind.BITUNIX }
        assertThat(restoredApi.credentialsEnc).isNull()                       // secret stripped
        assertThat(restoredApi.status).isEqualTo(DataSourceStatus.DISABLED)   // parked pending keys
        assertThat(restoredApi.statusDetail).isEqualTo("BACKUP_NO_CREDENTIALS")
        assertThat(restoredApi.lastSyncedAt).isEqualTo(watermark)             // watermark kept
        assertThat(dataSourceService.cursorOf(restoredApi).lastExternalId).isEqualTo("ext-2")

        val restoredFile = restoredSources.first { it.kind == SourceKind.JOURNAL_CSV }
        assertThat(restoredFile.status).isEqualTo(DataSourceStatus.ACTIVE)    // non-API status preserved

        val restoredPositions = positions.findAllByDataSourceIdOrderByClosedAtAsc(restoredApi.id)
        assertThat(restoredPositions).hasSize(1)
        val rp = restoredPositions[0]
        assertThat(rp.externalId).isEqualTo("ext-1")
        assertThat(rp.symbolBase).isEqualTo("BTC")
        assertThat(rp.realizedPnl).isEqualByComparingTo("1000")
        assertThat(rp.netPnl).isEqualByComparingTo("990")
        assertThat(rp.note).isEqualTo("good entry")
        assertThat(rp.exchange).isEqualTo("Bitunix")

        assertThat(fills.findAllByPositionIdOrderBySeqAsc(rp.id)).singleElement()
            .satisfies({ assertThat(it.action).isEqualTo(FillAction.OPEN) })

        // The tag link resurfaces, resolved against the freshly recreated taxonomy.
        val restoredGroups = tagGroups.findAllByUserIdOrderBySortOrderAscNameAsc(user.id)
        assertThat(restoredGroups).singleElement().satisfies({ assertThat(it.code).isEqualTo("origen") })
        val link = positionTags.findAllByIdPositionIdIn(listOf(rp.id))
        assertThat(link).hasSize(1)
        assertThat(tags.findById(link[0].tagId).orElseThrow().code).isEqualTo("senal")
    }

    @Test
    fun `import refuses a backup from a newer export version`() {
        val user = users.save(User(email = "ver${System.nanoTime()}@example.com", passwordHash = "x"))
        val future = BackupEnvelope(
            meta = BackupMeta(exportVersion = BACKUP_EXPORT_VERSION + 1, schemaVersion = "V999", exportedAt = Instant.parse("2026-01-01T00:00:00Z")),
            user = BackupUser(user.email, "en", "UTC"),
            taxonomy = BackupTaxonomy(emptyList()),
            profiles = emptyList(),
        )
        org.assertj.core.api.Assertions.assertThatThrownBy { importService.replaceAll(user, future) }
            .hasMessageContaining("BACKUP_VERSION_UNSUPPORTED")
    }
}
