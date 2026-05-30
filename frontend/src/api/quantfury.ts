import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { SyncRun } from "./sync";

export interface PreviewPosition {
  symbol: string;
  side: string;
  openedAt: string;
  closedAt: string;
  qty: string;
  realizedPnl: string;
}

export interface QuantfuryPreview {
  totalPositions: number;
  dateFrom: string | null;
  dateTo: string | null;
  sumRealizedPnl: string;
  symbols: string[];
  sample: PreviewPosition[];
}

function asForm(file: File): FormData {
  const fd = new FormData();
  fd.append("file", file);
  return fd;
}

export function useQuantfuryPreview(profileId: string, dataSourceId: string) {
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (file: File) =>
      (
        await apiClient.post<QuantfuryPreview>(
          `/profiles/${profileId}/data-sources/${dataSourceId}/quantfury/preview`,
          asForm(file),
          { headers: { "Content-Type": null } },
        )
      ).data,
  });
}

export function useQuantfuryExecute(profileId: string, dataSourceId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (file: File) =>
      (
        await apiClient.post<SyncRun>(
          `/profiles/${profileId}/data-sources/${dataSourceId}/quantfury/execute`,
          asForm(file),
          { headers: { "Content-Type": null } },
        )
      ).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["dataSources", profileId] });
      qc.invalidateQueries({ queryKey: ["positions", profileId] });
      qc.invalidateQueries({ queryKey: ["pnlCumulative"] });
    },
  });
}
