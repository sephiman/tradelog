import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type RunStatus = "RUNNING" | "SUCCESS" | "ERROR";

export interface SyncRun {
  id: string;
  dataSourceId: string;
  trigger: "LOGIN" | "MANUAL" | "UPLOAD";
  status: RunStatus;
  startedAt: string;
  finishedAt: string | null;
  inserted: number;
  updated: number;
  errorCode: string | null;
}

function invalidate(qc: ReturnType<typeof useQueryClient>, profileId: string) {
  qc.invalidateQueries({ queryKey: ["dataSources", profileId] });
  qc.invalidateQueries({ queryKey: ["positions", profileId] });
  qc.invalidateQueries({ queryKey: ["positionExchanges", profileId] });
  qc.invalidateQueries({ queryKey: ["analyticsClosed", profileId] });
}

export function useSyncOne(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (dataSourceId: string) =>
      (await apiClient.post<SyncRun>(`/profiles/${profileId}/data-sources/${dataSourceId}/sync`)).data,
    onSuccess: () => invalidate(qc, profileId),
  });
}

export function useSyncAll(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async () => (await apiClient.post<SyncRun[]>(`/profiles/${profileId}/sync`)).data,
    onSuccess: () => invalidate(qc, profileId),
  });
}
