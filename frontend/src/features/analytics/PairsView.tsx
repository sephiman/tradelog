import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ClosedPosition } from "@/api/analytics";
import { pairsRankings, type PairStat } from "./compute";
import { MetricCard } from "./MetricCard";
import { DASH } from "./display";

export function PairsView({ rows }: { rows: ClosedPosition[]; timeZone: string }) {
  const { t } = useTranslation();
  const ranks = useMemo(() => pairsRankings(rows), [rows]);

  return (
    <div className="grid gap-6 md:grid-cols-3">
      <MetricCard title={t("analytics.mostTraded")} info={t("analytics.mostTradedInfo")}>
        <PairTable rows={ranks.mostTraded} metric="count" t={t} />
      </MetricCard>
      <MetricCard title={t("analytics.mostProfitable")} info={t("analytics.mostProfitableInfo")}>
        <PairTable rows={ranks.mostProfitable} metric="pnl" t={t} />
      </MetricCard>
      <MetricCard title={t("analytics.leastProfitable")} info={t("analytics.leastProfitableInfo")}>
        <PairTable rows={ranks.leastProfitable} metric="pnl" t={t} />
      </MetricCard>
    </div>
  );
}

function PairTable({ rows, metric, t }: { rows: PairStat[]; metric: "count" | "pnl"; t: (k: string) => string }) {
  if (rows.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{DASH}</p>;
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-left text-gray-500 dark:text-gray-400">
          <th className="py-1 font-medium">{t("analytics.pair")}</th>
          <th className="py-1 text-right font-medium">{t("analytics.trades")}</th>
          <th className="py-1 text-right font-medium">{t("analytics.totalPnl")}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((p) => (
          <tr key={p.pair} className="border-t border-border dark:border-gray-700">
            <td className="py-1.5 font-medium">{p.pair}</td>
            <td className={cn("py-1.5 text-right tabular-nums", metric === "count" && "font-semibold")}>{p.count}</td>
            <td className={cn("py-1.5 text-right tabular-nums", pnlTone(String(p.totalPnl)), metric === "pnl" && "font-semibold")}>
              {fmtUsd(p.totalPnl, { sign: true })}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
