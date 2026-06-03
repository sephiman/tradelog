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
import { MonthNav, YearNav } from "./PeriodNav";
import { LONG_COLOR, SHORT_COLOR, useChartTheme, WINRATE_LINE } from "./chartTheme";
import { SignedBar } from "./chartShapes";

export function PerformanceView({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t, i18n } = useTranslation();
  const theme = useChartTheme();
  const now = new Date();
  const [{ year, month }, setYm] = useState({ year: now.getFullYear(), month: now.getMonth() + 1 });
  const [calYear, setCalYear] = useState(now.getFullYear());

  const activity = useMemo(() => activityByDayOfMonth(rows, year, month, timeZone), [rows, year, month, timeZone]);
  const dayPnl = useMemo(() => pnlByDayOfMonth(rows, year, month, timeZone), [rows, year, month, timeZone]);
  const monthly = useMemo(() => pnlByMonth(rows, calYear, timeZone), [rows, calYear, timeZone]);
  const monthName = (m: number) => new Intl.DateTimeFormat(i18n.language, { month: "short" }).format(new Date(2020, m - 1, 1));

  const pctAxis = { yAxisId: "rate", orientation: "right" as const, domain: [0, 100], unit: "%", stroke: theme.axisColor, fontSize: 12, width: 44 };

  return (
    <div className="space-y-6">
      <MetricCard title={t("analytics.activity")} info={t("analytics.activityInfo")} action={<MonthNav year={year} month={month} onChange={(y, m) => setYm({ year: y, month: m })} />}>
        <div className="h-80 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={activity} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
              <XAxis dataKey="day" stroke={theme.axisColor} fontSize={12} />
              <YAxis yAxisId="count" allowDecimals={false} stroke={theme.axisColor} fontSize={12} width={36} />
              <YAxis {...pctAxis} />
              <Tooltip contentStyle={theme.tooltipStyle} />
              <Legend />
              <Bar yAxisId="count" dataKey="longs" name={t("analytics.longs")} fill={LONG_COLOR} />
              <Bar yAxisId="count" dataKey="shorts" name={t("analytics.shorts")} fill={SHORT_COLOR} />
              <Line yAxisId="rate" type="monotone" dataKey="winRate" name={t("analytics.winRate")} stroke={WINRATE_LINE} dot={false} connectNulls />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      </MetricCard>

      <MetricCard title={t("analytics.pnlPerDay")} info={t("analytics.pnlPerDayInfo")} action={<MonthNav year={year} month={month} onChange={(y, m) => setYm({ year: y, month: m })} />}>
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

      <MetricCard title={t("analytics.monthlySummary")} info={t("analytics.monthlySummaryInfo")} action={<YearNav year={calYear} onChange={setCalYear} />}>
        <div className="h-72 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={monthly} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
              <XAxis dataKey="month" tickFormatter={monthName} stroke={theme.axisColor} fontSize={12} />
              <YAxis yAxisId="pnl" stroke={theme.axisColor} fontSize={12} width={64} />
              <YAxis {...pctAxis} />
              <Tooltip contentStyle={theme.tooltipStyle} labelFormatter={(m) => monthName(Number(m))} />
              <Legend />
              <Bar yAxisId="pnl" dataKey="pnl" name={t("analytics.pnl")} shape={<SignedBar />} />
              <Line yAxisId="rate" type="monotone" dataKey="winRate" name={t("analytics.winRate")} stroke={WINRATE_LINE} dot={false} connectNulls />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      </MetricCard>
    </div>
  );
}
