import type { SourceKind } from "@/api/positions";

/** Per-kind facts the UI needs, mirroring the backend's `SourceKind`. Order = dropdown order. */
export const SOURCE_KINDS: readonly SourceKind[] = [
  "BITUNIX",
  "BINGX",
  "BITMART",
  "BINANCE_FUTURES",
  "BYBIT",
  "OKX",
  "BITGET",
  "BITGET_CLASSIC",
  "KRAKEN_FUTURES",
  "KRAKEN_SPOT",
  "GATEIO_FUTURES",
  "MEXC_FUTURES",
  "KUCOIN_FUTURES",
  "QUANTFURY",
  "JOURNAL_CSV",
];

/** The two sources with no API: updated by uploading a file, never by sync. */
const FILE_KINDS: ReadonlySet<SourceKind> = new Set<SourceKind>(["QUANTFURY", "JOURNAL_CSV"]);

/** REST API sources: they hold encrypted credentials and sync incrementally. */
export const isApiKind = (kind: SourceKind): boolean => !FILE_KINDS.has(kind);

/** Venues needing an API passphrase. Mirrors `SourceKind.requiresPassphrase`. */
const PASSPHRASE_KINDS: ReadonlySet<SourceKind> = new Set<SourceKind>(["OKX", "BITGET", "BITGET_CLASSIC", "KUCOIN_FUTURES"]);

export const needsPassphrase = (kind: SourceKind): boolean => PASSPHRASE_KINDS.has(kind);

/**
 * The platform each key comes from, which is why several say "Futures". NOT the backend's
 * `venueLabel` — that is the shorter venue identity capital and ROI group by ("Binance", "KuCoin").
 */
export const SOURCE_LABELS: Record<SourceKind, string> = {
  BITUNIX: "Bitunix",
  BINGX: "BingX",
  BITMART: "BitMart",
  BINANCE_FUTURES: "Binance Futures",
  BYBIT: "Bybit",
  OKX: "OKX",
  BITGET: "Bitget",
  BITGET_CLASSIC: "Bitget Classic",
  KRAKEN_FUTURES: "Kraken Futures",
  KRAKEN_SPOT: "Kraken Spot",
  GATEIO_FUTURES: "Gate.io Futures",
  MEXC_FUTURES: "MEXC Futures",
  KUCOIN_FUTURES: "KuCoin Futures",
  QUANTFURY: "Quantfury",
  JOURNAL_CSV: "Journal CSV",
};

/** Venues closing, as an ISO date. Mirrors `SourceKind.retiredAt`; nothing is ever deleted. */
const RETIRES_ON: Partial<Record<SourceKind, string>> = {
  BITMART: "2026-08-26",
};

export interface Retirement {
  /** ISO date the venue closes. */
  date: string;
  /** True once that date has passed: warn beforehand, state the fact afterwards. */
  closed: boolean;
}

export function retirementOf(kind: SourceKind, now: Date = new Date()): Retirement | null {
  const date = RETIRES_ON[kind];
  if (!date) return null;
  return { date, closed: now >= new Date(`${date}T00:00:00Z`) };
}
