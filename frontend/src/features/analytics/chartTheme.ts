import type { CSSProperties } from "react";
import { useTheme } from "@/lib/theme";

/**
 * Color rule for the dashboard: red and green are reserved EXCLUSIVELY for the sign of a PnL value
 * (loss / profit). Categories — direction, trader style, trade counts — never use red/green; they use
 * the violet accent borrowed from the reference design. Time series share one consistent blue.
 */

// PnL sign only.
export const GREEN = "#22c55e";
export const RED = "#ef4444";
export const NEUTRAL = "#9ca3af";

// Violet accent for non-PnL chart elements (donuts, category bars, etc.).
export const ACCENT = "#8b5cf6";
/** Two distinct violet shades for the Long / Short categories (never red/green). */
export const LONG_COLOR = "#7c3aed";
export const SHORT_COLOR = "#a78bfa";
/** Neutral violet scale for the trader-style donut (scalper → day → swing). */
export const VIOLET_SCALE = ["#7c3aed", "#a78bfa", "#c4b5fd"];

// Single blue accent shared by every time series and win-rate line.
export const LINE_ACCENT = "#0ea5e9";
export const WINRATE_LINE = LINE_ACCENT;

// Amber for fee amounts (a cost — not a category, not a PnL sign).
export const FEE_COLOR = "#f59e0b";

/**
 * Categorical palette for per-exchange series (capital evolution). Red/green stay reserved for
 * PnL sign, so this order skips those hue families. Both mode variants were validated with the
 * palette checker (lightness band, chroma, adjacent-pair CVD ≥ 8, normal-vision ≥ 15) against
 * white and gray-800 surfaces. Hues are assigned to exchanges in fixed alphabetical order and
 * never re-assigned when a filter hides series; a 6th+ exchange folds into "Other" (muted slate).
 */
export const EXCHANGE_SERIES_LIGHT = ["#2a78d6", "#eda100", "#4a3aa7", "#1baf7a", "#eb6834"];
export const EXCHANGE_SERIES_DARK = ["#3987e5", "#c98500", "#9085e9", "#199e70", "#d95926"];
export const EXCHANGE_OTHER_LIGHT = "#64748b";
export const EXCHANGE_OTHER_DARK = "#94a3b8";
/** Marker for manual adjustment (anchor) days overlaid on the capital evolution chart. */
export const ANCHOR_MARKER = "#f59e0b";

/** Hex fill for a signed value — the chart analog of `pnlTone`. */
export function barColor(n: number): string {
  if (n > 0) return GREEN;
  if (n < 0) return RED;
  return NEUTRAL;
}

export interface ChartTheme {
  axisColor: string;
  gridColor: string;
  tooltipStyle: CSSProperties;
}

/** Shared recharts colors derived from the active theme (extracted from the old dashboard chart). */
export function useChartTheme(): ChartTheme {
  const { resolvedTheme } = useTheme();
  const dark = resolvedTheme === "dark";
  const gridColor = dark ? "#374151" : "#e5e7eb";
  return {
    axisColor: dark ? "#9ca3af" : "#6b7280",
    gridColor,
    tooltipStyle: {
      backgroundColor: dark ? "#1f2937" : "#fff",
      border: `1px solid ${gridColor}`,
      borderRadius: 8,
      fontSize: 12,
    },
  };
}
