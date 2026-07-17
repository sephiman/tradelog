import { useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fmtDate, fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ClosedPosition } from "@/api/analytics";
import { useRoi } from "@/api/capital";
import { computeStats, equityCurve } from "./compute";
import { MetricCard } from "./MetricCard";
import { InfoTooltip } from "./InfoTooltip";
import { useChartTheme, WINRATE_LINE } from "./chartTheme";
import { DASH, decStr, fmtPctFraction } from "./display";
import type { DateRange } from "./useAnalyticsFilters";

function Stat({ label, info, value, tone }: { label: string; info: string; value: ReactNode; tone?: string }) {
  return (
    <div className="rounded-md border border-border p-3 dark:border-gray-700">
      <div className="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
        <span>{label}</span>
        <InfoTooltip text={info} />
      </div>
      <div className={cn("mt-1 text-lg font-semibold tabular-nums", tone)}>{value}</div>
    </div>
  );
}

const money = (s: string | null, sign = false) => (s === null ? DASH : fmtUsd(s, { sign }));

/** Signed percent from a fraction string ("0.052" → "+5.20%"); blank when unavailable. */
const fmtRoi = (roi: string | null | undefined): string => {
  if (roi === null || roi === undefined) return DASH;
  const n = Number(roi) * 100;
  if (!Number.isFinite(n)) return DASH;
  return `${n > 0 ? "+" : ""}${n.toFixed(2)}%`;
};

export function StatisticsCard({
  rows,
  profileId,
  range,
  exchange,
}: {
  rows: ClosedPosition[];
  profileId: string | null;
  /** Period filter bounds — ROI's denominator is the capital at the range's first day. */
  range: DateRange;
  exchange: string;
}) {
  const { t } = useTranslation();
  const stats = useMemo(() => computeStats(rows), [rows]);
  const avgRR = stats.avgRR ? stats.avgRR.toFixed(2) : DASH;
  // ROI follows Period and Exchange but deliberately NOT Origen: capital isn't tagged by origen.
  const { data: roi } = useRoi(
    profileId,
    range.from?.toISOString(),
    range.to?.toISOString(),
    exchange,
  );

  return (
    <MetricCard title={t("analytics.stats.title")} info={t("analytics.stats.info")}>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-5">
        <Stat label={t("analytics.stats.totalPnl")} info={t("analytics.stats.totalPnlInfo")} value={money(decStr(stats.totalPnl), true)} tone={pnlTone(stats.totalPnl.toString())} />
        <Stat label={t("analytics.stats.roi")} info={t("analytics.stats.roiInfo")} value={fmtRoi(roi?.roi)} tone={roi?.roi != null ? pnlTone(roi.roi) : undefined} />
        <Stat label={t("analytics.stats.volume")} info={t("analytics.stats.volumeInfo")} value={money(decStr(stats.volume))} />
        <Stat label={t("analytics.stats.winRate")} info={t("analytics.stats.winRateInfo")} value={fmtPctFraction(stats.winRate)} />
        <Stat label={t("analytics.stats.avgWin")} info={t("analytics.stats.avgWinInfo")} value={money(decStr(stats.avgWin))} tone={stats.avgWin ? pnlTone(stats.avgWin.toString()) : undefined} />
        <Stat label={t("analytics.stats.avgLoss")} info={t("analytics.stats.avgLossInfo")} value={money(decStr(stats.avgLoss))} tone={stats.avgLoss ? pnlTone(stats.avgLoss.toString()) : undefined} />
        <Stat label={t("analytics.stats.expectancy")} info={t("analytics.stats.expectancyInfo")} value={money(decStr(stats.expectancy), true)} tone={stats.expectancy ? pnlTone(stats.expectancy.toString()) : undefined} />
        <Stat label={t("analytics.stats.avgRR")} info={t("analytics.stats.avgRRInfo")} value={avgRR} />
        <Stat label={t("analytics.stats.largestWin")} info={t("analytics.stats.largestWinInfo")} value={money(decStr(stats.largestWin))} tone={stats.largestWin ? pnlTone(stats.largestWin.toString()) : undefined} />
        <Stat label={t("analytics.stats.largestLoss")} info={t("analytics.stats.largestLossInfo")} value={money(decStr(stats.largestLoss))} tone={stats.largestLoss ? pnlTone(stats.largestLoss.toString()) : undefined} />
      </div>
    </MetricCard>
  );
}

export function CumulativeProfitCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const equity = useMemo(() => equityCurve(rows), [rows]);

  return (
    <MetricCard title={t("analytics.cumulativeProfit")} info={t("analytics.cumulativeProfitInfo")}>
      <div className="h-44 w-full md:h-80">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={equity} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="closedAt" tickFormatter={fmtDate} stroke={theme.axisColor} fontSize={12} minTickGap={32} />
            <YAxis stroke={theme.axisColor} fontSize={12} width={72} />
            <Tooltip
              contentStyle={theme.tooltipStyle}
              labelFormatter={(v) => fmtDate(String(v))}
              formatter={(v) => [fmtUsd(Number(v), { sign: true }), t("analytics.cumulativeProfit")]}
            />
            <Line type="monotone" dataKey="cumulative" name={t("analytics.cumulativeProfit")} stroke={WINRATE_LINE} dot={false} strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}
