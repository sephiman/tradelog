import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface PnlCumPoint {
  date: string;
  cumulative: string;
}

export interface PnlSeries {
  profileId: string;
  profileName: string;
  points: PnlCumPoint[];
}

export function usePnlCumulative() {
  return useQuery({
    queryKey: ["pnlCumulative"],
    queryFn: async () => (await apiClient.get<PnlSeries[]>("/analytics/pnl-cumulative")).data,
  });
}
