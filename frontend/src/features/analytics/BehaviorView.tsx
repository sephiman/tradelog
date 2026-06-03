import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ClosedPosition } from "@/api/analytics";
import { directionBreakdown, traderStyle, winRateByHour, winRateByWeekday, type DirStat } from "./compute";
import { MetricCard } from "./MetricCard";
import { useChartTheme, WINRATE_LINE } from "./chartTheme";
import { SignedBar } from "./chartShapes";
import { DASH, fmtDuration, fmtPctFraction } from "./display";

export function BehaviorView({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t, i18n } = useTranslation();
  const theme = useChartTheme();

  const byHour = useMemo(() => winRateByHour(rows, timeZone), [rows, timeZone]);
  const byWeekday = useMemo(() => winRateByWeekday(rows, timeZone), [rows, timeZone]);
  const dir = useMemo(() => directionBreakdown(rows), [rows]);
  const style = useMemo(() => traderStyle(rows), [rows]);

  const weekdayName = (i: number) => new Intl.DateTimeFormat(i18n.language, { weekday: "short" }).format(new Date(2024, 0, 1 + i));
  const pctAxis = { yAxisId: "rate", orientation: "right" as const, domain: [0, 100], unit: "%", stroke: theme.axisColor, fontSize: 12, width: 44 };

  const styleData = [
    { key: "scalper", label: t("analytics.style.scalper"), ...style.scalper },
    { key: "day", label: t("analytics.style.day"), ...style.day },
    { key: "swing", label: t("analytics.style.swing"), ...style.swing },
  ];

  return (
    <div className="space-y-6">
      <MetricCard title={t("analytics.winRateByHour")} info={t("analytics.winRateByHourInfo")}>
        <RateChart data={byHour} xKey="key" theme={theme} pctAxis={pctAxis} countLabel={t("analytics.trades")} rateLabel={t("analytics.winRate")} />
      </MetricCard>

      <MetricCard title={t("analytics.winRateByWeekday")} info={t("analytics.winRateByWeekdayInfo")}>
        <RateChart data={byWeekday} xKey="key" tickFormatter={weekdayName} theme={theme} pctAxis={pctAxis} countLabel={t("analytics.trades")} rateLabel={t("analytics.winRate")} />
      </MetricCard>

      <MetricCard title={t("analytics.direction")} info={t("analytics.directionInfo")}>
        <table className="hidden w-full text-sm md:table">
          <thead>
            <tr className="text-left text-gray-500 dark:text-gray-400">
              <th className="py-1 font-medium">{t("analytics.direction")}</th>
              <th className="py-1 text-right font-medium">{t("analytics.trades")}</th>
              <th className="py-1 text-right font-medium">{t("analytics.winRate")}</th>
              <th className="py-1 text-right font-medium">{t("analytics.totalPnl")}</th>
              <th className="py-1 text-right font-medium">{t("analytics.stats.expectancy")}</th>
            </tr>
          </thead>
          <tbody>
            <DirRow label={t("analytics.longs")} stat={dir.long} />
            <DirRow label={t("analytics.shorts")} stat={dir.short} />
          </tbody>
        </table>
        <div className="space-y-3 md:hidden">
          <DirCard label={t("analytics.longs")} stat={dir.long} t={t} />
          <DirCard label={t("analytics.shorts")} stat={dir.short} t={t} />
        </div>
      </MetricCard>

      <MetricCard title={t("analytics.traderStyle")} info={t("analytics.traderStyleInfo")}>
        <div className="h-64 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={styleData} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
              <XAxis dataKey="label" stroke={theme.axisColor} fontSize={12} />
              <YAxis allowDecimals={false} stroke={theme.axisColor} fontSize={12} width={36} />
              <Tooltip contentStyle={theme.tooltipStyle} />
              <Bar dataKey="count" name={t("analytics.trades")} shape={<SignedBar />} />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
          <Foot label={t("analytics.style.avgDuration")} value={fmtDuration(style.avgDurationMs)} />
          <Foot label={t("analytics.style.longest")} value={fmtDuration(style.longestMs)} />
          <Foot label={t("analytics.style.shortest")} value={fmtDuration(style.shortestMs)} />
          <Foot label={t("analytics.style.predominant")} value={style.predominant ? t(`analytics.style.${style.predominant}`) : DASH} />
        </dl>
      </MetricCard>
    </div>
  );
}

function Foot({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-border p-2 dark:border-gray-700">
      <dt className="text-xs text-gray-500 dark:text-gray-400">{label}</dt>
      <dd className="mt-0.5 font-medium">{value}</dd>
    </div>
  );
}

function DirCard({ label, stat, t }: { label: string; stat: DirStat; t: (k: string) => string }) {
  return (
    <div className="rounded-md border border-border p-3 dark:border-gray-700">
      <div className="font-medium">{label}</div>
      <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-2 text-sm">
        <DirCell label={t("analytics.trades")} value={String(stat.count)} />
        <DirCell label={t("analytics.winRate")} value={fmtPctFraction(stat.winRate)} />
        <DirCell label={t("analytics.totalPnl")} value={fmtUsd(stat.totalPnl.toString(), { sign: true })} tone={pnlTone(stat.totalPnl.toString())} />
        <DirCell
          label={t("analytics.stats.expectancy")}
          value={stat.expectancy ? fmtUsd(stat.expectancy.toString(), { sign: true }) : DASH}
          tone={stat.expectancy ? pnlTone(stat.expectancy.toString()) : ""}
        />
      </dl>
    </div>
  );
}

function DirCell({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className="text-gray-500 dark:text-gray-400">{label}</dt>
      <dd className={cn("font-medium tabular-nums", tone)}>{value}</dd>
    </div>
  );
}

function DirRow({ label, stat }: { label: string; stat: DirStat }) {
  return (
    <tr className="border-t border-border dark:border-gray-700">
      <td className="py-1.5">{label}</td>
      <td className="py-1.5 text-right tabular-nums">{stat.count}</td>
      <td className="py-1.5 text-right tabular-nums">{fmtPctFraction(stat.winRate)}</td>
      <td className={cn("py-1.5 text-right tabular-nums", pnlTone(stat.totalPnl.toString()))}>{fmtUsd(stat.totalPnl.toString(), { sign: true })}</td>
      <td className={cn("py-1.5 text-right tabular-nums", stat.expectancy ? pnlTone(stat.expectancy.toString()) : "")}>
        {stat.expectancy ? fmtUsd(stat.expectancy.toString(), { sign: true }) : DASH}
      </td>
    </tr>
  );
}

interface RateDatum {
  key: number;
  winRate: number | null;
  count: number;
}

function RateChart({
  data,
  xKey,
  tickFormatter,
  theme,
  pctAxis,
  countLabel,
  rateLabel,
}: {
  data: RateDatum[];
  xKey: string;
  tickFormatter?: (v: number) => string;
  theme: ReturnType<typeof useChartTheme>;
  pctAxis: object;
  countLabel: string;
  rateLabel: string;
}) {
  return (
    <div className="h-72 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
          <XAxis dataKey={xKey} tickFormatter={tickFormatter} stroke={theme.axisColor} fontSize={12} />
          <YAxis yAxisId="count" allowDecimals={false} stroke={theme.axisColor} fontSize={12} width={36} />
          <YAxis {...pctAxis} />
          <Tooltip contentStyle={theme.tooltipStyle} labelFormatter={(v) => (tickFormatter ? tickFormatter(Number(v)) : String(v))} />
          <Legend />
          <Bar yAxisId="count" dataKey="count" name={countLabel} fill="#64748b" />
          <Line yAxisId="rate" type="monotone" dataKey="winRate" name={rateLabel} stroke={WINRATE_LINE} dot={false} connectNulls />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
