import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { DataSource } from "./dataSources";
import { isApiKind } from "@/lib/sourceKinds";
import type { ToastKind } from "@/lib/toastBus";

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

/** Sources a bulk sync will actually attempt: the client-side mirror of the backend's own filter. */
export const syncableSources = (sources: DataSource[]): DataSource[] =>
  sources.filter((s) => isApiKind(s.kind) && s.status !== "DISABLED");

export interface SyncSummary {
  key: string;
  params?: Record<string, number>;
  tone: ToastKind;
}

/** What to tell the user after a bulk sync: no runs is not a success, and a failed run is not a synced one. */
export function summarizeSyncAll(runs: SyncRun[], syncable: number): SyncSummary {
  if (runs.length === 0) {
    return syncable === 0 ? { key: "sync.nothingEnabled", tone: "info" } : { key: "sync.skipped", tone: "info" };
  }
  const failed = runs.filter((r) => r.status === "ERROR");
  const inserted = runs.reduce((a, r) => a + r.inserted, 0);
  const updated = runs.reduce((a, r) => a + r.updated, 0);
  if (failed.length === runs.length) {
    // Worth naming an outage: nothing is wrong with the keys, and retrying later just works.
    const allUnreachable = failed.every((r) => r.errorCode === "SYNC_UNREACHABLE");
    return { key: allUnreachable ? "sync.unreachable" : "sync.failed", tone: "error" };
  }
  if (failed.length > 0) {
    return { key: "sync.partial", params: { inserted, updated, failed: failed.length }, tone: "error" };
  }
  return { key: "sync.synced", params: { inserted, updated }, tone: "success" };
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
