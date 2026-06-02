import { describe, expect, it } from "vitest";
import type { ClosedPosition } from "@/api/analytics";
import {
  computeStats,
  cumulativeFees,
  directionBreakdown,
  equityCurve,
  feeRatioByDay,
  feesByDayOfMonth,
  netOf,
  pairsRankings,
  recoveryRate,
  streaks,
  styleOf,
  traderStyle,
  winRateByHour,
  zonedParts,
  DAY_MAX_MS,
  SCALPER_MAX_MS,
} from "./compute";

let seq = 0;
function pos(over: Partial<ClosedPosition>): ClosedPosition {
  return {
    id: over.id ?? `id-${seq++}`,
    source: "BITUNIX",
    exchange: "Bitunix",
    symbolBase: "BTC",
    symbolQuote: "USDT",
    side: "LONG",
    openedAt: "2026-02-01T00:00:00Z",
    closedAt: "2026-02-01T01:00:00Z",
    realizedPnl: "0",
    netPnl: "0",
    fees: "0",
    funding: "0",
    tags: [],
    ...over,
  };
}

/** Build a row whose net result is `net` (the backend's netPnl). */
const net = (net: number, over: Partial<ClosedPosition> = {}) => pos({ netPnl: String(net), ...over });

describe("netOf", () => {
  it("returns the backend's netPnl", () => {
    expect(netOf(pos({ netPnl: "7", realizedPnl: "10", fees: "2", funding: "1" })).toNumber()).toBe(7);
  });
});

describe("computeStats", () => {
  it("handles an empty set without NaN", () => {
    const s = computeStats([]);
    expect(s.total).toBe(0);
    expect(s.totalPnl.toNumber()).toBe(0);
    expect(s.winRate).toBeNull();
    expect(s.avgRR).toBeNull();
    expect(s.expectancy).toBeNull();
  });

  it("computes the 8 indicators and excludes zero-pnl from win rate", () => {
    const s = computeStats([net(10), net(-4), net(6), net(0)]);
    expect(s.total).toBe(4);
    expect(s.wins).toBe(2);
    expect(s.losses).toBe(1);
    expect(s.totalPnl.toNumber()).toBe(12);
    expect(s.winRate).toBeCloseTo(2 / 3);
    expect(s.avgWin!.toNumber()).toBe(8);
    expect(s.avgLoss!.toNumber()).toBe(-4);
    expect(s.expectancy!.toNumber()).toBeCloseTo(4); // 2/3*8 - 1/3*4
    expect(s.avgRR!.toNumber()).toBe(2); // 8 / |−4|
    expect(s.largestWin!.toNumber()).toBe(10);
    expect(s.largestLoss!.toNumber()).toBe(-4);
  });

  it("all wins: expectancy equals avgWin, no avgRR", () => {
    const s = computeStats([net(5), net(15)]);
    expect(s.winRate).toBe(1);
    expect(s.expectancy!.toNumber()).toBe(10);
    expect(s.avgRR).toBeNull();
    expect(s.largestLoss).toBeNull();
  });
});

describe("equityCurve", () => {
  it("is a running sum ordered by close date regardless of input order", () => {
    const rows = [
      net(5, { closedAt: "2026-02-03T00:00:00Z" }),
      net(10, { closedAt: "2026-02-01T00:00:00Z" }),
      net(-4, { closedAt: "2026-02-02T00:00:00Z" }),
    ];
    expect(equityCurve(rows).map((p) => p.cumulative)).toEqual([10, 6, 11]);
  });
});

describe("zonedParts", () => {
  it("rolls the day back when the zone is behind UTC", () => {
    // 01:30Z on 2026-02-01 is 20:30 on 2026-01-31 in New York (UTC−5 in winter).
    const z = zonedParts("2026-02-01T01:30:00Z", "America/New_York");
    expect(z.year).toBe(2026);
    expect(z.month).toBe(1);
    expect(z.day).toBe(31);
    expect(z.hour).toBe(20);
    expect(z.weekday).toBe(5); // Saturday
  });

  it("UTC parts match the instant", () => {
    const z = zonedParts("2026-02-02T09:00:00Z", "UTC");
    expect(z).toMatchObject({ year: 2026, month: 2, day: 2, hour: 9, weekday: 0 }); // Monday
  });
});

describe("winRateByHour", () => {
  it("buckets in the user's time zone", () => {
    const buckets = winRateByHour([net(1, { closedAt: "2026-02-01T01:30:00Z" })], "America/New_York");
    expect(buckets).toHaveLength(24);
    expect(buckets[20].count).toBe(1);
    expect(buckets[20].winRate).toBe(100);
    expect(buckets[1].count).toBe(0);
    expect(buckets[1].winRate).toBeNull();
  });
});

describe("traderStyle", () => {
  it("classifies on the 15min / 24h boundaries", () => {
    expect(styleOf(SCALPER_MAX_MS - 1)).toBe("scalper");
    expect(styleOf(SCALPER_MAX_MS)).toBe("day");
    expect(styleOf(DAY_MAX_MS)).toBe("day");
    expect(styleOf(DAY_MAX_MS + 1)).toBe("swing");
  });

  it("summarizes durations and predominant style", () => {
    const mk = (openMs: number, closeMs: number) =>
      net(1, { openedAt: new Date(openMs).toISOString(), closedAt: new Date(closeMs).toISOString() });
    const ts = traderStyle([
      mk(0, 60_000), // 1 min -> scalper
      mk(0, 60 * 60_000), // 1 h -> day
      mk(0, 2 * 60 * 60_000), // 2 h -> day
    ]);
    expect(ts.scalper.count).toBe(1);
    expect(ts.day.count).toBe(2);
    expect(ts.swing.count).toBe(0);
    expect(ts.predominant).toBe("day");
    expect(ts.shortestMs).toBe(60_000);
    expect(ts.longestMs).toBe(2 * 60 * 60_000);
  });
});

describe("directionBreakdown", () => {
  it("splits by side", () => {
    const d = directionBreakdown([net(10, { side: "LONG" }), net(-2, { side: "SHORT" }), net(4, { side: "LONG" })]);
    expect(d.long.count).toBe(2);
    expect(d.long.totalPnl.toNumber()).toBe(14);
    expect(d.short.count).toBe(1);
    expect(d.short.totalPnl.toNumber()).toBe(-2);
  });
});

describe("streaks", () => {
  it("measures winning and losing runs and skips zero-pnl", () => {
    const c = (i: number) => ({ closedAt: `2026-02-${String(i + 1).padStart(2, "0")}T00:00:00Z` });
    const s = streaks([net(10, c(0)), net(5, c(1)), net(-3, c(2)), net(-2, c(3)), net(-1, c(4)), net(8, c(5))]);
    expect(s.winning.longestLength).toBe(2);
    expect(s.winning.longestNetPnl).toBe(15);
    expect(s.winning.avgLength).toBe(1.5); // runs of 2 and 1
    expect(s.losing.longestLength).toBe(3);
    expect(s.losing.longestNetPnl).toBe(-6);
  });

  it("a zero-pnl trade does not break a winning run", () => {
    const c = (i: number) => ({ closedAt: `2026-02-${String(i + 1).padStart(2, "0")}T00:00:00Z` });
    const s = streaks([net(10, c(0)), net(5, c(1)), net(0, c(2)), net(8, c(3))]);
    expect(s.winning.longestLength).toBe(3);
    expect(s.winning.longestNetPnl).toBe(23);
  });
});

describe("recoveryRate", () => {
  it("is the share of losses followed by a win", () => {
    const c = (i: number) => ({ closedAt: `2026-02-${String(i + 1).padStart(2, "0")}T00:00:00Z` });
    const r = recoveryRate([net(-1, c(0)), net(5, c(1)), net(-2, c(2)), net(-3, c(3)), net(4, c(4))]);
    expect(r.sample).toBe(3);
    expect(r.rate).toBeCloseTo(2 / 3);
  });
});

describe("pairsRankings", () => {
  it("ranks by count and by profit sign", () => {
    const rows = [
      net(5, { symbolBase: "BTC" }),
      net(3, { symbolBase: "BTC" }),
      net(2, { symbolBase: "BTC" }),
      net(-4, { symbolBase: "ETH" }),
    ];
    const r = pairsRankings(rows);
    expect(r.mostTraded[0]).toMatchObject({ pair: "BTC/USDT", count: 3 });
    expect(r.mostProfitable[0].pair).toBe("BTC/USDT");
    expect(r.leastProfitable[0]).toMatchObject({ pair: "ETH/USDT", totalPnl: -4 });
  });
});

describe("fees", () => {
  it("sums fees per day and over the month", () => {
    const rows = [
      pos({ fees: "2", closedAt: "2026-02-05T00:00:00Z" }),
      pos({ fees: "3", closedAt: "2026-02-05T10:00:00Z" }),
      pos({ fees: "1", closedAt: "2026-02-06T00:00:00Z" }),
    ];
    const f = feesByDayOfMonth(rows, 2026, 2, "UTC");
    expect(f.total).toBe(6);
    expect(f.days[4].fee).toBe(5); // day 5
    expect(f.days[5].fee).toBe(1); // day 6
  });

  it("accumulates cumulative fees in close order", () => {
    const rows = [
      pos({ fees: "1", closedAt: "2026-02-01T00:00:00Z" }),
      pos({ fees: "2", closedAt: "2026-02-02T00:00:00Z" }),
    ];
    expect(cumulativeFees(rows).map((p) => p.cumulative)).toEqual([1, 3]);
  });

  it("computes a bounded fee ratio", () => {
    const rows = [pos({ netPnl: "8", fees: "2", closedAt: "2026-02-05T00:00:00Z" })];
    const fr = feeRatioByDay(rows, 2026, 2, "UTC");
    // ratio = fees / (|net| + fees) = 2 / (8 + 2) = 0.2
    expect(fr.days[4].ratio).toBeCloseTo(0.2);
    expect(fr.monthRatio).toBeCloseTo(0.2);
  });
});
