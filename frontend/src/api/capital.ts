import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface CapitalEntry {
  exchange: string;
  amount: string;
  entryMode: string;
}

export interface RiskPercents {
  pct1: string;
  pct2: string;
}

export interface CapitalSettings {
  entries: CapitalEntry[];
  riskPercents: RiskPercents;
  knownExchanges: string[];
}

export interface UpdateCapitalRequest {
  entries: { exchange: string; amount: string | null }[];
  riskPercents: { pct1: string; pct2: string };
}

export function useCapital(profileId: string | null) {
  return useQuery({
    enabled: !!profileId,
    queryKey: ["capital", profileId],
    queryFn: async () => (await apiClient.get<CapitalSettings>(`/profiles/${profileId}/capital`)).data,
  });
}

export function useUpdateCapital(profileId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateCapitalRequest) =>
      (await apiClient.put<CapitalSettings>(`/profiles/${profileId}/capital`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["capital", profileId] }),
  });
}
