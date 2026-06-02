import { useMemo, useState } from "react";
import {
  endOfDay,
  endOfMonth,
  endOfQuarter,
  endOfWeek,
  endOfYear,
  startOfDay,
  startOfMonth,
  startOfQuarter,
  startOfWeek,
  startOfYear,
} from "date-fns";

export type PeriodPreset = "all" | "today" | "week" | "month" | "quarter" | "year" | "custom";

export const PERIOD_PRESETS: PeriodPreset[] = ["all", "today", "week", "month", "quarter", "year", "custom"];

export interface DateRange {
  from: Date | null;
  to: Date | null;
}

/**
 * Period boundaries are computed against the browser-local clock (not the user's configured
 * analytics time zone) — a deliberate simplification so we avoid a tz date library. Hour/weekday
 * bucketing still uses the configured zone.
 */
function computeRange(period: PeriodPreset, from: string, to: string): DateRange {
  const now = new Date();
  switch (period) {
    case "all":
      return { from: null, to: null };
    case "today":
      return { from: startOfDay(now), to: endOfDay(now) };
    case "week":
      return { from: startOfWeek(now, { weekStartsOn: 1 }), to: endOfWeek(now, { weekStartsOn: 1 }) };
    case "month":
      return { from: startOfMonth(now), to: endOfMonth(now) };
    case "quarter":
      return { from: startOfQuarter(now), to: endOfQuarter(now) };
    case "year":
      return { from: startOfYear(now), to: endOfYear(now) };
    case "custom":
      return {
        from: from ? startOfDay(new Date(from)) : null,
        to: to ? endOfDay(new Date(to)) : null,
      };
  }
}

export interface AnalyticsFilters {
  period: PeriodPreset;
  setPeriod: (p: PeriodPreset) => void;
  from: string;
  setFrom: (v: string) => void;
  to: string;
  setTo: (v: string) => void;
  exchange: string; // "ALL" or a venue label
  setExchange: (v: string) => void;
  origenTagId: string; // "ALL" or an origen tag id
  setOrigenTagId: (v: string) => void;
  range: DateRange;
}

export function useAnalyticsFilters(): AnalyticsFilters {
  const [period, setPeriod] = useState<PeriodPreset>("month");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [exchange, setExchange] = useState("ALL");
  const [origenTagId, setOrigenTagId] = useState("ALL");
  const range = useMemo(() => computeRange(period, from, to), [period, from, to]);
  return {
    period, setPeriod, from, setFrom, to, setTo,
    exchange, setExchange, origenTagId, setOrigenTagId, range,
  };
}
