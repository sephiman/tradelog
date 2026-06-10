// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.journal

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.connector.FileImportService
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class JournalCsvImportIntegrationTest @Autowired constructor(
    private val importService: FileImportService,
    private val positions: PositionRepository,
    private val dataSources: DataSourceRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private val header =
        "symbol;side;opened_at;closed_at;quantity;entry_price;exit_price;realized_pnl;fees;funding;exchange;note"

    private fun csv(vararg rows: String) = (listOf(header) + rows).joinToString("\n").toByteArray()

    private fun journalSource(label: String): Pair<UUID, UUID> {
        val u = users.save(User(email = "csv${System.nanoTime()}@example.com", passwordHash = "x"))
        val p = profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
        val ds = dataSources.save(DataSource(profileId = p, kind = SourceKind.JOURNAL_CSV, label = label)).id
        return p to ds
    }

    @Test
    fun `imports journal csv, resolves venue, and is idempotent`() {
        val (profileId, dsId) = journalSource("Dead Exchanges")
        val bytes = csv(
            "BTC/USDT;long;2024-10-31;2024-11-06;1;21147.66;22514.18;1366.52;171.76;0;FTX;swing",
            "SUI/USDT;short;2024-11-09;2024-11-09;3;6824.37;6808.25;;21.81;0;;", // blank exchange -> data source label
        )

        val preview = importService.preview(profileId, dsId, bytes.inputStream())
        assertThat(preview.totalPositions).isEqualTo(2)
        assertThat(preview.symbols).contains("BTC/USDT", "SUI/USDT")

        // This is the only path that inserts a JOURNAL_CSV row — it exercises the V007 CHECK constraint.
        val run1 = importService.execute(profileId, dsId, bytes.inputStream())
        assertThat(run1.inserted).isEqualTo(2)

        val btc = positions.findAll().first { it.symbolBase == "BTC" && it.dataSourceId == dsId }
        assertThat(btc.exchange).isEqualTo("FTX")
        assertThat(btc.note).isEqualTo("swing")
        // realizedPnl is GROSS (before fees); netPnl = gross − fees − funding = 1366.52 − 171.76 = 1194.76.
        assertThat(btc.realizedPnl.setScale(2, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1366.52")
        assertThat(btc.netPnl.setScale(2, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1194.76")

        val sui = positions.findAll().first { it.symbolBase == "SUI" && it.dataSourceId == dsId }
        assertThat(sui.exchange).isEqualTo("Dead Exchanges") // fell back to the data source label
        assertThat(sui.side).isEqualTo(PositionSide.SHORT)
        // computed short pnl: (entry - exit) * qty = (6824.37 - 6808.25) * 3 = 48.36
        assertThat(sui.realizedPnl.setScale(2, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("48.36")

        // Re-importing the same file must not duplicate.
        val run2 = importService.execute(profileId, dsId, bytes.inputStream())
        assertThat(run2.inserted).isEqualTo(0)
        assertThat(positions.countByDataSourceIdAndDeletedAtIsNull(dsId)).isEqualTo(2)

        assertThat(positions.findDistinctExchanges(profileId)).containsExactly("Dead Exchanges", "FTX")
    }
}
