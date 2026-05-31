import type Decimal from "decimal.js";

export const DASH = "—";

/** Format a fraction (0–1) as a percentage. */
export const fmtPctFraction = (r: number | null, digits = 1): string => (r === null ? DASH : `${(r * 100).toFixed(digits)}%`);

/** Format an already-percentage value (0–100). */
export const fmtPctValue = (r: number | null, digits = 1): string => (r === null ? DASH : `${r.toFixed(digits)}%`);

/** A Decimal as a plain string for the formatters in lib/format, or null. */
export const decStr = (d: Decimal | null): string | null => (d === null ? null : d.toString());

/** Human duration from milliseconds, at most two units (e.g. "2d 3h", "45m", "12s"). */
export function fmtDuration(ms: number | null): string {
  if (ms === null) return DASH;
  const totalSec = Math.round(ms / 1000);
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const parts: string[] = [];
  if (d) parts.push(`${d}d`);
  if (h) parts.push(`${h}h`);
  if (m) parts.push(`${m}m`);
  if (s || parts.length === 0) parts.push(`${s}s`);
  return parts.slice(0, 2).join(" ");
}

export const WEEKDAY_KEYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
