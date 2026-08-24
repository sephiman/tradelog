import { afterEach, describe, expect, it } from "vitest";
import {
  dateInputToIso,
  fmtDate,
  fmtDateTime,
  fmtNum,
  fmtPrice,
  fmtUsd,
  dateTimeInputToIso,
  isoToDateInput,
  isoToDateTimeInput,
  pnlTone,
  setDisplayTimeZone,
  toDecimal,
} from "./format";

// Number separators follow the runtime locale, so assertions stay separator-agnostic.
describe("format", () => {
  it("formats USDT with two decimals", () => {
    expect(fmtUsd("2.8")).toMatch(/^2[.,]80$/);
    expect(fmtUsd("1234.5")).toMatch(/^1[.,]?234[.,]50$/);
  });

  it("adds a sign for positive values when requested", () => {
    expect(fmtUsd("2.81", { sign: true }).startsWith("+")).toBe(true);
    expect(fmtUsd("-4.70", { sign: true }).startsWith("-")).toBe(true);
    expect(fmtUsd("2.81").startsWith("+")).toBe(false);
  });

  it("trims trailing zeros for quantities", () => {
    expect(fmtNum("0.12300000")).toMatch(/^0[.,]123$/);
  });

  it("scales price precision to magnitude so one row cannot widen a column", () => {
    // The reported case: a full-precision BTC exit price next to rounded ones.
    expect(fmtPrice("77027.75197434")).toMatch(/^77[.,]027[.,]75$/);
    expect(fmtPrice("541.3")).toMatch(/^541[.,]30$/);
    expect(fmtPrice("1.23456789")).toMatch(/^1[.,]2346$/);
    // Sub-unit prices keep the decimals they actually need.
    expect(fmtPrice("0.00001234")).toMatch(/^0[.,]00001234$/);
    expect(fmtPrice("-77027.75197434")).toMatch(/^-77[.,]027[.,]75$/);
  });

  it("parses decimals defensively", () => {
    expect(toDecimal(null).toNumber()).toBe(0);
    expect(toDecimal("abc").toNumber()).toBe(0);
    expect(toDecimal("12.5").toNumber()).toBe(12.5);
  });

  it("tones reflect sign", () => {
    expect(pnlTone("5")).toContain("green");
    expect(pnlTone("-5")).toContain("red");
    expect(pnlTone("0")).toContain("gray");
  });
});

describe("dates in the account timezone", () => {
  afterEach(() => setDisplayTimeZone(undefined));

  it("renders dates and datetimes in the display zone", () => {
    setDisplayTimeZone("Asia/Tokyo");
    // 23:30 UTC on the 12th is 08:30 on the 13th in Tokyo.
    expect(fmtDateTime("2026-07-12T23:30:00Z")).toBe("2026-07-13 08:30");
    expect(fmtDate("2026-07-12T23:30:00Z")).toBe("2026-07-13");

    setDisplayTimeZone("UTC");
    expect(fmtDateTime("2026-07-12T23:30:00Z")).toBe("2026-07-12 23:30");
  });

  it("builds date-filter bounds in the display zone", () => {
    setDisplayTimeZone("Asia/Tokyo"); // UTC+9, no DST
    expect(dateInputToIso("2026-07-12")).toBe("2026-07-11T15:00:00.000Z");
    expect(dateInputToIso("2026-07-12", true)).toBe("2026-07-12T14:59:59.999Z");
    // A trade at 23:30 UTC on the 12th belongs to July 13 in Tokyo — the July-12 filter excludes it.
    const to = dateInputToIso("2026-07-12", true)!;
    expect("2026-07-12T23:30:00.000Z" > to).toBe(true);
  });

  it("handles DST transitions when resolving day bounds", () => {
    setDisplayTimeZone("Europe/Madrid");
    // DST starts 2026-03-29 in Madrid: midnight is still CET (+1).
    expect(dateInputToIso("2026-03-29")).toBe("2026-03-28T23:00:00.000Z");
    // End of that day is CEST (+2).
    expect(dateInputToIso("2026-03-29", true)).toBe("2026-03-29T21:59:59.999Z");
  });

  it("reads a datetime input in the display zone, DST included", () => {
    setDisplayTimeZone("Asia/Tokyo"); // UTC+9, no DST
    expect(dateTimeInputToIso("2026-07-13T08:30")).toBe("2026-07-12T23:30:00.000Z");

    setDisplayTimeZone("Europe/Madrid");
    // 01:30 on 2026-03-29 is still CET (+1); 03:30 the same morning is already CEST (+2).
    expect(dateTimeInputToIso("2026-03-29T01:30")).toBe("2026-03-29T00:30:00.000Z");
    expect(dateTimeInputToIso("2026-03-29T03:30")).toBe("2026-03-29T01:30:00.000Z");

    expect(dateTimeInputToIso("")).toBeUndefined();
  });

  it("round-trips a datetime through the display zone", () => {
    setDisplayTimeZone("America/New_York");
    const iso = "2026-11-02T14:45:00.000Z";
    expect(dateTimeInputToIso(isoToDateTimeInput(iso))).toBe(iso);
    expect(isoToDateTimeInput(undefined)).toBe("");
  });

  it("round-trips date input values through the display zone", () => {
    setDisplayTimeZone("America/New_York");
    expect(isoToDateInput(dateInputToIso("2026-01-15"))).toBe("2026-01-15");
    expect(isoToDateInput(dateInputToIso("2026-07-15", true))).toBe("2026-07-15");
  });

  it("is defensive about garbage input", () => {
    expect(fmtDateTime("not-a-date")).toBe("not-a-date");
    expect(dateInputToIso("")).toBeUndefined();
    expect(isoToDateInput("garbage")).toBe("");
  });
});
