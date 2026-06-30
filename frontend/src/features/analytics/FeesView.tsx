import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fmtDate, fmtUsd } from "@/lib/format";
import type { ClosedPosition } from "@/api/analytics";
import { cumulativeFees, feeRatioByDay, feesByDayOfMonth } from "./compute";
import { MetricCard } from "./MetricCard";
import { MonthNav, type MonthNavState } from "./PeriodNav";
import { FEE_COLOR, LINE_ACCENT, useChartTheme } from "./chartTheme";
import { SignedBar } from "./chartShapes";
import { fmtRatio } from "./display";

export function FeesCard({ rows, timeZone, nav }: { rows: ClosedPosition[]; timeZone: string; nav: MonthNavState }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const fees = useMemo(() => feesByDayOfMonth(rows, nav.year, nav.month, timeZone), [rows, nav.year, nav.month, timeZone]);

  return (
    <MetricCard
      title={t("analytics.fees")}
      info={t("analytics.feesInfo")}
      action={
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-500 dark:text-gray-400">
            {t("analytics.monthTotal")}: {fmtUsd(fees.total)}
          </span>
          <MonthNav year={nav.year} month={nav.month} onChange={nav.set} />
        </div>
      }
    >
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={fees.days} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="day" stroke={theme.axisColor} fontSize={12} />
            <YAxis stroke={theme.axisColor} fontSize={12} width={64} />
            <Tooltip contentStyle={theme.tooltipStyle} formatter={(v) => [fmtUsd(Number(v)), t("analytics.fees")]} />
            <Bar dataKey="fee" name={t("analytics.fees")} fill={FEE_COLOR} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}

export function CumulativeFeesCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const cumFees = useMemo(() => cumulativeFees(rows), [rows]);

  return (
    <MetricCard title={t("analytics.cumulativeFees")} info={t("analytics.cumulativeFeesInfo")}>
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={cumFees} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="closedAt" tickFormatter={fmtDate} stroke={theme.axisColor} fontSize={12} minTickGap={32} />
            <YAxis stroke={theme.axisColor} fontSize={12} width={72} />
            <Tooltip contentStyle={theme.tooltipStyle} labelFormatter={(v) => fmtDate(String(v))} formatter={(v) => [fmtUsd(Number(v)), t("analytics.cumulativeFees")]} />
            <Line type="monotone" dataKey="cumulative" name={t("analytics.cumulativeFees")} stroke={LINE_ACCENT} dot={false} strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}

export function FeeRatioCard({ rows, timeZone, nav }: { rows: ClosedPosition[]; timeZone: string; nav: MonthNavState }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const ratio = useMemo(() => feeRatioByDay(rows, nav.year, nav.month, timeZone), [rows, nav.year, nav.month, timeZone]);

  return (
    <MetricCard
      title={t("analytics.feeRatio")}
      info={t("analytics.feeRatioInfo")}
      action={
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-500 dark:text-gray-400">
            {t("analytics.monthValue")}: {fmtRatio(ratio.monthRatio)}
          </span>
          <MonthNav year={nav.year} month={nav.month} onChange={nav.set} />
        </div>
      }
    >
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={ratio.days} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
            <XAxis dataKey="day" stroke={theme.axisColor} fontSize={12} />
            <YAxis stroke={theme.axisColor} fontSize={12} width={56} tickFormatter={(v: number) => fmtRatio(v, 1)} />
            <Tooltip contentStyle={theme.tooltipStyle} formatter={(v) => [fmtRatio(Number(v)), t("analytics.feeRatio")]} />
            <Bar dataKey="ratio" name={t("analytics.feeRatio")} shape={<SignedBar />} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </MetricCard>
  );
}
