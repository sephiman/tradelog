import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import "@/i18n";
import type { ClosedPosition } from "@/api/analytics";
import { ThemeProvider } from "@/lib/theme";
import { TraderStyleCard } from "./BehaviorView";

const MINUTE = 60_000;

function closed(netPnl: number, holdMs: number): ClosedPosition {
  return {
    id: crypto.randomUUID(),
    source: "BITUNIX",
    exchange: "Bitunix",
    symbolBase: "BTC",
    symbolQuote: "USDT",
    side: "LONG",
    openedAt: new Date(0).toISOString(),
    closedAt: new Date(holdMs).toISOString(),
    qty: "0",
    entryPrice: "0",
    exitPrice: "0",
    realizedPnl: "0",
    netPnl: String(netPnl),
    fees: "0",
    funding: "0",
    volume: null,
    tags: [],
  };
}

const winRateIn = (block: HTMLElement) =>
  within(block).getByText("Win rate").nextElementSibling?.textContent;

describe("TraderStyleCard", () => {
  it("shows each style's win rate, excluding breakeven and dashing styles with no decided trades", () => {
    render(
      <ThemeProvider>
        <TraderStyleCard
          rows={[
            closed(5, MINUTE), // scalper win
            closed(-1, MINUTE), // scalper loss
            closed(0, MINUTE), // scalper breakeven
            closed(3, 60 * MINUTE), // day win
            closed(-2, 60 * MINUTE), // day loss
            closed(-2, 2 * 60 * MINUTE), // day loss
            closed(0, 48 * 60 * MINUTE), // swing breakeven only
          ]}
        />
      </ThemeProvider>,
    );

    expect(
      winRateIn(
        screen.getByText("Scalper", { selector: "div" }).parentElement!,
      ),
    ).toBe("50.0%");
    expect(
      winRateIn(screen.getByText("Swing", { selector: "div" }).parentElement!),
    ).toBe("—");
    expect(
      screen.getByText(/^Day trader: 3 trades · 42\.9% · win rate 33\.3%$/),
    ).toBeInTheDocument();
  });
});
