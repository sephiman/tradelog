// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.journal

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.position.PositionSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset

class JournalCsvConnectorTest {

    private val connector = JournalCsvConnector()
    private val header = "symbol;side;opened_at;closed_at;quantity;entry_price;exit_price;realized_pnl;fees;funding;note"

    private fun parse(vararg rows: String) =
        connector.parse(ByteArrayInputStream((listOf(header) + rows).joinToString("\n").toByteArray()))

    private fun round2(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_EVEN)

    @Test
    fun `notional-style long with explicit pnl maps cleanly`() {
        // Journal row 4 (BTC), totals as prices, qty=1, gross pnl supplied.
        val p = parse("BTC;long;2024-10-31;2024-11-06;1;21147.66;22514.18;1366.52;171.76;0;").single()
        assertEquals("BTC", p.symbol.base)
        assertEquals("USDT", p.symbol.quote)
        assertEquals(PositionSide.LONG, p.side)
        assertEquals(0, p.qty.compareTo(BigDecimal.ONE))
        assertEquals(BigDecimal("1366.52"), round2(p.realizedPnl))
        assertEquals(BigDecimal("171.76"), round2(p.fees))
        assertEquals(2024, p.openedAt.atZone(ZoneOffset.UTC).year)
    }

    @Test
    fun `pnl is computed from prices when the column is blank`() {
        // Short loses when price rises: (entry - exit) * qty = (297.60 - 300.40) * 2 = -5.60
        val p = parse("TAO/USDT;short;2024-11-01;2024-11-01;2;297.60;300.40;;1,00;0;short scalp").single()
        assertEquals(PositionSide.SHORT, p.side)
        assertEquals(BigDecimal("-5.60"), round2(p.realizedPnl))
        assertEquals(BigDecimal("1.00"), round2(p.fees))
        assertEquals("short scalp", p.note)
    }

    @Test
    fun `european decimals and thousands separators parse`() {
        val p = parse("ETH;long;2024-11-29;2024-11-29;1;\"6.866,25\";\"7.047,17\";\"154,20\";\"26,72\";0;").single()
        assertEquals(BigDecimal("6866.25"), round2(p.entryPrice))
        assertEquals(BigDecimal("154.20"), round2(p.realizedPnl))
    }

    @Test
    fun `quantity defaults to one and optional columns default to zero`() {
        val p = parse("SUI;long;2024-11-08;2024-11-08;;100;110;;;;").single()
        assertEquals(0, p.qty.compareTo(BigDecimal.ONE))
        assertEquals(BigDecimal("10.00"), round2(p.realizedPnl))
        assertEquals(0, p.fees.compareTo(BigDecimal.ZERO))
        assertEquals(0, p.funding.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `identical same-day rows get distinct external ids but are deterministic`() {
        val row = "DOGE;long;2024-11-13;2024-11-13;1;100;110;10;0;0;"
        val first = parse(row, row)
        assertEquals(2, first.size)
        assertNotEquals(first[0].externalId, first[1].externalId)
        // Re-parsing the same file reproduces the same ids (idempotent upsert).
        val second = parse(row, row)
        assertEquals(first.map { it.externalId }, second.map { it.externalId })
    }

    @Test
    fun `exchange column populates the venue, note stays the user text`() {
        val h = "symbol;side;opened_at;closed_at;entry_price;exit_price;exchange;note"
        val csv = "$h\nBTC;long;2024-01-01;2024-01-02;100;110;FTX;swing long"
        val p = connector.parse(ByteArrayInputStream(csv.toByteArray())).single()
        assertEquals("FTX", p.exchange)
        assertEquals("swing long", p.note)

        val csv2 = "$h\nBTC;long;2024-01-01;2024-01-02;100;110;;"
        val blank = connector.parse(ByteArrayInputStream(csv2.toByteArray())).single()
        assertEquals(null, blank.exchange)
    }

    @Test
    fun `blank lines and comments are ignored`() {
        val p = parse("# this is a comment", "", "LINK;long;2024-11-09;2024-11-09;1;658.23;679.38;19.82;1.34;0;")
        assertEquals(1, p.size)
    }

    @Test
    fun `bad side is rejected with a row-scoped error`() {
        val ex = assertThrows(AppException::class.java) {
            parse("BTC;sideways;2024-11-01;2024-11-02;1;100;110;;;;")
        }
        assertTrue(ex.code == "IMPORT_ROW_INVALID" || ex.code == "IMPORT_BAD_SIDE")
    }

    @Test
    fun `missing required column is rejected`() {
        assertThrows(AppException::class.java) {
            parse("BTC;long;2024-11-01;2024-11-02;1;;110;;;;") // entry_price empty
        }
    }
}
