import { useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ClosedPosition } from "@/api/analytics";
import { directionBreakdown, traderStyle, winRateByHour, winRateByWeekday } from "./compute";
import { MetricCard } from "./MetricCard";
import { ACCENT, LONG_COLOR, SHORT_COLOR, useChartTheme, VIOLET_SCALE, WINRATE_LINE } from "./chartTheme";
import { Donut } from "./chartShapes";
import { DASH, fmtDuration, fmtPctFraction, fmtPctValue } from "./display";

export function WinRateByHourCard({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const byHour = useMemo(() => winRateByHour(rows, timeZone), [rows, timeZone]);
  return (
    <MetricCard title={t("analytics.winRateByHour")} info={t("analytics.winRateByHourInfo")}>
      <RateChart data={byHour} xKey="key" theme={theme} countLabel={t("analytics.trades")} rateLabel={t("analytics.winRate")} />
    </MetricCard>
  );
}

export function WinRateByWeekdayCard({ rows, timeZone }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t, i18n } = useTranslation();
  const theme = useChartTheme();
  const byWeekday = useMemo(() => winRateByWeekday(rows, timeZone), [rows, timeZone]);
  const weekdayName = (i: number) => new Intl.DateTimeFormat(i18n.language, { weekday: "short" }).format(new Date(2024, 0, 1 + i));
  return (
    <MetricCard title={t("analytics.winRateByWeekday")} info={t("analytics.winRateByWeekdayInfo")}>
      <RateChart data={byWeekday} xKey="key" tickFormatter={weekdayName} theme={theme} countLabel={t("analytics.trades")} rateLabel={t("analytics.winRate")} />
    </MetricCard>
  );
}

export function TradeDirectionCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const dir = useMemo(() => directionBreakdown(rows), [rows]);

  const donut = [
    { name: t("analytics.longs"), value: dir.long.count, fill: LONG_COLOR },
    { name: t("analytics.shorts"), value: dir.short.count, fill: SHORT_COLOR },
  ];

  return (
    <MetricCard title={t("analytics.direction")} info={t("analytics.directionInfo")}>
      <div className="@container">
        <div className="grid grid-cols-1 items-center gap-6 @sm:grid-cols-3">
          <StatBlock
            label={t("analytics.longs")}
            color={LONG_COLOR}
            stats={[
              { label: t("analytics.trades"), value: dir.long.count },
              { label: t("analytics.winRate"), value: fmtPctFraction(dir.long.winRate) },
            ]}
          />
          <div>
            <div className="mx-auto h-40 w-full">
              <Donut data={donut} tooltipStyle={theme.tooltipStyle} format={(v) => `${v} ${t("analytics.trades").toLowerCase()}`} />
            </div>
            <DonutLegend items={[{ label: t("analytics.longs"), color: LONG_COLOR }, { label: t("analytics.shorts"), color: SHORT_COLOR }]} />
          </div>
          <StatBlock
            label={t("analytics.shorts")}
            color={SHORT_COLOR}
            stats={[
              { label: t("analytics.trades"), value: dir.short.count },
              { label: t("analytics.winRate"), value: fmtPctFraction(dir.short.winRate) },
            ]}
          />
        </div>

        <hr className="my-4 border-border dark:border-gray-700" />

        <div className="grid grid-cols-2 gap-6">
          <FooterCol
            heading={t("analytics.longs")}
            headingColor={LONG_COLOR}
            items={[
              { label: t("analytics.totalPnl"), value: fmtUsd(dir.long.totalPnl.toString(), { sign: true }), tone: pnlTone(dir.long.totalPnl.toString()) },
              {
                label: t("analytics.stats.expectancy"),
                value: dir.long.expectancy ? fmtUsd(dir.long.expectancy.toString(), { sign: true }) : DASH,
                tone: dir.long.expectancy ? pnlTone(dir.long.expectancy.toString()) : undefined,
              },
            ]}
          />
          <FooterCol
            heading={t("analytics.shorts")}
            headingColor={SHORT_COLOR}
            items={[
              { label: t("analytics.totalPnl"), value: fmtUsd(dir.short.totalPnl.toString(), { sign: true }), tone: pnlTone(dir.short.totalPnl.toString()) },
              {
                label: t("analytics.stats.expectancy"),
                value: dir.short.expectancy ? fmtUsd(dir.short.expectancy.toString(), { sign: true }) : DASH,
                tone: dir.short.expectancy ? pnlTone(dir.short.expectancy.toString()) : undefined,
              },
            ]}
          />
        </div>
      </div>
    </MetricCard>
  );
}

export function TraderStyleCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const theme = useChartTheme();
  const style = useMemo(() => traderStyle(rows), [rows]);

  const total = style.scalper.count + style.day.count + style.swing.count;
  const weight = (count: number) => fmtPctValue(total > 0 ? (count / total) * 100 : null);

  const donut = [
    { name: t("analytics.style.scalper"), value: style.scalper.count, fill: VIOLET_SCALE[0] },
    { name: t("analytics.style.day"), value: style.day.count, fill: VIOLET_SCALE[1] },
    { name: t("analytics.style.swing"), value: style.swing.count, fill: VIOLET_SCALE[2] },
  ];

  return (
    <MetricCard title={t("analytics.traderStyle")} info={t("analytics.traderStyleInfo")}>
      <div className="@container">
        <div className="grid grid-cols-1 items-center gap-6 @sm:grid-cols-3">
          <StatBlock
            label={t("analytics.style.scalper")}
            color={VIOLET_SCALE[0]}
            stats={[
              { label: t("analytics.trades"), value: style.scalper.count },
              { label: t("analytics.style.weight"), value: weight(style.scalper.count) },
            ]}
          />
          <div>
            <div className="mx-auto h-40 w-full">
              <Donut data={donut} tooltipStyle={theme.tooltipStyle} format={(v) => `${v} ${t("analytics.trades").toLowerCase()}`} />
            </div>
            <p className="mt-2 text-center text-sm font-semibold" style={{ color: VIOLET_SCALE[1] }}>
              {t("analytics.style.day")}: {style.day.count} {t("analytics.trades").toLowerCase()} · {weight(style.day.count)}
            </p>
            <DonutLegend
              items={[
                { label: t("analytics.style.scalper"), color: VIOLET_SCALE[0] },
                { label: t("analytics.style.day"), color: VIOLET_SCALE[1] },
                { label: t("analytics.style.swing"), color: VIOLET_SCALE[2] },
              ]}
            />
          </div>
          <StatBlock
            label={t("analytics.style.swing")}
            color={VIOLET_SCALE[2]}
            stats={[
              { label: t("analytics.trades"), value: style.swing.count },
              { label: t("analytics.style.weight"), value: weight(style.swing.count) },
            ]}
          />
        </div>

        <hr className="my-4 border-border dark:border-gray-700" />

        <div className="grid grid-cols-2 gap-6">
          <FooterCol
            items={[
              { label: t("analytics.style.avgDuration"), value: fmtDuration(style.avgDurationMs) },
              { label: t("analytics.style.longest"), value: fmtDuration(style.longestMs) },
            ]}
          />
          <FooterCol
            items={[
              { label: t("analytics.style.shortest"), value: fmtDuration(style.shortestMs) },
              { label: t("analytics.style.predominant"), value: style.predominant ? t(`analytics.style.${style.predominant}`) : DASH },
            ]}
          />
        </div>
      </div>
    </MetricCard>
  );
}

function StatBlock({ label, color, stats }: { label: string; color: string; stats: { label: string; value: ReactNode }[] }) {
  return (
    <div className="text-center">
      <div className="text-sm font-semibold" style={{ color }}>
        {label}
      </div>
      <dl className="mt-2 space-y-2">
        {stats.map((s) => (
          <div key={s.label}>
            <dt className="text-xs text-gray-500 dark:text-gray-400">{s.label}</dt>
            <dd className="text-lg font-semibold tabular-nums">{s.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function DonutLegend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div className="mt-3 flex flex-wrap items-center justify-center gap-x-4 gap-y-1 text-xs text-gray-600 dark:text-gray-300">
      {items.map((i) => (
        <span key={i.label} className="inline-flex items-center gap-1.5">
          <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: i.color }} />
          {i.label}
        </span>
      ))}
    </div>
  );
}

function FooterCol({ heading, headingColor, items }: { heading?: string; headingColor?: string; items: { label: string; value: ReactNode; tone?: string }[] }) {
  return (
    <div>
      {heading && (
        <div className="text-sm font-semibold" style={headingColor ? { color: headingColor } : undefined}>
          {heading}
        </div>
      )}
      <dl className={cn("space-y-1 text-sm", heading && "mt-1")}>
        {items.map((i) => (
          <div key={i.label} className="flex items-center justify-between gap-3">
            <dt className="text-gray-500 dark:text-gray-400">{i.label}</dt>
            <dd className={cn("font-medium tabular-nums", i.tone)}>{i.value}</dd>
          </div>
        ))}
      </dl>
    </div>
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
  countLabel,
  rateLabel,
}: {
  data: RateDatum[];
  xKey: string;
  tickFormatter?: (v: number) => string;
  theme: ReturnType<typeof useChartTheme>;
  countLabel: string;
  rateLabel: string;
}) {
  return (
    <div className="h-44 w-full md:h-72">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={theme.gridColor} />
          <XAxis dataKey={xKey} tickFormatter={tickFormatter} stroke={theme.axisColor} fontSize={12} />
          <YAxis yAxisId="count" allowDecimals={false} stroke={theme.axisColor} fontSize={12} width={36} />
          <YAxis yAxisId="rate" orientation="right" domain={[0, 100]} unit="%" stroke={theme.axisColor} fontSize={12} width={44} />
          <Tooltip contentStyle={theme.tooltipStyle} labelFormatter={(v) => (tickFormatter ? tickFormatter(Number(v)) : String(v))} />
          <Legend />
          <Bar yAxisId="count" dataKey="count" name={countLabel} fill={ACCENT} />
          <Line yAxisId="rate" type="linear" dataKey="winRate" name={rateLabel} stroke={WINRATE_LINE} dot={false} connectNulls />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
