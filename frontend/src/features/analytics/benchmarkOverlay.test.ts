import { describe, expect, it } from "vitest";
import type { BenchmarkMonthlySeries } from "@/api/benchmarks";
import { benchmarkColors, withBenchmarkColumns } from "./benchmarkOverlay";
import { computeMonthlyRoiSeries } from "./compute";

const rows = computeMonthlyRoiSeries([]);

/** A twelve-month series where only the listed months carry a return; the rest are gaps. */
function series(key: string, returns: Record<number, string>): BenchmarkMonthlySeries {
  const months = Array.from({ length: 12 }, (_, i) => ({ month: i + 1, ret: returns[i + 1] ?? null }));
  return { key, months, partial: months.some((m) => m.ret === null) };
}

describe("withBenchmarkColumns", () => {
  it("converts fractions to percentage points on the matching month", () => {
    const merged = withBenchmarkColumns(rows, [series("gold", { 1: "0.052", 3: "-0.031" })], ["gold"]);

    expect(merged[0].bench_gold).toBeCloseTo(5.2);
    expect(merged[2].bench_gold).toBeCloseTo(-3.1);
  });

  it("keeps a gap month null so the line breaks instead of reading as 0%", () => {
    const merged = withBenchmarkColumns(rows, [series("sp500", { 1: "0.01" })], ["sp500"]);

    expect(merged[0].bench_sp500).toBeCloseTo(1);
    expect(merged[1].bench_sp500).toBeNull();
    expect(merged[1].benchExact_sp500).toBeNull();
  });

  it("clamps the plotted value but keeps the true return for the tooltip", () => {
    const merged = withBenchmarkColumns(rows, [series("bitcoin", { 5: "1.8", 6: "-1.4" })], ["bitcoin"]);

    expect(merged[4].bench_bitcoin).toBe(100);
    expect(merged[4].benchExact_bitcoin).toBeCloseTo(180);
    expect(merged[5].bench_bitcoin).toBe(-100);
    expect(merged[5].benchExact_bitcoin).toBeCloseTo(-140);
  });

  it("merges only the selected benchmarks", () => {
    const all = [series("gold", { 1: "0.01" }), series("sp500", { 1: "0.02" })];

    const merged = withBenchmarkColumns(rows, all, ["sp500"]);

    expect(merged[0].bench_sp500).toBeCloseTo(2);
    expect(merged[0]).not.toHaveProperty("bench_gold");
  });

  it("leaves the ROI rows untouched when nothing is selected", () => {
    const merged = withBenchmarkColumns(rows, [series("gold", { 1: "0.01" })], []);

    expect(merged).toEqual(rows);
  });

  it("preserves the existing ROI fields alongside the benchmark columns", () => {
    const roiRows = computeMonthlyRoiSeries([
      { month: 1, roi: "0.10", startCapital: "1000", netPnl: "100" },
      { month: 2, roi: "0.20", startCapital: "1100", netPnl: "220" },
    ]);

    const merged = withBenchmarkColumns(roiRows, [series("gold", { 1: "0.01" })], ["gold"]);

    expect(merged[0].roi).toBeCloseTo(10);
    expect(merged[0].displayRoi).toBeCloseTo(10);
    expect(merged[1].cumulativeRoi).toBeCloseTo(32); // 1.10 * 1.20 - 1
    expect(merged[0].bench_gold).toBeCloseTo(1);
  });
});

describe("benchmarkColors", () => {
  it("never assigns red or green, which are reserved for the sign of PnL", () => {
    const keys = ["bitcoin", "crypto_index", "msci_world", "sp500", "gold"];

    for (const dark of [false, true]) {
      const colors = benchmarkColors(keys, dark);
      for (const key of keys) {
        expect(colors[key]).not.toBe("#22c55e");
        expect(colors[key]).not.toBe("#ef4444");
      }
      // And distinct from one another, and from the two lines already on this chart.
      const used = [...keys.map((k) => colors[k]), "#0ea5e9", "#8b5cf6"];
      expect(new Set(used).size).toBe(used.length);
    }
  });

  it("gives an unregistered benchmark a stable fallback hue", () => {
    const colors = benchmarkColors(["gold", "future_index"], false);

    expect(colors.future_index).toBeTruthy();
    expect(colors.future_index).not.toBe(colors.gold);
    expect(benchmarkColors(["gold", "future_index"], false)).toEqual(colors);
  });
});
