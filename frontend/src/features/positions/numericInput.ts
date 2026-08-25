import Decimal from "decimal.js";
import { toDecimal } from "@/lib/format";

/**
 * Decimal-text handling shared by the manual-entry forms. `toDecimal` falls back to 0 on anything
 * unparseable, so validity is always checked before parsing. The comma decimal mark is accepted
 * here just as the CSV import accepts it.
 */
const NUMERIC = /^-?\d+([.]\d+)?$/;

export const normalize = (raw: string) => raw.trim().replace(",", ".");

export const isNum = (raw: string) => NUMERIC.test(normalize(raw));

export const num = (raw: string): Decimal => toDecimal(isNum(raw) ? normalize(raw) : "0");

/** The value only when the field actually holds a number, for fields where blank ≠ zero. */
export const optionalNum = (raw: string): Decimal | null => (isNum(raw) ? num(raw) : null);
