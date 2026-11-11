import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import { useInstanceFeatures } from "./features";
import type { MonthlyRoiItem } from "./capital";

export interface AiSettings {
  enabled: boolean;
  /** Where the aggregates go, named in the setting's help text and the button's tooltip. */
  model: string;
  providerHost: string;
}

export type AiRunStatus = "RUNNING" | "COMPLETED" | "FAILED" | "INTERRUPTED";

export interface AiCitation {
  path: string;
  value: unknown;
  grounded: boolean;
}

export interface AiObservation {
  claim: string;
  sampleSize: number | null;
  confidence: string | null;
  citations: AiCitation[];
}

export interface AiInsufficientItem {
  topic: string;
  reason: string;
  citations: AiCitation[];
}

/** The dashboard filter state a run was produced under; kept on the row so old runs stay interpretable. */
export interface AiRunScope {
  periodPreset: string;
  rangeFrom: string | null;
  rangeTo: string | null;
  exchange: string;
  /** The anonymized label ("Tag A") as the model saw it; real names come back in tagNames. */
  origenLabel: string | null;
  timeZone: string;
  sampleSize: number;
}

export interface AiRun {
  id: string;
  status: AiRunStatus;
  createdAt: string;
  finishedAt: string | null;
  scope: AiRunScope;
  model: string;
  promptVersion: string;
  /** Anonymized label -> the tag's real name; a deleted tag keeps its label. */
  tagNames: Record<string, string>;
  observations: AiObservation[];
  insufficientSample: AiInsufficientItem[];
  observationsCount: number | null;
  citationsTotal: number | null;
  citationsUngrounded: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  reasoningTokens: number | null;
  latencyMs: number | null;
  errorCode: string | null;
}

const settingsKey = ["ai-settings"];

/** Only requested once the instance reports the feature; otherwise nothing AI-related is fetched. */
export function useAiSettings() {
  const { data: features } = useInstanceFeatures();
  return useQuery({
    queryKey: settingsKey,
    queryFn: async () => (await apiClient.get<AiSettings>("/ai/settings")).data,
    enabled: features?.ai === true,
  });
}

/** The analysis tab and its trigger exist only for a user who turned the setting on. */
export function useAiOn(): AiSettings | null {
  const { data: settings } = useAiSettings();
  return settings?.enabled ? settings : null;
}

export function useUpdateAiSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (enabled: boolean) =>
      (await apiClient.put<AiSettings>("/ai/settings", { enabled })).data,
    onSuccess: (data) => qc.setQueryData(settingsKey, data),
    meta: { silentSuccess: true },
  });
}

const POLL_MS = 1500;

/** The profile's run history, newest first. Polls while any run is still in progress. */
export function useAiAnalysisRuns(profileId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ["ai-analysis", profileId],
    queryFn: async () =>
      (await apiClient.get<AiRun[]>(`/profiles/${profileId}/ai-analysis`)).data,
    enabled: !!profileId && enabled,
    refetchInterval: (query) =>
      query.state.data?.some((r) => r.status === "RUNNING") ? POLL_MS : false,
  });
}

export function useStartAiAnalysis(profileId: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (payload: unknown) =>
      (await apiClient.post<AiRun>(`/profiles/${profileId}/ai-analysis`, { payload })).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["ai-analysis", profileId] }),
    // The run card appearing (and then polling) is the feedback; a "Saved" toast would lie early.
    meta: { silentSuccess: true },
  });
}

export function useDeleteAiAnalysisRun(profileId: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (runId: string) => {
      await apiClient.delete(`/profiles/${profileId}/ai-analysis/${runId}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["ai-analysis", profileId] }),
    meta: { silentSuccess: true },
  });
}

/** Monthly ROI series for every year with trades, matching the Monthly ROI card's own source. */
export function useMonthlyRoiYears(profileId: string | null, years: number[], exchange: string) {
  return useQueries({
    queries: years.map((year) => ({
      queryKey: ["capital", profileId, "roi", "monthly", year, exchange],
      queryFn: async () =>
        (
          await apiClient.get<MonthlyRoiItem[]>(`/profiles/${profileId}/capital/roi/monthly`, {
            params: { year, exchange: exchange === "ALL" ? undefined : exchange },
          })
        ).data,
      enabled: !!profileId && years.length > 0,
    })),
    combine: (results) => {
      const byKey = new Map<string, MonthlyRoiItem>();
      years.forEach((year, i) => {
        for (const item of results[i].data ?? []) {
          if (item.roi !== null) byKey.set(`${year}-${item.month}`, item);
        }
      });
      return byKey;
    },
  });
}
