import { describe, expect, it } from "vitest";
import type { SnapshotDay } from "@/api/capital";
import { pricesByDate, projectLumpSum } from "./capitalBenchmark";

/** Snapshot days from a compact {date: {exchange: amount}} literal. */
function daysOf(spec: Record<string, Record<string, number>>): SnapshotDay[] {
  return Object.entries(spec).map(([date, values]) => ({
    date,
    values: Object.entries(values).map(([exchange, amount]) => ({
      exchange,
      amount: String(amount),
      manual: false,
    })),
  }));
}

const prices = (spec: Record<string, number | null>) =>
  pricesByDate(Object.entries(spec).map(([date, close]) => ({ date, close: close === null ? null : String(close) })));

const ALL = () => true;

describe("projectLumpSum", () => {
  it("projects the baseline capital forward at the benchmark's growth", () => {
    const days = daysOf({ "2026-01-01": { A: 1000 }, "2026-01-02": { A: 900 }, "2026-01-03": { A: 800 } });
    const p = prices({ "2026-01-01": 100, "2026-01-02": 110, "2026-01-03": 50 });

    const out = projectLumpSum(days, ALL, p);

    // The projection tracks the benchmark, never the capital it was seeded from.
    expect(out.get("2026-01-01")).toBeCloseTo(1000);
    expect(out.get("2026-01-02")).toBeCloseTo(1100);
    expect(out.get("2026-01-03")).toBeCloseTo(500);
  });

  it("anchors each exchange on its own first day, so a mid-range arrival is not growth", () => {
    // B shows up on day 2, after the benchmark has already doubled. Anchoring on the combined
    // total would credit B's 500 with that doubling and invent 500 of gains.
    const days = daysOf({
      "2026-01-01": { A: 1000 },
      "2026-01-02": { A: 1000, B: 500 },
      "2026-01-03": { A: 1000, B: 500 },
    });
    const p = prices({ "2026-01-01": 100, "2026-01-02": 200, "2026-01-03": 400 });

    const out = projectLumpSum(days, ALL, p);

    expect(out.get("2026-01-01")).toBeCloseTo(1000); // A only
    expect(out.get("2026-01-02")).toBeCloseTo(2500); // A doubled to 2000, B enters flat at 500
    expect(out.get("2026-01-03")).toBeCloseTo(5000); // both doubled again
  });

  it("starts an exchange on its first positive day, not on a zero balance", () => {
    // A flat zero projected forward would draw a line pinned at 0 for the whole window.
    const days = daysOf({ "2026-01-01": { A: 0 }, "2026-01-02": { A: 0 }, "2026-01-03": { A: 400 } });
    const p = prices({ "2026-01-01": 100, "2026-01-02": 200, "2026-01-03": 400 });

    const out = projectLumpSum(days, ALL, p);

    expect(out.get("2026-01-01")).toBeNull();
    expect(out.get("2026-01-02")).toBeNull();
    expect(out.get("2026-01-03")).toBeCloseTo(400);
  });

  it("keeps the baseline amount even after the real capital changes", () => {
    // Lump sum: later deposits are deliberately not projected.
    const days = daysOf({ "2026-01-01": { A: 100 }, "2026-01-02": { A: 9999 } });
    const p = prices({ "2026-01-01": 10, "2026-01-02": 20 });

    const out = projectLumpSum(days, ALL, p);

    expect(out.get("2026-01-02")).toBeCloseTo(200);
  });

  it("honours the exchange filter, which moves the baseline", () => {
    const days = daysOf({ "2026-01-01": { A: 1000, B: 500 }, "2026-01-02": { A: 1000, B: 500 } });
    const p = prices({ "2026-01-01": 100, "2026-01-02": 200 });

    expect(projectLumpSum(days, ALL, p).get("2026-01-02")).toBeCloseTo(3000);
    expect(projectLumpSum(days, (ex) => ex === "B", p).get("2026-01-02")).toBeCloseTo(1000);
  });

  it("breaks the line on a day the benchmark cannot price", () => {
    const days = daysOf({ "2026-01-01": { A: 100 }, "2026-01-02": { A: 100 }, "2026-01-03": { A: 100 } });
    const p = prices({ "2026-01-01": 10, "2026-01-02": null, "2026-01-03": 20 });

    const out = projectLumpSum(days, ALL, p);

    expect(out.get("2026-01-01")).toBeCloseTo(100);
    expect(out.get("2026-01-02")).toBeNull();
    expect(out.get("2026-01-03")).toBeCloseTo(200);
  });

  it("gaps the whole line rather than silently summing only the priceable exchanges", () => {
    // B's baseline day has no price, so its share can never be projected. Reporting A alone would
    // read as the benchmark underperforming, when really part of the comparison is missing.
    const days = daysOf({ "2026-01-01": { A: 100 }, "2026-01-02": { A: 100, B: 100 }, "2026-01-03": { A: 100, B: 100 } });
    const p = prices({ "2026-01-01": 10, "2026-01-02": null, "2026-01-03": 20 });

    const out = projectLumpSum(days, ALL, p);

    expect(out.get("2026-01-01")).toBeCloseTo(100); // only A had started
    expect(out.get("2026-01-02")).toBeNull();
    expect(out.get("2026-01-03")).toBeNull();
  });

  it("is empty before any exchange holds capital", () => {
    const out = projectLumpSum(daysOf({ "2026-01-01": {} }), ALL, prices({ "2026-01-01": 10 }));

    expect(out.get("2026-01-01")).toBeNull();
  });

  it("yields nothing when the benchmark has no prices at all", () => {
    const days = daysOf({ "2026-01-01": { A: 100 }, "2026-01-02": { A: 100 } });

    const out = projectLumpSum(days, ALL, new Map());

    expect([...out.values()]).toEqual([null, null]);
  });
});

describe("pricesByDate", () => {
  it("treats a null, empty, non-numeric or non-positive close as a gap", () => {
    const p = pricesByDate([
      { date: "d1", close: null },
      { date: "d2", close: "" },
      { date: "d3", close: "nope" },
      { date: "d4", close: "0" },
      { date: "d5", close: "-5" },
      { date: "d6", close: "12.5" },
    ]);

    expect(p.get("d1")).toBeNull();
    expect(p.get("d2")).toBeNull();
    expect(p.get("d3")).toBeNull();
    expect(p.get("d4")).toBeNull(); // a zero price would divide by zero at the baseline
    expect(p.get("d5")).toBeNull();
    expect(p.get("d6")).toBeCloseTo(12.5);
  });
});
