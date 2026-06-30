import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { getDaysInMonth, startOfMonth } from "date-fns";
import { fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ClosedPosition } from "@/api/analytics";
import { calendar, recoveryRate, streaks, type StreakStat } from "./compute";
import { MetricCard } from "./MetricCard";
import { MonthNav } from "./PeriodNav";
import { DASH, fmtPctFraction } from "./display";

export function WinningStreaksCard({ rows, className }: { rows: ClosedPosition[]; className?: string }) {
  const { t } = useTranslation();
  const s = useMemo(() => streaks(rows), [rows]);
  return (
    <MetricCard title={t("analytics.winningStreaks")} info={t("analytics.winningStreaksInfo")} className={className}>
      <StreakBody stat={s.winning} t={t} />
    </MetricCard>
  );
}

export function LosingStreaksCard({ rows, className }: { rows: ClosedPosition[]; className?: string }) {
  const { t } = useTranslation();
  const s = useMemo(() => streaks(rows), [rows]);
  return (
    <MetricCard title={t("analytics.losingStreaks")} info={t("analytics.losingStreaksInfo")} className={className}>
      <StreakBody stat={s.losing} t={t} />
    </MetricCard>
  );
}

export function RecoveryCard({ rows, className }: { rows: ClosedPosition[]; className?: string }) {
  const { t } = useTranslation();
  const rec = useMemo(() => recoveryRate(rows), [rows]);
  return (
    <MetricCard title={t("analytics.recovery")} info={t("analytics.recoveryInfo")} className={className}>
      <div className="text-3xl font-bold tabular-nums">{fmtPctFraction(rec.rate)}</div>
      <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("analytics.recoverySample", { count: rec.sample })}</p>
    </MetricCard>
  );
}

export function CalendarCard({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t, i18n } = useTranslation();
  const now = new Date();
  const [{ year, month }, setYm] = useState({ year: now.getFullYear(), month: now.getMonth() + 1 });
  const cal = useMemo(() => calendar(rows, year, month, timeZone), [rows, year, month, timeZone]);
  return (
    <MetricCard title={t("analytics.calendar")} info={t("analytics.calendarInfo")} action={<MonthNav year={year} month={month} onChange={(y, m) => setYm({ year: y, month: m })} />}>
      <Calendar year={year} month={month} pnlByDay={cal} lang={i18n.language} />
    </MetricCard>
  );
}

function StreakBody({ stat, t }: { stat: StreakStat; t: (k: string) => string }) {
  return (
    <dl className="space-y-2 text-sm">
      <div className="flex justify-between">
        <dt className="text-gray-500 dark:text-gray-400">{t("analytics.avgStreak")}</dt>
        <dd className="font-medium tabular-nums">{stat.avgLength === null ? DASH : stat.avgLength.toFixed(1)}</dd>
      </div>
      <div className="flex justify-between">
        <dt className="text-gray-500 dark:text-gray-400">{t("analytics.longestStreak")}</dt>
        <dd className="font-medium tabular-nums">{stat.longestLength}</dd>
      </div>
      <div className="flex justify-between">
        <dt className="text-gray-500 dark:text-gray-400">{t("analytics.longestStreakPnl")}</dt>
        <dd className={cn("font-medium tabular-nums", pnlTone(String(stat.longestNetPnl)))}>{fmtUsd(stat.longestNetPnl, { sign: true })}</dd>
      </div>
    </dl>
  );
}

function Calendar({ year, month, pnlByDay, lang }: { year: number; month: number; pnlByDay: Record<number, number>; lang: string }) {
  const first = startOfMonth(new Date(year, month - 1, 1));
  const days = getDaysInMonth(first);
  const lead = (first.getDay() + 6) % 7; // Monday-first offset
  const weekdayName = (i: number) => new Intl.DateTimeFormat(lang, { weekday: "short" }).format(new Date(2024, 0, 1 + i));

  const cells: (number | null)[] = [...Array(lead).fill(null), ...Array.from({ length: days }, (_, i) => i + 1)];
  while (cells.length % 7 !== 0) cells.push(null);

  return (
    <div>
      <div className="mb-1 grid grid-cols-7 gap-1 text-center text-xs text-gray-500 dark:text-gray-400">
        {Array.from({ length: 7 }, (_, i) => (
          <div key={i}>{weekdayName(i)}</div>
        ))}
      </div>
      <div className="grid grid-cols-7 gap-1">
        {cells.map((day, idx) => {
          if (day === null) return <div key={idx} />;
          const pnl = pnlByDay[day];
          const has = pnl !== undefined;
          const tone = !has ? "bg-gray-50 dark:bg-gray-800" : pnl > 0 ? "bg-green-100 dark:bg-green-900/40" : pnl < 0 ? "bg-red-100 dark:bg-red-900/40" : "bg-gray-100 dark:bg-gray-700";
          return (
            <div key={idx} className={cn("min-h-14 rounded-md border border-border p-1 dark:border-gray-700", tone)}>
              <div className="text-xs text-gray-500 dark:text-gray-400">{day}</div>
              {has && <div className={cn("mt-1 text-xs font-semibold tabular-nums", pnlTone(String(pnl)))}>{fmtUsd(pnl, { sign: true })}</div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}
