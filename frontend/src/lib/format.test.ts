import { describe, expect, it } from "vitest";
import { fmtUsd, fmtNum, pnlTone, toDecimal } from "./format";

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
