import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { SyncRun } from "./sync";
import type { PreviewPosition } from "./quantfury";

export interface ImportWarningRow {
  row: number | null;
  symbol: string;
  side: string;
  openedAt: string;
  closedAt: string;
}

export interface ImportWarning {
  code: string;
  count: number;
  rows: ImportWarningRow[];
}

export interface JournalCsvPreview {
  totalPositions: number;
  dateFrom: string | null;
  dateTo: string | null;
  sumRealizedPnl: string;
  sumFees: string;
  sumFunding: string;
  sumNetPnl: string;
  symbols: string[];
  sample: PreviewPosition[];
  warnings: ImportWarning[];
}

function asForm(file: File): FormData {
  const fd = new FormData();
  fd.append("file", file);
  return fd;
}

const noContentType = { headers: { "Content-Type": null } } as const;

export function useJournalCsvPreview(profileId: string, dataSourceId: string) {
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (file: File) =>
      (
        await apiClient.post<JournalCsvPreview>(
          `/profiles/${profileId}/data-sources/${dataSourceId}/journal-csv/preview`,
          asForm(file),
          noContentType,
        )
      ).data,
  });
}

export function useJournalCsvExecute(profileId: string, dataSourceId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silentSuccess: true },
    mutationFn: async (file: File) =>
      (
        await apiClient.post<SyncRun>(
          `/profiles/${profileId}/data-sources/${dataSourceId}/journal-csv/execute`,
          asForm(file),
          noContentType,
        )
      ).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["dataSources", profileId] });
      qc.invalidateQueries({ queryKey: ["positions", profileId] });
      qc.invalidateQueries({ queryKey: ["positionExchanges", profileId] });
      qc.invalidateQueries({ queryKey: ["pnlCumulative"] });
    },
  });
}
