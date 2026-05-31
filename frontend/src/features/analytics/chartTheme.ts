import type { CSSProperties } from "react";
import { useTheme } from "@/lib/theme";

export const GREEN = "#22c55e";
export const RED = "#ef4444";
export const NEUTRAL = "#9ca3af";
export const WINRATE_LINE = "#0ea5e9";
export const LONG_COLOR = GREEN;
export const SHORT_COLOR = RED;
export const FEE_COLOR = "#f59e0b";

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
