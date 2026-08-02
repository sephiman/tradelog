import type { BenchmarkDailyClose, BenchmarkDailySeries } from "@/api/benchmarks";
import type { SnapshotDay } from "@/api/capital";

/** Close per calendar day, as a number; null where the benchmark has a genuine gap. */
export function pricesByDate(points: BenchmarkDailyClose[] | undefined): Map<string, number | null> {
  const map = new Map<string, number | null>();
  for (const p of points ?? []) {
    const close = p.close === null || p.close === "" ? null : Number(p.close);
    map.set(p.date, close !== null && Number.isFinite(close) && close > 0 ? close : null);
  }
  return map;
}

export function benchmarkSeriesByKey(series: BenchmarkDailySeries[] | undefined): Map<string, Map<string, number | null>> {
  return new Map((series ?? []).map((s) => [s.key, pricesByDate(s.points)]));
}

/**
 * The lump-sum comparison drawn over the capital areas: what the capital already held at the start
 * of the window would be worth had it tracked the benchmark instead.
 *
 * Each exchange is anchored on its OWN first day in the window rather than on the combined total.
 * Anchoring on the total would make an exchange added mid-range look like benchmark-beating growth,
 * when nothing was earned — the money was simply moved into view. Per-exchange baselines then sum
 * into the single line the chart renders.
 *
 * This is a lump sum by definition: capital added after an exchange's baseline day is NOT projected,
 * so the two curves diverge on deposits. That is the accepted limitation of the comparison, not a
 * defect — the data model records balances, and cannot tell a deposit from a corrected balance.
 *
 * [days] must be ascending by date, which is how the snapshot endpoint returns them.
 */
export function projectLumpSum(
  days: SnapshotDay[],
  isVisible: (exchange: string) => boolean,
  priceByDate: Map<string, number | null>,
): Map<string, number | null> {
  const out = new Map<string, number | null>();
  /** Baselined exchanges: the amount held on the baseline day, and the price paid that day. */
  const holdings: { amount: number; basePrice: number }[] = [];
  const baselined = new Set<string>();
  /** An exchange entered the window on a day the benchmark cannot price, so no sum is honest. */
  let unpriceable = false;

  for (const day of days) {
    for (const value of day.values) {
      if (!isVisible(value.exchange) || baselined.has(value.exchange)) continue;
      const amount = Number(value.amount);
      // Zero is a real balance ("no capital here"), but projecting it forward just draws a flat
      // zero for ever. An exchange starts contributing on the first day it actually holds something.
      if (!Number.isFinite(amount) || amount <= 0) continue;
      baselined.add(value.exchange);
      const basePrice = priceByDate.get(day.date) ?? null;
      if (basePrice === null) {
        unpriceable = true;
        continue;
      }
      holdings.push({ amount, basePrice });
    }

    const price = priceByDate.get(day.date) ?? null;
    if (unpriceable || holdings.length === 0 || price === null) {
      // Summing only the exchanges that happen to be priceable would quietly understate the line,
      // which reads as underperformance rather than as missing data. A gap says what is true.
      out.set(day.date, null);
      continue;
    }
    out.set(
      day.date,
      holdings.reduce((total, h) => total + (h.amount * price) / h.basePrice, 0),
    );
  }
  return out;
}
