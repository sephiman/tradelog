import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type RunStatus = "RUNNING" | "SUCCESS" | "ERROR";

export interface SyncRun {
  id: string;
  dataSourceId: string;
  trigger: "LOGIN" | "MANUAL" | "UPLOAD" | "SCHEDULED";
  status: RunStatus;
  startedAt: string;
  finishedAt: string | null;
  inserted: number;
  updated: number;
  errorCode: string | null;
}

/**
 * Everything a completed sync/import can change. Capital is included because the backend now
 * refreshes the AUTO snapshot series (chart + ROI base) as new trades land, so the cached capital
 * views would otherwise stay stale. `invalidateQueries` matches by key prefix, so the single
 * `["capital", profileId]` entry covers the overview, adjustments, snapshots and ROI sub-queries.
 * Shared with the Quantfury import so the two lists never drift apart.
 */
export function invalidateAfterSync(qc: ReturnType<typeof useQueryClient>, profileId: string) {
  qc.invalidateQueries({ queryKey: ["dataSources", profileId] });
  qc.invalidateQueries({ queryKey: ["positions", profileId] });
  qc.invalidateQueries({ queryKey: ["positionExchanges", profileId] });
  qc.invalidateQueries({ queryKey: ["analyticsClosed", profileId] });
  qc.invalidateQueries({ queryKey: ["capital", profileId] });
}

export function useSyncOne(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (dataSourceId: string) =>
      (await apiClient.post<SyncRun>(`/profiles/${profileId}/data-sources/${dataSourceId}/sync`)).data,
    onSuccess: () => invalidateAfterSync(qc, profileId),
  });
}

export function useSyncAll(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async () => (await apiClient.post<SyncRun[]>(`/profiles/${profileId}/sync`)).data,
    onSuccess: () => invalidateAfterSync(qc, profileId),
  });
}
