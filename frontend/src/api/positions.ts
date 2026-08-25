import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type PositionSide = "LONG" | "SHORT";
/** Mirrors the backend `SourceKind`. See `lib/sourceKinds.ts` for the per-kind facts the UI needs. */
export type SourceKind =
  | "BITUNIX"
  | "BINGX"
  | "BITMART"
  | "BINANCE_FUTURES"
  | "BYBIT"
  | "OKX"
  | "BITGET"
  | "BITGET_CLASSIC"
  | "KRAKEN_FUTURES"
  | "KRAKEN_SPOT"
  | "GATEIO_FUTURES"
  | "MEXC_FUTURES"
  | "KUCOIN_FUTURES"
  | "QUANTFURY"
  | "JOURNAL_CSV";
export type FillAction = "OPEN" | "ADD" | "REDUCE" | "CLOSE";
/** What the row records. A grid-bot run has no single size or leg price, so those come back null. */
export type PositionKind = "TRADE" | "GRID_BOT";

export interface PositionTagView {
  groupId: string;
  groupCode: string;
  groupName: string;
  tagId: string;
  tagName: string;
}

export interface Position {
  id: string;
  source: SourceKind;
  exchange: string | null;
  symbolBase: string;
  symbolQuote: string;
  side: PositionSide;
  openedAt: string;
  closedAt: string;
  kind: PositionKind;
  qty: string | null;
  entryPrice: string | null;
  exitPrice: string | null;
  realizedPnl: string;
  netPnl: string;
  fees: string;
  funding: string;
  pnlCurrency: string;
  /** Traded notional, both legs. Null = derive it from the legs. */
  volume: string | null;
  leverage: string | null;
  investment: string | null;
  note: string | null;
  tags: PositionTagView[];
  fillCount: number;
  /** Hand-added trades can be edited; synced ones would be overwritten by the next sync. */
  editable: boolean;
}

export interface PositionFill {
  seq: number;
  action: FillAction;
  side: "BUY" | "SELL";
  ts: string;
  price: string;
  qty: string;
  value: string | null;
  fee: string | null;
}

export interface PositionDetail {
  position: Position;
  fills: PositionFill[];
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface PositionFilters {
  symbol?: string;
  side?: PositionSide | "";
  source?: SourceKind | "";
  exchange?: string;
  from?: string;
  to?: string;
  tagId?: string;
  /** Keep only positions with no tag in this group (e.g. "origen unset"). */
  untaggedGroupId?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export function usePositions(profileId: string | null, filters: PositionFilters) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["positions", profileId, filters],
    queryFn: async () => {
      const params: Record<string, string> = {};
      if (filters.symbol) params.symbol = filters.symbol;
      if (filters.side) params.side = filters.side;
      if (filters.source) params.source = filters.source;
      if (filters.exchange) params.exchange = filters.exchange;
      if (filters.from) params.from = filters.from;
      if (filters.to) params.to = filters.to;
      if (filters.tagId) params.tagId = filters.tagId;
      if (filters.untaggedGroupId) params.untaggedGroupId = filters.untaggedGroupId;
      params.page = String(filters.page ?? 0);
      params.size = String(filters.size ?? 50);
      params.sort = filters.sort ?? "closed_desc";
      const res = await apiClient.get<PageResponse<Position>>(`/profiles/${profileId}/positions`, { params });
      return res.data;
    },
  });
}

export function usePositionExchanges(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["positionExchanges", profileId],
    queryFn: async () =>
      (await apiClient.get<string[]>(`/profiles/${profileId}/positions/exchanges`)).data,
  });
}

/** Pairs already traded in the profile, spelled "BASE-QUOTE" — the manual forms' suggestions. */
export function usePositionSymbols(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["positionSymbols", profileId],
    queryFn: async () =>
      (await apiClient.get<string[]>(`/profiles/${profileId}/positions/symbols`)).data,
  });
}

export function usePositionDetail(profileId: string | null, positionId: string | null) {
  return useQuery({
    enabled: !!profileId && !!positionId,
    queryKey: ["position", profileId, positionId],
    queryFn: async () =>
      (await apiClient.get<PositionDetail>(`/profiles/${profileId}/positions/${positionId}`)).data,
  });
}

export function useSetNote(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ positionId, note }: { positionId: string; note: string | null }) => {
      await apiClient.put(`/profiles/${profileId}/positions/${positionId}/note`, { note });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["positions", profileId] }),
  });
}

export function useSetTag(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async ({ positionId, groupId, tagId }: { positionId: string; groupId: string; tagId: string }) => {
      await apiClient.put(`/profiles/${profileId}/positions/${positionId}/tags/${groupId}`, { tagId });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["positions", profileId] }),
  });
}

export interface BulkSetTagBody {
  tagId: string | null;
  positionIds?: string[];
  filters?: PositionFilters;
}

export function useBulkSetTag(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async ({ groupId, body }: { groupId: string; body: BulkSetTagBody }) => {
      const res = await apiClient.post<{ updated: number }>(
        `/profiles/${profileId}/positions/tags/${groupId}/bulk`,
        body,
      );
      return res.data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["positions", profileId] }),
  });
}

export function useDeletePosition(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (positionId: string) => {
      await apiClient.delete(`/profiles/${profileId}/positions/${positionId}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["positions", profileId] });
      qc.invalidateQueries({ queryKey: ["analyticsClosed", profileId] });
    },
  });
}

export interface BulkDeleteBody {
  positionIds?: string[];
  filters?: PositionFilters;
}

export function useBulkDeletePositions(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (body: BulkDeleteBody) => {
      const res = await apiClient.post<{ deleted: number }>(`/profiles/${profileId}/positions/bulk-delete`, body);
      return res.data;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["positions", profileId] });
      qc.invalidateQueries({ queryKey: ["analyticsClosed", profileId] });
    },
  });
}

export interface ManualPositionBody {
  symbol: string;
  side: PositionSide;
  openedAt: string;
  closedAt: string;
  qty: string;
  entryPrice: string;
  exitPrice: string;
  /** Omitted means "derive it from the leg prices", the same rule the CSV import follows. */
  realizedPnl?: string;
  fees: string;
  funding: string;
  exchange?: string;
  note?: string;
  /** A group with a null tag clears that group, which is how an edit removes the tag. */
  tagId?: string | null;
  tagGroupId?: string;
}

/** Which figure of the exchange's closed-grid screen the user typed. */
export type GridPnlBasis = "NET" | "GROSS";

export interface GridRunBody {
  symbol: string;
  side: PositionSide;
  /** Required on a grid run: with no leg prices, the venue is all that anchors capital and ROI. */
  exchange: string;
  openedAt: string;
  closedAt: string;
  pnl: string;
  pnlBasis: GridPnlBasis;
  fees: string;
  funding: string;
  /** Omitted when the volume calculator was left empty — the run then has no volume at all. */
  volume?: string;
  leverage?: string;
  investment?: string;
  note?: string;
  tagId?: string | null;
  tagGroupId?: string;
}

function invalidateTrades(qc: ReturnType<typeof useQueryClient>, profileId: string) {
  qc.invalidateQueries({ queryKey: ["positions", profileId] });
  qc.invalidateQueries({ queryKey: ["positionExchanges", profileId] });
  qc.invalidateQueries({ queryKey: ["positionSymbols", profileId] });
  qc.invalidateQueries({ queryKey: ["analyticsClosed", profileId] });
  // A hand-added trade shifts the capital curve the moment it lands, exactly as an import does.
  qc.invalidateQueries({ queryKey: ["capital", profileId] });
}

export function useCreatePosition(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (body: ManualPositionBody) => {
      const res = await apiClient.post<Position>(`/profiles/${profileId}/positions`, body);
      return res.data;
    },
    onSuccess: () => invalidateTrades(qc, profileId),
  });
}

export function useUpdatePosition(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async ({ positionId, body }: { positionId: string; body: ManualPositionBody }) => {
      const res = await apiClient.put<Position>(`/profiles/${profileId}/positions/${positionId}`, body);
      return res.data;
    },
    onSuccess: (_data, vars) => {
      invalidateTrades(qc, profileId);
      qc.invalidateQueries({ queryKey: ["position", profileId, vars.positionId] });
    },
  });
}

export function useCreateGridRun(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (body: GridRunBody) => {
      const res = await apiClient.post<Position>(`/profiles/${profileId}/positions/grid-runs`, body);
      return res.data;
    },
    onSuccess: () => invalidateTrades(qc, profileId),
  });
}

export function useUpdateGridRun(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async ({ positionId, body }: { positionId: string; body: GridRunBody }) => {
      const res = await apiClient.put<Position>(`/profiles/${profileId}/positions/grid-runs/${positionId}`, body);
      return res.data;
    },
    onSuccess: (_data, vars) => {
      invalidateTrades(qc, profileId);
      qc.invalidateQueries({ queryKey: ["position", profileId, vars.positionId] });
    },
  });
}

export function useClearTag(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async ({ positionId, groupId }: { positionId: string; groupId: string }) => {
      await apiClient.delete(`/profiles/${profileId}/positions/${positionId}/tags/${groupId}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["positions", profileId] }),
  });
}
