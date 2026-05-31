import { useTranslation } from "react-i18next";
import { Chip, Input, Label, Select } from "@/components/ui/primitives";
import { PERIOD_PRESETS, type AnalyticsFilters } from "./useAnalyticsFilters";

export function FilterBar({ filters, exchanges }: { filters: AnalyticsFilters; exchanges: string[] }) {
  const { t } = useTranslation();

  return (
    <div className="sticky top-0 z-10 space-y-3 rounded-lg border border-border bg-white/95 p-3 shadow-sm backdrop-blur dark:border-gray-700 dark:bg-gray-800/95">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-gray-500 dark:text-gray-400">{t("analytics.periodLabel")}</span>
        {PERIOD_PRESETS.map((p) => (
          <Chip key={p} active={filters.period === p} onClick={() => filters.setPeriod(p)}>
            {t(`analytics.period.${p}`)}
          </Chip>
        ))}
      </div>

      {filters.period === "custom" && (
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <Label htmlFor="af-from">{t("analytics.from")}</Label>
            <Input
              id="af-from"
              type="date"
              className="w-44"
              value={filters.from}
              max={filters.to || undefined}
              onChange={(e) => filters.setFrom(e.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="af-to">{t("analytics.to")}</Label>
            <Input
              id="af-to"
              type="date"
              className="w-44"
              value={filters.to}
              min={filters.from || undefined}
              onChange={(e) => filters.setTo(e.target.value)}
            />
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-gray-500 dark:text-gray-400">{t("analytics.exchangeLabel")}</span>
        <Select
          className="w-48"
          value={filters.exchange}
          onChange={(e) => filters.setExchange(e.target.value)}
          aria-label={t("analytics.exchangeLabel")}
        >
          <option value="ALL">{t("analytics.allExchanges")}</option>
          {exchanges.map((x) => (
            <option key={x} value={x}>
              {x}
            </option>
          ))}
        </Select>
      </div>
    </div>
  );
}
