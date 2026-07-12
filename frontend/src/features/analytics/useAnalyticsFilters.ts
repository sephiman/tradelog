import { useMemo, useState } from "react";
import { dateInputToIso, todayInDisplayZone } from "@/lib/format";

export type PeriodPreset = "all" | "today" | "week" | "month" | "quarter" | "year" | "custom";

export const PERIOD_PRESETS: PeriodPreset[] = ["all", "today", "week", "month", "quarter", "year", "custom"];

export interface DateRange {
  from: Date | null;
  to: Date | null;
}

const pad = (n: number) => String(n).padStart(2, "0");
/** Calendar date of a UTC-anchored Date as "YYYY-MM-DD" (arithmetic happens on UTC fields). */
const dayStr = (d: Date) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;

function bounds(startDay: string, endDay: string): DateRange {
  return {
    from: new Date(dateInputToIso(startDay)!),
    to: new Date(dateInputToIso(endDay, true)!),
  };
}

/**
 * Period boundaries are day-precise in the account's display zone, matching the calendar/day
 * bucketing and the positions-page filters: "today"'s calendar date comes from the display zone,
 * boundary days are derived with UTC-anchored calendar arithmetic, and each boundary day converts
 * to an instant via [dateInputToIso].
 */
function computeRange(period: PeriodPreset, from: string, to: string): DateRange {
  if (period === "all") return { from: null, to: null };
  if (period === "custom") {
    return {
      from: from ? new Date(dateInputToIso(from)!) : null,
      to: to ? new Date(dateInputToIso(to, true)!) : null,
    };
  }
  const { y, m, d } = todayInDisplayZone();
  const today = new Date(Date.UTC(y, m - 1, d));
  switch (period) {
    case "today":
      return bounds(dayStr(today), dayStr(today));
    case "week": {
      const monday = new Date(today);
      monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
      const sunday = new Date(monday);
      sunday.setUTCDate(sunday.getUTCDate() + 6);
      return bounds(dayStr(monday), dayStr(sunday));
    }
    case "month":
      return bounds(dayStr(new Date(Date.UTC(y, m - 1, 1))), dayStr(new Date(Date.UTC(y, m, 0))));
    case "quarter": {
      const qStart = Math.floor((m - 1) / 3) * 3;
      return bounds(dayStr(new Date(Date.UTC(y, qStart, 1))), dayStr(new Date(Date.UTC(y, qStart + 3, 0))));
    }
    case "year":
      return bounds(`${y}-01-01`, `${y}-12-31`);
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
