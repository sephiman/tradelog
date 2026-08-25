import Decimal from "decimal.js";
import { describe, expect, it } from "vitest";
import { gridRoi, gridVolume, grossFromNet, netFromGross, qtyFromNotional, realizedFromPrices } from "./entryMath";

const d = (v: string | number) => new Decimal(v);

describe("realizedFromPrices", () => {
  it("is the rise for a long and the fall for a short, times the quantity", () => {
    expect(realizedFromPrices("LONG", d(500), d("541.30"), d(1)).toString()).toBe("41.3");
    expect(realizedFromPrices("SHORT", d(60000), d(59000), d(2)).toString()).toBe("2000");
  });
});

describe("qtyFromNotional", () => {
  it("divides the notional by the entry price", () => {
    expect(qtyFromNotional(d(500), d(2000))!.toString()).toBe("0.25");
  });

  it("has no answer until an entry price is known", () => {
    expect(qtyFromNotional(d(500), d(0))).toBeNull();
  });
});

describe("net and gross grid PnL", () => {
  // The reference run: Realized PnL 26.87, trading fee 3.56, funding 0.15 → Total Profit 23.16.
  it("round-trips between the two figures a closed grid shows", () => {
    expect(netFromGross(d("26.87"), d("3.56"), d("0.15")).toString()).toBe("23.16");
    expect(grossFromNet(d("23.16"), d("3.56"), d("0.15")).toString()).toBe("26.87");
  });

  it("treats a rebate and received funding as raising the net", () => {
    expect(netFromGross(d(100), d(-2), d(-1)).toString()).toBe("103");
  });
});

describe("gridVolume", () => {
  it("counts both legs of every matched order", () => {
    // 40 matched orders of 0.5 base units around 250 → 40 × 0.5 × 250 × 2.
    expect(gridVolume(d(40), d("0.5"), d(250))!.toString()).toBe("10000");
  });

  it("has no answer while any of the three inputs is missing", () => {
    expect(gridVolume(d(0), d("0.5"), d(250))).toBeNull();
    expect(gridVolume(d(40), d(0), d(250))).toBeNull();
    expect(gridVolume(d(40), d("0.5"), d(0))).toBeNull();
  });
});

describe("gridRoi", () => {
  it("is the net result over the capital the grid was given, as a percentage", () => {
    expect(gridRoi(d("23.16"), d(250))!.toDecimalPlaces(2).toString()).toBe("9.26");
  });

  it("has no answer without an investment", () => {
    expect(gridRoi(d("23.16"), d(0))).toBeNull();
  });
});
