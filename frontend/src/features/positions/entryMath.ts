import Decimal from "decimal.js";
import type { PositionSide } from "@/api/positions";

/**
 * Money math behind the manual-entry forms. Kept out of the components so each rule can be
 * unit-tested on its own and stated once.
 */

/** Gross realized PnL from the leg prices — the same rule the backend applies when the field is blank. */
export function realizedFromPrices(side: PositionSide, entry: Decimal, exit: Decimal, qty: Decimal): Decimal {
  return (side === "LONG" ? exit.minus(entry) : entry.minus(exit)).mul(qty);
}

/** Position size from the notional the user typed. Null until an entry price makes it computable. */
export function qtyFromNotional(notional: Decimal, entryPrice: Decimal): Decimal | null {
  if (entryPrice.lte(0)) return null;
  return notional.div(entryPrice);
}

/**
 * The two figures a closed grid shows, each recoverable from the other:
 * net = gross − fees − funding (verified against a reference run: 26.87 − 3.56 − 0.15 = 23.16).
 */
export function grossFromNet(net: Decimal, fees: Decimal, funding: Decimal): Decimal {
  return net.plus(fees).plus(funding);
}

export function netFromGross(gross: Decimal, fees: Decimal, funding: Decimal): Decimal {
  return gross.minus(fees).minus(funding);
}

/**
 * Traded volume of a grid run, from what its detail screen shows: every matched order is a buy and
 * a sell, so each contributes twice its notional — the same both-legs convention the Volume
 * statistic uses for ordinary positions.
 *
 * Deliberately not margin × leverage: that is the maximum deployable notional, and a grid usually
 * deploys only part of it (the reference run deployed 51%), so the error would be unbounded.
 */
export function gridVolume(matchedOrders: Decimal, sizePerGrid: Decimal, referencePrice: Decimal): Decimal | null {
  if (matchedOrders.lte(0) || sizePerGrid.lte(0) || referencePrice.lte(0)) return null;
  return matchedOrders.mul(sizePerGrid).mul(referencePrice).mul(2);
}

/** The grid's own return on the capital it was given. Informative feedback only — never analytics. */
export function gridRoi(net: Decimal, investment: Decimal): Decimal | null {
  if (investment.lte(0)) return null;
  return net.div(investment).mul(100);
}
