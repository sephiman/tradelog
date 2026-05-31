import Decimal from "decimal.js";
import { format, parseISO } from "date-fns";

/** Parse a backend decimal-as-string safely. */
export function toDecimal(value: string | number | null | undefined): Decimal {
  if (value === null || value === undefined || value === "") return new Decimal(0);
  try {
    return new Decimal(value);
  } catch {
    return new Decimal(0);
  }
}

/** USDT amount, 2 dp, grouped. */
export function fmtUsd(value: string | number, opts: { sign?: boolean } = {}): string {
  const d = toDecimal(value);
  const base = d.toNumber().toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (opts.sign && d.gt(0)) return `+${base}`;
  return base;
}

/** Price/quantity with up to [maxFrac] decimals, trailing zeros trimmed. */
export function fmtNum(value: string | number, maxFrac = 8): string {
  const d = toDecimal(value);
  return d.toDecimalPlaces(maxFrac).toNumber().toLocaleString(undefined, { maximumFractionDigits: maxFrac });
}

export function fmtDateTime(iso: string): string {
  try {
    return format(parseISO(iso), "yyyy-MM-dd HH:mm");
  } catch {
    return iso;
  }
}

export function fmtDate(iso: string): string {
  try {
    return format(parseISO(iso), "yyyy-MM-dd");
  } catch {
    return iso;
  }
}

/** A date input value ("YYYY-MM-DD") → ISO instant at local start (or end) of that day. */
export function dateInputToIso(dateStr: string, endOfDay = false): string | undefined {
  if (!dateStr) return undefined;
  const d = new Date(`${dateStr}T${endOfDay ? "23:59:59.999" : "00:00:00.000"}`);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** ISO instant → "YYYY-MM-DD" in local time, for binding a date input's value. */
export function isoToDateInput(iso?: string): string {
  if (!iso) return "";
  try {
    return format(parseISO(iso), "yyyy-MM-dd");
  } catch {
    return "";
  }
}

/** Tailwind text-color class for a signed PnL value. */
export function pnlTone(value: string | number): string {
  const d = toDecimal(value);
  if (d.gt(0)) return "text-green-600 dark:text-green-400";
  if (d.lt(0)) return "text-red-600 dark:text-red-400";
  return "text-gray-500 dark:text-gray-400";
}
