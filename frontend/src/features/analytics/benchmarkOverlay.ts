import type { BenchmarkMonthlySeries } from "@/api/benchmarks";
import type { ComputedMonthlyRoi } from "./compute";

/**
 * Benchmark comparison lines for the Monthly ROI chart.
 *
 * Colour rule (see chartTheme): red and green stay reserved for the sign of a PnL value, so no
 * benchmark may use them however iconic — the S&P 500's usual red would read as "loss" against the
 * bars it sits on. Each hue is also kept clear of the two lines already on this chart, the sky-blue
 * cumulative ROI (#0ea5e9) and the violet 3-month average (#8b5cf6). The crypto index's magenta
 * sits at hue ~333°, well outside the reserved red band. Light/dark variants follow the same
 * light-on-white / bright-on-gray-800 split as the per-exchange palette.
 */
const BENCHMARK_COLORS_LIGHT: Record<string, string> = {
  bitcoin: "#e8820c",
  crypto_index: "#db2777",
  msci_world: "#0d9488",
  sp500: "#1d4ed8",
  gold: "#a16207",
};

const BENCHMARK_COLORS_DARK: Record<string, string> = {
  bitcoin: "#f7931a",
  crypto_index: "#ec4899",
  msci_world: "#14b8a6",
  sp500: "#3b82f6",
  gold: "#d4af37",
};

/**
 * Fallback hues for a benchmark added later as a DB row, assigned in registry order so it still
 * gets a stable colour with no chart code change. Also red/green-free.
 */
const BENCHMARK_PALETTE_LIGHT = ["#7c3aed", "#0891b2", "#c2410c", "#4d7c0f"];
const BENCHMARK_PALETTE_DARK = ["#a78bfa", "#22d3ee", "#fb923c", "#a3e635"];

/** Colour per benchmark key: known keys get their assigned hue, unknown keys rotate the palette. */
export function benchmarkColors(keys: string[], dark: boolean): Record<string, string> {
  const known = dark ? BENCHMARK_COLORS_DARK : BENCHMARK_COLORS_LIGHT;
  const palette = dark ? BENCHMARK_PALETTE_DARK : BENCHMARK_PALETTE_LIGHT;
  const map: Record<string, string> = {};
  let fallbackIdx = 0;
  keys.forEach((k) => {
    map[k] = known[k] ?? palette[fallbackIdx++ % palette.length];
  });
  return map;
}

export const benchKey = (key: string) => `bench_${key}`;
export const benchExactKey = (key: string) => `benchExact_${key}`;

export type MonthlyRoiRow = ComputedMonthlyRoi & Record<string, number | string | null>;

/**
 * Merges the selected benchmarks' monthly returns onto the twelve ROI rows, by month.
 *
 * Returns arrive as fractions and become percentage points, matching the ROI bars they are compared
 * against. Each benchmark contributes two fields, mirroring how the bars already work: `bench_<key>`
 * is clamped to [-100, 100] so a single extreme crypto month cannot blow out the shared axis and
 * squash everything else, and `benchExact_<key>` keeps the true value for the tooltip.
 *
 * A month the backend reported as a gap stays null, so the line breaks rather than implying 0%.
 */
export function withBenchmarkColumns(
  rows: ComputedMonthlyRoi[],
  series: BenchmarkMonthlySeries[] | undefined,
  selected: string[]
): MonthlyRoiRow[] {
  const selectedSet = new Set(selected);
  const byKey = new Map(
    (series ?? [])
      .filter((s) => selectedSet.has(s.key))
      .map((s) => [s.key, new Map(s.months.map((m) => [m.month, m.ret]))])
  );
  if (byKey.size === 0) return rows as MonthlyRoiRow[];

  return rows.map((row) => {
    const merged: MonthlyRoiRow = { ...row };
    byKey.forEach((months, key) => {
      const raw = months.get(row.month);
      const fraction = raw == null || raw === "" ? null : Number(raw);
      const pct = fraction !== null && Number.isFinite(fraction) ? fraction * 100 : null;
      merged[benchKey(key)] = pct === null ? null : Math.min(100, Math.max(-100, pct));
      merged[benchExactKey(key)] = pct;
    });
    return merged;
  });
}
