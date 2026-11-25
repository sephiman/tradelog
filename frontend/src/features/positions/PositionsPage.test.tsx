import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Position } from "@/api/positions";
import type { Tag, TagGroup } from "@/api/taxonomy";
import i18n from "@/i18n";
import { PositionsPage } from "./PositionsPage";

const mockUsePositions = vi.fn();
const mockUseTaxonomy = vi.fn<() => { data: TagGroup[] }>(() => ({ data: [] }));
const mockUsePositionDetail = vi.fn(() => ({ data: undefined as unknown, isLoading: false }));
const mockDownloadCsv = vi.fn<(profileId: string, filters: unknown) => Promise<void>>(() => Promise.resolve());

vi.mock("@/features/profiles/ActiveProfile", () => ({
  useActiveProfile: () => ({ activeProfileId: "prof-1" }),
}));

vi.mock("@/api/taxonomy", () => ({
  isArchived: (tag: Tag) => tag.archivedAt !== null,
  useTaxonomy: () => mockUseTaxonomy(),
}));

vi.mock("@/api/positions", () => ({
  downloadPositionsCsv: (profileId: string, filters: unknown) => mockDownloadCsv(profileId, filters),
  usePositions: (...args: unknown[]) => mockUsePositions(...args),
  usePositionExchanges: () => ({ data: [] }),
  useBulkSetTag: () => ({ mutate: vi.fn(), isPending: false }),
  useBulkDeletePositions: () => ({ mutate: vi.fn(), isPending: false }),
  useSetTag: () => ({ mutate: vi.fn() }),
  useClearTag: () => ({ mutate: vi.fn() }),
  useSetNote: () => ({ mutate: vi.fn(), isPending: false }),
  useDeletePosition: () => ({ mutate: vi.fn(), isPending: false }),
  usePositionDetail: () => mockUsePositionDetail(),
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
  mockUseTaxonomy.mockReturnValue({ data: [] });
  mockUsePositionDetail.mockReturnValue({ data: undefined, isLoading: false });
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

describe("PositionsPage row layout flexibility", () => {
  it("excludes unused archived origen tags from the width sizer while keeping active and used archived tags", () => {
    mockUseTaxonomy.mockReturnValue({
      data: [
        {
          id: "g-origen",
          code: "origen",
          name: "Origen",
          sortOrder: 1,
          tags: [
            { id: "t-own", code: "own", name: "Own", sortOrder: 1, archivedAt: null },
            { id: "t-old-unused", code: "old_unused", name: "Very Long Unused Archived Tag", sortOrder: 2, archivedAt: "2026-01-01T00:00:00Z" },
            { id: "t-old-used", code: "old_used", name: "Old Used Tag", sortOrder: 3, archivedAt: "2026-01-01T00:00:00Z" },
          ],
        },
      ],
    });

    const p = makePosition("p1", "2026-07-10T00:00:00Z", "2026-07-11T03:02:30Z");
    p.tags = [{ groupId: "g-origen", groupCode: "origen", groupName: "Origen", tagId: "t-old-used", tagName: "Old Used Tag" }];
    const { container } = renderPage([p]);

    const sizer = container.querySelector("select[aria-hidden='true']");
    expect(sizer).not.toBeNull();
    const sizerOptions = Array.from(sizer!.querySelectorAll("option")).map((o) => o.textContent);
    expect(sizerOptions).toContain("Own");
    expect(sizerOptions).toContain("Old Used Tag (archived)");
    expect(sizerOptions).not.toContain("Very Long Unused Archived Tag (archived)");

    // Expand toggle cell is not pinned sticky right-0 where it would overlap the origen select.
    const table = screen.getByRole("table");
    const toggleBtn = within(table).getByRole("button", { name: "Show details" });
    expect(toggleBtn.closest("td")).not.toHaveClass("sticky");
  });

  it("keeps the expanded operations Quantity header visible to match its cells", () => {
    mockUsePositionDetail.mockReturnValue({
      data: {
        ...makePosition("p1", "2026-07-10T00:00:00Z", "2026-07-11T03:02:30Z"),
        fills: [{ seq: 1, action: "OPEN", side: "BUY", ts: "2026-07-10T00:00:00Z", price: "50000", qty: "0.5", value: "25000" }],
      },
      isLoading: false,
    });

    renderPage([makePosition("p1", "2026-07-10T00:00:00Z", "2026-07-11T03:02:30Z")]);
    const mainTable = screen.getByRole("table");
    fireEvent.click(within(mainTable).getByRole("button", { name: "Show details" }));

    const tables = within(mainTable).getAllByRole("table");
    expect(tables).toHaveLength(1);
    const qtyHeader = within(tables[0]).getByRole("columnheader", { name: "Quantity" });
    expect(qtyHeader).not.toHaveClass("hidden");
  });
});

describe("PositionsPage CSV export", () => {
  it("exports with the filters currently applied", () => {
    renderPage([makePosition("p1", "2026-03-01T10:00:00Z", "2026-03-01T12:00:00Z")]);
    fireEvent.change(screen.getByPlaceholderText("BTC"), { target: { value: "ETH" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Side" }), { target: { value: "SHORT" } });

    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    expect(mockDownloadCsv).toHaveBeenCalledWith(
      "prof-1",
      expect.objectContaining({ symbol: "ETH", side: "SHORT", sort: "closed_desc" }),
    );
  });

  it("disables export when no position matches", () => {
    renderPage([]);
    expect(screen.getByRole("button", { name: "Export CSV" })).toBeDisabled();
  });
});
