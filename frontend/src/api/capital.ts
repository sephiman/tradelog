import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type SnapshotFrequency = "DAILY" | "WEEKLY" | "MONTHLY";

/** Estimated CURRENT capital of one exchange; amount is null when it has no anchor yet. */
export interface CapitalEntry {
  exchange: string;
  amount: string | null;
  anchorDate: string | null;
  anchorAmount: string | null;
}

export interface RiskPercents {
  pct1: string;
  pct2: string;
}

export interface CapitalOverview {
  entries: CapitalEntry[];
  total: string;
  riskPercents: RiskPercents;
  snapshotFrequency: SnapshotFrequency;
  knownExchanges: string[];
  hasAnchors: boolean;
  /** The account's IANA time zone — every day boundary of this feature uses it. */
  timeZone: string;
}

export interface UpdateCapitalSettingsRequest {
  riskPercents: { pct1: string; pct2: string };
  snapshotFrequency: SnapshotFrequency;
}

/** One anchor: the asserted capital of one exchange at the start of `date`. */
export interface Adjustment {
  id: string;
  date: string; // YYYY-MM-DD
  exchange: string;
  amount: string;
}

/** Upserts anchors for one date; a null amount removes that exchange's anchor on that date. */
export interface SaveAdjustmentsRequest {
  date: string;
  entries: { exchange: string; amount: string | null }[];
}

export interface SnapshotValue {
  exchange: string;
  amount: string;
  /** True = an anchor (adjustment / manually edited day); never overwritten by the job. */
  manual: boolean;
}

export interface SnapshotDay {
  date: string; // YYYY-MM-DD
  values: SnapshotValue[];
}

export interface SnapshotSeries {
  days: SnapshotDay[];
  exchanges: string[];
}

/** What an on-demand snapshot backfill did: AUTO rows written, refreshed, or dropped. */
export interface RecomputeResult {
  created: number;
  updated: number;
  deleted: number;
}

export interface RoiResult {
  /** Fraction (0.052 = +5.2%); null = unavailable for the requested period/exchange. */
  roi: string | null;
  startCapital: string | null;
  netPnl: string | null;
  cutDate: string | null;
}

export function useCapital(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["capital", profileId],
    queryFn: async () => (await apiClient.get<CapitalOverview>(`/profiles/${profileId}/capital`)).data,
  });
}

export function useUpdateCapitalSettings(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateCapitalSettingsRequest) =>
      (await apiClient.put<CapitalOverview>(`/profiles/${profileId}/capital`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["capital", profileId] }),
  });
}

export function useAdjustments(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["capital", profileId, "adjustments"],
    queryFn: async () =>
      (await apiClient.get<Adjustment[]>(`/profiles/${profileId}/capital/adjustments`)).data,
  });
}

export function useSaveAdjustments(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: SaveAdjustmentsRequest) =>
      (await apiClient.post<Adjustment[]>(`/profiles/${profileId}/capital/adjustments`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["capital", profileId] }),
  });
}

export function usePatchAdjustment(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: { id: string; date?: string; amount?: string }) =>
      (await apiClient.patch<Adjustment[]>(`/profiles/${profileId}/capital/adjustments/${id}`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["capital", profileId] }),
  });
}

export function useDeleteAdjustment(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/profiles/${profileId}/capital/adjustments/${id}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["capital", profileId] }),
  });
}

/** Materialize the AUTO series at the configured frequency since the first adjustment, right now. */
export function useBackfillSnapshots(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    // The caller shows a toast with the counts instead of the generic "Saved".
    meta: { silentSuccess: true },
    mutationFn: async () =>
      (await apiClient.post<RecomputeResult>(`/profiles/${profileId}/capital/snapshots/backfill`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["capital", profileId] }),
  });
}

export function useCapitalSnapshots(profileId: string | null, from?: string, to?: string) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["capital", profileId, "snapshots", from ?? null, to ?? null],
    queryFn: async () =>
      (
        await apiClient.get<SnapshotSeries>(`/profiles/${profileId}/capital/snapshots`, {
          params: { from, to },
        })
      ).data,
  });
}

/** ROI of the dashboard's period/exchange filters. Origen deliberately never applies here. */
export function useRoi(profileId: string | null, from?: string, to?: string, exchange?: string) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["capital", profileId, "roi", from ?? null, to ?? null, exchange ?? "ALL"],
    queryFn: async () =>
      (
        await apiClient.get<RoiResult>(`/profiles/${profileId}/capital/roi`, {
          params: { from, to, exchange: exchange === "ALL" ? undefined : exchange },
        })
      ).data,
  });
}
