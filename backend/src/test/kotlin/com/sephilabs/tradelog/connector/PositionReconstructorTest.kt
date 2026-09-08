// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.position.FillAction
import com.sephilabs.tradelog.position.PositionSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionReconstructorTest {

    private val normalize: (String) -> Symbol = { Symbols.split(it) }
    private fun t(sec: Long) = Instant.ofEpochSecond(1_700_000_000 + sec)
    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `simple long open-close`() {
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("110"), qty = bd("1")),
        )
        val result = PositionReconstructor.reconstruct(fills, normalize)
        assertEquals(1, result.size)
        val p = result[0]
        assertEquals(PositionSide.LONG, p.side)
        assertEquals(0, p.qty!!.compareTo(bd("1")))
        assertEquals(0, p.entryPrice!!.compareTo(bd("100")))
        assertEquals(0, p.exitPrice!!.compareTo(bd("110")))
        assertEquals(Symbol("BTC", "USDT"), p.symbol)
        assertEquals(listOf(FillAction.OPEN, FillAction.CLOSE), p.fills.map { it.action })
    }

    @Test
    fun `short open-close`() {
        val fills = listOf(
            RawFill("ETHUSDT", t(0), buy = false, price = bd("50"), qty = bd("2")),
            RawFill("ETHUSDT", t(10), buy = true, price = bd("45"), qty = bd("2")),
        )
        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        assertEquals(PositionSide.SHORT, p.side)
        assertEquals(0, p.qty!!.compareTo(bd("2")))
        assertEquals(0, p.entryPrice!!.compareTo(bd("50")))
        assertEquals(0, p.exitPrice!!.compareTo(bd("45")))
    }

    @Test
    fun `add then full close yields vwap entry`() {
        val fills = listOf(
            RawFill("SUIUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("SUIUSDT", t(5), buy = true, price = bd("110"), qty = bd("1")),
            RawFill("SUIUSDT", t(9), buy = false, price = bd("120"), qty = bd("2")),
        )
        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        assertEquals(0, p.qty!!.compareTo(bd("2")))
        assertEquals(0, p.entryPrice!!.compareTo(bd("105")))
        assertEquals(0, p.exitPrice!!.compareTo(bd("120")))
        assertEquals(listOf(FillAction.OPEN, FillAction.ADD, FillAction.CLOSE), p.fills.map { it.action })
    }

    @Test
    fun `zero crossing closes long and leaves short open (open not emitted)`() {
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("90"), qty = bd("3")),
        )
        val result = PositionReconstructor.reconstruct(fills, normalize)
        // Only the closed LONG (qty 1) is emitted; the remaining SHORT (qty 2) is still open.
        assertEquals(1, result.size)
        assertEquals(PositionSide.LONG, result[0].side)
        assertEquals(0, result[0].qty!!.compareTo(bd("1")))
    }

    @Test
    fun `two separate lifecycles on same symbol`() {
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(5), buy = false, price = bd("110"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = true, price = bd("200"), qty = bd("1")),
            RawFill("BTCUSDT", t(15), buy = false, price = bd("190"), qty = bd("1")),
        )
        val result = PositionReconstructor.reconstruct(fills, normalize)
        assertEquals(2, result.size)
        assertTrue(result.all { it.side == PositionSide.LONG })
        assertEquals(0, result[1].entryPrice!!.compareTo(bd("200")))
    }

    @Test
    fun `rounding residual snaps to flat and does not contaminate the next trade`() {
        // BingX derives base qty as notional/price, so a clean close lands slightly off zero
        // (~0.01-0.05% of size). Two back-to-back round-trips, each closing 0.02% short of flat:
        // both must emit, and the first's residue must not bleed into the second (which historically
        // left a phantom sliver that kept the second lifecycle OPEN, dropping it entirely).
        val fills = listOf(
            RawFill("NEARUSDT", t(0), buy = true, price = bd("2.138"), qty = bd("93")),
            RawFill("NEARUSDT", t(5), buy = false, price = bd("2.160"), qty = bd("92.98")), // 0.02 residual
            RawFill("NEARUSDT", t(10), buy = true, price = bd("2.200"), qty = bd("50")),
            RawFill("NEARUSDT", t(15), buy = false, price = bd("2.210"), qty = bd("49.99")), // 0.01 residual
        )
        val result = PositionReconstructor.reconstruct(fills, normalize)
        assertEquals(2, result.size)
        assertTrue(result.all { it.side == PositionSide.LONG })
        assertEquals(0, result[0].qty!!.compareTo(bd("93")))
        assertEquals(0, result[1].qty!!.compareTo(bd("50")))
    }

    @Test
    fun `fee and pnl allocated proportionally on split`() {
        // Close 2 against open 1 splits the closing fill; the closing portion is 1/2 of its fee/pnl.
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("90"), qty = bd("2"), fee = bd("4"), realizedPnl = bd("-20")),
        )
        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        // 1 of the 2 closing units belongs to this lifecycle => half of fee and pnl.
        assertEquals(0, p.fees.compareTo(bd("2")))
        assertEquals(0, p.realizedPnl.compareTo(bd("-10")))
    }

    @Test
    fun `a declared reduce with nothing open is dropped instead of opening the other side`() {
        // Hedge mode: a SELL on the LONG side can only close. Its opening fills aged out of the
        // venue's history, so without the guard it would read as a SHORT that swallows the next trade.
        val fills = listOf(
            RawFill("BTCUSDT LONG", t(0), buy = false, price = bd("105"), qty = bd("1"), reduces = true),
            RawFill("BTCUSDT LONG", t(10), buy = true, price = bd("100"), qty = bd("1"), reduces = false),
            RawFill("BTCUSDT LONG", t(20), buy = false, price = bd("110"), qty = bd("1"), reduces = true),
        )
        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        assertEquals(PositionSide.LONG, p.side)
        assertEquals(t(10), p.openedAt)
        assertEquals(0, p.entryPrice!!.compareTo(bd("100")))
        assertEquals(0, p.exitPrice!!.compareTo(bd("110")))
    }

    @Test
    fun `a declared reduce larger than the visible opening drops the fragment and keeps the next trade`() {
        // Only the last add of an older position is in range: 0.5 opened, 1.5 closed. The fragment
        // would report a third of the real size, so it is not emitted; the following trade is intact.
        val fills = listOf(
            RawFill("ETHUSDT LONG", t(0), buy = true, price = bd("100"), qty = bd("0.5"), reduces = false),
            RawFill("ETHUSDT LONG", t(10), buy = false, price = bd("120"), qty = bd("1.5"), reduces = true),
            RawFill("ETHUSDT LONG", t(20), buy = true, price = bd("200"), qty = bd("2"), reduces = false),
            RawFill("ETHUSDT LONG", t(30), buy = false, price = bd("210"), qty = bd("2"), reduces = true),
        )
        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        assertEquals(t(20), p.openedAt)
        assertEquals(0, p.qty!!.compareTo(bd("2")))
        assertEquals(0, p.entryPrice!!.compareTo(bd("200")))
    }

    @Test
    fun `an undeclared zero crossing still flips sides in one-way mode`() {
        // Without a declared intent (one-way `BOTH`), selling past flat is a legitimate reversal.
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("110"), qty = bd("2")),
            RawFill("BTCUSDT", t(20), buy = true, price = bd("105"), qty = bd("1")),
        )
        val result = PositionReconstructor.reconstruct(fills, normalize)
        assertEquals(listOf(PositionSide.LONG, PositionSide.SHORT), result.map { it.side })
    }

    // ---------------------------------------------------------------------------------------------
    // Anchoring on the venue's open exposure: the visible history need not start flat.
    // ---------------------------------------------------------------------------------------------

    private val flatNow = mapOf("BTCUSDT" to BigDecimal.ZERO)

    @Test
    fun `a history that starts inside a position is anchored to what the venue holds now`() {
        // One-way mode: the first BUY covers a short opened before the history began. Read as an
        // opening, it would swallow both later round trips; anchored on "flat now", it is dropped.
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("0.1131")),
            RawFill("BTCUSDT", t(10), buy = true, price = bd("100"), qty = bd("0.05")),
            RawFill("BTCUSDT", t(20), buy = false, price = bd("110"), qty = bd("0.05")),
            RawFill("BTCUSDT", t(30), buy = true, price = bd("100"), qty = bd("0.02")),
            RawFill("BTCUSDT", t(40), buy = false, price = bd("105"), qty = bd("0.02")),
        )
        assertEquals(0, PositionReconstructor.reconstruct(fills, normalize).size)

        val anchored = PositionReconstructor.reconstruct(fills, flatNow, normalize)
        assertEquals(listOf(t(10), t(30)), anchored.map { it.openedAt })
        assertEquals(listOf(t(20), t(40)), anchored.map { it.closedAt })
        assertTrue(anchored.all { it.side == PositionSide.LONG })
    }

    @Test
    fun `an open position is left out while the round trips before it are emitted`() {
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("110"), qty = bd("1")),
            RawFill("BTCUSDT", t(20), buy = true, price = bd("105"), qty = bd("2")),
        )
        val p = PositionReconstructor.reconstruct(fills, mapOf("BTCUSDT" to bd("2")), normalize).single()
        assertEquals(t(0), p.openedAt)
        assertEquals(t(10), p.closedAt)
    }

    @Test
    fun `a one-way flip out of a pre-history position opens the new side cleanly`() {
        // BUY 0.3 covers an unseen 0.1 short and opens 0.2 long, which the SELL then closes.
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("0.3")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("110"), qty = bd("0.2")),
        )
        val p = PositionReconstructor.reconstruct(fills, flatNow, normalize).single()
        assertEquals(PositionSide.LONG, p.side)
        assertEquals(0, p.qty!!.compareTo(bd("0.2")))
        assertEquals(0, p.entryPrice!!.compareTo(bd("100")))
        assertEquals(0, p.realizedPnl.compareTo(bd("0")))  // derived later by the connector, not here
    }

    @Test
    fun `anchoring survives derived-quantity rounding across many round trips`() {
        // Each SELL lands 0.02% short of its BUY, as BingX's notional/price quantities do. Over 30
        // round trips that error (0.00006) exceeds the tolerance of the small unseen short (0.00002),
        // so undoing the fills must snap to flat at every round trip rather than carry the drift.
        val fills = mutableListOf(RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("0.02")))
        for (i in 1..30) {
            fills += RawFill("BTCUSDT", t(i * 20L), buy = true, price = bd("100"), qty = bd("0.01"))
            fills += RawFill("BTCUSDT", t(i * 20L + 10), buy = false, price = bd("101"), qty = bd("0.009998"))
        }
        val result = PositionReconstructor.reconstruct(fills, flatNow, normalize)
        assertEquals(30, result.size)
        assertTrue(result.none { it.openedAt == t(0) })
    }

    @Test
    fun `without an anchor the venue's silence keeps the flat-start assumption`() {
        val fills = listOf(
            RawFill("BTCUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("BTCUSDT", t(10), buy = false, price = bd("110"), qty = bd("1")),
        )
        assertEquals(1, PositionReconstructor.reconstruct(fills, null, normalize).size)
        assertEquals(1, PositionReconstructor.reconstruct(fills, emptyMap(), normalize).size)
    }
}
