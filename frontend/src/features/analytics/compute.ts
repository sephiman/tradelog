import Decimal from "decimal.js";
import { getDaysInMonth } from "date-fns";
import { toDecimal } from "@/lib/format";
import type { ClosedPosition } from "@/api/analytics";

/**
 * Pure metric computations for the analytics dashboard. No React / recharts here so everything is
 * unit-testable. Money math uses decimal.js; calendar bucketing uses date-fns; hour/day/weekday are
 * resolved in the user's time zone via Intl (no extra dependency).
 *
 * Conventions:
 * - net result of a trade = the backend's authoritative `netPnl` (gross realizedPnl − fees −
 *   funding, with per-source corrections applied server-side). Used directly, never recomputed.
 * - A trade is a win if net > 0, a loss if net < 0; net == 0 is excluded from win-rate denominators.
 * - `winRate` in chart-facing bucket functions is a percentage (0–100); in `Stats` it is a fraction
 *   (0–1), since it feeds the expectancy formula directly.
 */

export const SCALPER_MAX_MS = 15 * 60 * 1000;
export const DAY_MAX_MS = 24 * 60 * 60 * 1000;
const ZERO = new Decimal(0);

/** Net result of a trade — the backend's authoritative bottom line. */
export function netOf(p: ClosedPosition): Decimal {
  return toDecimal(p.netPnl);
}

/**
 * Notional volume of a round-trip trade, exchange-standard: both legs count, so
 * volume = qty × (entryPrice + exitPrice). This matches how venues tally traded volume for fee
 * tiers — opening and closing each contribute their notional.
 */
export function volumeOf(p: ClosedPosition): Decimal {
  return toDecimal(p.qty).times(toDecimal(p.entryPrice).plus(toDecimal(p.exitPrice)));
}

export function pairOf(p: ClosedPosition): string {
  return `${p.symbolBase}/${p.symbolQuote}`;
}

const byClose = (a: ClosedPosition, b: ClosedPosition): number =>
  a.closedAt < b.closedAt ? -1 : a.closedAt > b.closedAt ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;

const sum = (xs: Decimal[]): Decimal => xs.reduce((acc, x) => acc.plus(x), ZERO);

// ---------------------------------------------------------------------------
// Time zone
// ---------------------------------------------------------------------------

export interface ZonedParts {
  year: number;
  month: number; // 1–12
  day: number; // 1–31
  hour: number; // 0–23
  weekday: number; // 0=Mon … 6=Sun
}

const WEEKDAY_INDEX: Record<string, number> = { Mon: 0, Tue: 1, Wed: 2, Thu: 3, Fri: 4, Sat: 5, Sun: 6 };

/** Calendar parts of an instant as seen in `timeZone`. */
export function zonedParts(iso: string, timeZone: string): ZonedParts {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    weekday: "short",
  }).formatToParts(new Date(iso));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  let hour = parseInt(get("hour"), 10);
  if (hour === 24) hour = 0; // some engines render midnight as "24"
  return {
    year: parseInt(get("year"), 10),
    month: parseInt(get("month"), 10),
    day: parseInt(get("day"), 10),
    hour,
    weekday: WEEKDAY_INDEX[get("weekday")] ?? 0,
  };
}

const pct = (wins: number, decided: number): number | null => (decided > 0 ? (wins / decided) * 100 : null);

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

export interface Stats {
  total: number; // all trades in the set (incl. net == 0)
  wins: number;
  losses: number;
  totalPnl: Decimal;
  volume: Decimal; // total notional traded, both legs (qty × (entry + exit))
  winRate: number | null; // fraction 0–1 over decided trades
  lossRate: number | null;
  avgWin: Decimal | null;
  avgLoss: Decimal | null; // negative
  expectancy: Decimal | null;
  avgRR: Decimal | null; // avgWin / |avgLoss|
  largestWin: Decimal | null;
  largestLoss: Decimal | null; // negative
}

export function computeStats(rows: ClosedPosition[]): Stats {
  const nets = rows.map(netOf);
  const wins = nets.filter((n) => n.gt(0));
  const losses = nets.filter((n) => n.lt(0));
  const decided = wins.length + losses.length;

  const winRate = decided > 0 ? wins.length / decided : null;
  const lossRate = decided > 0 ? losses.length / decided : null;
  const avgWin = wins.length > 0 ? sum(wins).div(wins.length) : null;
  const avgLoss = losses.length > 0 ? sum(losses).div(losses.length) : null;

  let expectancy: Decimal | null = null;
  if (decided > 0) {
    const wTerm = winRate !== null && avgWin ? new Decimal(winRate).times(avgWin) : ZERO;
    const lTerm = lossRate !== null && avgLoss ? new Decimal(lossRate).times(avgLoss.abs()) : ZERO;
    expectancy = wTerm.minus(lTerm);
  }
  const avgRR = avgWin && avgLoss && !avgLoss.isZero() ? avgWin.div(avgLoss.abs()) : null;

  return {
    total: rows.length,
    wins: wins.length,
    losses: losses.length,
    totalPnl: sum(nets),
    volume: sum(rows.map(volumeOf)),
    winRate,
    lossRate,
    avgWin,
    avgLoss,
    expectancy,
    avgRR,
    largestWin: wins.length > 0 ? wins.reduce((a, n) => Decimal.max(a, n)) : null,
    largestLoss: losses.length > 0 ? losses.reduce((a, n) => Decimal.min(a, n)) : null,
  };
}

export interface EquityPoint {
  closedAt: string;
  cumulative: number;
}

/** Running sum of net results, ordered by close date — the equity curve. */
export function equityCurve(rows: ClosedPosition[]): EquityPoint[] {
  let run = ZERO;
  return [...rows].sort(byClose).map((p) => {
    run = run.plus(netOf(p));
    return { closedAt: p.closedAt, cumulative: run.toNumber() };
  });
}

// ---------------------------------------------------------------------------
// Performance (monthly / yearly)
// ---------------------------------------------------------------------------

export interface DayActivity {
  day: number;
  longs: number;
  shorts: number;
  winRate: number | null; // 0–100
}

export function activityByDayOfMonth(rows: ClosedPosition[], year: number, month: number, tz: string): DayActivity[] {
  const days = getDaysInMonth(new Date(year, month - 1, 1));
  const acc = Array.from({ length: days }, (_, i) => ({ day: i + 1, longs: 0, shorts: 0, wins: 0, decided: 0 }));
  for (const p of rows) {
    const z = zonedParts(p.closedAt, tz);
    if (z.year !== year || z.month !== month) continue;
    const b = acc[z.day - 1];
    if (p.side === "LONG") b.longs++;
    else b.shorts++;
    const n = netOf(p);
    if (!n.isZero()) {
      b.decided++;
      if (n.gt(0)) b.wins++;
    }
  }
  return acc.map((b) => ({ day: b.day, longs: b.longs, shorts: b.shorts, winRate: pct(b.wins, b.decided) }));
}

export interface DayPnl {
  day: number;
  pnl: number;
}

export function pnlByDayOfMonth(rows: ClosedPosition[], year: number, month: number, tz: string): DayPnl[] {
  const days = getDaysInMonth(new Date(year, month - 1, 1));
  const acc = Array.from({ length: days }, () => ZERO);
  for (const p of rows) {
    const z = zonedParts(p.closedAt, tz);
    if (z.year !== year || z.month !== month) continue;
    acc[z.day - 1] = acc[z.day - 1].plus(netOf(p));
  }
  return acc.map((pnl, i) => ({ day: i + 1, pnl: pnl.toNumber() }));
}

export interface MonthPnl {
  month: number; // 1–12
  pnl: number;
  winRate: number | null; // 0–100
}

export function pnlByMonth(rows: ClosedPosition[], year: number, tz: string): MonthPnl[] {
  const acc = Array.from({ length: 12 }, () => ({ pnl: ZERO, wins: 0, decided: 0 }));
  for (const p of rows) {
    const z = zonedParts(p.closedAt, tz);
    if (z.year !== year) continue;
    const b = acc[z.month - 1];
    const n = netOf(p);
    b.pnl = b.pnl.plus(n);
    if (!n.isZero()) {
      b.decided++;
      if (n.gt(0)) b.wins++;
    }
  }
  return acc.map((b, i) => ({ month: i + 1, pnl: b.pnl.toNumber(), winRate: pct(b.wins, b.decided) }));
}

// ---------------------------------------------------------------------------
// Behavior
// ---------------------------------------------------------------------------

export interface RateBucket {
  key: number; // hour 0–23 or weekday 0–6
  winRate: number | null; // 0–100
  count: number;
}

function rateBuckets(rows: ClosedPosition[], size: number, keyOf: (z: ZonedParts) => number, tz: string): RateBucket[] {
  const acc = Array.from({ length: size }, () => ({ wins: 0, decided: 0, count: 0 }));
  for (const p of rows) {
    const b = acc[keyOf(zonedParts(p.closedAt, tz))];
    b.count++;
    const n = netOf(p);
    if (!n.isZero()) {
      b.decided++;
      if (n.gt(0)) b.wins++;
    }
  }
  return acc.map((b, i) => ({ key: i, winRate: pct(b.wins, b.decided), count: b.count }));
}

export const winRateByHour = (rows: ClosedPosition[], tz: string): RateBucket[] =>
  rateBuckets(rows, 24, (z) => z.hour, tz);

export const winRateByWeekday = (rows: ClosedPosition[], tz: string): RateBucket[] =>
  rateBuckets(rows, 7, (z) => z.weekday, tz);

export interface DirStat {
  count: number;
  winRate: number | null; // fraction 0–1
  totalPnl: Decimal;
  expectancy: Decimal | null;
}

export interface DirectionBreakdown {
  long: DirStat;
  short: DirStat;
}

export function directionBreakdown(rows: ClosedPosition[]): DirectionBreakdown {
  const dir = (side: "LONG" | "SHORT"): DirStat => {
    const s = computeStats(rows.filter((p) => p.side === side));
    return { count: s.total, winRate: s.winRate, totalPnl: s.totalPnl, expectancy: s.expectancy };
  };
  return { long: dir("LONG"), short: dir("SHORT") };
}

export type TraderStyleKey = "scalper" | "day" | "swing";

export interface StyleBucket {
  count: number;
  pnl: number;
  winRate: number | null; // 0–100
}

export interface TraderStyle {
  scalper: StyleBucket;
  day: StyleBucket;
  swing: StyleBucket;
  avgDurationMs: number | null;
  longestMs: number | null;
  shortestMs: number | null;
  predominant: TraderStyleKey | null;
}

const durationMs = (p: ClosedPosition): number => new Date(p.closedAt).getTime() - new Date(p.openedAt).getTime();

export function styleOf(ms: number): TraderStyleKey {
  if (ms < SCALPER_MAX_MS) return "scalper";
  if (ms <= DAY_MAX_MS) return "day";
  return "swing";
}

export function traderStyle(rows: ClosedPosition[]): TraderStyle {
  const acc: Record<TraderStyleKey, { count: number; pnl: Decimal; wins: number; decided: number }> = {
    scalper: { count: 0, pnl: ZERO, wins: 0, decided: 0 },
    day: { count: 0, pnl: ZERO, wins: 0, decided: 0 },
    swing: { count: 0, pnl: ZERO, wins: 0, decided: 0 },
  };
  const durations: number[] = [];
  for (const p of rows) {
    const ms = durationMs(p);
    durations.push(ms);
    const b = acc[styleOf(ms)];
    b.count++;
    const n = netOf(p);
    b.pnl = b.pnl.plus(n);
    if (!n.isZero()) {
      b.decided++;
      if (n.gt(0)) b.wins++;
    }
  }
  const bucket = (k: TraderStyleKey): StyleBucket => ({ count: acc[k].count, pnl: acc[k].pnl.toNumber(), winRate: pct(acc[k].wins, acc[k].decided) });
  const predominant = (["scalper", "day", "swing"] as TraderStyleKey[]).reduce<TraderStyleKey | null>(
    (best, k) => (acc[k].count === 0 ? best : best === null || acc[k].count > acc[best].count ? k : best),
    null,
  );
  return {
    scalper: bucket("scalper"),
    day: bucket("day"),
    swing: bucket("swing"),
    avgDurationMs: durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : null,
    longestMs: durations.length ? Math.max(...durations) : null,
    shortestMs: durations.length ? Math.min(...durations) : null,
    predominant,
  };
}

// ---------------------------------------------------------------------------
// Streaks
// ---------------------------------------------------------------------------

export interface StreakStat {
  avgLength: number | null;
  longestLength: number;
  longestNetPnl: number;
}

export interface Streaks {
  winning: StreakStat;
  losing: StreakStat;
}

/** Walks trades in close order; net == 0 rows are skipped so they neither extend nor break a run. */
export function streaks(rows: ClosedPosition[]): Streaks {
  const seq = [...rows]
    .sort(byClose)
    .map((p) => ({ win: netOf(p), value: netOf(p) }))
    .filter((x) => !x.win.isZero());

  const runs = { win: [] as { len: number; pnl: Decimal }[], loss: [] as { len: number; pnl: Decimal }[] };
  let curWin: boolean | null = null;
  let len = 0;
  let pnl = ZERO;
  const flush = () => {
    if (curWin === null || len === 0) return;
    runs[curWin ? "win" : "loss"].push({ len, pnl });
  };
  for (const x of seq) {
    const isWin = x.value.gt(0);
    if (isWin !== curWin) {
      flush();
      curWin = isWin;
      len = 0;
      pnl = ZERO;
    }
    len++;
    pnl = pnl.plus(x.value);
  }
  flush();

  const stat = (rs: { len: number; pnl: Decimal }[]): StreakStat => {
    if (rs.length === 0) return { avgLength: null, longestLength: 0, longestNetPnl: 0 };
    const longest = rs.reduce((a, b) => (b.len > a.len ? b : a));
    return {
      avgLength: rs.reduce((a, b) => a + b.len, 0) / rs.length,
      longestLength: longest.len,
      longestNetPnl: longest.pnl.toNumber(),
    };
  };
  return { winning: stat(runs.win), losing: stat(runs.loss) };
}

export interface Recovery {
  rate: number | null; // fraction 0–1
  sample: number; // losses that had a following trade
}

/** Share of losing trades immediately followed by a win (net == 0 followers count as non-recoveries). */
export function recoveryRate(rows: ClosedPosition[]): Recovery {
  const nets = [...rows].sort(byClose).map(netOf);
  let sample = 0;
  let recoveries = 0;
  for (let i = 0; i < nets.length - 1; i++) {
    if (nets[i].lt(0)) {
      sample++;
      if (nets[i + 1].gt(0)) recoveries++;
    }
  }
  return { rate: sample > 0 ? recoveries / sample : null, sample };
}

/** day-of-month -> net PnL, for the month calendar (only days that have trades appear). */
export function calendar(rows: ClosedPosition[], year: number, month: number, tz: string): Record<number, number> {
  const acc: Record<number, Decimal> = {};
  for (const p of rows) {
    const z = zonedParts(p.closedAt, tz);
    if (z.year !== year || z.month !== month) continue;
    acc[z.day] = (acc[z.day] ?? ZERO).plus(netOf(p));
  }
  const out: Record<number, number> = {};
  for (const [day, pnl] of Object.entries(acc)) out[Number(day)] = pnl.toNumber();
  return out;
}

// ---------------------------------------------------------------------------
// Pairs
// ---------------------------------------------------------------------------

export interface PairStat {
  pair: string;
  count: number;
  totalPnl: number;
}

export interface PairsRankings {
  mostTraded: PairStat[];
  mostProfitable: PairStat[];
  leastProfitable: PairStat[];
}

export function pairsRankings(rows: ClosedPosition[], limit = 10): PairsRankings {
  const acc = new Map<string, { count: number; pnl: Decimal }>();
  for (const p of rows) {
    const key = pairOf(p);
    const b = acc.get(key) ?? { count: 0, pnl: ZERO };
    b.count++;
    b.pnl = b.pnl.plus(netOf(p));
    acc.set(key, b);
  }
  const all: PairStat[] = [...acc.entries()].map(([pair, b]) => ({ pair, count: b.count, totalPnl: b.pnl.toNumber() }));
  return {
    mostTraded: [...all].sort((a, b) => b.count - a.count || b.totalPnl - a.totalPnl).slice(0, limit),
    mostProfitable: all.filter((p) => p.totalPnl > 0).sort((a, b) => b.totalPnl - a.totalPnl).slice(0, limit),
    leastProfitable: all.filter((p) => p.totalPnl < 0).sort((a, b) => a.totalPnl - b.totalPnl).slice(0, limit),
  };
}

// ---------------------------------------------------------------------------
// Fees
// ---------------------------------------------------------------------------

export interface DayFee {
  day: number;
  fee: number;
}

export interface FeesMonth {
  days: DayFee[];
  total: number;
}

export function feesByDayOfMonth(rows: ClosedPosition[], year: number, month: number, tz: string): FeesMonth {
  const days = getDaysInMonth(new Date(year, month - 1, 1));
  const acc = Array.from({ length: days }, () => ZERO);
  for (const p of rows) {
    const z = zonedParts(p.closedAt, tz);
    if (z.year !== year || z.month !== month) continue;
    acc[z.day - 1] = acc[z.day - 1].plus(toDecimal(p.fees));
  }
  return { days: acc.map((fee, i) => ({ day: i + 1, fee: fee.toNumber() })), total: sum(acc).toNumber() };
}

export interface CumFeePoint {
  closedAt: string;
  cumulative: number;
}

export function cumulativeFees(rows: ClosedPosition[]): CumFeePoint[] {
  let run = ZERO;
  return [...rows].sort(byClose).map((p) => {
    run = run.plus(toDecimal(p.fees));
    return { closedAt: p.closedAt, cumulative: run.toNumber() };
  });
}

export interface DayFeeRatio {
  day: number;
  fees: number;
  pnl: number; // day net PnL — drives bar sign/color
  ratio: number | null; // pnl / fees, signed; null when fees is zero (no bar)
}

export interface FeeRatioMonth {
  days: DayFeeRatio[];
  monthRatio: number | null; // month PnL ÷ month fees, from monthly totals
}

/** Per-day return on fees (net PnL ÷ fees), plus the month aggregate from monthly totals. */
export function feeRatioByDay(rows: ClosedPosition[], year: number, month: number, tz: string): FeeRatioMonth {
  const days = getDaysInMonth(new Date(year, month - 1, 1));
  const acc = Array.from({ length: days }, () => ({ fees: ZERO, pnl: ZERO }));
  let monthFees = ZERO;
  let monthPnl = ZERO;
  for (const p of rows) {
    const z = zonedParts(p.closedAt, tz);
    if (z.year !== year || z.month !== month) continue;
    const fee = toDecimal(p.fees);
    const pnl = netOf(p);
    acc[z.day - 1].fees = acc[z.day - 1].fees.plus(fee);
    acc[z.day - 1].pnl = acc[z.day - 1].pnl.plus(pnl);
    monthFees = monthFees.plus(fee);
    monthPnl = monthPnl.plus(pnl);
  }
  // Skip days with no fees (also covers days with no trades): never divide by zero.
  const ratio = (pnl: Decimal, fees: Decimal): number | null => (fees.isZero() ? null : pnl.div(fees).toNumber());
  return {
    days: acc.map((b, i) => ({ day: i + 1, fees: b.fees.toNumber(), pnl: b.pnl.toNumber(), ratio: ratio(b.pnl, b.fees) })),
    monthRatio: ratio(monthPnl, monthFees),
  };
}
