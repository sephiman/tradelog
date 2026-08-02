import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

/**
 * Benchmark reference data: buy-and-hold returns of market indices/assets, compared against the
 * user's monthly ROI. Global market data, so these queries are not profile-scoped — the same
 * series serve every profile, and react-query dedupes them across cards.
 */
export interface Benchmark {
  key: string;
  /** False while the background job has not filled this series yet — the legend disables it. */
  hasData: boolean;
  availableFrom: string | null; // YYYY-MM-DD
  availableTo: string | null; // YYYY-MM-DD
}

export interface BenchmarkMonthlyReturn {
  month: number; // 1..12
  /** Fraction (0.052 = +5.2%), matching MonthlyRoiItem.roi; null is a data gap, not a 0% month. */
  ret: string | null;
}

export interface BenchmarkMonthlySeries {
  key: string;
  months: BenchmarkMonthlyReturn[];
  /** True when any of the twelve months is a gap. */
  partial: boolean;
}

export function useBenchmarks() {
  return useQuery({
    queryKey: ["benchmarks"],
    queryFn: async () => (await apiClient.get<Benchmark[]>("/benchmarks")).data,
  });
}

/** Buy-and-hold monthly returns of every benchmark for one calendar year. */
export function useBenchmarkMonthly(year: number) {
  return useQuery({
    queryKey: ["benchmarks", "monthly", year],
    queryFn: async () =>
      (await apiClient.get<BenchmarkMonthlySeries[]>("/benchmarks/monthly", { params: { year } })).data,
  });
}

export interface BenchmarkDailyClose {
  date: string; // YYYY-MM-DD
  /** USD close, carried across closed markets; null is a genuine gap, never a carried-on price. */
  close: string | null;
}

export interface BenchmarkDailySeries {
  key: string;
  points: BenchmarkDailyClose[];
  partial: boolean;
}

/**
 * Daily closes over a window, for the given keys only. Disabled while nothing is selected, so a
 * chart with no benchmark switched on issues no request at all.
 */
export function useBenchmarkDaily(from: string | undefined, to: string | undefined, keys: string[]) {
  const sorted = [...keys].sort();
  return useQuery({
    enabled: !!from && !!to && sorted.length > 0,
    queryKey: ["benchmarks", "daily", from ?? null, to ?? null, sorted],
    queryFn: async () =>
      (
        await apiClient.get<BenchmarkDailySeries[]>("/benchmarks/daily", {
          params: { from, to, keys: sorted.join(",") },
        })
      ).data,
  });
}
