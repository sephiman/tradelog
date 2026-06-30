import { useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { fmtUsd, toDecimal } from "@/lib/format";
import { useCapital, type CapitalEntry } from "@/api/capital";
import { MetricCard } from "./MetricCard";
import { InfoTooltip } from "./InfoTooltip";

function Tile({ label, info, value }: { label: string; info?: string; value: ReactNode }) {
  return (
    <div className="rounded-md border border-border p-3 dark:border-gray-700">
      <div className="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
        <span>{label}</span>
        {info && <InfoTooltip text={info} />}
      </div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
    </div>
  );
}

/** Strip trailing zeros from a percentage string ("1.000" → "1"). */
const fmtPct = (s: string): string => {
  const n = Number(s);
  return Number.isFinite(n) ? String(n) : s;
};

/**
 * Current trading capital and the max loss per trade. Honors ONLY the Exchange filter —
 * it is a current balance and is deliberately independent of the Period/Origen filters.
 */
export function CapitalRiskView({ profileId, exchange }: { profileId: string | null; exchange: string }) {
  const { t } = useTranslation();
  const { data } = useCapital(profileId);

  const entries = useMemo<CapitalEntry[]>(() => {
    if (!data) return [];
    return exchange === "ALL" ? data.entries : data.entries.filter((e) => e.exchange === exchange);
  }, [data, exchange]);

  const total = useMemo(
    () => entries.reduce((acc, e) => acc.plus(toDecimal(e.amount)), new Decimal(0)),
    [entries],
  );

  if (!data) return null;

  const p1 = toDecimal(data.riskPercents.pct1);
  const p2 = toDecimal(data.riskPercents.pct2);
  const pct1Label = fmtPct(data.riskPercents.pct1);
  const pct2Label = fmtPct(data.riskPercents.pct2);
  const risk = (capital: Decimal, pct: Decimal) => capital.times(pct).div(100);

  const totalLabel = exchange === "ALL" ? t("analytics.capital.totalCapital") : exchange;
  const showBreakdown = exchange === "ALL" && entries.length > 0;
  const nothingSet = entries.length === 0 || total.isZero();

  return (
    <MetricCard title={t("analytics.capital.title")} info={t("analytics.capital.info")}>
      {nothingSet ? (
        <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("analytics.capital.empty")}</p>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Tile label={totalLabel} info={t("analytics.capital.capitalInfo")} value={fmtUsd(total.toString())} />
            <Tile
              label={t("analytics.capital.maxLossAt", { pct: pct1Label })}
              info={t("analytics.capital.maxLossInfo")}
              value={fmtUsd(risk(total, p1).toString())}
            />
            <Tile
              label={t("analytics.capital.maxLossAt", { pct: pct2Label })}
              info={t("analytics.capital.maxLossInfo")}
              value={fmtUsd(risk(total, p2).toString())}
            />
          </div>

          {showBreakdown && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[28rem] text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs text-gray-500 dark:border-gray-700 dark:text-gray-400">
                    <th className="py-2 pr-3 font-medium">{t("analytics.exchangeLabel")}</th>
                    <th className="py-2 pr-3 text-right font-medium">{t("analytics.capital.capital")}</th>
                    <th className="py-2 pr-3 text-right font-medium">{t("analytics.capital.maxLossAt", { pct: pct1Label })}</th>
                    <th className="py-2 text-right font-medium">{t("analytics.capital.maxLossAt", { pct: pct2Label })}</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((e) => {
                    const cap = toDecimal(e.amount);
                    return (
                      <tr key={e.exchange} className="border-b border-border last:border-0 dark:border-gray-700">
                        <td className="py-2 pr-3">{e.exchange}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{fmtUsd(cap.toString())}</td>
                        <td className="py-2 pr-3 text-right tabular-nums">{fmtUsd(risk(cap, p1).toString())}</td>
                        <td className="py-2 text-right tabular-nums">{fmtUsd(risk(cap, p2).toString())}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </MetricCard>
  );
}
