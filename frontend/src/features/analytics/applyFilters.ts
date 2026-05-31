import type { ClosedPosition } from "@/api/analytics";
import type { DateRange } from "./useAnalyticsFilters";

/**
 * Filter closed positions by close-date range and venue. `exchange === "ALL"` keeps every venue.
 * Range bounds are inclusive; a null bound is open-ended.
 */
export function filterRows(rows: ClosedPosition[], range: DateRange, exchange: string): ClosedPosition[] {
  const fromT = range.from?.getTime() ?? null;
  const toT = range.to?.getTime() ?? null;
  return rows.filter((p) => {
    if (exchange !== "ALL" && (p.exchange ?? "") !== exchange) return false;
    if (fromT !== null || toT !== null) {
      const t = new Date(p.closedAt).getTime();
      if (fromT !== null && t < fromT) return false;
      if (toT !== null && t > toT) return false;
    }
    return true;
  });
}
