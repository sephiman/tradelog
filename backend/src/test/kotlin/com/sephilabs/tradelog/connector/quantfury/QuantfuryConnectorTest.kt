// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import com.sephilabs.tradelog.position.PositionSide
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

class QuantfuryConnectorTest {

    private val connector = QuantfuryConnector()

    /** Builds a minimal PDF whose extracted lines mimic Quantfury "Closed Positions" rows. */
    private fun pdf(lines: List<String>): ByteArray {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 8f)
            cs.setLeading(12f)
            cs.newLineAtOffset(20f, 760f)
            for (line in lines) {
                cs.showText(line)
                cs.newLine()
            }
            cs.endText()
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun round2(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_EVEN)

    @Test
    fun `parses a long and a short position with computed pnl`() {
        val bytes = pdf(
            listOf(
                "ETH/USDT 28/09/2025 5:01:15 PM UTC BUY (open) 4033.01 0.12397688 ETH 500.00",
                "ETH/USDT 28/09/2025 8:35:47 PM UTC SELL (close) 4055.69 0.12397688 ETH 500.00 +2.81",
                "TAO/USDT 30/09/2025 5:52:15 PM UTC SELL (open) 297.6 1.68010753 TAO 500.00",
                "TAO/USDT 30/09/2025 6:47:19 PM UTC BUY (close) 300.4 1.68010753 TAO 500.00 -4.70",
            ),
        )
        val records = connector.parse(ByteArrayInputStream(bytes)).sortedBy { it.openedAt }
        assertEquals(2, records.size)

        val eth = records[0]
        assertEquals("ETH", eth.symbol.base)
        assertEquals("USDT", eth.symbol.quote)
        assertEquals(PositionSide.LONG, eth.side)
        assertEquals(BigDecimal("2.81"), round2(eth.realizedPnl))
        assertEquals(0, eth.fees.compareTo(BigDecimal.ZERO))

        val tao = records[1]
        assertEquals(PositionSide.SHORT, tao.side)
        assertEquals(BigDecimal("-4.70"), round2(tao.realizedPnl))
    }

    @Test
    fun `multi-leg position uses vwap and stays a single position`() {
        val bytes = pdf(
            listOf(
                "SUI/USDT 30/09/2025 1:02:10 PM UTC BUY (open) 100 1 SUI 100.00",
                "SUI/USDT 30/09/2025 6:04:11 PM UTC BUY (add) 110 1 SUI 110.00",
                "SUI/USDT 30/09/2025 8:34:01 PM UTC SELL (close) 120 2 SUI 240.00",
            ),
        )
        val p = connector.parse(ByteArrayInputStream(bytes)).single()
        assertEquals(PositionSide.LONG, p.side)
        assertEquals(0, p.qty.compareTo(BigDecimal("2")))
        assertEquals(0, p.entryPrice.compareTo(BigDecimal("105")))
        // (120 - 105) * 2 = 30
        assertEquals(BigDecimal("30.00"), round2(p.realizedPnl))
        assertEquals(3, p.fills.size)
    }
}
