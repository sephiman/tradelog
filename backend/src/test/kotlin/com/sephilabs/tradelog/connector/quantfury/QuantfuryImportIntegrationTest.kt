// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.datasource.DataSource
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.util.UUID

class QuantfuryImportIntegrationTest @Autowired constructor(
    private val importService: QuantfuryImportService,
    private val positions: PositionRepository,
    private val dataSources: DataSourceRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private fun pdf(): ByteArray {
        val doc = PDDocument(); val page = PDPage(); doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 8f); cs.setLeading(12f)
            cs.newLineAtOffset(20f, 760f)
            // Plain numbers (no Tether ₮ glyph): PDFBox's Helvetica/WinAnsi can't encode U+20AE,
            // and the parser tolerates a missing currency symbol anyway. ₮/$ handling is covered by
            // the unit test + the opt-in real-PDF test.
            listOf(
                "ETH/USDT 28/09/2025 5:01:15 PM UTC BUY (open) 4,033.01 0.12397688 ETH 500.00",
                "ETH/USDT 28/09/2025 8:35:47 PM UTC SELL (close) 4,055.69 0.12397688 ETH 500.00 +2.81",
                "TAO/USDT 30/09/2025 5:52:15 PM UTC SELL (open) 297.6 1.68010753 TAO 500.00",
                "TAO/USDT 30/09/2025 6:47:19 PM UTC BUY (close) 300.4 1.68010753 TAO 500.00 -4.70",
            ).forEach { cs.showText(it); cs.newLine() }
            cs.endText()
        }
        val out = ByteArrayOutputStream(); doc.save(out); doc.close(); return out.toByteArray()
    }

    private fun quantfuryProfileAndSource(): Pair<UUID, UUID> {
        val u = users.save(User(email = "qf${System.nanoTime()}@example.com", passwordHash = "x"))
        val p = profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
        val ds = dataSources.save(DataSource(profileId = p, kind = SourceKind.QUANTFURY, label = "pdf")).id
        return p to ds
    }

    @Test
    fun `preview then execute imports closed positions and is idempotent`() {
        val (profileId, dsId) = quantfuryProfileAndSource()
        val bytes = pdf()

        val preview = importService.preview(profileId, dsId, bytes.inputStream())
        assertThat(preview.totalPositions).isEqualTo(2)
        assertThat(preview.symbols).contains("ETH/USDT", "TAO/USDT")

        val run1 = importService.execute(profileId, dsId, bytes.inputStream())
        assertThat(run1.inserted).isEqualTo(2)
        assertThat(positions.countByDataSourceId(dsId)).isEqualTo(2)

        // Re-uploading the same export must not duplicate.
        val run2 = importService.execute(profileId, dsId, bytes.inputStream())
        assertThat(run2.inserted).isEqualTo(0)
        assertThat(positions.countByDataSourceId(dsId)).isEqualTo(2)

        // PnL computed from leg prices, fees/funding zero for Quantfury.
        val eth = positions.findAll().first { it.symbolBase == "ETH" && it.dataSourceId == dsId }
        assertThat(eth.realizedPnl.setScale(2, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("2.81")
        assertThat(eth.fees).isEqualByComparingTo("0")
    }
}
