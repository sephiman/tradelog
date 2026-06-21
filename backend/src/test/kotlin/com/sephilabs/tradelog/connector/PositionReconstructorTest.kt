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
        assertEquals(0, p.qty.compareTo(bd("1")))
        assertEquals(0, p.entryPrice.compareTo(bd("100")))
        assertEquals(0, p.exitPrice.compareTo(bd("110")))
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
        assertEquals(0, p.qty.compareTo(bd("2")))
        assertEquals(0, p.entryPrice.compareTo(bd("50")))
        assertEquals(0, p.exitPrice.compareTo(bd("45")))
    }

    @Test
    fun `add then full close yields vwap entry`() {
        val fills = listOf(
            RawFill("SUIUSDT", t(0), buy = true, price = bd("100"), qty = bd("1")),
            RawFill("SUIUSDT", t(5), buy = true, price = bd("110"), qty = bd("1")),
            RawFill("SUIUSDT", t(9), buy = false, price = bd("120"), qty = bd("2")),
        )
        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        assertEquals(0, p.qty.compareTo(bd("2")))
        assertEquals(0, p.entryPrice.compareTo(bd("105")))
        assertEquals(0, p.exitPrice.compareTo(bd("120")))
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
        assertEquals(0, result[0].qty.compareTo(bd("1")))
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
        assertEquals(0, result[1].entryPrice.compareTo(bd("200")))
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
        assertEquals(0, result[0].qty.compareTo(bd("93")))
        assertEquals(0, result[1].qty.compareTo(bd("50")))
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
}
