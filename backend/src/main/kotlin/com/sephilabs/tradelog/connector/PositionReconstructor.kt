// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.position.FillAction
import com.sephilabs.tradelog.position.FillSide
import com.sephilabs.tradelog.position.PositionSide
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

/** A single execution as reported by an exchange, before reconstruction into positions. */
data class RawFill(
    val symbol: String,
    val ts: Instant,
    val buy: Boolean,
    val price: BigDecimal,
    val qty: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    val funding: BigDecimal = BigDecimal.ZERO,
)

/**
 * Folds raw fills into canonical flat-to-flat positions: net exposure leaving zero until it
 * returns to zero is one position; scaling in/out within that lifecycle stays a single position.
 * A fill that crosses zero is split into a closing leg on the old side and an opening leg on the
 * new side. Per-fill fees and realized PnL are allocated proportionally when a fill is split.
 *
 * This is pure, deterministic logic shared by API connectors that must reconstruct positions
 * (e.g. BingX), and is exercised directly by unit tests.
 */
object PositionReconstructor {

    private val MC = MathContext(34, RoundingMode.HALF_EVEN)

    // Flat-detection tolerance: net exposure within max(size * REL_EPS, ABS_EPS) counts as closed.
    private val REL_EPS = BigDecimal("0.001")
    private val ABS_EPS = BigDecimal("0.00000001")

    fun reconstruct(fills: List<RawFill>, normalize: (String) -> Symbol): List<PositionRecord> =
        fills.groupBy { it.symbol }
            .flatMap { (symbol, symFills) -> reconstructSymbol(symbol, symFills.sortedBy { it.ts }, normalize) }

    /**
     * Gross realized PnL (quote currency) derived from the position's average leg prices:
     * `(exit − entry) × qty` for a long, negated for a short. Used by sources whose fills don't
     * carry a PnL field (Quantfury's spread-inclusive prices; BingX's fill endpoint omits PnL).
     */
    fun realizedFromPrices(r: PositionRecord): BigDecimal {
        val diff = if (r.side == PositionSide.LONG) r.exitPrice.subtract(r.entryPrice)
        else r.entryPrice.subtract(r.exitPrice)
        return diff.multiply(r.qty)
    }

    private fun reconstructSymbol(symbol: String, sorted: List<RawFill>, normalize: (String) -> Symbol): List<PositionRecord> {
        val out = mutableListOf<PositionRecord>()
        var net = BigDecimal.ZERO            // signed open exposure (base qty)
        var acc: Lifecycle? = null

        for (f in sorted) {
            var remaining = f.qty.abs()
            while (remaining > BigDecimal.ZERO) {
                if (net.signum() == 0) {
                    // Opening from flat — consume the whole remainder on this side.
                    acc = Lifecycle(if (f.buy) PositionSide.LONG else PositionSide.SHORT, f.ts)
                    acc.applyEntry(f, remaining)
                    net = if (f.buy) remaining else remaining.negate()
                    remaining = BigDecimal.ZERO
                } else {
                    val sameSign = (net.signum() > 0 && f.buy) || (net.signum() < 0 && !f.buy)
                    val cur = acc!!
                    if (sameSign) {
                        cur.applyEntry(f, remaining)
                        net = net.add(if (f.buy) remaining else remaining.negate())
                        remaining = BigDecimal.ZERO
                    } else {
                        // Reducing/closing; cannot reduce by more than the open exposure in one step.
                        val reduce = remaining.min(net.abs())
                        cur.applyExit(f, reduce)
                        net = if (net.signum() > 0) net.subtract(reduce) else net.add(reduce)
                        remaining = remaining.subtract(reduce)
                        // Snap a tiny residual to flat: derived quantities rarely hit *exactly*
                        // zero (Quantfury open/close differ by ~1e-8; BingX qty=notional/price
                        // rounding by ~0.01-0.05%). Without a tolerance wide enough for the source,
                        // the next position's fills would merge into this never-closed lifecycle.
                        val flatEps = cur.entrySize().multiply(REL_EPS).max(ABS_EPS)
                        if (net.abs() <= flatEps) {
                            out += cur.build(symbol, normalize)
                            acc = null
                            net = BigDecimal.ZERO
                            if (remaining <= flatEps) remaining = BigDecimal.ZERO
                        }
                    }
                }
            }
        }
        // A still-open lifecycle (net != 0) is an OPEN position — out of scope; do not emit.
        return out
    }

    private class Lifecycle(val side: PositionSide, val openTs: Instant) {
        private var closeTs: Instant = openTs
        private var entryQty = BigDecimal.ZERO
        private var entryNotional = BigDecimal.ZERO
        private var exitQty = BigDecimal.ZERO
        private var exitNotional = BigDecimal.ZERO
        private var fees = BigDecimal.ZERO
        private var pnl = BigDecimal.ZERO
        private var funding = BigDecimal.ZERO
        private val legs = mutableListOf<FillRecord>()

        /** Total quantity opened on the entry side, used to scale the flat-detection tolerance. */
        fun entrySize(): BigDecimal = entryQty

        fun applyEntry(f: RawFill, portion: BigDecimal) {
            entryQty = entryQty.add(portion)
            entryNotional = entryNotional.add(f.price.multiply(portion))
            allocate(f, portion)
            closeTs = f.ts
            legs += leg(if (legs.isEmpty()) FillAction.OPEN else FillAction.ADD, f, portion)
        }

        fun applyExit(f: RawFill, portion: BigDecimal) {
            exitQty = exitQty.add(portion)
            exitNotional = exitNotional.add(f.price.multiply(portion))
            allocate(f, portion)
            closeTs = f.ts
            // CLOSE vs REDUCE is finalized in build(); mark provisionally as REDUCE.
            legs += leg(FillAction.REDUCE, f, portion)
        }

        private fun allocate(f: RawFill, portion: BigDecimal) {
            val frac = if (f.qty.signum() == 0) BigDecimal.ONE else portion.divide(f.qty.abs(), MC)
            fees = fees.add(f.fee.multiply(frac))
            pnl = pnl.add(f.realizedPnl.multiply(frac))
            funding = funding.add(f.funding.multiply(frac))
        }

        private fun leg(action: FillAction, f: RawFill, portion: BigDecimal) = FillRecord(
            seq = legs.size,
            action = action,
            side = if (f.buy) FillSide.BUY else FillSide.SELL,
            ts = f.ts,
            price = f.price,
            qty = portion,
            value = f.price.multiply(portion),
            fee = f.fee,
        )

        fun build(symbol: String, normalize: (String) -> Symbol): PositionRecord {
            // The last exit leg is the close.
            legs.lastOrNull()?.let { last ->
                if (last.action == FillAction.REDUCE) legs[legs.size - 1] = last.copy(action = FillAction.CLOSE)
            }
            val sym = normalize(symbol)
            val entry = if (entryQty.signum() > 0) entryNotional.divide(entryQty, MC) else BigDecimal.ZERO
            val exit = if (exitQty.signum() > 0) exitNotional.divide(exitQty, MC) else BigDecimal.ZERO
            return PositionRecord(
                externalId = "${sym.base}${sym.quote}-${openTs.toEpochMilli()}-${closeTs.toEpochMilli()}-${side.name.first()}",
                symbol = sym,
                side = side,
                openedAt = openTs,
                closedAt = closeTs,
                qty = entryQty,
                entryPrice = entry,
                exitPrice = exit,
                realizedPnl = pnl,
                fees = fees,
                funding = funding,
                fills = legs.toList(),
            )
        }
    }
}
