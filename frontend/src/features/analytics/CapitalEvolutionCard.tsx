import { useMemo } from "react";
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
import { useCapitalSnapshots } from "@/api/capital";
import { fmtUsd, isoToDateInput } from "@/lib/format";
import { useTheme } from "@/lib/theme";
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
  const { resolvedTheme } = useTheme();
  const dark = resolvedTheme === "dark";
  const palette = dark ? EXCHANGE_SERIES_DARK : EXCHANGE_SERIES_LIGHT;
  const otherColor = dark ? EXCHANGE_OTHER_DARK : EXCHANGE_OTHER_LIGHT;

  const from = range.from ? isoToDateInput(range.from.toISOString()) : undefined;
  const to = range.to ? isoToDateInput(range.to.toISOString()) : undefined;
  const { data } = useCapitalSnapshots(profileId, from, to);

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

  return (
    <MetricCard title={t("analytics.capitalEvolution")} info={t("analytics.capitalEvolutionInfo")}>
      <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("analytics.capitalEvolutionSubtitle")}</p>
      {rows.length === 0 ? (
        <p className="py-12 text-center text-sm text-gray-500 dark:text-gray-400">
          {t("analytics.capitalEvolutionEmpty")}
        </p>
      ) : (
        <div className="h-44 w-full md:h-80">
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={rows} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
              <XAxis dataKey="date" stroke={theme.axisColor} fontSize={12} minTickGap={32} />
              <YAxis stroke={theme.axisColor} fontSize={12} width={72} />
              <Tooltip
                contentStyle={theme.tooltipStyle}
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
                dot={{ r: 4, fill: ANCHOR_MARKER, stroke: dark ? "#1f2937" : "#ffffff", strokeWidth: 2 }}
                legendType="circle"
              />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      )}
    </MetricCard>
  );
}
