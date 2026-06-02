import type { ClosedPosition } from "@/api/analytics";
import type { DateRange } from "./useAnalyticsFilters";

/**
 * Filter closed positions by close-date range, venue and origen tag. `exchange === "ALL"` keeps
 * every venue and `origenTagId === "ALL"` keeps every origen. Range bounds are inclusive; a null
 * bound is open-ended. A position matches an origen filter when it carries that exact tag.
 */
export function filterRows(
  rows: ClosedPosition[],
  range: DateRange,
  exchange: string,
  origenTagId: string,
): ClosedPosition[] {
  const fromT = range.from?.getTime() ?? null;
  const toT = range.to?.getTime() ?? null;
  return rows.filter((p) => {
    if (exchange !== "ALL" && (p.exchange ?? "") !== exchange) return false;
    if (origenTagId !== "ALL" && !p.tags.some((tg) => tg.tagId === origenTagId)) return false;
    if (fromT !== null || toT !== null) {
      const t = new Date(p.closedAt).getTime();
      if (fromT !== null && t < fromT) return false;
      if (toT !== null && t > toT) return false;
    }
    return true;
  });
}
