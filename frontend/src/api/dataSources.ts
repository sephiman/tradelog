import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { SourceKind } from "./positions";

export type DataSourceStatus = "ACTIVE" | "ERROR" | "DISABLED";

export interface DataSource {
  id: string;
  kind: SourceKind;
  label: string;
  status: DataSourceStatus;
  statusDetail: string | null;
  hasCredentials: boolean;
  lastSyncedAt: string | null;
  positionCount: number;
  createdAt: string;
}

export interface CreateDataSourceRequest {
  kind: SourceKind;
  label: string;
  apiKey?: string;
  apiSecret?: string;
  passphrase?: string;
}

export interface UpdateDataSourceRequest {
  label?: string;
  status?: DataSourceStatus;
  apiKey?: string;
  apiSecret?: string;
  passphrase?: string;
}

export function useDataSources(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["dataSources", profileId],
    queryFn: async () => (await apiClient.get<DataSource[]>(`/profiles/${profileId}/data-sources`)).data,
  });
}

export function useCreateDataSource(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateDataSourceRequest) =>
      (await apiClient.post<DataSource>(`/profiles/${profileId}/data-sources`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dataSources", profileId] }),
  });
}

export function useUpdateDataSource(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: UpdateDataSourceRequest }) =>
      (await apiClient.put<DataSource>(`/profiles/${profileId}/data-sources/${id}`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dataSources", profileId] }),
  });
}

export function useDeleteDataSource(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/profiles/${profileId}/data-sources/${id}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["dataSources", profileId] });
      qc.invalidateQueries({ queryKey: ["positions", profileId] });
    },
  });
}
