import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type PositionSide = "LONG" | "SHORT";
export type SourceKind = "BITUNIX" | "BINGX" | "QUANTFURY" | "JOURNAL_CSV";
export type FillAction = "OPEN" | "ADD" | "REDUCE" | "CLOSE";

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
  qty: string;
  entryPrice: string;
  exitPrice: string;
  realizedPnl: string;
  netPnl: string;
  fees: string;
  funding: string;
  pnlCurrency: string;
  note: string | null;
  tags: PositionTagView[];
  fillCount: number;
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
