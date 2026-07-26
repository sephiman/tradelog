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
import { useMonthlyRoi } from "@/api/capital";

import type { ClosedPosition } from "@/api/analytics";
import { activityByDayOfMonth, pnlByDayOfMonth, pnlByMonth } from "./compute";
import { MetricCard } from "./MetricCard";
import { MonthNav, YearNav, type MonthNavState } from "./PeriodNav";
import { LONG_COLOR, SHORT_COLOR, useChartTheme, WINRATE_LINE } from "./chartTheme";
import { SignedBar } from "./chartShapes";
import { DASH } from "./display";


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
      <div className="h-44 w-full md:h-80">
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
      <div className="h-44 w-full md:h-72">
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
      <div className="h-44 w-full md:h-72">
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

export function MonthlyRoiCard({ profileId, exchange }: { profileId: string | null; exchange: string }) {
  const { t, i18n } = useTranslation();
  const theme = useChartTheme();
  const [calYear, setCalYear] = useState(new Date().getFullYear());
  const { data = [] } = useMonthlyRoi(profileId, calYear, exchange);

  const monthName = (m: number) => new Intl.DateTimeFormat(i18n.language, { month: "short" }).format(new Date(2020, m - 1, 1));

  const monthlyRoiData = useMemo(() => {
    const itemsByMonth = new Map(data.map((item) => [item.month, item]));
    return Array.from({ length: 12 }, (_, i) => {
      const month = i + 1;
      const item = itemsByMonth.get(month);
      const rawRoi = item?.roi != null ? Number(item.roi) * 100 : null;
      const displayRoi = rawRoi != null ? Math.min(100, Math.max(-100, rawRoi)) : null;
      return {
        month,
        roi: rawRoi,
        displayRoi,
        startCapital: item?.startCapital ?? null,
        netPnl: item?.netPnl ?? null,
      };
    });
  }, [data]);

  const fmtExactRoi = (roi: number | null) => {
    if (roi === null || !Number.isFinite(roi)) return DASH;
    const sign = roi > 0 ? "+" : "";
    return `${sign}${roi.toFixed(2)}%`;
  };

  return (
    <MetricCard title={t("analytics.monthlyRoi")} info={t("analytics.monthlyRoiInfo")} action={<YearNav year={calYear} onChange={setCalYear} />}>
      <div className="h-44 w-full md:h-72">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={monthlyRoiData} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="month" tickFormatter={monthName} stroke={theme.axisColor} fontSize={12} />
            <YAxis stroke={theme.axisColor} fontSize={12} width={64} tickFormatter={(v) => `${v}%`} />
            <Tooltip
              contentStyle={theme.tooltipStyle}
              labelFormatter={(m) => monthName(Number(m))}
              formatter={(_v, _n, entry) => {
                const rawRoi = (entry.payload as { roi: number | null }).roi;
                return [fmtExactRoi(rawRoi), t("analytics.stats.roi")];
              }}
            />
            <Bar dataKey="displayRoi" name={t("analytics.stats.roi")} shape={<SignedBar />} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}

