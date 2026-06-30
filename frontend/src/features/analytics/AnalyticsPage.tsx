import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useClosedPositions } from "@/api/analytics";
import { usePositionExchanges } from "@/api/positions";
import { useTaxonomy } from "@/api/taxonomy";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { useAuth } from "@/auth/AuthContext";
import { Card, CardBody } from "@/components/ui/primitives";
import { useAnalyticsFilters } from "./useAnalyticsFilters";
import { filterRows } from "./applyFilters";
import { FilterBar } from "./FilterBar";
import { ViewTabs, type ViewKey } from "./ViewTabs";
import { SummaryView } from "./SummaryView";
import { PerformanceView } from "./PerformanceView";
import { BehaviorView } from "./BehaviorView";
import { StreaksView } from "./StreaksView";
import { PairsView } from "./PairsView";
import { FeesView } from "./FeesView";
import { CapitalRiskView } from "./CapitalRiskView";

export function AnalyticsPage() {
  const { t } = useTranslation();
  const { activeProfileId } = useActiveProfile();
  const { user } = useAuth();
  const timeZone = user?.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone;

  const { data: rows = [], isLoading } = useClosedPositions(activeProfileId);
  const { data: exchanges = [] } = usePositionExchanges(activeProfileId);
  const { data: taxonomy = [] } = useTaxonomy();
  const origenTags = useMemo(
    () => (taxonomy.find((g) => g.code === "origen") ?? taxonomy[0])?.tags ?? [],
    [taxonomy],
  );
  const filters = useAnalyticsFilters();
  const [view, setView] = useState<ViewKey>("all");

  const filtered = useMemo(
    () => filterRows(rows, filters.range, filters.exchange, filters.origenTagId),
    [rows, filters.range, filters.exchange, filters.origenTagId],
  );

  const show = (k: ViewKey) => view === "all" || view === k;
  const props = { rows: filtered, timeZone };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("analytics.title")}</h1>
      <FilterBar filters={filters} exchanges={exchanges} origenTags={origenTags} />
      <ViewTabs value={view} onChange={setView} />

      {/* Capital & risk is a current balance: independent of trade history and the Period/Origen
          filters (it uses the Exchange filter only), so it renders outside the closed-positions guard. */}
      {show("capital") && <CapitalRiskView profileId={activeProfileId} exchange={filters.exchange} />}

      {/* The trade-history views (and their loading/empty card) are hidden when only the
          capital tab is active, since capital is shown above and needs no closed positions. */}
      {view !== "capital" &&
        (isLoading ? (
          <Card>
            <CardBody>
              <p className="text-sm text-gray-500">{t("common.loading")}</p>
            </CardBody>
          </Card>
        ) : rows.length === 0 ? (
          <Card>
            <CardBody>
              <p className="py-12 text-center text-sm text-gray-500 dark:text-gray-400">{t("analytics.noData")}</p>
            </CardBody>
          </Card>
        ) : (
          <div className="space-y-6">
            {show("summary") && <SummaryView {...props} />}
            {show("performance") && <PerformanceView {...props} />}
            {show("behavior") && <BehaviorView {...props} />}
            {show("streaks") && <StreaksView {...props} />}
            {show("pairs") && <PairsView {...props} />}
            {show("fees") && <FeesView {...props} />}
          </div>
        ))}
    </div>
  );
}
