import type { ClosedPosition } from "@/api/analytics";
import type { CapitalOverview, MonthlyRoiItem, RoiResult } from "@/api/capital";
import type { PositionKind } from "@/api/positions";
import type { Tag, TagGroup } from "@/api/taxonomy";
import Decimal from "decimal.js";
import { toDecimal } from "@/lib/format";
import {
  computeStats,
  cumulativeFees,
  directionBreakdown,
  equityCurve,
  feeRatioByDay,
  feesByDayOfMonth,
  netOf,
  pairOf,
  pairsRankings,
  pnlByMonth,
  recoveryRate,
  streaks,
  styleOf,
  traderStyle,
  volumeOf,
  winRateByHour,
  winRateByWeekday,
  zonedParts,
} from "./compute";

/**
 * Shapes the exact numbers the dashboard already computed into the payload the AI style analysis
 * sends. Every figure comes from the same functions the widgets use, rounded to display precision,
 * and carries its sample size `n`. Breakdown buckets below [MIN_BUCKET_SAMPLE] are dropped (counted
 * in `droppedBuckets`) so the model cannot read patterns into noise.
 *
 * Tag group and tag names are anonymized here, in the browser, before anything leaves it: Origen
 * tags carry "Tag A", "Tag B"…, extra tag groups carry "Group A" / "Tag A-1"…, and `tagMap` links
 * each label to its id so past runs can be re-labelled later.
 */

export const MIN_BUCKET_SAMPLE = 10;
/** Coarse structural dimensions (dayType, session, exchange, kind, style, concurrency) surface splits at >= 3 trades. */
export const MIN_COARSE_BUCKET_SAMPLE = 3;
export const MAX_INLINE_TRADES = 80;
const SAMPLED_RECENT_COUNT = 40;
const SAMPLED_OUTLIER_COUNT = 10;

const round = (n: number, dp: number): number => {
  const f = 10 ** dp;
  return Math.round(n * f) / f;
};
/** Money / ratios: 2 dp, like the dashboard. */
export const money = (n: number): number => round(n, 2);
/** Money / ratios that may be absent. */
export const moneyOr = (n: number | null): number | null => (n === null ? null : money(n));
/** Percentages shown by the charts: 1 dp. */
export const pct = (fraction: number | null): number | null =>
  fraction === null ? null : round(fraction * 100, 1);

export interface AiScope {
  periodPreset: string;
  rangeFrom: string | null;
  rangeTo: string | null;
  exchange: string;
  /** Anonymized origen label, or null when no tag filter is applied. */
  origen: string | null;
  timeZone: string;
}

export interface ReentrySignal {
  n: number;
  winRatePct: number | null;
  totalPnl: number;
  expectancy: number | null;
  sizeMultiplier: number | null;
}

export interface GroupBucketStats {
  n: number;
  totalPnl: number;
  winRatePct: number | null;
  expectancy: number | null;
  totalFees: number;
}

export interface BenchmarkReturnInput {
  key: string;
  returnPct: number;
  from: string;
  to: string;
}

export interface AnonymizedTradeRow {
  seq: number;
  pair: string;
  side: "LONG" | "SHORT";
  kind?: PositionKind;
  exchange?: string;
  openedAt: string;
  closedAt: string;
  holdHours: number;
  volume: number | null;
  grossPnl: number;
  fees: number;
  funding: number;
  netPnl: number;
  returnOnVolumePct?: number | null;
  priceMovePct?: number | null;
  tags?: string[];
}

export interface AiStylePayload {
  scope: AiScope;
  summary: {
    n: number;
    wins: number;
    losses: number;
    unresolved: number;
    totalPnl: number;
    grossPnl: number;
    totalFunding: number;
    volume: number;
    winRatePct: number | null;
    lossRatePct: number | null;
    avgWin: number | null;
    avgLoss: number | null;
    expectancy: number | null;
    avgRR: number | null;
    profitFactor: number | null;
    largestWin: number | null;
    largestLoss: number | null;
  };
  drawdown: {
    maxUsd: number;
    currentUsd: number;
    maxPctOfStartCapital: number | null;
  };
  tailAndConcentration: {
    pnlWithoutWorstTrade: number | null;
    pnlWithoutBestTrade: number | null;
    top3WinnersPnl: number | null;
    top3LosersPnl: number | null;
    lossesOver2xAvg: { n: number; totalPnl: number } | null;
    largestLossPctOfCapital: number | null;
    avgLossPctOfCapital: number | null;
  };
  /** Only present when the capital feature has an anchor for the scope. */
  roi?: { roiPct: number; startCapital: number; netPnl: number } | null;
  benchmarks?: BenchmarkReturnInput[];
  capitalAndRisk?: {
    riskPct1: number;
    riskPct2: number;
    total: { capital: number; maxLossAtPct1: number; maxLossAtPct2: number };
    byExchange: { exchange: string; capital: number; maxLossAtPct1: number; maxLossAtPct2: number }[];
  };
  direction: {
    long: { n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null };
    short: { n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null };
  };
  style: {
    minSample: number;
    droppedBuckets: number;
    buckets: { style: string; n: number; pnl: number; winRatePct: number | null }[];
    duration: {
      avgMs: number;
      avgWinMs: number | null;
      avgLossMs: number | null;
      avgHours: number;
      avgWinHours: number | null;
      avgLossHours: number | null;
      lossToWinDurationRatio: number | null;
      longestMs: number;
      longestHours: number;
      shortestMs: number;
      shortestHours: number;
      predominant: string | null;
    };
  };
  byExchange?: {
    minSample: number;
    droppedBuckets: number;
    buckets: ({ exchange: string } & GroupBucketStats)[];
  };
  byKind?: {
    minSample: number;
    droppedBuckets: number;
    buckets: ({ kind: PositionKind } & GroupBucketStats)[];
  };
  bySession: {
    minSample: number;
    droppedBuckets: number;
    buckets: ({ session: "asia" | "europe" | "us" | "late" } & GroupBucketStats)[];
  };
  byDayType: {
    minSample: number;
    droppedBuckets: number;
    buckets: ({ dayType: "weekday" | "weekend" } & GroupBucketStats)[];
  };
  directionStyle: {
    minSample: number;
    droppedBuckets: number;
    buckets: ({ combo: string } & GroupBucketStats)[];
  };
  byHour: {
    minSample: number;
    droppedBuckets: number;
    buckets: { hour: number; n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null }[];
  };
  byWeekday: {
    minSample: number;
    droppedBuckets: number;
    buckets: { weekday: number; n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null }[];
  };
  byMonth: {
    year: number;
    month: number;
    n: number;
    pnl: number;
    winRatePct: number | null;
    fees: number;
    feeRatio: number | null;
    roiPct?: number;
    startCapital?: number;
  }[];
  streaks: {
    winning: { avgLength: number | null; longestLength: number; longestNetPnl: number };
    losing: { avgLength: number | null; longestLength: number; longestNetPnl: number };
    recovery: { ratePct: number | null; n: number };
  };
  pairs: {
    minSample: number;
    totalPairs: number;
    droppedBuckets: number;
    mostTraded: ({ pair: string } & GroupBucketStats)[];
    mostProfitable: ({ pair: string } & GroupBucketStats)[];
    leastProfitable: ({ pair: string } & GroupBucketStats)[];
  };
  portfolioHeat: {
    minSample: number;
    maxConcurrentOpen: number;
    avgConcurrentAtEntry: number;
    isolated: GroupBucketStats | null;
    overlapping: GroupBucketStats | null;
    sameSideOverlap: GroupBucketStats | null;
  };
  fees: {
    n: number;
    totalFees: number;
    totalFunding: number;
    totalCosts: number;
    avgFeePerTrade: number;
    feeToGrossProfitPct: number | null;
    feeToVolumePct: number | null;
  };
  behavioralSignals: {
    postLossReentry: ReentrySignal;
    postWinReentry: ReentrySignal;
    fatigue: {
      minSample: number;
      trades1to3: { n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null };
      trades4to6: { n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null } | null;
      trades7plus: { n: number; winRatePct: number | null; totalPnl: number; expectancy: number | null } | null;
    };
    sizing: {
      avgWinVolume: number | null;
      avgLossVolume: number | null;
      lossToWinRatio: number | null;
      volumeCv: number | null;
      after2LossesSizeMultiplier: number | null;
      after2WinsSizeMultiplier: number | null;
    };
    whatIf: {
      skipPostLossReentryPnl: number | null;
      skipPostWinReentryPnl: number | null;
      skipTrades7PlusPnl: number | null;
      capLossAt2xAvgWin: { cappedTrades: number; simulatedTotalPnl: number } | null;
      skipWorstPair: { pair: string; simulatedTotalPnl: number } | null;
    };
  };
  /** Only present when the taxonomy has origen tags and no single tag is filtered. */
  byOrigen?: {
    minSample: number;
    droppedBuckets: number;
    buckets: { tag: string; n: number; totalPnl: number; winRatePct: number | null; expectancy: number | null }[];
  };
  /** Non-origen tag groups with at least one tagged trade in scope. */
  byTagGroups?: {
    group: string;
    minSample: number;
    droppedBuckets: number;
    buckets: { tag: string; n: number; totalPnl: number; winRatePct: number | null; expectancy: number | null }[];
  }[];
  tradesIncluded: { total: number; mode: "all" | "sampled" };
  trades?: AnonymizedTradeRow[];
  recentTrades?: AnonymizedTradeRow[];
  topLosers?: AnonymizedTradeRow[];
  topWinners?: AnonymizedTradeRow[];
  /** label -> tag or group id; stripped server-side before anything reaches the model. */
  tagMap: Record<string, string>;
}

export interface AiPayloadInput {
  rows: ClosedPosition[];
  scope: AiScope;
  /** All origen tags of the taxonomy (any group order); already archived ones included. */
  origenTags: Tag[];
  /** Full taxonomy groups; when provided, non-origen groups are also anonymized and aggregated. */
  tagGroups?: TagGroup[];
  capital?: CapitalOverview;
  roi?: RoiResult;
  /** Backend monthly ROI per `${year}-${month}`, matching the Monthly ROI card's source. */
  monthlyRoi: Map<string, MonthlyRoiItem>;
  benchmarks?: BenchmarkReturnInput[];
}

/** Labels in tag sort order, so the anonymization is stable across runs of the same taxonomy. */
export function anonymizeTags(tags: Tag[]): Map<string, string> {
  const ordered = [...tags].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
  const labels = new Map<string, string>();
  ordered.forEach((tag, i) => labels.set(tag.id, i < 26 ? `Tag ${String.fromCharCode(65 + i)}` : `Tag ${i + 1}`));
  return labels;
}

const letterSuffix = (i: number): string => (i < 26 ? String.fromCharCode(65 + i) : `${i + 1}`);

export function buildAiStylePayload(input: AiPayloadInput): AiStylePayload {
  const { rows, scope, origenTags, tagGroups, capital, roi, monthlyRoi, benchmarks } = input;
  const labels = anonymizeTags(origenTags);

  const stats = computeStats(rows);
  const grossPnl = rows.reduce((acc, r) => acc.plus(toDecimal(r.realizedPnl)), new Decimal(0));
  const totalFunding = rows.reduce((acc, r) => acc.plus(toDecimal(r.funding)), new Decimal(0));
  const totalFees = cumulativeFees(rows).at(-1)?.cumulative ?? 0;
  const profitFactor = computeProfitFactor(rows);

  const summary = {
    n: rows.length,
    wins: stats.wins,
    losses: stats.losses,
    unresolved: rows.length - stats.wins - stats.losses,
    totalPnl: money(stats.totalPnl.toNumber()),
    grossPnl: money(grossPnl.toNumber()),
    totalFunding: money(totalFunding.toNumber()),
    volume: money(stats.volume.toNumber()),
    winRatePct: pct(stats.winRate),
    lossRatePct: pct(stats.lossRate),
    avgWin: moneyOr(stats.avgWin?.toNumber() ?? null),
    avgLoss: moneyOr(stats.avgLoss?.toNumber() ?? null),
    expectancy: moneyOr(stats.expectancy?.toNumber() ?? null),
    avgRR: stats.avgRR === null ? null : round(stats.avgRR.toNumber(), 2),
    profitFactor,
    largestWin: moneyOr(stats.largestWin?.toNumber() ?? null),
    largestLoss: moneyOr(stats.largestLoss?.toNumber() ?? null),
  };

  const risk = capitalSection(capital, scope.exchange);
  const startCap = roi?.startCapital != null ? Number(roi.startCapital) : null;
  const pairs = pairsSection(rows);
  const postLossReentry = detectRevengeTrading(rows);
  const postWinReentry = detectPostWinReentry(rows);
  const fatigue = detectFatigueCurve(rows, scope.timeZone);
  const totalCosts = totalFees + totalFunding.toNumber();
  const grossNum = grossPnl.toNumber();
  const volNum = stats.volume.toNumber();

  const payload: AiStylePayload = {
    scope: { ...scope },
    summary,
    drawdown: drawdownSection(rows, startCap),
    tailAndConcentration: tailSection(rows, summary, risk?.total.capital ?? null),
    direction: directionSection(rows),
    style: styleSection(rows),
    bySession: sessionSection(rows),
    byDayType: dayTypeSection(rows, scope.timeZone),
    directionStyle: directionStyleSection(rows),
    byHour: hourSection(rows, scope.timeZone),
    byWeekday: weekdaySection(rows, scope.timeZone),
    byMonth: monthSection(rows, scope.timeZone, monthlyRoi),
    streaks: streaksSection(rows),
    pairs,
    portfolioHeat: portfolioHeatSection(rows),
    fees: {
      n: rows.length,
      totalFees: money(totalFees),
      totalFunding: money(totalFunding.toNumber()),
      totalCosts: money(totalCosts),
      avgFeePerTrade: rows.length > 0 ? money(totalFees / rows.length) : 0,
      feeToGrossProfitPct: grossNum > 0 ? round((totalCosts / grossNum) * 100, 1) : null,
      feeToVolumePct: volNum > 0 ? round((totalFees / volNum) * 100, 4) : null,
    },
    behavioralSignals: {
      postLossReentry,
      postWinReentry,
      fatigue,
      sizing: detectSizingAsymmetry(rows),
      whatIf: whatIfSection(
        rows,
        summary.totalPnl,
        summary.avgWin,
        postLossReentry,
        postWinReentry,
        fatigue,
        pairs.leastProfitable[0] ?? null,
      ),
    },
    tradesIncluded: {
      total: rows.length,
      mode: rows.length <= MAX_INLINE_TRADES ? "all" : "sampled",
    },
    tagMap: {},
  };

  if (roi?.roi !== null && roi?.roi !== undefined) {
    payload.roi = {
      roiPct: round(Number(roi.roi) * 100, 2),
      startCapital: money(Number(roi.startCapital ?? 0)),
      netPnl: money(Number(roi.netPnl ?? 0)),
    };
  }

  if (benchmarks && benchmarks.length > 0) {
    payload.benchmarks = benchmarks.map((b) => ({
      key: b.key,
      returnPct: round(b.returnPct, 2),
      from: b.from,
      to: b.to,
    }));
  }

  if (risk) payload.capitalAndRisk = risk;

  if (scope.exchange === "ALL") {
    const byExchange = exchangeSection(rows);
    if (byExchange) payload.byExchange = byExchange;
  }

  const byKind = kindSection(rows);
  if (byKind) payload.byKind = byKind;

  if (scope.origen === null && origenTags.length > 0) {
    payload.byOrigen = tagBreakdownSection(rows, origenTags, labels);
  }

  // Anonymize origen tags into tagMap first.
  for (const tag of origenTags) {
    const label = labels.get(tag.id);
    if (label) payload.tagMap[label] = tag.id;
  }
  if (scope.origen !== null) {
    const match = origenTags.find((t) => labels.get(t.id) === scope.origen);
    if (match) payload.tagMap[scope.origen] = match.id;
  }

  // Additional non-origen tag groups.
  const allTagLabels = new Map(labels);
  if (tagGroups && tagGroups.length > 0) {
    const extra = extraTagGroupsSection(rows, tagGroups, origenTags);
    for (const [id, label] of extra.labelById) allTagLabels.set(id, label);
    if (extra.sections.length > 0) {
      payload.byTagGroups = extra.sections;
      Object.assign(payload.tagMap, extra.extraMap);
    }
  }

  Object.assign(payload, tradesSection(rows, scope, allTagLabels));

  return payload;
}

// ---------------------------------------------------------------------------
// Aggregate helpers
// ---------------------------------------------------------------------------

function computeProfitFactor(rows: ClosedPosition[]): number | null {
  let grossWin = new Decimal(0);
  let grossLoss = new Decimal(0);
  for (const r of rows) {
    const n = netOf(r);
    if (n.gt(0)) grossWin = grossWin.plus(n);
    else if (n.lt(0)) grossLoss = grossLoss.plus(n.abs());
  }
  if (grossLoss.isZero()) return null;
  return round(grossWin.div(grossLoss).toNumber(), 2);
}

function drawdownSection(rows: ClosedPosition[], startCapital: number | null): AiStylePayload["drawdown"] {
  const curve = equityCurve(rows);
  let peak = 0;
  let maxDd = 0;
  for (const pt of curve) {
    if (pt.cumulative > peak) peak = pt.cumulative;
    const dd = peak - pt.cumulative;
    if (dd > maxDd) maxDd = dd;
  }
  const lastCum = curve.at(-1)?.cumulative ?? 0;
  const currentDd = Math.max(0, peak - lastCum);
  return {
    maxUsd: money(maxDd),
    currentUsd: money(currentDd),
    maxPctOfStartCapital: startCapital && startCapital > 0 ? round((maxDd / startCapital) * 100, 1) : null,
  };
}

function tailSection(
  rows: ClosedPosition[],
  summary: AiStylePayload["summary"],
  capitalTotal: number | null,
): AiStylePayload["tailAndConcentration"] {
  const nets = rows.map((r) => netOf(r).toNumber());
  const wins = nets.filter((n) => n > 0).sort((a, b) => b - a);
  const losses = nets.filter((n) => n < 0).sort((a, b) => a - b); // most negative first

  let lossesOver2x: { n: number; totalPnl: number } | null = null;
  if (summary.avgLoss !== null && summary.avgLoss < 0) {
    const threshold = summary.avgLoss * 2;
    const outliers = losses.filter((n) => n < threshold);
    lossesOver2x = {
      n: outliers.length,
      totalPnl: money(outliers.reduce((acc, v) => acc + v, 0)),
    };
  }

  return {
    pnlWithoutWorstTrade: summary.largestLoss !== null ? money(summary.totalPnl - summary.largestLoss) : null,
    pnlWithoutBestTrade: summary.largestWin !== null ? money(summary.totalPnl - summary.largestWin) : null,
    top3WinnersPnl: wins.length >= 3 ? money(wins.slice(0, 3).reduce((a, b) => a + b, 0)) : null,
    top3LosersPnl: losses.length >= 3 ? money(losses.slice(0, 3).reduce((a, b) => a + b, 0)) : null,
    lossesOver2xAvg: lossesOver2x,
    largestLossPctOfCapital:
      capitalTotal && capitalTotal > 0 && summary.largestLoss !== null
        ? round((Math.abs(summary.largestLoss) / capitalTotal) * 100, 2)
        : null,
    avgLossPctOfCapital:
      capitalTotal && capitalTotal > 0 && summary.avgLoss !== null
        ? round((Math.abs(summary.avgLoss) / capitalTotal) * 100, 2)
        : null,
  };
}

function bucketStatsOf(rows: ClosedPosition[]): GroupBucketStats {
  const s = computeStats(rows);
  const totalFees = rows.reduce((acc, r) => acc.plus(toDecimal(r.fees)), new Decimal(0));
  return {
    n: rows.length,
    totalPnl: money(s.totalPnl.toNumber()),
    winRatePct: pct(s.winRate),
    expectancy: moneyOr(s.expectancy?.toNumber() ?? null),
    totalFees: money(totalFees.toNumber()),
  };
}

function groupRows<K>(rows: ClosedPosition[], keyOf: (row: ClosedPosition) => K): Map<K, ClosedPosition[]> {
  const map = new Map<K, ClosedPosition[]>();
  for (const r of rows) {
    const key = keyOf(r);
    const list = map.get(key) ?? [];
    list.push(r);
    map.set(key, list);
  }
  return map;
}

// ---------------------------------------------------------------------------
// Sections, each straight from the widget's own computation
// ---------------------------------------------------------------------------

function directionSection(rows: ClosedPosition[]): AiStylePayload["direction"] {
  const dir = directionBreakdown(rows);
  const side = (s: {
    count: number;
    winRate: number | null;
    totalPnl: Decimal;
    expectancy: Decimal | null;
  }) => ({
    n: s.count,
    winRatePct: pct(s.winRate),
    totalPnl: money(s.totalPnl.toNumber()),
    expectancy: moneyOr(s.expectancy?.toNumber() ?? null),
  });
  return { long: side(dir.long), short: side(dir.short) };
}

function styleSection(rows: ClosedPosition[]): AiStylePayload["style"] {
  const style = traderStyle(rows);
  const raw = [
    { style: "scalper", bucket: style.scalper },
    { style: "day", bucket: style.day },
    { style: "swing", bucket: style.swing },
  ].filter((b) => b.bucket.count > 0);
  const kept = raw.filter((b) => b.bucket.count >= MIN_COARSE_BUCKET_SAMPLE);
  const durationOf = (p: ClosedPosition) => new Date(p.closedAt).getTime() - new Date(p.openedAt).getTime();
  const wins = rows.filter((r) => netOf(r).gt(0));
  const losses = rows.filter((r) => netOf(r).lt(0));
  const avg = (list: ClosedPosition[]) => (list.length ? Math.round(list.reduce((acc, p) => acc + durationOf(p), 0) / list.length) : null);
  const avgMs = Math.round(style.avgDurationMs ?? 0);
  const avgWinMs = avg(wins);
  const avgLossMs = avg(losses);
  const longestMs = Math.round(style.longestMs ?? 0);
  const shortestMs = Math.round(style.shortestMs ?? 0);
  const msToHours = (ms: number | null): number | null => (ms === null ? null : round(ms / 3_600_000, 1));
  const lossToWinDurationRatio = avgWinMs && avgLossMs && avgWinMs > 0 ? round(avgLossMs / avgWinMs, 2) : null;
  return {
    minSample: MIN_COARSE_BUCKET_SAMPLE,
    droppedBuckets: raw.length - kept.length,
    buckets: kept.map(({ style, bucket }) => ({
      style,
      n: bucket.count,
      pnl: money(bucket.pnl),
      winRatePct: pct((bucket.winRate ?? 0) / 100),
    })),
    duration: {
      avgMs,
      avgWinMs,
      avgLossMs,
      avgHours: round(avgMs / 3_600_000, 1),
      avgWinHours: msToHours(avgWinMs),
      avgLossHours: msToHours(avgLossMs),
      lossToWinDurationRatio,
      longestMs,
      longestHours: round(longestMs / 3_600_000, 1),
      shortestMs,
      shortestHours: round(shortestMs / 3_600_000, 1),
      predominant: style.predominant,
    },
  };
}

function hourSection(rows: ClosedPosition[], timeZone: string): AiStylePayload["byHour"] {
  const byHour = groupRows(rows, (r) => zonedParts(r.closedAt, timeZone).hour);
  const nonEmpty = winRateByHour(rows, timeZone).filter((b) => b.count > 0);
  const kept = nonEmpty.filter((b) => b.count >= MIN_BUCKET_SAMPLE);
  return {
    minSample: MIN_BUCKET_SAMPLE,
    droppedBuckets: nonEmpty.length - kept.length,
    buckets: kept.map((b) => {
      const s = computeStats(byHour.get(b.key) ?? []);
      return {
        hour: b.key,
        n: b.count,
        winRatePct: pct((b.winRate ?? 0) / 100),
        totalPnl: money(s.totalPnl.toNumber()),
        expectancy: moneyOr(s.expectancy?.toNumber() ?? null),
      };
    }),
  };
}

function weekdaySection(rows: ClosedPosition[], timeZone: string): AiStylePayload["byWeekday"] {
  const byDay = groupRows(rows, (r) => zonedParts(r.closedAt, timeZone).weekday);
  const nonEmpty = winRateByWeekday(rows, timeZone).filter((b) => b.count > 0);
  const kept = nonEmpty.filter((b) => b.count >= MIN_BUCKET_SAMPLE);
  return {
    minSample: MIN_BUCKET_SAMPLE,
    droppedBuckets: nonEmpty.length - kept.length,
    buckets: kept.map((b) => {
      const s = computeStats(byDay.get(b.key) ?? []);
      return {
        weekday: b.key,
        n: b.count,
        winRatePct: pct((b.winRate ?? 0) / 100),
        totalPnl: money(s.totalPnl.toNumber()),
        expectancy: moneyOr(s.expectancy?.toNumber() ?? null),
      };
    }),
  };
}

function keyedBuckets<K extends string>(
  grouped: Map<K, ClosedPosition[]>,
  minSample: number = MIN_BUCKET_SAMPLE,
): { minSample: number; droppedBuckets: number; buckets: { key: K; stats: GroupBucketStats }[] } {
  const buckets: { key: K; stats: GroupBucketStats }[] = [];
  let dropped = 0;
  for (const [key, list] of [...grouped.entries()].sort((a, b) => b[1].length - a[1].length)) {
    if (list.length >= minSample) {
      buckets.push({ key, stats: bucketStatsOf(list) });
    } else {
      dropped++;
    }
  }
  return { minSample, droppedBuckets: dropped, buckets };
}

function sessionSection(rows: ClosedPosition[]): AiStylePayload["bySession"] {
  const sessionOf = (iso: string): "asia" | "europe" | "us" | "late" => {
    const h = new Date(iso).getUTCHours();
    if (h < 8) return "asia";
    if (h < 13) return "europe";
    if (h < 21) return "us";
    return "late";
  };
  const res = keyedBuckets(groupRows(rows, (r) => sessionOf(r.openedAt)), MIN_COARSE_BUCKET_SAMPLE);
  return {
    minSample: res.minSample,
    droppedBuckets: res.droppedBuckets,
    buckets: res.buckets.map((b) => ({ session: b.key, ...b.stats })),
  };
}

function dayTypeSection(rows: ClosedPosition[], timeZone: string): AiStylePayload["byDayType"] {
  const dayTypeOf = (iso: string): "weekday" | "weekend" =>
    zonedParts(iso, timeZone).weekday >= 5 ? "weekend" : "weekday";
  const res = keyedBuckets(groupRows(rows, (r) => dayTypeOf(r.openedAt)), MIN_COARSE_BUCKET_SAMPLE);
  return {
    minSample: res.minSample,
    droppedBuckets: res.droppedBuckets,
    buckets: res.buckets.map((b) => ({ dayType: b.key, ...b.stats })),
  };
}

function directionStyleSection(rows: ClosedPosition[]): AiStylePayload["directionStyle"] {
  const comboOf = (r: ClosedPosition): string => {
    const ms = new Date(r.closedAt).getTime() - new Date(r.openedAt).getTime();
    return `${r.side}_${styleOf(ms)}`;
  };
  const res = keyedBuckets(groupRows(rows, comboOf), MIN_BUCKET_SAMPLE);
  return {
    minSample: res.minSample,
    droppedBuckets: res.droppedBuckets,
    buckets: res.buckets.map((b) => ({ combo: b.key, ...b.stats })),
  };
}

function exchangeSection(rows: ClosedPosition[]): AiStylePayload["byExchange"] | null {
  const grouped = groupRows(rows, (r) => r.exchange ?? "Unknown");
  if (grouped.size <= 1) return null;
  const buckets: ({ exchange: string } & GroupBucketStats)[] = [];
  let dropped = 0;
  for (const [exchange, list] of [...grouped.entries()].sort((a, b) => b[1].length - a[1].length)) {
    if (list.length >= MIN_COARSE_BUCKET_SAMPLE) {
      buckets.push({ exchange, ...bucketStatsOf(list) });
    } else {
      dropped++;
    }
  }
  return { minSample: MIN_COARSE_BUCKET_SAMPLE, droppedBuckets: dropped, buckets };
}

function kindSection(rows: ClosedPosition[]): AiStylePayload["byKind"] | null {
  const grouped = groupRows(rows, (r) => r.kind ?? "TRADE");
  if (grouped.size <= 1) return null;
  const buckets: ({ kind: PositionKind } & GroupBucketStats)[] = [];
  let dropped = 0;
  for (const [kind, list] of grouped.entries()) {
    if (list.length >= MIN_COARSE_BUCKET_SAMPLE) {
      buckets.push({ kind, ...bucketStatsOf(list) });
    } else {
      dropped++;
    }
  }
  return { minSample: MIN_COARSE_BUCKET_SAMPLE, droppedBuckets: dropped, buckets };
}

function portfolioHeatSection(rows: ClosedPosition[]): AiStylePayload["portfolioHeat"] {
  if (rows.length === 0) {
    return {
      minSample: MIN_COARSE_BUCKET_SAMPLE,
      maxConcurrentOpen: 0,
      avgConcurrentAtEntry: 0,
      isolated: null,
      overlapping: null,
      sameSideOverlap: null,
    };
  }

  const intervals = rows.map((r) => ({
    row: r,
    open: new Date(r.openedAt).getTime(),
    close: Math.max(new Date(r.openedAt).getTime(), new Date(r.closedAt).getTime()),
  }));

  // Closes before opens at the exact same timestamp so instant sequential flips do not count as overlapping.
  const events: { t: number; delta: number }[] = [];
  for (const iv of intervals) {
    events.push({ t: iv.open, delta: 1 });
    events.push({ t: iv.close, delta: -1 });
  }
  events.sort((a, b) => a.t - b.t || a.delta - b.delta);

  let active = 0;
  let maxConcurrentOpen = 0;
  for (const ev of events) {
    active += ev.delta;
    if (active > maxConcurrentOpen) maxConcurrentOpen = active;
  }

  const isolatedRows: ClosedPosition[] = [];
  const overlappingRows: ClosedPosition[] = [];
  const sameSideOverlapRows: ClosedPosition[] = [];
  let concurrentSum = 0;

  for (const current of intervals) {
    let otherOpen = 0;
    let otherSameSide = 0;
    for (const other of intervals) {
      if (other.row === current.row) continue;
      if (other.open <= current.open && other.close > current.open) {
        otherOpen++;
        if (other.row.side === current.row.side) otherSameSide++;
      }
    }
    concurrentSum += 1 + otherOpen;
    if (otherOpen === 0) isolatedRows.push(current.row);
    else overlappingRows.push(current.row);
    if (otherSameSide > 0) sameSideOverlapRows.push(current.row);
  }

  const bucketOrNull = (list: ClosedPosition[]) =>
    list.length >= MIN_COARSE_BUCKET_SAMPLE ? bucketStatsOf(list) : null;

  return {
    minSample: MIN_COARSE_BUCKET_SAMPLE,
    maxConcurrentOpen,
    avgConcurrentAtEntry: round(concurrentSum / rows.length, 2),
    isolated: bucketOrNull(isolatedRows),
    overlapping: bucketOrNull(overlappingRows),
    sameSideOverlap: bucketOrNull(sameSideOverlapRows),
  };
}

function monthSection(
  rows: ClosedPosition[],
  timeZone: string,
  monthlyRoi: Map<string, MonthlyRoiItem>,
): AiStylePayload["byMonth"] {
  const years = [...new Set(rows.map((p) => zonedParts(p.closedAt, timeZone).year))].sort((a, b) => a - b);
  const out: AiStylePayload["byMonth"] = [];
  for (const year of years) {
    for (const m of pnlByMonth(rows, year, timeZone)) {
      if (m.trades === 0) continue;
      const item = monthlyRoi.get(`${year}-${m.month}`);
      out.push({
        year,
        month: m.month,
        n: m.trades,
        pnl: money(m.pnl),
        winRatePct: pct((m.winRate ?? 0) / 100),
        fees: money(feesByDayOfMonth(rows, year, m.month, timeZone).total),
        feeRatio: moneyOr(feeRatioByDay(rows, year, m.month, timeZone).monthRatio ?? null),
        roiPct: item?.roi != null ? round(Number(item.roi) * 100, 2) : undefined,
        startCapital: item?.startCapital != null ? money(Number(item.startCapital)) : undefined,
      });
    }
  }
  return out;
}

function streaksSection(rows: ClosedPosition[]): AiStylePayload["streaks"] {
  const s = streaks(rows);
  const rec = recoveryRate(rows);
  const stat = (st: { avgLength: number | null; longestLength: number; longestNetPnl: number }) => ({
    avgLength: st.avgLength === null ? null : round(st.avgLength, 1),
    longestLength: st.longestLength,
    longestNetPnl: money(st.longestNetPnl),
  });
  return {
    winning: stat(s.winning),
    losing: stat(s.losing),
    recovery: { ratePct: pct(rec.rate), n: rec.sample },
  };
}

function pairsSection(rows: ClosedPosition[]): AiStylePayload["pairs"] {
  const ranks = pairsRankings(rows);
  const byPair = groupRows(rows, pairOf);
  const statsByPair = new Map<string, GroupBucketStats>();
  for (const [pair, list] of byPair.entries()) {
    if (list.length >= MIN_BUCKET_SAMPLE) {
      statsByPair.set(pair, bucketStatsOf(list));
    }
  }
  const shape = (list: typeof ranks.mostTraded) =>
    list
      .filter((p) => p.count >= MIN_BUCKET_SAMPLE)
      .map((p) => ({
        pair: p.pair,
        ...(statsByPair.get(p.pair) ?? { n: p.count, totalPnl: money(p.totalPnl), winRatePct: null, expectancy: null, totalFees: 0 }),
      }));
  const mostTraded = shape(ranks.mostTraded);
  const mostProfitable = shape(ranks.mostProfitable);
  const leastProfitable = shape(ranks.leastProfitable);
  const kept = new Set([...mostTraded, ...mostProfitable, ...leastProfitable].map((p) => p.pair));
  return {
    minSample: MIN_BUCKET_SAMPLE,
    totalPairs: byPair.size,
    droppedBuckets: byPair.size - kept.size,
    mostTraded,
    mostProfitable,
    leastProfitable,
  };
}

function capitalSection(capital: CapitalOverview | undefined, exchange: string): AiStylePayload["capitalAndRisk"] | null {
  if (!capital) return null;
  // Same entries the Capital & risk card shows: anchored, and not dormant under "ALL".
  const entries =
    exchange === "ALL"
      ? capital.entries.filter((e) => e.amount !== null && !e.dormant)
      : capital.entries.filter((e) => e.amount !== null && e.exchange === exchange);
  if (entries.length === 0) return null;
  const pct1 = Number(capital.riskPercents.pct1);
  const pct2 = Number(capital.riskPercents.pct2);
  const maxLoss = (amount: number) => ({
    maxLossAtPct1: money((amount * pct1) / 100),
    maxLossAtPct2: money((amount * pct2) / 100),
  });
  const byExchange = entries.map((e) => ({
    exchange: e.exchange,
    capital: money(Number(e.amount)),
    ...maxLoss(Number(e.amount)),
  }));
  const total = byExchange.reduce((acc, e) => acc + e.capital, 0);
  return {
    riskPct1: pct1,
    riskPct2: pct2,
    total: { capital: money(total), ...maxLoss(total) },
    byExchange,
  };
}

function tagBreakdownSection(
  rows: ClosedPosition[],
  tags: Tag[],
  labels: Map<string, string>,
): NonNullable<AiStylePayload["byOrigen"]> {
  const buckets: NonNullable<AiStylePayload["byOrigen"]>["buckets"] = [];
  let dropped = 0;
  for (const tag of [...tags].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))) {
    const subset = rows.filter((p) => p.tags.some((t) => t.tagId === tag.id));
    if (subset.length === 0) continue;
    const s = computeStats(subset);
    const bucket = {
      tag: labels.get(tag.id) ?? "Tag ?",
      n: subset.length,
      totalPnl: money(s.totalPnl.toNumber()),
      winRatePct: pct(s.winRate),
      expectancy: moneyOr(s.expectancy?.toNumber() ?? null),
    };
    if (subset.length >= MIN_BUCKET_SAMPLE) buckets.push(bucket);
    else dropped++;
  }
  return { minSample: MIN_BUCKET_SAMPLE, droppedBuckets: dropped, buckets };
}

function extraTagGroupsSection(
  rows: ClosedPosition[],
  tagGroups: TagGroup[],
  origenTags: Tag[],
): {
  sections: NonNullable<AiStylePayload["byTagGroups"]>;
  extraMap: Record<string, string>;
  labelById: Map<string, string>;
} {
  const origenTagIds = new Set(origenTags.map((t) => t.id));
  const extraGroups = [...tagGroups]
    .filter((g) => g.code !== "origen" && !g.tags.some((t) => origenTagIds.has(t.id)))
    .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));

  const sections: NonNullable<AiStylePayload["byTagGroups"]> = [];
  const extraMap: Record<string, string> = {};
  const labelById = new Map<string, string>();

  extraGroups.forEach((group, gi) => {
    const suffix = letterSuffix(gi);
    const groupLabel = `Group ${suffix}`;
    const orderedTags = [...group.tags].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
    const labels = new Map<string, string>();
    orderedTags.forEach((tag, ti) => {
      const label = `Tag ${suffix}-${ti + 1}`;
      labels.set(tag.id, label);
      labelById.set(tag.id, label);
    });
    const breakdown = tagBreakdownSection(rows, orderedTags, labels);
    if (breakdown.buckets.length === 0 && breakdown.droppedBuckets === 0) return;

    extraMap[groupLabel] = group.id;
    for (const tag of orderedTags) {
      const label = labels.get(tag.id);
      if (label) extraMap[label] = tag.id;
    }
    sections.push({ group: groupLabel, ...breakdown });
  });

  return { sections, extraMap, labelById };
}

function formatZonedTimestamp(iso: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(new Date(iso));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  const hour = get("hour") === "24" ? "00" : get("hour");
  return `${get("year")}-${get("month")}-${get("day")} ${hour}:${get("minute")}`;
}

function tradesSection(
  rows: ClosedPosition[],
  scope: AiScope,
  labelById: Map<string, string>,
): Pick<AiStylePayload, "trades" | "recentTrades" | "topLosers" | "topWinners"> {
  const sorted = [...rows].sort(
    (a, b) => new Date(a.closedAt).getTime() - new Date(b.closedAt).getTime() || a.openedAt.localeCompare(b.openedAt),
  );
  const toRow = (r: ClosedPosition, seq: number): AnonymizedTradeRow => {
    const holdMs = Math.max(0, new Date(r.closedAt).getTime() - new Date(r.openedAt).getTime());
    const vol = volumeOf(r);
    const volNum = vol ? money(vol.toNumber()) : null;
    const netNum = money(netOf(r).toNumber());
    const anonTags = r.tags.map((t) => labelById.get(t.tagId)).filter((x): x is string => Boolean(x));
    const entry = r.entryPrice ? Number(r.entryPrice) : null;
    const exit = r.exitPrice ? Number(r.exitPrice) : null;
    const priceMovePct =
      entry && exit && Number.isFinite(entry) && Number.isFinite(exit) && entry > 0
        ? round(((r.side === "LONG" ? exit - entry : entry - exit) / entry) * 100, 2)
        : null;
    const row: AnonymizedTradeRow = {
      seq,
      pair: pairOf(r),
      side: r.side,
      openedAt: formatZonedTimestamp(r.openedAt, scope.timeZone),
      closedAt: formatZonedTimestamp(r.closedAt, scope.timeZone),
      holdHours: round(holdMs / 3_600_000, 1),
      volume: volNum,
      grossPnl: money(toDecimal(r.realizedPnl).toNumber()),
      fees: money(toDecimal(r.fees).toNumber()),
      funding: money(toDecimal(r.funding).toNumber()),
      netPnl: netNum,
      returnOnVolumePct: volNum && volNum > 0 ? round((netNum / volNum) * 100, 2) : null,
    };
    if (priceMovePct !== null) row.priceMovePct = priceMovePct;
    if (r.kind && r.kind !== "TRADE") row.kind = r.kind;
    if (scope.exchange === "ALL" && r.exchange) row.exchange = r.exchange;
    if (anonTags.length > 0) row.tags = anonTags;
    return row;
  };

  const indexed = sorted.map((r, i) => toRow(r, i + 1));
  if (indexed.length <= MAX_INLINE_TRADES) {
    return { trades: indexed };
  }
  return {
    recentTrades: indexed.slice(-SAMPLED_RECENT_COUNT),
    topLosers: [...indexed].filter((t) => t.netPnl < 0).sort((a, b) => a.netPnl - b.netPnl).slice(0, SAMPLED_OUTLIER_COUNT),
    topWinners: [...indexed].filter((t) => t.netPnl > 0).sort((a, b) => b.netPnl - a.netPnl).slice(0, SAMPLED_OUTLIER_COUNT),
  };
}

// ---------------------------------------------------------------------------
// Behavioral signals
// ---------------------------------------------------------------------------

function numericVolume(r: ClosedPosition): number {
  const v = volumeOf(r);
  return v ? v.toNumber() : 0;
}

function reentrySignal(rows: ClosedPosition[], matchesPrior: (prev: ClosedPosition) => boolean): ReentrySignal {
  if (rows.length < 2) {
    return { n: 0, winRatePct: null, totalPnl: 0, expectancy: null, sizeMultiplier: null };
  }
  const closedSorted = [...rows].sort((a, b) => new Date(a.closedAt).getTime() - new Date(b.closedAt).getTime());
  const baselineAvgVol = rows.reduce((acc, r) => acc + numericVolume(r), 0) / rows.length;
  const WINDOW_MS = 15 * 60 * 1000;
  const matched: ClosedPosition[] = [];

  for (const current of rows) {
    const openTime = new Date(current.openedAt).getTime();
    const prior = closedSorted.find((prev) => {
      if (prev.id === current.id) return false;
      const prevClose = new Date(prev.closedAt).getTime();
      return prevClose <= openTime && openTime - prevClose <= WINDOW_MS && matchesPrior(prev);
    });
    if (prior) matched.push(current);
  }

  if (matched.length === 0) {
    return { n: 0, winRatePct: null, totalPnl: 0, expectancy: null, sizeMultiplier: null };
  }

  const stats = computeStats(matched);
  const avgVol = matched.reduce((acc, r) => acc + numericVolume(r), 0) / matched.length;
  const sizeMult = baselineAvgVol > 0 ? round(avgVol / baselineAvgVol, 2) : null;

  return {
    n: matched.length,
    winRatePct: pct(stats.winRate),
    totalPnl: money(stats.totalPnl.toNumber()),
    expectancy: moneyOr(stats.expectancy?.toNumber() ?? null),
    sizeMultiplier: sizeMult,
  };
}

export function detectRevengeTrading(rows: ClosedPosition[]): ReentrySignal {
  return reentrySignal(rows, (prev) => netOf(prev).lt(0));
}

export function detectPostWinReentry(rows: ClosedPosition[]): ReentrySignal {
  return reentrySignal(rows, (prev) => netOf(prev).gt(0));
}

export function detectFatigueCurve(rows: ClosedPosition[], timeZone: string): AiStylePayload["behavioralSignals"]["fatigue"] {
  const byDay = new Map<string, ClosedPosition[]>();
  for (const row of rows) {
    const z = zonedParts(row.openedAt, timeZone);
    const dayKey = `${z.year}-${String(z.month).padStart(2, "0")}-${String(z.day).padStart(2, "0")}`;
    const list = byDay.get(dayKey) ?? [];
    list.push(row);
    byDay.set(dayKey, list);
  }

  const trades1to3: ClosedPosition[] = [];
  const trades4to6: ClosedPosition[] = [];
  const trades7plus: ClosedPosition[] = [];

  for (const dayTrades of byDay.values()) {
    dayTrades.sort((a, b) => new Date(a.openedAt).getTime() - new Date(b.openedAt).getTime());
    dayTrades.forEach((trade, idx) => {
      if (idx < 3) trades1to3.push(trade);
      else if (idx < 6) trades4to6.push(trade);
      else trades7plus.push(trade);
    });
  }

  const bucket = (list: ClosedPosition[]) => {
    if (list.length < MIN_BUCKET_SAMPLE) return null;
    const stats = computeStats(list);
    return {
      n: list.length,
      winRatePct: pct(stats.winRate),
      totalPnl: money(stats.totalPnl.toNumber()),
      expectancy: moneyOr(stats.expectancy?.toNumber() ?? null),
    };
  };

  const b1 = computeStats(trades1to3);
  return {
    minSample: MIN_BUCKET_SAMPLE,
    trades1to3: {
      n: trades1to3.length,
      winRatePct: trades1to3.length ? pct(b1.winRate) : null,
      totalPnl: money(b1.totalPnl.toNumber()),
      expectancy: moneyOr(b1.expectancy?.toNumber() ?? null),
    },
    trades4to6: bucket(trades4to6),
    trades7plus: bucket(trades7plus),
  };
}

function streakSizeMultiplier(rows: ClosedPosition[], minStreak: number, wantWin: boolean): number | null {
  if (rows.length <= minStreak) return null;
  const sorted = [...rows].sort((a, b) => new Date(a.closedAt).getTime() - new Date(b.closedAt).getTime());
  const baseline = sorted.reduce((acc, r) => acc + numericVolume(r), 0) / sorted.length;
  if (baseline <= 0) return null;

  let run = 0;
  const vols: number[] = [];
  for (const trade of sorted) {
    if (run >= minStreak) vols.push(numericVolume(trade));
    const n = netOf(trade);
    const hit = wantWin ? n.gt(0) : n.lt(0);
    if (hit) run++;
    else if (!n.isZero()) run = 0;
  }
  if (vols.length === 0) return null;
  const avg = vols.reduce((a, b) => a + b, 0) / vols.length;
  return round(avg / baseline, 2);
}

export function detectSizingAsymmetry(rows: ClosedPosition[]): AiStylePayload["behavioralSignals"]["sizing"] {
  const wins = rows.filter((r) => netOf(r).gt(0));
  const losses = rows.filter((r) => netOf(r).lt(0));
  const avgVol = (list: ClosedPosition[]) =>
    list.length ? round(list.reduce((acc, r) => acc + numericVolume(r), 0) / list.length, 2) : null;
  const avgWinVol = avgVol(wins);
  const avgLossVol = avgVol(losses);
  const ratio = avgWinVol && avgLossVol && avgWinVol > 0 ? round(avgLossVol / avgWinVol, 2) : null;

  const positiveVols = rows.map(numericVolume).filter((v) => v > 0);
  let volumeCv: number | null = null;
  if (positiveVols.length >= 2) {
    const mean = positiveVols.reduce((a, b) => a + b, 0) / positiveVols.length;
    if (mean > 0) {
      const variance = positiveVols.reduce((acc, v) => acc + (v - mean) ** 2, 0) / positiveVols.length;
      volumeCv = round(Math.sqrt(variance) / mean, 2);
    }
  }

  return {
    avgWinVolume: avgWinVol,
    avgLossVolume: avgLossVol,
    lossToWinRatio: ratio,
    volumeCv,
    after2LossesSizeMultiplier: streakSizeMultiplier(rows, 2, false),
    after2WinsSizeMultiplier: streakSizeMultiplier(rows, 2, true),
  };
}

function whatIfSection(
  rows: ClosedPosition[],
  totalPnl: number,
  avgWin: number | null,
  postLossReentry: ReentrySignal,
  postWinReentry: ReentrySignal,
  fatigue: AiStylePayload["behavioralSignals"]["fatigue"],
  worstPair: ({ pair: string } & GroupBucketStats) | null,
): AiStylePayload["behavioralSignals"]["whatIf"] {
  let capLossAt2xAvgWin: { cappedTrades: number; simulatedTotalPnl: number } | null = null;
  if (avgWin !== null && avgWin > 0) {
    const cap = -2 * avgWin;
    let cappedTrades = 0;
    let saved = 0;
    for (const r of rows) {
      const n = netOf(r).toNumber();
      if (n < cap) {
        cappedTrades++;
        saved += cap - n;
      }
    }
    if (cappedTrades > 0) {
      capLossAt2xAvgWin = { cappedTrades, simulatedTotalPnl: money(totalPnl + saved) };
    }
  }

  return {
    skipPostLossReentryPnl: postLossReentry.n > 0 ? money(totalPnl - postLossReentry.totalPnl) : null,
    skipPostWinReentryPnl: postWinReentry.n > 0 ? money(totalPnl - postWinReentry.totalPnl) : null,
    skipTrades7PlusPnl: fatigue.trades7plus ? money(totalPnl - fatigue.trades7plus.totalPnl) : null,
    capLossAt2xAvgWin,
    skipWorstPair:
      worstPair && worstPair.totalPnl < 0
        ? { pair: worstPair.pair, simulatedTotalPnl: money(totalPnl - worstPair.totalPnl) }
        : null,
  };
}

/** Replaces the anonymized labels in a model claim with the tags' real names, longest first so
 *  "Tag A" never matches inside a hypothetical "Tag AA". */
export function resolveTagLabels(text: string, tagNames: Record<string, string>): string {
  let out = text;
  for (const [label, name] of Object.entries(tagNames).sort((a, b) => b[0].length - a[0].length)) {
    out = out.split(label).join(name);
  }
  return out;
}

/** Clamps a period's end date ("YYYY-MM-DD") to today in [timeZone] so active presets never read as future dates. */
export function clampScopeEndDate(
  rangeTo: string | null,
  rangeFrom: string | null,
  timeZone: string,
  now: Date = new Date(),
): string | null {
  if (!rangeTo) return null;
  const z = zonedParts(now.toISOString(), timeZone);
  const today = `${z.year}-${String(z.month).padStart(2, "0")}-${String(z.day).padStart(2, "0")}`;
  if (rangeFrom && rangeFrom > today) return rangeTo;
  return rangeTo > today ? today : rangeTo;
}
