import { useMemo, useState } from "react";
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
import { fmtUsd } from "@/lib/format";
import type { ClosedPosition } from "@/api/analytics";
import { activityByDayOfMonth, pnlByDayOfMonth, pnlByMonth } from "./compute";
import { MetricCard } from "./MetricCard";
import { MonthNav, YearNav, type MonthNavState } from "./PeriodNav";
import { LONG_COLOR, SHORT_COLOR, useChartTheme, WINRATE_LINE } from "./chartTheme";
import { SignedBar } from "./chartShapes";

const pctAxis = (axisColor: string) => ({
  yAxisId: "rate" as const,
  orientation: "right" as const,
  domain: [0, 100] as [number, number],
  unit: "%",
  stroke: axisColor,
  fontSize: 12,
  width: 44,
});

export function ActivityCard({ rows, timeZone, nav }: { rows: ClosedPosition[]; timeZone: string; nav: MonthNavState }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const activity = useMemo(() => activityByDayOfMonth(rows, nav.year, nav.month, timeZone), [rows, nav.year, nav.month, timeZone]);

  return (
    <MetricCard title={t("analytics.activity")} info={t("analytics.activityInfo")} action={<MonthNav year={nav.year} month={nav.month} onChange={nav.set} />}>
      <div className="h-80 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={activity} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="day" stroke={theme.axisColor} fontSize={12} />
            <YAxis yAxisId="count" allowDecimals={false} stroke={theme.axisColor} fontSize={12} width={36} />
            <YAxis {...pctAxis(theme.axisColor)} />
            <Tooltip contentStyle={theme.tooltipStyle} />
            <Legend />
            <Bar yAxisId="count" dataKey="longs" name={t("analytics.longs")} fill={LONG_COLOR} />
            <Bar yAxisId="count" dataKey="shorts" name={t("analytics.shorts")} fill={SHORT_COLOR} />
            <Line yAxisId="rate" type="linear" dataKey="winRate" name={t("analytics.winRate")} stroke={WINRATE_LINE} dot={false} connectNulls />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}

export function PnlPerDayCard({ rows, timeZone, nav }: { rows: ClosedPosition[]; timeZone: string; nav: MonthNavState }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const dayPnl = useMemo(() => pnlByDayOfMonth(rows, nav.year, nav.month, timeZone), [rows, nav.year, nav.month, timeZone]);

  return (
    <MetricCard title={t("analytics.pnlPerDay")} info={t("analytics.pnlPerDayInfo")} action={<MonthNav year={nav.year} month={nav.month} onChange={nav.set} />}>
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={dayPnl} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="day" stroke={theme.axisColor} fontSize={12} />
            <YAxis stroke={theme.axisColor} fontSize={12} width={64} />
            <Tooltip contentStyle={theme.tooltipStyle} formatter={(v) => [fmtUsd(Number(v), { sign: true }), t("analytics.pnlPerDay")]} />
            <Bar dataKey="pnl" name={t("analytics.pnlPerDay")} shape={<SignedBar />} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}

export function MonthlySummaryCard({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t, i18n } = useTranslation();
  const theme = useChartTheme();
  const [calYear, setCalYear] = useState(new Date().getFullYear());
  const monthly = useMemo(() => pnlByMonth(rows, calYear, timeZone), [rows, calYear, timeZone]);
  const monthName = (m: number) => new Intl.DateTimeFormat(i18n.language, { month: "short" }).format(new Date(2020, m - 1, 1));

  return (
    <MetricCard title={t("analytics.monthlySummary")} info={t("analytics.monthlySummaryInfo")} action={<YearNav year={calYear} onChange={setCalYear} />}>
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={monthly} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="month" tickFormatter={monthName} stroke={theme.axisColor} fontSize={12} />
            <YAxis yAxisId="pnl" stroke={theme.axisColor} fontSize={12} width={64} />
            <YAxis {...pctAxis(theme.axisColor)} />
            <Tooltip contentStyle={theme.tooltipStyle} labelFormatter={(m) => monthName(Number(m))} />
            <Legend />
            <Bar yAxisId="pnl" dataKey="pnl" name={t("analytics.pnl")} shape={<SignedBar />} />
            <Line yAxisId="rate" type="linear" dataKey="winRate" name={t("analytics.winRate")} stroke={WINRATE_LINE} dot={false} connectNulls />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}
