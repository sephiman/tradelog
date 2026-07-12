import Decimal from "decimal.js";

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

// ---------------------------------------------------------------------------
// Dates render in the account's timezone (the user's saved setting), not the browser's, so the
// positions list and date filters attribute trades to the same day as the analytics dashboard.
// AuthContext keeps this in sync; the browser zone is only the pre-login fallback.
let displayZone: string = Intl.DateTimeFormat().resolvedOptions().timeZone;

export function setDisplayTimeZone(zone: string | null | undefined): void {
  displayZone = zone || Intl.DateTimeFormat().resolvedOptions().timeZone;
}

function zonedParts(at: Date): Record<string, string> {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone: displayZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
  const p = Object.fromEntries(dtf.formatToParts(at).map((x) => [x.type, x.value]));
  if (p.hour === "24") p.hour = "00"; // some runtimes render midnight as 24 with hour12:false
  return p;
}

export function fmtDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = zonedParts(d);
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`;
}

export function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = zonedParts(d);
  return `${p.year}-${p.month}-${p.day}`;
}

/** Offset (ms) between the display zone's wall clock and UTC at the given instant. */
function zoneOffsetMs(at: Date): number {
  const p = zonedParts(at);
  const asUtc = Date.UTC(
    Number(p.year), Number(p.month) - 1, Number(p.day),
    Number(p.hour), Number(p.minute), Number(p.second),
  );
  return asUtc - at.getTime();
}

/** A date input value ("YYYY-MM-DD") → ISO instant at start (or end) of that day in the display zone. */
export function dateInputToIso(dateStr: string, endOfDay = false): string | undefined {
  if (!dateStr) return undefined;
  // Offset math runs at whole-second precision (Intl parts carry no milliseconds); the end-of-day
  // .999 ms is appended after the zone conversion.
  const wall = new Date(`${dateStr}T${endOfDay ? "23:59:59" : "00:00:00"}Z`);
  if (Number.isNaN(wall.getTime())) return undefined;
  // Interpret that wall-clock time in the display zone: subtract the zone offset, refining once
  // so a DST transition on the target day still resolves to the correct instant.
  let ts = wall.getTime() - zoneOffsetMs(wall);
  ts = wall.getTime() - zoneOffsetMs(new Date(ts));
  return new Date(ts + (endOfDay ? 999 : 0)).toISOString();
}

/** Today's calendar date in the display zone. */
export function todayInDisplayZone(): { y: number; m: number; d: number } {
  const p = zonedParts(new Date());
  return { y: Number(p.year), m: Number(p.month), d: Number(p.day) };
}

/** ISO instant → "YYYY-MM-DD" in the display zone, for binding a date input's value. */
export function isoToDateInput(iso?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p = zonedParts(d);
  return `${p.year}-${p.month}-${p.day}`;
}

/** Tailwind text-color class for a signed PnL value. */
export function pnlTone(value: string | number): string {
  const d = toDecimal(value);
  if (d.gt(0)) return "text-green-600 dark:text-green-400";
  if (d.lt(0)) return "text-red-600 dark:text-red-400";
  return "text-gray-500 dark:text-gray-400";
}
