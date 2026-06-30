import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ClosedPosition } from "@/api/analytics";
import { pairsRankings, type PairStat } from "./compute";
import { MetricCard } from "./MetricCard";
import { ACCENT, GREEN, RED } from "./chartTheme";
import { DASH } from "./display";

export function MostTradedCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const ranks = useMemo(() => pairsRankings(rows), [rows]);
  return (
    <MetricCard title={t("analytics.mostTraded")} info={t("analytics.mostTradedInfo")}>
      {/* Trade counts, not PnL — violet accent (never red/green). */}
      <PairBars rows={ranks.mostTraded} metric="count" color={ACCENT} t={t} />
    </MetricCard>
  );
}

export function MostProfitableCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const ranks = useMemo(() => pairsRankings(rows), [rows]);
  return (
    <MetricCard title={t("analytics.mostProfitable")} info={t("analytics.mostProfitableInfo")}>
      <PairBars rows={ranks.mostProfitable} metric="pnl" color={GREEN} t={t} />
    </MetricCard>
  );
}

export function LeastProfitableCard({ rows }: { rows: ClosedPosition[] }) {
  const { t } = useTranslation();
  const ranks = useMemo(() => pairsRankings(rows), [rows]);
  return (
    <MetricCard title={t("analytics.leastProfitable")} info={t("analytics.leastProfitableInfo")}>
      <PairBars rows={ranks.leastProfitable} metric="pnl" color={RED} t={t} />
    </MetricCard>
  );
}

function PairBars({ rows, metric, color, t }: { rows: PairStat[]; metric: "count" | "pnl"; color: string; t: (k: string) => string }) {
  if (rows.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{DASH}</p>;
  const valueOf = (p: PairStat) => (metric === "count" ? p.count : p.totalPnl);
  const max = Math.max(...rows.map((p) => Math.abs(valueOf(p))), 1);
  return (
    <ul className="space-y-2.5">
      {rows.map((p) => {
        const width = `${(Math.abs(valueOf(p)) / max) * 100}%`;
        return (
          <li key={p.pair} className="text-sm">
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-medium">{p.pair}</span>
              {metric === "count" ? (
                <span className="tabular-nums text-gray-500 dark:text-gray-400">
                  {p.count} {t("analytics.trades").toLowerCase()}
                </span>
              ) : (
                <span className={cn("tabular-nums font-medium", pnlTone(String(p.totalPnl)))}>{fmtUsd(p.totalPnl, { sign: true })}</span>
              )}
            </div>
            <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
              <div className="h-full rounded-full" style={{ width, backgroundColor: color }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
