import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { useBenchmarkDaily, useBenchmarks } from "@/api/benchmarks";
import { useCapitalSnapshots } from "@/api/capital";
import { fmtUsd, isoToDateInput } from "@/lib/format";
import { BenchmarkChips, type BenchmarkChipItem } from "./BenchmarkChips";
import { benchmarkColors, benchKey } from "./benchmarkOverlay";
import { benchmarkSeriesByKey, projectLumpSum } from "./capitalBenchmark";
import { MetricCard } from "./MetricCard";
import {
  ANCHOR_MARKER,
  EXCHANGE_OTHER_DARK,
  EXCHANGE_OTHER_LIGHT,
  EXCHANGE_SERIES_DARK,
  EXCHANGE_SERIES_LIGHT,
  useChartTheme,
} from "./chartTheme";
import type { DateRange } from "./useAnalyticsFilters";

/** Slots in the validated categorical palette; further exchanges fold into "Other". */
const MAX_SERIES = 5;
const OTHER_KEY = "__other__";
const ANCHOR_KEY = "__anchors__";

type ChartRow = Record<string, string | number | null> & { date: string };

/**
 * Stacked area of the stored daily capital snapshots, one series per exchange, with manual
 * adjustment days overlaid as markers so deposits/withdrawals aren't read as trading gains.
 * Follows the Exchange and Period filters; capital has no origen, so that filter never applies.
 */
export function CapitalEvolutionCard({
  profileId,
  range,
  exchange,
}: {
  profileId: string | null;
  range: DateRange;
  exchange: string;
}) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const dark = theme.dark;
  const palette = dark ? EXCHANGE_SERIES_DARK : EXCHANGE_SERIES_LIGHT;
  const otherColor = dark ? EXCHANGE_OTHER_DARK : EXCHANGE_OTHER_LIGHT;

  const from = range.from ? isoToDateInput(range.from.toISOString()) : undefined;
  const to = range.to ? isoToDateInput(range.to.toISOString()) : undefined;
  const { data } = useCapitalSnapshots(profileId, from, to);

  // Off by default: the chart is exactly as it was until a benchmark is switched on.
  const [selectedBenchmarks, setSelectedBenchmarks] = useState<string[]>([]);
  const { data: benchmarks = [] } = useBenchmarks();

  // The window the benchmark must cover is the one actually drawn — the stored snapshot days —
  // not the filter's range, which may reach back before any capital existed (or be open-ended).
  const days = data?.days ?? [];
  const windowFrom = days[0]?.date;
  const windowTo = days[days.length - 1]?.date;
  const { data: benchmarkDaily } = useBenchmarkDaily(windowFrom, windowTo, selectedBenchmarks);

  const benchmarkColor = useMemo(() => benchmarkColors(benchmarks.map((b) => b.key), dark), [benchmarks, dark]);
  const benchmarkName = (key: string) => t(`analytics.benchmark.${key}`, { defaultValue: key });

  const toggleBenchmark = (key: string) =>
    setSelectedBenchmarks((prev) => (prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]));

  // A benchmark that starts after the window opens cannot price the baseline day, so its line
  // would be one long gap. Say so on the chip instead of offering a toggle that draws nothing.
  const chips: BenchmarkChipItem[] = benchmarks.map((b) => ({
    key: b.key,
    label: benchmarkName(b.key),
    color: benchmarkColor[b.key],
    usable: b.hasData && !!windowFrom && !!b.availableFrom && b.availableFrom <= windowFrom,
    unavailableTitle: t(b.hasData ? "analytics.benchmarkNoDataForRange" : "analytics.benchmarkNoData"),
  }));

  // Hues follow the exchange in the FULL alphabetical list, so applying the Exchange filter
  // never repaints the surviving series.
  const { rows, series } = useMemo(() => {
    const all = data?.exchanges ?? [];
    const colorOf = (ex: string): { key: string; color: string; folded: boolean } => {
      const idx = all.indexOf(ex);
      if (idx >= 0 && idx < MAX_SERIES) return { key: ex, color: palette[idx], folded: false };
      return { key: OTHER_KEY, color: otherColor, folded: true };
    };
    const visible = exchange === "ALL" ? all : all.filter((ex) => ex === exchange);
    const series: { key: string; name: string; color: string }[] = [];
    for (const ex of visible) {
      const c = colorOf(ex);
      if (!series.some((s) => s.key === c.key)) {
        series.push({ key: c.key, name: c.folded ? t("analytics.capitalEvolutionOther") : ex, color: c.color });
      }
    }
    const rows: ChartRow[] = (data?.days ?? []).map((day) => {
      const row: ChartRow = { date: day.date, [ANCHOR_KEY]: null };
      let total = 0;
      let hasAnchor = false;
      for (const v of day.values) {
        if (exchange !== "ALL" && v.exchange !== exchange) continue;
        const { key } = colorOf(v.exchange);
        const amount = Number(v.amount);
        row[key] = ((row[key] as number | undefined) ?? 0) + amount;
        total += amount;
        if (v.manual) hasAnchor = true;
      }
      if (hasAnchor) row[ANCHOR_KEY] = total;
      return row;
    });
    return { rows, series };
  }, [data, exchange, palette, otherColor, t]);

  // Recomputed whenever the Exchange filter moves: the baselines are per exchange, so hiding one
  // changes what the projection started from.
  const chartRows = useMemo(() => {
    if (selectedBenchmarks.length === 0) return rows;
    const byKey = benchmarkSeriesByKey(benchmarkDaily);
    const isVisible = (ex: string) => exchange === "ALL" || ex === exchange;
    const projections = selectedBenchmarks.map(
      (key) => [benchKey(key), projectLumpSum(data?.days ?? [], isVisible, byKey.get(key) ?? new Map())] as const,
    );
    return rows.map((row) => {
      const merged: ChartRow = { ...row };
      for (const [key, values] of projections) merged[key] = values.get(row.date) ?? null;
      return merged;
    });
  }, [rows, data, exchange, selectedBenchmarks, benchmarkDaily]);

  return (
    <MetricCard title={t("analytics.capitalEvolution")} info={t("analytics.capitalEvolutionInfo")}>
      <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("analytics.capitalEvolutionSubtitle")}</p>
      {rows.length === 0 ? (
        <p className="py-12 text-center text-sm text-gray-500 dark:text-gray-400">
          {t("analytics.capitalEvolutionEmpty")}
        </p>
      ) : (
        <>
          <BenchmarkChips
            items={chips}
            selected={selectedBenchmarks}
            onToggle={toggleBenchmark}
            note={t("analytics.capitalBenchmarkNote")}
          />
          <div className="h-44 w-full md:h-80">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={chartRows} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
                <XAxis dataKey="date" stroke={theme.axisColor} fontSize={12} minTickGap={32} />
                <YAxis stroke={theme.axisColor} fontSize={12} width={72} />
                <Tooltip
                  contentStyle={theme.tooltipStyle}
                  cursor={theme.cursorStyle}
                  formatter={(value, name) => [fmtUsd(Number(value)), String(name)]}
                />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                {series.map((s) => (
                  <Area
                    key={s.key}
                    type="monotone"
                    stackId="capital"
                    dataKey={s.key}
                    name={s.name}
                    stroke={s.color}
                    strokeWidth={2}
                    fill={s.color}
                    fillOpacity={0.35}
                  />
                ))}
                {/* Anchor days: a non-stacked marker at the day's total, so manual adjustments are
                    visible as events rather than being mistaken for trading gains. */}
                <Line
                  dataKey={ANCHOR_KEY}
                  name={t("analytics.capitalEvolutionAnchors")}
                  stroke="none"
                  isAnimationActive={false}
                  dot={{ r: 4, fill: ANCHOR_MARKER, stroke: theme.surface, strokeWidth: 2 }}
                  legendType="circle"
                />
                {/* One aggregated line per benchmark, unstacked so it reads against the capital total
                    rather than adding to it. Gaps stay broken instead of bridging missing prices. */}
                {selectedBenchmarks.map((key) => (
                  <Line
                    key={key}
                    type="monotone"
                    dataKey={benchKey(key)}
                    name={benchmarkName(key)}
                    stroke={benchmarkColor[key] ?? theme.axisColor}
                    strokeDasharray="2 3"
                    strokeWidth={1.5}
                    dot={false}
                    connectNulls={false}
                    isAnimationActive={false}
                  />
                ))}
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </MetricCard>
  );
}
