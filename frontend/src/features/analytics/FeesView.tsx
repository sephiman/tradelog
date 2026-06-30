import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fmtDate, fmtUsd } from "@/lib/format";
import type { ClosedPosition } from "@/api/analytics";
import { cumulativeFees, feeRatioByDay, feesByDayOfMonth } from "./compute";
import { MetricCard } from "./MetricCard";
import { MonthNav } from "./PeriodNav";
import { FEE_COLOR, useChartTheme } from "./chartTheme";
import { SignedBar } from "./chartShapes";
import { fmtRatio } from "./display";

export function FeesView({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const now = new Date();
  const [{ year, month }, setYm] = useState({ year: now.getFullYear(), month: now.getMonth() + 1 });

  const fees = useMemo(() => feesByDayOfMonth(rows, year, month, timeZone), [rows, year, month, timeZone]);
  const cumFees = useMemo(() => cumulativeFees(rows), [rows]);
  const ratio = useMemo(() => feeRatioByDay(rows, year, month, timeZone), [rows, year, month, timeZone]);

  const monthNav = <MonthNav year={year} month={month} onChange={(y, m) => setYm({ year: y, month: m })} />;

  return (
    <div className="space-y-6">
      <MetricCard
        title={t("analytics.fees")}
        info={t("analytics.feesInfo")}
        action={
          <div className="flex items-center gap-3">
            <span className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.monthTotal")}: {fmtUsd(fees.total)}</span>
            {monthNav}
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

      <MetricCard title={t("analytics.cumulativeFees")} info={t("analytics.cumulativeFeesInfo")}>
        <div className="h-72 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={cumFees} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
              <XAxis dataKey="closedAt" tickFormatter={fmtDate} stroke={theme.axisColor} fontSize={12} minTickGap={32} />
              <YAxis stroke={theme.axisColor} fontSize={12} width={72} />
              <Tooltip contentStyle={theme.tooltipStyle} labelFormatter={(v) => fmtDate(String(v))} formatter={(v) => [fmtUsd(Number(v)), t("analytics.cumulativeFees")]} />
              <Line type="monotone" dataKey="cumulative" name={t("analytics.cumulativeFees")} stroke={FEE_COLOR} dot={false} strokeWidth={2} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </MetricCard>

      <MetricCard
        title={t("analytics.feeRatio")}
        info={t("analytics.feeRatioInfo")}
        action={
          <div className="flex items-center gap-3">
            <span className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.monthValue")}: {fmtRatio(ratio.monthRatio)}</span>
            {monthNav}
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
    </div>
  );
}
