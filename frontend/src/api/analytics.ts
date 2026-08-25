import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { PositionSide, PositionTagView, SourceKind } from "./positions";

/**
 * One closed position as served by the analytics endpoint. Money fields are decimal strings.
 * `netPnl` is the backend's authoritative bottom line (gross realizedPnl − fees − funding); the
 * dashboard uses it directly so it never diverges from the rest of the app.
 */
export interface ClosedPosition {
  id: string;
  source: SourceKind;
  exchange: string | null;
  symbolBase: string;
  symbolQuote: string;
  side: PositionSide;
  openedAt: string;
  closedAt: string;
  /** Null on a grid-bot run, which has no single size or leg price. */
  qty: string | null;
  entryPrice: string | null;
  exitPrice: string | null;
  realizedPnl: string;
  netPnl: string;
  fees: string;
  funding: string;
  /** Traded notional as recorded, when the legs cannot produce it; null = derive from the legs. */
  volume: string | null;
  tags: PositionTagView[];
}

/** All closed positions for a profile, oldest close first — the raw input to every dashboard metric. */
export function useClosedPositions(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["analyticsClosed", profileId],
    queryFn: async () =>
      (await apiClient.get<ClosedPosition[]>(`/profiles/${profileId}/positions/closed-summary`)).data,
  });
}
