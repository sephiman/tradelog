import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Position } from "@/api/positions";
import i18n from "@/i18n";
import { PositionsPage } from "./PositionsPage";

const mockUsePositions = vi.fn();

vi.mock("@/features/profiles/ActiveProfile", () => ({
  useActiveProfile: () => ({ activeProfileId: "prof-1" }),
}));

vi.mock("@/api/taxonomy", () => ({
  isArchived: () => false,
  useTaxonomy: () => ({ data: [] }),
}));

vi.mock("@/api/positions", () => ({
  usePositions: (...args: unknown[]) => mockUsePositions(...args),
  usePositionExchanges: () => ({ data: [] }),
  useBulkSetTag: () => ({ mutate: vi.fn(), isPending: false }),
  useBulkDeletePositions: () => ({ mutate: vi.fn(), isPending: false }),
  useSetTag: () => ({ mutate: vi.fn() }),
  useClearTag: () => ({ mutate: vi.fn() }),
  useSetNote: () => ({ mutate: vi.fn(), isPending: false }),
  useDeletePosition: () => ({ mutate: vi.fn(), isPending: false }),
  usePositionDetail: () => ({ data: undefined, isLoading: false }),
}));

function makePosition(id: string, openedAt: string, closedAt: string, fillCount = 2): Position {
  return {
    id,
    source: "BINANCE_FUTURES",
    exchange: "Binance",
    symbolBase: "BTC",
    symbolQuote: "USDT",
    side: "LONG",
    openedAt,
    closedAt,
    kind: "TRADE",
    qty: "1",
    entryPrice: "50000",
    exitPrice: "50500",
    realizedPnl: "500",
    netPnl: "495",
    fees: "5",
    funding: "0",
    pnlCurrency: "USDT",
    volume: null,
    leverage: null,
    investment: null,
    note: null,
    tags: [],
    fillCount,
    manual: false,
    edited: false,
  };
}

function renderPage(items: Position[]) {
  mockUsePositions.mockReturnValue({
    data: { items, page: 0, size: 50, total: items.length },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  });
  const qc = new QueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <PositionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("PositionsPage duration column", () => {
  it("renders trade duration from openedAt to closedAt in table and cards", () => {
    renderPage([
      makePosition("p1", "2026-07-10T00:00:00Z", "2026-07-11T03:02:30Z", 5),
      makePosition("p2", "2026-07-11T10:00:00Z", "2026-07-11T13:05:00Z"),
      makePosition("p3", "2026-07-11T14:00:00Z", "2026-07-11T14:06:15Z"),
      makePosition("p4", "2026-07-11T15:00:00Z", "2026-07-11T15:00:42Z"),
    ]);

    const table = screen.getByRole("table");
    expect(within(table).getByRole("columnheader", { name: "Duration" })).toBeInTheDocument();

    const rows = within(table).getAllByRole("row").slice(1);
    expect(within(rows[0]).getByText("1d3h2m")).toBeInTheDocument();
    expect(within(rows[1]).getByText("3h5m")).toBeInTheDocument();
    expect(within(rows[2]).getByText("6m")).toBeInTheDocument();
    expect(within(rows[3]).getByText("<1m")).toBeInTheDocument();

    // Mobile cards also display each formatted duration.
    expect(screen.getAllByText("1d3h2m")).toHaveLength(2);
    expect(screen.getAllByText("3h5m")).toHaveLength(2);
    expect(screen.getAllByText("6m")).toHaveLength(2);
    expect(screen.getAllByText("<1m")).toHaveLength(2);
  });
});
