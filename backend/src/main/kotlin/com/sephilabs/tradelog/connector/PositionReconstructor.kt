// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.position.FillAction
import com.sephilabs.tradelog.position.FillSide
import com.sephilabs.tradelog.position.PositionSide
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import org.slf4j.LoggerFactory

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
    /** True when the venue itself says this fill reduces an open position; null when it does not say. */
    val reduces: Boolean? = null,
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

    private val log = LoggerFactory.getLogger(PositionReconstructor::class.java)

    private val MC = MathContext(34, RoundingMode.HALF_EVEN)

    // Flat-detection tolerance: net exposure within max(size * REL_EPS, ABS_EPS) counts as closed.
    private val REL_EPS = BigDecimal("0.001")
    private val ABS_EPS = BigDecimal("0.00000001")

    fun reconstruct(fills: List<RawFill>, normalize: (String) -> Symbol): List<PositionRecord> =
        reconstruct(fills, null, normalize)

    /** [openNow] is each group's signed exposure at the venue right now, which anchors a history that starts inside a position; null = unknown. */
    fun reconstruct(
        fills: List<RawFill>,
        openNow: Map<String, BigDecimal>?,
        normalize: (String) -> Symbol,
    ): List<PositionRecord> =
        fills.groupBy { it.symbol }.flatMap { (symbol, symFills) ->
            val sorted = symFills.sortedBy { it.ts }
            val predating = openNow?.let { exposureBeforeHistory(sorted, it[symbol] ?: BigDecimal.ZERO) } ?: BigDecimal.ZERO
            reconstructSymbol(symbol, sorted, normalize, predating)
        }

    /** Undoes the fills from the present back to the first one, snapping to flat at every round trip so derived-quantity rounding never accumulates. */
    private fun exposureBeforeHistory(sorted: List<RawFill>, openNow: BigDecimal): BigDecimal {
        var net = openNow
        var walked = BigDecimal.ZERO // gross quantity since the last flat point, which scales the tolerance
        for (f in sorted.asReversed()) {
            val qty = f.qty.abs()
            net = if (f.buy) net.subtract(qty) else net.add(qty)
            walked = walked.add(qty)
            if (net.abs() <= walked.multiply(REL_EPS).max(ABS_EPS)) {
                net = BigDecimal.ZERO
                walked = BigDecimal.ZERO
            }
        }
        return if (net.abs() <= ABS_EPS) BigDecimal.ZERO else net
    }

    /**
     * Gross realized PnL (quote currency) derived from the position's average leg prices:
     * `(exit − entry) × qty` for a long, negated for a short. Used by sources whose fills don't
     * carry a PnL field (Quantfury's spread-inclusive prices; BingX's fill endpoint omits PnL).
     */
    /** Zero for a record with no legs — a grid-bot run reports its PnL instead of deriving it. */
    fun realizedFromPrices(r: PositionRecord): BigDecimal {
        val entry = r.entryPrice ?: return BigDecimal.ZERO
        val exit = r.exitPrice ?: return BigDecimal.ZERO
        val qty = r.qty ?: return BigDecimal.ZERO
        return realizedFromPrices(r.side, entry, exit, qty)
    }

    fun realizedFromPrices(side: PositionSide, entry: BigDecimal, exit: BigDecimal, qty: BigDecimal): BigDecimal {
        val diff = if (side == PositionSide.LONG) exit.subtract(entry) else entry.subtract(exit)
        return diff.multiply(qty)
    }

    private fun reconstructSymbol(
        symbol: String,
        sorted: List<RawFill>,
        normalize: (String) -> Symbol,
        predating: BigDecimal,
    ): List<PositionRecord> {
        val out = mutableListOf<PositionRecord>()
        var net = predating                  // signed open exposure (base qty)
        var acc: Lifecycle? = null
        val orphans = Orphans(symbol, predating)
        if (predating.signum() != 0) {
            // The history starts inside a position: track it so its exits land somewhere, never emit it.
            val side = if (predating.signum() > 0) PositionSide.LONG else PositionSide.SHORT
            acc = Lifecycle.predating(side, predating.abs(), sorted.first().ts)
        }

        for (f in sorted) {
            var remaining = f.qty.abs()
            if (remaining.signum() == 0) {
                // A zero-quantity fill carries no exposure but may still carry money (e.g. a
                // funding- or fee-only row). Attribute it to the open lifecycle rather than
                // silently dropping it; with no open position there is nothing to attach it to.
                acc?.absorb(f)
                continue
            }
            while (remaining > BigDecimal.ZERO) {
                if (net.signum() == 0) {
                    if (f.reduces == true) {
                        // Nothing is open to reduce: the position was opened before the fetched history
                        // and cannot be rebuilt. Dropping the fill beats reading it as opening the other side.
                        orphans.fill(f)
                        break
                    }
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
                            // Reducing more than was ever seen opening means the rest of this position
                            // predates the fetched history: what was rebuilt is only a fragment.
                            val fragment = remaining > flatEps && f.reduces == true
                            if (fragment || cur.predatesHistory) orphans.position(f) else out += cur.build(symbol, normalize)
                            if (fragment) remaining = BigDecimal.ZERO
                            acc = null
                            net = BigDecimal.ZERO
                            if (remaining <= flatEps) remaining = BigDecimal.ZERO
                        }
                    }
                }
            }
        }
        orphans.report()
        // A still-open lifecycle (net != 0) is an OPEN position — out of scope; do not emit.
        return out
    }

    /** Fills and positions dropped because their opening predates the fetched history. */
    private class Orphans(private val symbol: String, private val predating: BigDecimal) {
        private var fills = 0
        private var positions = 0
        private var first: Instant? = null
        private var last: Instant? = null

        fun fill(f: RawFill) { fills++; span(f.ts) }

        fun position(f: RawFill) { positions++; span(f.ts) }

        fun report() {
            if (fills == 0 && positions == 0) return
            log.warn(
                "{}: dropped {} reducing fill(s) and {} position(s) between {} and {} whose opening predates the fetched history (exposure open at its start: {})",
                symbol, fills, positions, first, last, predating,
            )
        }

        private fun span(ts: Instant) {
            if (first == null || ts.isBefore(first)) first = ts
            if (last == null || ts.isAfter(last)) last = ts
        }
    }

    private class Lifecycle(val side: PositionSide, val openTs: Instant, val predatesHistory: Boolean = false) {
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

        companion object {
            /** A position already open when the history begins: sized so exits and the tolerance work, priced by nothing. */
            fun predating(side: PositionSide, qty: BigDecimal, firstTs: Instant): Lifecycle =
                Lifecycle(side, firstTs, predatesHistory = true).apply { entryQty = qty }
        }

        /** Fold a zero-quantity fill's money (fee/PnL/funding) into this lifecycle; no leg is added. */
        fun absorb(f: RawFill) {
            fees = fees.add(f.fee)
            pnl = pnl.add(f.realizedPnl)
            funding = funding.add(f.funding)
        }

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

        private fun leg(action: FillAction, f: RawFill, portion: BigDecimal): FillRecord {
            // A fill split across a zero-crossing produces one leg per side; each leg must carry
            // its proportional share of the fill's fee or the legs would sum to more than the fill.
            val frac = if (f.qty.signum() == 0) BigDecimal.ONE else portion.divide(f.qty.abs(), MC)
            return FillRecord(
                seq = legs.size,
                action = action,
                side = if (f.buy) FillSide.BUY else FillSide.SELL,
                ts = f.ts,
                price = f.price,
                qty = portion,
                value = f.price.multiply(portion),
                fee = f.fee.multiply(frac),
            )
        }

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
