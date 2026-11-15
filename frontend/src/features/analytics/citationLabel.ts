import type { AiCitation, AiRunScope } from "@/api/ai";
import type { TFunction } from "i18next";

const EXACT_KEYS: Record<string, string> = {
  "portfolioHeat.maxConcurrentOpen": "analytics.ai.citations.maxConcurrentOpen",
  "portfolioHeat.avgConcurrentAtEntry": "analytics.ai.citations.avgConcurrentAtEntry",
  "summary.n": "analytics.ai.citations.summaryN",
  "summary.wins": "analytics.ai.citations.summaryWins",
  "summary.losses": "analytics.ai.citations.summaryLosses",
  "summary.totalPnl": "analytics.ai.citations.summaryTotalPnl",
  "summary.grossPnl": "analytics.ai.citations.summaryGrossPnl",
  "summary.totalFunding": "analytics.ai.citations.summaryTotalFunding",
  "summary.volume": "analytics.ai.citations.summaryVolume",
  "summary.winRatePct": "analytics.ai.citations.summaryWinRatePct",
  "summary.lossRatePct": "analytics.ai.citations.summaryLossRatePct",
  "summary.avgWin": "analytics.ai.citations.summaryAvgWin",
  "summary.avgLoss": "analytics.ai.citations.summaryAvgLoss",
  "summary.expectancy": "analytics.ai.citations.summaryExpectancy",
  "summary.avgRR": "analytics.ai.citations.summaryAvgRR",
  "summary.profitFactor": "analytics.ai.citations.summaryProfitFactor",
  "summary.largestWin": "analytics.ai.citations.summaryLargestWin",
  "summary.largestLoss": "analytics.ai.citations.summaryLargestLoss",
  "drawdown.maxUsd": "analytics.ai.citations.drawdownMaxUsd",
  "drawdown.currentUsd": "analytics.ai.citations.drawdownCurrentUsd",
  "drawdown.maxPctOfStartCapital": "analytics.ai.citations.drawdownMaxPct",
  "tailAndConcentration.pnlWithoutWorstTrade": "analytics.ai.citations.pnlWithoutWorst",
  "tailAndConcentration.pnlWithoutBestTrade": "analytics.ai.citations.pnlWithoutBest",
  "tailAndConcentration.top3WinnersPnl": "analytics.ai.citations.top3Winners",
  "tailAndConcentration.top3LosersPnl": "analytics.ai.citations.top3Losers",
  "tailAndConcentration.lossesOver2xAvg.n": "analytics.ai.citations.lossesOver2xCount",
  "tailAndConcentration.lossesOver2xAvg.totalPnl": "analytics.ai.citations.lossesOver2xPnl",
  "tailAndConcentration.largestLossPctOfCapital": "analytics.ai.citations.largestLossPctCap",
  "tailAndConcentration.avgLossPctOfCapital": "analytics.ai.citations.avgLossPctCap",
  "roi.roiPct": "analytics.ai.citations.roiPct",
  "roi.startCapital": "analytics.ai.citations.roiStartCapital",
  "roi.netPnl": "analytics.ai.citations.roiNetPnl",
  "capitalAndRisk.total.capital": "analytics.ai.citations.totalCapital",
  "capitalAndRisk.total.maxLossAtPct1": "analytics.ai.citations.maxLossPct1",
  "capitalAndRisk.total.maxLossAtPct2": "analytics.ai.citations.maxLossPct2",
  "fees.totalFees": "analytics.ai.citations.totalFees",
  "fees.totalFunding": "analytics.ai.citations.totalFunding",
  "fees.totalCosts": "analytics.ai.citations.totalCosts",
  "fees.avgFeePerTrade": "analytics.ai.citations.avgFeePerTrade",
  "fees.feeToGrossProfitPct": "analytics.ai.citations.feeToGrossProfitPct",
  "fees.feeToVolumePct": "analytics.ai.citations.feeToVolumePct",
  "style.duration.avgMs": "analytics.ai.citations.avgDuration",
  "style.duration.avgWinMs": "analytics.ai.citations.avgWinDuration",
  "style.duration.avgLossMs": "analytics.ai.citations.avgLossDuration",
  "style.duration.avgHours": "analytics.ai.citations.avgDurationHours",
  "style.duration.avgWinHours": "analytics.ai.citations.avgWinDurationHours",
  "style.duration.avgLossHours": "analytics.ai.citations.avgLossDurationHours",
  "style.duration.longestHours": "analytics.ai.citations.longestDurationHours",
  "style.duration.shortestHours": "analytics.ai.citations.shortestDurationHours",
  "style.duration.lossToWinDurationRatio": "analytics.ai.citations.lossToWinDurationRatio",
  "behavioralSignals.sizing.avgWinVolume": "analytics.ai.citations.avgWinVolume",
  "behavioralSignals.sizing.avgLossVolume": "analytics.ai.citations.avgLossVolume",
  "behavioralSignals.sizing.lossToWinRatio": "analytics.ai.citations.sizingLossToWinRatio",
  "behavioralSignals.sizing.volumeCv": "analytics.ai.citations.volumeCv",
  "behavioralSignals.sizing.after2LossesSizeMultiplier": "analytics.ai.citations.after2LossesSize",
  "behavioralSignals.sizing.after2WinsSizeMultiplier": "analytics.ai.citations.after2WinsSize",
  "behavioralSignals.whatIf.skipPostLossReentryPnl": "analytics.ai.citations.whatIfSkipPostLoss",
  "behavioralSignals.whatIf.skipPostWinReentryPnl": "analytics.ai.citations.whatIfSkipPostWin",
  "behavioralSignals.whatIf.skipTrades7PlusPnl": "analytics.ai.citations.whatIfSkip7Plus",
  "behavioralSignals.whatIf.capLossAt2xAvgWin.simulatedTotalPnl": "analytics.ai.citations.whatIfCapLosses",
  "behavioralSignals.whatIf.capLossAt2xAvgWin.cappedTrades": "analytics.ai.citations.whatIfCapLossesCount",
  "behavioralSignals.whatIf.skipWorstPair.simulatedTotalPnl": "analytics.ai.citations.whatIfSkipWorstPair",
};

const FIELD_KEYS: Record<string, string> = {
  n: "analytics.ai.citations.fieldN",
  winRatePct: "analytics.ai.citations.fieldWinRate",
  totalPnl: "analytics.ai.citations.fieldPnl",
  pnl: "analytics.ai.citations.fieldPnl",
  expectancy: "analytics.ai.citations.fieldExpectancy",
  totalFees: "analytics.ai.citations.fieldFees",
  fees: "analytics.ai.citations.fieldFees",
  feeRatio: "analytics.ai.citations.fieldFeeRatio",
  sizeMultiplier: "analytics.ai.citations.fieldSizeMultiplier",
  returnPct: "analytics.ai.citations.fieldReturnPct",
  roiPct: "analytics.ai.citations.fieldRoiPct",
  avgLength: "analytics.ai.citations.fieldAvgLength",
  longestLength: "analytics.ai.citations.fieldLongestLength",
  longestNetPnl: "analytics.ai.citations.fieldLongestPnl",
  ratePct: "analytics.ai.citations.fieldRecoveryRate",
  droppedBuckets: "analytics.ai.citations.fieldDroppedBuckets",
  minSample: "analytics.ai.citations.fieldMinSample",
  pair: "analytics.ai.citations.fieldPair",
  exchange: "analytics.ai.citations.fieldExchange",
  style: "analytics.ai.citations.fieldStyle",
  session: "analytics.ai.citations.fieldSession",
  dayType: "analytics.ai.citations.fieldDayType",
  combo: "analytics.ai.citations.fieldCombo",
  tag: "analytics.ai.citations.fieldTag",
  hour: "analytics.ai.citations.fieldHour",
  weekday: "analytics.ai.citations.fieldWeekday",
  kind: "analytics.ai.citations.fieldKind",
  key: "analytics.ai.citations.fieldBenchmark",
  holdHours: "analytics.ai.citations.fieldHoldHours",
  volume: "analytics.ai.citations.fieldVolume",
  grossPnl: "analytics.ai.citations.fieldGrossPnl",
  netPnl: "analytics.ai.citations.fieldPnl",
  returnOnVolumePct: "analytics.ai.citations.fieldReturnOnVolumePct",
  priceMovePct: "analytics.ai.citations.fieldPriceMovePct",
  funding: "analytics.ai.citations.fieldFunding",
  side: "analytics.ai.citations.fieldSide",
};

const PREFIX_KEYS: [RegExp, string][] = [
  [/^direction\.(long|short)\.(.+)$/, "analytics.ai.citations.prefixDirection"],
  [/^portfolioHeat\.(isolated|overlapping|sameSideOverlap)\.(.+)$/, "analytics.ai.citations.prefixPortfolioHeat"],
  [/^bySession\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixSession"],
  [/^byDayType\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixDayType"],
  [/^directionStyle\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixDirectionStyle"],
  [/^byHour\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixHour"],
  [/^byWeekday\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixWeekday"],
  [/^byMonth\.\d+\.(.+)$/, "analytics.ai.citations.prefixMonth"],
  [/^byExchange\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixExchange"],
  [/^byKind\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixKind"],
  [/^byOrigen\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixOrigen"],
  [/^byTagGroups\.\d+\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixTagGroup"],
  [/^style\.buckets\.\d+\.(.+)$/, "analytics.ai.citations.prefixStyle"],
  [/^pairs\.(mostTraded|mostProfitable|leastProfitable)\.\d+\.(.+)$/, "analytics.ai.citations.prefixPairs"],
  [/^streaks\.(winning|losing|recovery)\.(.+)$/, "analytics.ai.citations.prefixStreaks"],
  [/^benchmarks\.\d+\.(.+)$/, "analytics.ai.citations.prefixBenchmarks"],
  [/^trades\.\d+\.(.+)$/, "analytics.ai.citations.prefixTrade"],
  [/^recentTrades\.\d+\.(.+)$/, "analytics.ai.citations.prefixRecentTrade"],
  [/^topLosers\.\d+\.(.+)$/, "analytics.ai.citations.prefixTopLoser"],
  [/^topWinners\.\d+\.(.+)$/, "analytics.ai.citations.prefixTopWinner"],
  [/^behavioralSignals\.postLossReentry\.(.+)$/, "analytics.ai.citations.prefixPostLoss"],
  [/^behavioralSignals\.postWinReentry\.(.+)$/, "analytics.ai.citations.prefixPostWin"],
  [/^behavioralSignals\.fatigue\.(trades1to3|trades4to6|trades7plus)\.(.+)$/, "analytics.ai.citations.prefixFatigue"],
];

/** Maps a dot/bracket payload path (and optional parent context) to a concise translated label. */
export function formatCitationPath(path: string, t: TFunction, context?: string | null): string {
  const normalized = path.replace(/\[(\d+)]/g, ".$1");
  const exact = EXACT_KEYS[normalized];
  if (exact) return t(exact, { defaultValue: normalized });

  const lastSeg = normalized.split(".").pop() ?? "";
  const fieldKey = FIELD_KEYS[lastSeg];
  if (context && fieldKey) {
    return `${context} · ${t(fieldKey, { defaultValue: lastSeg })}`;
  }

  for (const [pattern, sectionKey] of PREFIX_KEYS) {
    const match = pattern.exec(normalized);
    if (!match) continue;
    const field = match[match.length - 1];
    const fk = FIELD_KEYS[field];
    const sectionLabel = t(sectionKey, {
      sub: match.length > 2 ? match[1] : undefined,
      defaultValue: normalized,
    });
    const fieldLabel = fk ? t(fk, { defaultValue: field }) : field;
    return `${sectionLabel} · ${fieldLabel}`;
  }

  return path;
}

const baseSymbol = (pair: string): string => pair.split("/")[0]?.trim() || pair;

const monthBounds = (ym: string): { from: string; to: string } | null => {
  const m = /^(\d{4})-(\d{2})$/.exec(ym);
  if (!m) return null;
  const year = Number(m[1]);
  const month = Number(m[2]);
  if (month < 1 || month > 12) return null;
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return { from: `${m[1]}-${m[2]}-01`, to: `${m[1]}-${m[2]}-${String(lastDay).padStart(2, "0")}` };
};

/** Maps a grounded citation to `/positions` search query parameters when it pinpoints filterable trades. */
export function citationPositionsQuery(citation: AiCitation, scope: AiRunScope): string | null {
  if (!citation.grounded) return null;
  const normalized = citation.path.replace(/\[(\d+)]/g, ".$1");
  const params = new URLSearchParams();
  let matched = false;

  if (scope.rangeFrom) params.set("from", scope.rangeFrom);
  if (scope.rangeTo) params.set("to", scope.rangeTo);
  if (scope.exchange && scope.exchange !== "ALL") params.set("exchange", scope.exchange);

  const ctx = citation.context?.trim() ?? "";

  if (/^(trades|recentTrades|topLosers|topWinners)\.\d+\./.test(normalized)) {
    const m = /^#\d+\s+([^\s]+)$/.exec(ctx);
    if (m) {
      params.set("symbol", baseSymbol(m[1]));
      matched = true;
    }
  } else if (/^pairs\./.test(normalized)) {
    const pair = ctx || (typeof citation.value === "string" ? citation.value.trim() : "");
    if (pair.includes("/")) {
      params.set("symbol", baseSymbol(pair));
      matched = true;
    }
  } else if (/^direction\.long\./.test(normalized)) {
    params.set("side", "LONG");
    matched = true;
  } else if (/^direction\.short\./.test(normalized)) {
    params.set("side", "SHORT");
    matched = true;
  } else if (/^directionStyle\.buckets\.\d+\./.test(normalized)) {
    const side = ctx.startsWith("LONG_") ? "LONG" : ctx.startsWith("SHORT_") ? "SHORT" : null;
    if (side) {
      params.set("side", side);
      matched = true;
    }
  } else if (/^(byExchange\.buckets|capitalAndRisk\.byExchange)\.\d+\./.test(normalized) && ctx) {
    params.set("exchange", ctx);
    matched = true;
  } else if (/^byMonth\.\d+\./.test(normalized)) {
    const bounds = monthBounds(ctx);
    if (bounds) {
      params.set("from", bounds.from);
      params.set("to", bounds.to);
      matched = true;
    }
  }

  return matched ? params.toString() : null;
}
