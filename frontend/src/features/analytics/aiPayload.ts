import type { ClosedPosition } from "@/api/analytics";
import type { CapitalOverview, MonthlyRoiItem, RoiResult } from "@/api/capital";
import type { Tag } from "@/api/taxonomy";
import type Decimal from "decimal.js";
import {
  computeStats,
  cumulativeFees,
  directionBreakdown,
  feeRatioByDay,
  feesByDayOfMonth,
  pairOf,
  pairsRankings,
  pnlByMonth,
  netOf,
  recoveryRate,
  streaks,
  traderStyle,
  winRateByHour,
  winRateByWeekday,
  zonedParts,
} from "./compute";

/**
 * Shapes the exact numbers the dashboard already computed into the payload the AI style analysis
 * sends. It never recomputes a metric: every value comes from the same functions the widgets use,
 * rounded to the precision the dashboard displays. Every figure carries its sample size `n`, and
 * breakdown buckets below [MIN_BUCKET_SAMPLE] are dropped (counted in `droppedBuckets`) so the
 * model cannot read patterns into noise.
 *
 * Origen tag names are anonymized here, in the browser, before anything leaves it: the payload
 * carries "Tag A", "Tag B"… and `tagMap` links each label to the tag's id so past runs can be
 * re-labelled later. Real tag names never reach the server or the provider.
 */

export const MIN_BUCKET_SAMPLE = 10;

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

export interface AiStylePayload {
  scope: AiScope;
  summary: {
    n: number;
    wins: number;
    losses: number;
    unresolved: number;
    totalPnl: number;
    volume: number;
    winRatePct: number | null;
    lossRatePct: number | null;
    avgWin: number | null;
    avgLoss: number | null;
    expectancy: number | null;
    avgRR: number | null;
    largestWin: number | null;
    largestLoss: number | null;
  };
  /** Only present when the capital feature has an anchor for the scope. */
  roi?: { roiPct: number; startCapital: number; netPnl: number } | null;
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
      longestMs: number;
      shortestMs: number;
      predominant: string | null;
    };
  };
  byHour: { minSample: number; droppedBuckets: number; buckets: { hour: number; n: number; winRatePct: number | null }[] };
  byWeekday: { minSample: number; droppedBuckets: number; buckets: { weekday: number; n: number; winRatePct: number | null }[] };
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
    mostTraded: { pair: string; n: number; totalPnl: number }[];
    mostProfitable: { pair: string; n: number; totalPnl: number }[];
    leastProfitable: { pair: string; n: number; totalPnl: number }[];
  };
  fees: { n: number; totalFees: number };
  /** Only present when the taxonomy has origen tags and no single tag is filtered. */
  byOrigen?: {
    minSample: number;
    droppedBuckets: number;
    buckets: { tag: string; n: number; totalPnl: number; winRatePct: number | null; expectancy: number | null }[];
  };
  /** label -> tag id; stripped server-side before anything reaches the model. */
  tagMap: Record<string, string>;
}

export interface AiPayloadInput {
  rows: ClosedPosition[];
  scope: AiScope;
  /** All origen tags of the taxonomy (any group order); already archived ones included. */
  origenTags: Tag[];
  capital?: CapitalOverview;
  roi?: RoiResult;
  /** Backend monthly ROI per `${year}-${month}`, matching the Monthly ROI card's source. */
  monthlyRoi: Map<string, MonthlyRoiItem>;
}

/** Labels in tag sort order, so the anonymization is stable across runs of the same taxonomy. */
export function anonymizeTags(tags: Tag[]): Map<string, string> {
  const ordered = [...tags].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
  const labels = new Map<string, string>();
  ordered.forEach((tag, i) => labels.set(tag.id, i < 26 ? `Tag ${String.fromCharCode(65 + i)}` : `Tag ${i + 1}`));
  return labels;
}

export function buildAiStylePayload(input: AiPayloadInput): AiStylePayload {
  const { rows, scope, origenTags, capital, roi, monthlyRoi } = input;
  const labels = anonymizeTags(origenTags);

  const stats = computeStats(rows);
  const summary = {
    n: rows.length,
    wins: stats.wins,
    losses: stats.losses,
    unresolved: rows.length - stats.wins - stats.losses,
    totalPnl: money(stats.totalPnl.toNumber()),
    volume: money(stats.volume.toNumber()),
    winRatePct: pct(stats.winRate),
    lossRatePct: pct(stats.lossRate),
    avgWin: moneyOr(stats.avgWin?.toNumber() ?? null),
    avgLoss: moneyOr(stats.avgLoss?.toNumber() ?? null),
    expectancy: moneyOr(stats.expectancy?.toNumber() ?? null),
    avgRR: stats.avgRR === null ? null : round(stats.avgRR.toNumber(), 2),
    largestWin: moneyOr(stats.largestWin?.toNumber() ?? null),
    largestLoss: moneyOr(stats.largestLoss?.toNumber() ?? null),
  };

  const payload: AiStylePayload = {
    scope: { ...scope },
    summary,
    direction: directionSection(rows),
    style: styleSection(rows),
    byHour: mapRate(rateSection(winRateByHour(rows, scope.timeZone)), (b) => ({ hour: b.key, n: b.n, winRatePct: b.winRatePct })),
    byWeekday: mapRate(rateSection(winRateByWeekday(rows, scope.timeZone)), (b) => ({ weekday: b.key, n: b.n, winRatePct: b.winRatePct })),
    byMonth: monthSection(rows, scope.timeZone, monthlyRoi),
    streaks: streaksSection(rows),
    pairs: pairsSection(rows),
    fees: { n: rows.length, totalFees: money(cumulativeFees(rows).at(-1)?.cumulative ?? 0) },
    tagMap: {},
  };

  if (roi?.roi !== null && roi?.roi !== undefined) {
    payload.roi = {
      roiPct: round(Number(roi.roi) * 100, 2),
      startCapital: money(Number(roi.startCapital ?? 0)),
      netPnl: money(Number(roi.netPnl ?? 0)),
    };
  }

  const risk = capitalSection(capital, scope.exchange);
  if (risk) payload.capitalAndRisk = risk;

  if (scope.origen === null && origenTags.length > 0) {
    payload.byOrigen = origenSection(rows, origenTags, labels);
  }

  // The tagMap travels separately: the server strips it before the model sees the payload.
  const usedTags = new Set(origenTags.map((t) => t.id));
  for (const id of usedTags) {
    const label = labels.get(id);
    if (label) payload.tagMap[label] = id;
  }
  if (scope.origen !== null) {
    // The filtered tag has no bucket of its own but its label still needs its real name back.
    const match = origenTags.find((t) => labels.get(t.id) === scope.origen);
    if (match) payload.tagMap[scope.origen] = match.id;
  }

  return payload;
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
  const kept = raw.filter((b) => b.bucket.count >= MIN_BUCKET_SAMPLE);
  const durationOf = (p: ClosedPosition) => new Date(p.closedAt).getTime() - new Date(p.openedAt).getTime();
  const wins = rows.filter((r) => netOf(r).gt(0));
  const losses = rows.filter((r) => netOf(r).lt(0));
  const avg = (list: ClosedPosition[]) => (list.length ? Math.round(list.reduce((acc, p) => acc + durationOf(p), 0) / list.length) : null);
  return {
    minSample: MIN_BUCKET_SAMPLE,
    droppedBuckets: raw.length - kept.length,
    buckets: kept.map(({ style, bucket }) => ({
      style,
      n: bucket.count,
      pnl: money(bucket.pnl),
      winRatePct: pct((bucket.winRate ?? 0) / 100),
    })),
    duration: {
      avgMs: Math.round(style.avgDurationMs ?? 0),
      avgWinMs: avg(wins),
      avgLossMs: avg(losses),
      longestMs: Math.round(style.longestMs ?? 0),
      shortestMs: Math.round(style.shortestMs ?? 0),
      predominant: style.predominant,
    },
  };
}

function rateSection(buckets: { key: number; count: number; winRate: number | null }[]): {
  minSample: number;
  droppedBuckets: number;
  buckets: { key: number; n: number; winRatePct: number | null }[];
} {
  const nonEmpty = buckets.filter((b) => b.count > 0);
  const kept = nonEmpty.filter((b) => b.count >= MIN_BUCKET_SAMPLE);
  return {
    minSample: MIN_BUCKET_SAMPLE,
    droppedBuckets: nonEmpty.length - kept.length,
    buckets: kept.map((b) => ({ key: b.key, n: b.count, winRatePct: pct((b.winRate ?? 0) / 100) })),
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

function mapRate<R>(
  section: { minSample: number; droppedBuckets: number; buckets: { key: number; n: number; winRatePct: number | null }[] },
  shape: (b: { key: number; n: number; winRatePct: number | null }) => R,
): { minSample: number; droppedBuckets: number; buckets: R[] } {
  return { minSample: section.minSample, droppedBuckets: section.droppedBuckets, buckets: section.buckets.map(shape) };
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
  const totalPairs = new Set(rows.map(pairOf)).size;
  const shape = (list: typeof ranks.mostTraded) =>
    list
      .filter((p) => p.count >= MIN_BUCKET_SAMPLE)
      .map((p) => ({ pair: p.pair, n: p.count, totalPnl: money(p.totalPnl) }));
  const mostTraded = shape(ranks.mostTraded);
  const mostProfitable = shape(ranks.mostProfitable);
  const leastProfitable = shape(ranks.leastProfitable);
  const kept = new Set([...mostTraded, ...mostProfitable, ...leastProfitable].map((p) => p.pair));
  return {
    minSample: MIN_BUCKET_SAMPLE,
    totalPairs,
    droppedBuckets: totalPairs - kept.size,
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

function origenSection(
  rows: ClosedPosition[],
  origenTags: Tag[],
  labels: Map<string, string>,
): AiStylePayload["byOrigen"] {
  type OrigenBucket = NonNullable<AiStylePayload["byOrigen"]>["buckets"][number];
  const buckets: OrigenBucket[] = [];
  let dropped = 0;
  for (const tag of [...origenTags].sort((a, b) => a.sortOrder - b.sortOrder)) {
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

/** Replaces the anonymized labels in a model claim with the tags' real names, longest first so
 *  "Tag A" never matches inside a hypothetical "Tag AA". */
export function resolveTagLabels(text: string, tagNames: Record<string, string>): string {
  let out = text;
  for (const [label, name] of Object.entries(tagNames).sort((a, b) => b[0].length - a[0].length)) {
    out = out.split(label).join(name);
  }
  return out;
}
