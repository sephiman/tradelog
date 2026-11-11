import { describe, expect, it } from "vitest";
import type { CapitalOverview } from "@/api/capital";
import type { PositionTagView, PositionSide } from "@/api/positions";
import type { ClosedPosition } from "@/api/analytics";
import type { Tag } from "@/api/taxonomy";
import { anonymizeTags, buildAiStylePayload, MIN_BUCKET_SAMPLE, resolveTagLabels } from "./aiPayload";

const TZ = "UTC";

function position(overrides: Partial<ClosedPosition> & { closedAt: string }): ClosedPosition {
  const { closedAt, ...rest } = overrides;
  return {
    id: rest.id ?? Math.random().toString(36).slice(2),
    source: "BINANCE",
    exchange: "Bybit",
    symbolBase: "BTC",
    symbolQuote: "USDT",
    side: "LONG" as PositionSide,
    openedAt: closedAt,
    closedAt,
    qty: "1",
    entryPrice: "100",
    exitPrice: "110",
    realizedPnl: "10",
    netPnl: "8",
    fees: "1",
    funding: "1",
    volume: "210",
    tags: [],
    ...rest,
  } as ClosedPosition;
}

function rowsWithHours(hours: number[]): ClosedPosition[] {
  // 2026-01-15 is a Thursday; the hour of `closedAt` drives the byHour buckets.
  return hours.map((h, i) =>
    position({ closedAt: `2026-01-15T${String(h).padStart(2, "0")}:00:00Z`, id: `p${i}` }),
  );
}

const origenTags: Tag[] = [
  { id: "tag-b-id", code: "copy", name: "Copy", sortOrder: 20, archivedAt: null },
  { id: "tag-a-id", code: "discrecional", name: "Discrecional", sortOrder: 10, archivedAt: null },
];

function tagged(id: string, tagId: string): ClosedPosition {
  const tag: PositionTagView = { groupId: "g1", groupCode: "origen", groupName: "Origen", tagId, tagName: "x" };
  return position({ closedAt: "2026-01-15T10:00:00Z", id, tags: [tag] });
}

const capital: CapitalOverview = {
  entries: [
    { exchange: "Bybit", amount: "1000.00", anchorDate: "2026-01-01", anchorAmount: "1000.00", dormant: false },
    { exchange: "Ghost", amount: null, anchorDate: null, anchorAmount: null, dormant: false },
    { exchange: "Dormant", amount: "0", anchorDate: "2026-01-01", anchorAmount: "0", dormant: true },
  ],
  total: "1000.00",
  riskPercents: { pct1: "1", pct2: "2" },
  snapshotFrequency: "DAILY",
  knownExchanges: ["Bybit"],
  hasAnchors: true,
  timeZone: TZ,
};

function payload(rows: ClosedPosition[], extra: Partial<Parameters<typeof buildAiStylePayload>[0]> = {}) {
  return buildAiStylePayload({
    rows,
    scope: { periodPreset: "all", rangeFrom: null, rangeTo: null, exchange: "ALL", origen: null, timeZone: TZ },
    origenTags,
    capital,
    roi: { roi: "0.05", startCapital: "1000", netPnl: "50", cutDate: null },
    monthlyRoi: new Map([["2026-1", { month: 1, roi: "0.05", startCapital: "1000", netPnl: "50" }]]),
    ...extra,
  });
}

describe("buildAiStylePayload", () => {
  it("keeps buckets at or above the minimum sample and counts the dropped ones", () => {
    // 10 trades at 14:00 (kept) and 3 at 3:00 (dropped).
    const p = payload([...rowsWithHours(Array(10).fill(14)), ...rowsWithHours(Array(3).fill(3))]);
    expect(MIN_BUCKET_SAMPLE).toBe(10);
    expect(p.byHour.minSample).toBe(10);
    expect(p.byHour.buckets).toEqual([{ hour: 14, n: 10, winRatePct: 100 }]);
    expect(p.byHour.droppedBuckets).toBe(1);
    // Empty buckets (no trades at all) are neither kept nor counted as dropped.
    expect(p.byHour.buckets.every((b) => b.n >= MIN_BUCKET_SAMPLE)).toBe(true);
  });

  it("anonymizes origen tags and keeps the real names out of the payload", () => {
    const rows = [
      tagged("a1", "tag-a-id"), tagged("a2", "tag-a-id"), tagged("a3", "tag-a-id"),
      tagged("a4", "tag-a-id"), tagged("a5", "tag-a-id"), tagged("a6", "tag-a-id"),
      tagged("a7", "tag-a-id"), tagged("a8", "tag-a-id"), tagged("a9", "tag-a-id"), tagged("a10", "tag-a-id"),
      tagged("b1", "tag-b-id"), tagged("b2", "tag-b-id"),
    ];
    const p = payload(rows);
    expect(p.tagMap).toEqual({ "Tag A": "tag-a-id", "Tag B": "tag-b-id" });
    expect(p.byOrigen?.buckets.map((b) => b.tag)).toEqual(["Tag A"]);
    expect(p.byOrigen?.droppedBuckets).toBe(1);
    const serialized = JSON.stringify(p);
    expect(serialized).not.toContain("Discrecional");
    expect(serialized).not.toContain("Copy");
  });

  it("summarizes with sample sizes from computeStats and period ROI", () => {
    const wins = rowsWithHours(Array(7).fill(10)).map((r, i) => ({ ...r, netPnl: i < 5 ? "10" : "-4" }));
    const p = payload(wins);
    expect(p.summary.n).toBe(7);
    expect(p.summary.wins).toBe(5);
    expect(p.summary.losses).toBe(2);
    expect(p.summary.winRatePct).toBe(71.4);
    expect(p.roi?.roiPct).toBe(5);
    expect(p.capitalAndRisk?.total).toEqual({ capital: 1000, maxLossAtPct1: 10, maxLossAtPct2: 20 });
    // Dormant and un-anchored venues are out, exactly like the Capital & risk card.
    expect(p.capitalAndRisk?.byExchange.map((e) => e.exchange)).toEqual(["Bybit"]);
  });

  it("records the filter scope it was built from", () => {
    const p = payload(rowsWithHours(Array(10).fill(10)), {
      scope: { periodPreset: "month", rangeFrom: "2026-01-01", rangeTo: "2026-01-31", exchange: "Bybit", origen: "Tag A", timeZone: TZ },
    });
    expect(p.scope).toEqual({
      periodPreset: "month", rangeFrom: "2026-01-01", rangeTo: "2026-01-31",
      exchange: "Bybit", origen: "Tag A", timeZone: TZ,
    });
    // A single-origen filter still carries its label's real name in the map.
    expect(p.tagMap["Tag A"]).toBe("tag-a-id");
  });

  it("skips the origen breakdown when a single tag is filtered", () => {
    const rows = Array.from({ length: 12 }, (_, i) => tagged(`t${i}`, "tag-a-id"));
    const p = payload(rows, {
      scope: { periodPreset: "all", rangeFrom: null, rangeTo: null, exchange: "ALL", origen: "Tag A", timeZone: TZ },
    });
    expect(p.byOrigen).toBeUndefined();
    expect(p.summary.n).toBe(12);
  });
});

describe("tag anonymization helpers", () => {
  it("labels follow tag sort order, not array order", () => {
    const labels = anonymizeTags(origenTags);
    expect(labels.get("tag-a-id")).toBe("Tag A");
    expect(labels.get("tag-b-id")).toBe("Tag B");
  });

  it("resolves labels back to real names, longest label first", () => {
    const names = { "Tag A": "Discrecional", "Tag AA": "Momentum" };
    expect(resolveTagLabels("Tag A y Tag AA", names)).toBe("Discrecional y Momentum");
  });
});
