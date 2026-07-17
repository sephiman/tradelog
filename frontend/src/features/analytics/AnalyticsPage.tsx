import { useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useClosedPositions } from "@/api/analytics";
import { usePositionExchanges } from "@/api/positions";
import { useTaxonomy } from "@/api/taxonomy";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { useAuth } from "@/auth/AuthContext";
import { Card, CardBody } from "@/components/ui/primitives";
import { QueryError } from "@/components/ui/QueryError";
import { useAnalyticsFilters, type DateRange } from "./useAnalyticsFilters";
import { filterRows } from "./applyFilters";
import { FilterBar } from "./FilterBar";
import { ViewTabs, type ViewKey } from "./ViewTabs";
import { useMonthNavState } from "./PeriodNav";
import { StatisticsCard, CumulativeProfitCard } from "./SummaryView";
import { ActivityCard, PnlPerDayCard, MonthlySummaryCard } from "./PerformanceView";
import { WinRateByHourCard, WinRateByWeekdayCard, TradeDirectionCard, TraderStyleCard } from "./BehaviorView";
import { WinningStreaksCard, LosingStreaksCard, RecoveryCard, CalendarCard } from "./StreaksView";
import { MostTradedCard, MostProfitableCard, LeastProfitableCard } from "./PairsView";
import { FeesCard, CumulativeFeesCard, FeeRatioCard } from "./FeesView";
import { CapitalRiskView } from "./CapitalRiskView";
import { CapitalEvolutionCard } from "./CapitalEvolutionCard";
import type { ClosedPosition } from "@/api/analytics";

const NO_RANGE: DateRange = { from: null, to: null };

/** A responsive pair of cards: two columns on desktop, stacked on mobile.
 * The base `grid-cols-1` is `minmax(0,1fr)`, which lets each track shrink to the
 * viewport so charts/tables fit instead of forcing horizontal scroll. */
function Row({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-1 gap-6 md:grid-cols-2">{children}</div>;
}

export function AnalyticsPage() {
  const { t } = useTranslation();
  const { activeProfileId } = useActiveProfile();
  const { user } = useAuth();
  const timeZone = user?.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone;

  const { data: rows = [], isLoading, isError, refetch } = useClosedPositions(activeProfileId);
  const { data: exchanges = [] } = usePositionExchanges(activeProfileId);
  const { data: taxonomy = [] } = useTaxonomy();
  const origenTags = useMemo(
    () => (taxonomy.find((g) => g.code === "origen") ?? taxonomy[0])?.tags ?? [],
    [taxonomy],
  );
  const filters = useAnalyticsFilters();
  const [view, setView] = useState<ViewKey>("all");

  // Two month-navs are shared across paired cards so they stay in sync: Activity ↔ PnL per day, and
  // Fees ↔ Fee ratio. Calendar and Monthly summary own their own period state.
  const perfNav = useMonthNavState();
  const feesNav = useMonthNavState();

  const filtered = useMemo(
    () => filterRows(rows, filters.range, filters.exchange, filters.origenTagId),
    [rows, filters.range, filters.exchange, filters.origenTagId],
  );

  // Cards with their own month/year navigator select their period themselves, so the global Period
  // filter doesn't apply to them — only Exchange and Origen do. Otherwise the default "This month"
  // preset would blank out any month/year the navigator moves to.
  const navRows = useMemo(
    () => filterRows(rows, NO_RANGE, filters.exchange, filters.origenTagId),
    [rows, filters.exchange, filters.origenTagId],
  );

  const capital = <CapitalRiskView profileId={activeProfileId} exchange={filters.exchange} />;
  const capitalEvolution = (
    <CapitalEvolutionCard profileId={activeProfileId} range={filters.range} exchange={filters.exchange} />
  );

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("analytics.title")}</h1>
      <FilterBar filters={filters} exchanges={exchanges} origenTags={origenTags} />
      <ViewTabs value={view} onChange={setView} />

      {/* Capital & risk is a current balance: independent of trade history and the Period/Origen
          filters (it uses the Exchange filter only). It renders even with no closed positions. */}
      {view === "capital" ? (
        <div className="space-y-6">
          {capital}
          {capitalEvolution}
        </div>
      ) : isError ? (
        <Card>
          <CardBody>
            <QueryError onRetry={() => void refetch()} />
          </CardBody>
        </Card>
      ) : isLoading ? (
        <Card>
          <CardBody>
            <p className="text-sm text-gray-500">{t("common.loading")}</p>
          </CardBody>
        </Card>
      ) : rows.length === 0 ? (
        <div className="space-y-6">
          {view === "all" && capital}
          {view === "all" && capitalEvolution}
          <Card>
            <CardBody>
              <p className="py-12 text-center text-sm text-gray-500 dark:text-gray-400">{t("analytics.noData")}</p>
            </CardBody>
          </Card>
        </div>
      ) : (
        <Dashboard
          view={view}
          rows={filtered}
          navRows={navRows}
          timeZone={timeZone}
          perfNav={perfNav}
          feesNav={feesNav}
          capital={capital}
          capitalEvolution={capitalEvolution}
          profileId={activeProfileId}
          range={filters.range}
          exchange={filters.exchange}
        />
      )}
    </div>
  );
}

function Dashboard({
  view,
  rows,
  navRows,
  timeZone,
  perfNav,
  feesNav,
  capital,
  capitalEvolution,
  profileId,
  range,
  exchange,
}: {
  view: ViewKey;
  rows: ClosedPosition[];
  /** Rows filtered by Exchange/Origen only — for cards whose own navigator picks the period. */
  navRows: ClosedPosition[];
  timeZone: string;
  perfNav: ReturnType<typeof useMonthNavState>;
  feesNav: ReturnType<typeof useMonthNavState>;
  capital: ReactNode;
  capitalEvolution: ReactNode;
  profileId: string | null;
  range: DateRange;
  exchange: string;
}) {
  if (view === "summary") {
    return (
      <div className="space-y-6">
        <StatisticsCard rows={rows} profileId={profileId} range={range} exchange={exchange} />
        <CumulativeProfitCard rows={rows} />
      </div>
    );
  }
  if (view === "performance") {
    return (
      <div className="space-y-6">
        <Row>
          <ActivityCard rows={navRows} timeZone={timeZone} nav={perfNav} />
          <PnlPerDayCard rows={navRows} timeZone={timeZone} nav={perfNav} />
        </Row>
        <MonthlySummaryCard rows={navRows} timeZone={timeZone} />
      </div>
    );
  }
  if (view === "behavior") {
    return (
      <div className="space-y-6">
        <Row>
          <WinRateByHourCard rows={rows} timeZone={timeZone} />
          <WinRateByWeekdayCard rows={rows} timeZone={timeZone} />
        </Row>
        <Row>
          <TradeDirectionCard rows={rows} />
          <TraderStyleCard rows={rows} />
        </Row>
      </div>
    );
  }
  if (view === "streaks") {
    return (
      <div className="space-y-6">
        <div className="grid grid-cols-1 gap-6 md:grid-cols-5">
          <WinningStreaksCard rows={rows} className="md:col-span-2" />
          <LosingStreaksCard rows={rows} className="md:col-span-2" />
          <RecoveryCard rows={rows} className="md:col-span-1" />
        </div>
        <CalendarCard rows={navRows} timeZone={timeZone} />
      </div>
    );
  }
  if (view === "pairs") {
    return (
      <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
        <MostTradedCard rows={rows} />
        <MostProfitableCard rows={rows} />
        <LeastProfitableCard rows={rows} />
      </div>
    );
  }
  if (view === "fees") {
    return (
      <div className="space-y-6">
        <Row>
          <FeesCard rows={navRows} timeZone={timeZone} nav={feesNav} />
          <CumulativeFeesCard rows={rows} />
        </Row>
        <FeeRatioCard rows={navRows} timeZone={timeZone} nav={feesNav} />
      </div>
    );
  }

  // view === "all": the full dashboard, matching the reference layout.
  return (
    <div className="space-y-6">
      <StatisticsCard rows={rows} profileId={profileId} range={range} exchange={exchange} />
      <Row>
        {capital}
        <CumulativeProfitCard rows={rows} />
      </Row>
      {capitalEvolution}
      <Row>
        <ActivityCard rows={navRows} timeZone={timeZone} nav={perfNav} />
        <PnlPerDayCard rows={navRows} timeZone={timeZone} nav={perfNav} />
      </Row>
      <Row>
        <WinRateByHourCard rows={rows} timeZone={timeZone} />
        <WinRateByWeekdayCard rows={rows} timeZone={timeZone} />
      </Row>
      <Row>
        <TradeDirectionCard rows={rows} />
        <TraderStyleCard rows={rows} />
      </Row>
      <div className="grid gap-6 md:grid-cols-5">
        <WinningStreaksCard rows={rows} className="md:col-span-2" />
        <LosingStreaksCard rows={rows} className="md:col-span-2" />
        <RecoveryCard rows={rows} className="md:col-span-1" />
      </div>
      <Row>
        <CalendarCard rows={navRows} timeZone={timeZone} />
        <MostTradedCard rows={rows} />
      </Row>
      <Row>
        <MostProfitableCard rows={rows} />
        <LeastProfitableCard rows={rows} />
      </Row>
      <Row>
        <FeesCard rows={navRows} timeZone={timeZone} nav={feesNav} />
        <CumulativeFeesCard rows={rows} />
      </Row>
      <Row>
        <FeeRatioCard rows={navRows} timeZone={timeZone} nav={feesNav} />
        <MonthlySummaryCard rows={navRows} timeZone={timeZone} />
      </Row>
    </div>
  );
}
