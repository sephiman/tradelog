import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { NoActiveProfile } from "@/features/profiles/NoActiveProfile";
import {
  useBulkDeletePositions,
  useBulkSetTag,
  useClearTag,
  useDeletePosition,
  usePositionDetail,
  usePositionExchanges,
  usePositions,
  useSetNote,
  useSetTag,
  type Position,
  type PositionFilters,
} from "@/api/positions";
import { useTaxonomy, type TagGroup } from "@/api/taxonomy";
import { Badge, Button, Card, CardBody, Input, Select, Textarea } from "@/components/ui/primitives";
import { QueryError } from "@/components/ui/QueryError";
import { cn } from "@/lib/cn";
import { dateInputToIso, fmtDateTime, fmtNum, fmtUsd, isoToDateInput, pnlTone, toDecimal } from "@/lib/format";
import { showToast } from "@/lib/toastBus";

/** Sentinel option value for the origen filter that selects positions with no origen tag. */
const ORIGEN_UNSET = "__unset__";

export function PositionsPage() {
  const { t } = useTranslation();
  const { activeProfileId } = useActiveProfile();
  const [filters, setFilters] = useState<PositionFilters>({ sort: "closed_desc", page: 0, size: 50 });
  const { data, isLoading, isError, refetch } = usePositions(activeProfileId, filters);
  const { data: exchanges = [] } = usePositionExchanges(activeProfileId);
  const { data: taxonomy = [] } = useTaxonomy();
  const origen = useMemo(() => taxonomy.find((g) => g.code === "origen") ?? taxonomy[0], [taxonomy]);

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [allMatching, setAllMatching] = useState(false);
  const [bulkTagId, setBulkTagId] = useState("");
  const [confirmingBulkDelete, setConfirmingBulkDelete] = useState(false);
  const bulkSetTag = useBulkSetTag(activeProfileId ?? "");
  const bulkDelete = useBulkDeletePositions(activeProfileId ?? "");

  // If the current page no longer exists (e.g. switching to a profile with fewer
  // positions), snap back to the last available page instead of showing an empty one.
  useEffect(() => {
    if (!data) return;
    const maxPage = Math.max(0, Math.ceil((data.total ?? 0) / (data.size || 50)) - 1);
    if ((filters.page ?? 0) > maxPage) {
      setFilters((f) => ({ ...f, page: maxPage }));
    }
  }, [data, filters.page]);

  if (!activeProfileId) return <NoActiveProfile />;

  const clearSelection = () => {
    setSelected(new Set());
    setAllMatching(false);
    setBulkTagId("");
    setConfirmingBulkDelete(false);
  };

  // Selection is scoped to a filter set; changing filters invalidates it.
  const set = (patch: Partial<PositionFilters>) => {
    setFilters((f) => ({ ...f, ...patch, page: 0 }));
    clearSelection();
  };

  const page = data?.page ?? 0;
  const size = data?.size ?? filters.size ?? 50;
  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / size));
  const goToPage = (p: number) => setFilters((f) => ({ ...f, page: p }));

  const items = data?.items ?? [];
  const visibleIds = items.map((p) => p.id);
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selected.has(id));
  const someVisibleSelected = visibleIds.some((id) => selected.has(id));

  const toggleRow = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const toggleAllVisible = () =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (allVisibleSelected) visibleIds.forEach((id) => next.delete(id));
      else visibleIds.forEach((id) => next.add(id));
      return next;
    });

  const cleanFilters = (): PositionFilters => {
    const f: PositionFilters = {};
    if (filters.symbol) f.symbol = filters.symbol;
    if (filters.side) f.side = filters.side;
    if (filters.source) f.source = filters.source;
    if (filters.exchange) f.exchange = filters.exchange;
    if (filters.from) f.from = filters.from;
    if (filters.to) f.to = filters.to;
    if (filters.tagId) f.tagId = filters.tagId;
    if (filters.untaggedGroupId) f.untaggedGroupId = filters.untaggedGroupId;
    return f;
  };

  const selectionCount = allMatching ? total : selected.size;

  const applyBulk = () => {
    if (!origen || selectionCount === 0) return;
    const body = {
      tagId: bulkTagId === "" ? null : bulkTagId,
      ...(allMatching ? { filters: cleanFilters() } : { positionIds: [...selected] }),
    };
    bulkSetTag.mutate(
      { groupId: origen.id, body },
      {
        onSuccess: (r) => {
          showToast(t("positions.bulkUpdated", { count: r.updated }), "success");
          clearSelection();
        },
      },
    );
  };

  const applyBulkDelete = () => {
    if (selectionCount === 0) return;
    const body = allMatching ? { filters: cleanFilters() } : { positionIds: [...selected] };
    bulkDelete.mutate(body, {
      onSuccess: (r) => {
        showToast(t("positions.bulkDeleted", { count: r.deleted }), "success");
        clearSelection();
      },
    });
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t("positions.title")}</h1>

      <Card>
        <CardBody>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            <FilterField label={t("positions.symbol")}>
              <Input className="w-full" value={filters.symbol ?? ""} onChange={(e) => set({ symbol: e.target.value })} placeholder="BTC" />
            </FilterField>
            <FilterField label={t("positions.side")}>
              <Select className="w-full" value={filters.side ?? ""} onChange={(e) => set({ side: e.target.value as PositionFilters["side"] })}>
                <option value="">{t("common.all")}</option>
                <option value="LONG">{t("positions.long")}</option>
                <option value="SHORT">{t("positions.short")}</option>
              </Select>
            </FilterField>
            <FilterField label={t("positions.source")}>
              <Select className="w-full" value={filters.source ?? ""} onChange={(e) => set({ source: e.target.value as PositionFilters["source"] })}>
                <option value="">{t("common.all")}</option>
                <option value="BITUNIX">Bitunix</option>
                <option value="BINGX">BingX</option>
                <option value="QUANTFURY">Quantfury</option>
              </Select>
            </FilterField>
            {exchanges.length > 0 && (
              <FilterField label={t("positions.exchange")}>
                <Select className="w-full" value={filters.exchange ?? ""} onChange={(e) => set({ exchange: e.target.value })}>
                  <option value="">{t("positions.allExchanges")}</option>
                  {exchanges.map((ex) => (
                    <option key={ex} value={ex}>{ex}</option>
                  ))}
                </Select>
              </FilterField>
            )}
            <FilterField label={t("positions.from")}>
              <Input
                type="date"
                className="w-full"
                value={isoToDateInput(filters.from)}
                onChange={(e) => set({ from: dateInputToIso(e.target.value) })}
              />
            </FilterField>
            <FilterField label={t("positions.to")}>
              <Input
                type="date"
                className="w-full"
                value={isoToDateInput(filters.to)}
                onChange={(e) => set({ to: dateInputToIso(e.target.value, true) })}
              />
            </FilterField>
            {origen && origen.tags.length > 0 && (
              <FilterField label={t("positions.origen")}>
                <Select
                  className="w-full"
                  value={filters.untaggedGroupId ? ORIGEN_UNSET : filters.tagId ?? ""}
                  onChange={(e) => {
                    const v = e.target.value;
                    if (v === ORIGEN_UNSET) set({ tagId: undefined, untaggedGroupId: origen.id });
                    else set({ tagId: v || undefined, untaggedGroupId: undefined });
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  <option value={ORIGEN_UNSET}>{t("positions.origenUnset")}</option>
                  {origen.tags.map((tag) => (
                    <option key={tag.id} value={tag.id}>{tag.name}</option>
                  ))}
                </Select>
              </FilterField>
            )}
            <FilterField label={t("positions.sort")}>
              <Select className="w-full" value={filters.sort} onChange={(e) => set({ sort: e.target.value })}>
                <option value="closed_desc">{t("positions.closed")} ↓</option>
                <option value="closed_asc">{t("positions.closed")} ↑</option>
                <option value="pnl_desc">{t("positions.pnl")} ↓</option>
                <option value="pnl_asc">{t("positions.pnl")} ↑</option>
              </Select>
            </FilterField>
            <div className="col-span-full flex justify-end">
              <Button
                variant="ghost"
                onClick={() => {
                  setFilters({ sort: "closed_desc", page: 0, size: 50 });
                  clearSelection();
                }}
              >
                {t("positions.clearFilters")}
              </Button>
            </div>
          </div>
        </CardBody>
      </Card>

      <Card>
        {selectionCount > 0 && (
          <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3 text-sm dark:border-gray-700">
            <span className="font-medium">
              {allMatching
                ? t("positions.allMatchingSelected", { total })
                : t("positions.selected", { count: selected.size })}
            </span>
            {origen && (
              <div className="flex items-center gap-2">
                <span className="text-gray-500 dark:text-gray-400">{t("positions.bulkSetOrigen")}</span>
                <Select className="w-36" value={bulkTagId} onChange={(e) => setBulkTagId(e.target.value)}>
                  <option value="">{t("common.none")}</option>
                  {origen.tags.map((tag) => (
                    <option key={tag.id} value={tag.id}>{tag.name}</option>
                  ))}
                </Select>
                <Button variant="primary" disabled={bulkSetTag.isPending} onClick={applyBulk}>
                  {t("positions.apply")}
                </Button>
              </div>
            )}
            {confirmingBulkDelete ? (
              <div className="flex items-center gap-2">
                <span className="text-gray-600 dark:text-gray-300">
                  {t("positions.confirmBulkDelete", { count: selectionCount })}
                </span>
                <Button variant="danger" disabled={bulkDelete.isPending} onClick={applyBulkDelete}>
                  {t("common.delete")}
                </Button>
                <Button variant="ghost" onClick={() => setConfirmingBulkDelete(false)}>
                  {t("common.cancel")}
                </Button>
              </div>
            ) : (
              <Button variant="danger" onClick={() => setConfirmingBulkDelete(true)}>
                {t("positions.bulkDelete")}
              </Button>
            )}
            <Button variant="ghost" onClick={clearSelection}>{t("positions.clearSelection")}</Button>
          </div>
        )}
        {allVisibleSelected && !allMatching && total > visibleIds.length && (
          <div className="flex flex-wrap items-center justify-center gap-2 border-b border-border bg-primary/5 px-4 py-2 text-sm dark:border-gray-700">
            <span className="text-gray-600 dark:text-gray-300">
              {t("positions.pageSelected", { count: visibleIds.length })}
            </span>
            <button type="button" className="font-medium text-primary hover:underline" onClick={() => setAllMatching(true)}>
              {t("positions.selectAllMatching", { total })}
            </button>
          </div>
        )}
        <div className="hidden overflow-x-auto md:block">
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
              <tr>
                <th className="w-8 py-2">
                  <SelectAllCheckbox
                    checked={allMatching || allVisibleSelected}
                    indeterminate={!allMatching && someVisibleSelected && !allVisibleSelected}
                    disabled={allMatching || visibleIds.length === 0}
                    onChange={toggleAllVisible}
                  />
                </th>
                <th className="py-2">{t("positions.closed")}</th>
                <th>{t("positions.symbol")}</th>
                <th>{t("positions.side")}</th>
                <th>{t("positions.source")}</th>
                <th>{t("positions.exchange")}</th>
                <th className="text-right">{t("positions.qty")}</th>
                <th className="text-right">{t("positions.entry")}</th>
                <th className="text-right">{t("positions.exit")}</th>
                <th className="text-right">{t("positions.netPnl")}</th>
                <th>{t("positions.origen")}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {isError ? (
                <tr><td colSpan={12}><QueryError onRetry={() => void refetch()} /></td></tr>
              ) : isLoading ? (
                <tr><td colSpan={12} className="py-8 text-center text-gray-500">{t("common.loading")}</td></tr>
              ) : items.length === 0 ? (
                <tr><td colSpan={12} className="py-8 text-center text-gray-500 dark:text-gray-400">{t("positions.noPositions")}</td></tr>
              ) : (
                items.map((p) => (
                  <PositionRow
                    key={p.id}
                    profileId={activeProfileId}
                    position={p}
                    origen={origen}
                    selected={allMatching || selected.has(p.id)}
                    selectDisabled={allMatching}
                    onToggleSelect={() => toggleRow(p.id)}
                  />
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="md:hidden">
          {isError ? (
            <QueryError onRetry={() => void refetch()} />
          ) : isLoading ? (
            <div className="py-8 text-center text-gray-500">{t("common.loading")}</div>
          ) : items.length === 0 ? (
            <div className="py-8 text-center text-gray-500 dark:text-gray-400">{t("positions.noPositions")}</div>
          ) : (
            <>
              <label className="flex items-center gap-2 border-b border-border px-4 py-2 text-sm text-gray-600 dark:border-gray-700 dark:text-gray-300">
                <SelectAllCheckbox
                  checked={allMatching || allVisibleSelected}
                  indeterminate={!allMatching && someVisibleSelected && !allVisibleSelected}
                  disabled={allMatching || visibleIds.length === 0}
                  onChange={toggleAllVisible}
                />
                {t("positions.selectAll")}
              </label>
              <div className="divide-y divide-border dark:divide-gray-700">
                {items.map((p) => (
                  <PositionCard
                    key={p.id}
                    profileId={activeProfileId}
                    position={p}
                    origen={origen}
                    selected={allMatching || selected.has(p.id)}
                    selectDisabled={allMatching}
                    onToggleSelect={() => toggleRow(p.id)}
                  />
                ))}
              </div>
            </>
          )}
        </div>
        {total > 0 && (
          <div className="flex items-center justify-between gap-3 border-t border-border px-4 py-3 text-sm text-gray-600 dark:border-gray-700 dark:text-gray-300">
            <span className="tabular-nums">
              {t("positions.showing", {
                from: page * size + 1,
                to: Math.min((page + 1) * size, total),
                total,
              })}
            </span>
            <div className="flex items-center gap-2">
              <Button variant="ghost" disabled={page <= 0} onClick={() => goToPage(page - 1)}>
                {t("positions.prev")}
              </Button>
              <span className="tabular-nums">{page + 1} / {pageCount}</span>
              <Button variant="ghost" disabled={page + 1 >= pageCount} onClick={() => goToPage(page + 1)}>
                {t("positions.next")}
              </Button>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}

function SelectAllCheckbox({
  checked,
  indeterminate,
  disabled,
  onChange,
}: {
  checked: boolean;
  indeterminate: boolean;
  disabled: boolean;
  onChange: () => void;
}) {
  const { t } = useTranslation();
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate;
  }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className="h-4 w-4 cursor-pointer accent-primary"
      checked={checked}
      disabled={disabled}
      onChange={onChange}
      aria-label={t("positions.selectAll")}
    />
  );
}

function FilterField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{label}</span>
      {children}
    </label>
  );
}

interface RowProps {
  profileId: string;
  position: Position;
  origen?: TagGroup;
  selected: boolean;
  selectDisabled: boolean;
  onToggleSelect: () => void;
}

/** Current origen tag id for a position + a setter that upserts/clears the link. Shared by row and card. */
function useOrigenTag(profileId: string, position: Position, origen?: TagGroup) {
  const setTag = useSetTag(profileId);
  const clearTag = useClearTag(profileId);
  const currentTagId = origen ? position.tags.find((tg) => tg.groupId === origen.id)?.tagId ?? "" : "";
  const onTagChange = (tagId: string) => {
    if (!origen) return;
    if (tagId === "") clearTag.mutate({ positionId: position.id, groupId: origen.id });
    else setTag.mutate({ positionId: position.id, groupId: origen.id, tagId });
  };
  return { currentTagId, onTagChange };
}

function OrigenSelect({
  origen,
  value,
  onChange,
  className,
}: {
  origen: TagGroup;
  value: string;
  onChange: (tagId: string) => void;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <Select className={className} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t("common.none")}</option>
      {origen.tags.map((tag) => (
        <option key={tag.id} value={tag.id}>{tag.name}</option>
      ))}
    </Select>
  );
}

function PositionRow({ profileId, position, origen, selected, selectDisabled, onToggleSelect }: RowProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const { currentTagId, onTagChange } = useOrigenTag(profileId, position, origen);

  return (
    <>
      <tr className={cn("border-b border-border last:border-0 dark:border-gray-700", selected && "bg-primary/5")}>
        <td className="py-2 text-center">
          <input
            type="checkbox"
            className="h-4 w-4 cursor-pointer accent-primary"
            checked={selected}
            disabled={selectDisabled}
            onChange={onToggleSelect}
            aria-label={t("positions.selectRow")}
          />
        </td>
        <td className="whitespace-nowrap py-2 text-gray-600 dark:text-gray-300">{fmtDateTime(position.closedAt)}</td>
        <td className="font-medium">{position.symbolBase}/{position.symbolQuote}</td>
        <td>
          <Badge tone={position.side === "LONG" ? "green" : "red"}>
            {position.side === "LONG" ? t("positions.long") : t("positions.short")}
          </Badge>
        </td>
        <td className="text-gray-500 dark:text-gray-400">{position.source}</td>
        <td className="text-gray-600 dark:text-gray-300">{position.exchange ?? "—"}</td>
        <td className="text-right tabular-nums">{fmtNum(position.qty)}</td>
        <td className="text-right tabular-nums">{fmtNum(position.entryPrice)}</td>
        <td className="text-right tabular-nums">{fmtNum(position.exitPrice)}</td>
        <td className={cn("text-right font-medium tabular-nums", pnlTone(position.netPnl))}>
          {fmtUsd(position.netPnl, { sign: true })}
        </td>
        <td>
          {origen && <OrigenSelect origen={origen} value={currentTagId} onChange={onTagChange} className="w-32" />}
        </td>
        <td className="text-right">
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            aria-label={t("positions.toggleDetails")}
            aria-expanded={expanded}
            className="px-2 text-gray-500 hover:text-primary"
          >
            {position.note ? "📝" : ""} {expanded ? "▲" : "▼"}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="bg-gray-50 dark:bg-gray-900/40">
          <td colSpan={12} className="p-4">
            <ExpandedPanel profileId={profileId} position={position} />
          </td>
        </tr>
      )}
    </>
  );
}

function PositionCard({ profileId, position, origen, selected, selectDisabled, onToggleSelect }: RowProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const { currentTagId, onTagChange } = useOrigenTag(profileId, position, origen);

  return (
    <div className={cn("px-4 py-3", selected && "bg-primary/5")}>
      <div className="flex items-start gap-3">
        <input
          type="checkbox"
          className="mt-1 h-4 w-4 shrink-0 cursor-pointer accent-primary"
          checked={selected}
          disabled={selectDisabled}
          onChange={onToggleSelect}
          aria-label={t("positions.selectRow")}
        />
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="font-medium">{position.symbolBase}/{position.symbolQuote}</span>
              <Badge tone={position.side === "LONG" ? "green" : "red"}>
                {position.side === "LONG" ? t("positions.long") : t("positions.short")}
              </Badge>
            </div>
            <span className={cn("font-medium tabular-nums", pnlTone(position.netPnl))}>
              {fmtUsd(position.netPnl, { sign: true })}
            </span>
          </div>
          <div className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {fmtDateTime(position.closedAt)} · {position.source}
            {position.exchange ? ` · ${position.exchange}` : ""}
          </div>
          <div className="mt-1 text-xs tabular-nums text-gray-500 dark:text-gray-400">
            {t("positions.qty")}: {fmtNum(position.qty)} · {t("positions.entry")}: {fmtNum(position.entryPrice)} ·{" "}
            {t("positions.exit")}: {fmtNum(position.exitPrice)}
          </div>
          <div className="mt-2 flex items-center justify-between gap-2">
            {origen ? (
              <OrigenSelect origen={origen} value={currentTagId} onChange={onTagChange} className="w-40" />
            ) : (
              <span />
            )}
            <button
              type="button"
              onClick={() => setExpanded((v) => !v)}
              aria-label={t("positions.toggleDetails")}
              aria-expanded={expanded}
              className="shrink-0 px-2 text-gray-500 hover:text-primary"
            >
              {position.note ? "📝" : ""} {expanded ? "▲" : "▼"}
            </button>
          </div>
        </div>
      </div>
      {expanded && (
        <div className="mt-3 rounded-md bg-gray-50 p-3 dark:bg-gray-900/40">
          <ExpandedPanel profileId={profileId} position={position} />
        </div>
      )}
    </div>
  );
}

function ExpandedPanel({ profileId, position }: { profileId: string; position: Position }) {
  const { t } = useTranslation();
  const { data: detail, isLoading } = usePositionDetail(profileId, position.id);
  const setNote = useSetNote(profileId);
  const deletePosition = useDeletePosition(profileId);
  const [note, setNote_] = useState(position.note ?? "");
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  return (
    <div className="grid gap-6 md:grid-cols-2">
      <div className="min-w-0">
        <h3 className="mb-2 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">{t("positions.operations")}</h3>
        {isLoading ? (
          <p className="text-sm text-gray-500">{t("common.loading")}</p>
        ) : (detail?.fills.length ?? 0) === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">—</p>
        ) : (
          <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="text-left text-gray-500 dark:text-gray-400">
              <tr>
                <th>{t("positions.action")}</th>
                <th>{t("positions.time")}</th>
                <th className="text-right">{t("positions.price")}</th>
                <th className="text-right">{t("positions.qty")}</th>
                <th className="text-right">{t("positions.value")}</th>
              </tr>
            </thead>
            <tbody>
              {detail!.fills.map((f) => (
                <tr key={f.seq} className="border-t border-border dark:border-gray-700">
                  <td><Badge tone={f.side === "BUY" ? "green" : "red"}>{f.action}</Badge></td>
                  <td className="whitespace-nowrap text-gray-600 dark:text-gray-300">{fmtDateTime(f.ts)}</td>
                  <td className="text-right tabular-nums">{fmtNum(f.price)}</td>
                  <td className="text-right tabular-nums">{fmtNum(f.qty)}</td>
                  <td className="text-right tabular-nums">{f.value ? fmtUsd(f.value) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
        <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-gray-500 dark:text-gray-400">
          <span>{t("positions.volume")}: {fmtUsd(toDecimal(position.entryPrice).mul(toDecimal(position.qty)).toString())}</span>
          <span>{t("positions.grossPnl")}: {fmtUsd(position.realizedPnl, { sign: true })}</span>
          <span>{t("positions.fees")}: {fmtUsd(position.fees)}</span>
          <span>{t("positions.funding")}: {fmtUsd(position.funding)}</span>
          <span>{t("positions.netPnl")}: {fmtUsd(position.netPnl, { sign: true })}</span>
        </div>
      </div>

      <div className="min-w-0">
        <h3 className="mb-2 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">{t("positions.note")}</h3>
        <Textarea className="w-full" rows={4} value={note} onChange={(e) => setNote_(e.target.value)} placeholder={t("positions.addNote")} />
        <Button
          className="mt-2"
          variant="secondary"
          disabled={setNote.isPending}
          onClick={() => setNote.mutate({ positionId: position.id, note: note.trim() || null })}
        >
          {t("common.save")}
        </Button>
      </div>

      <div className="flex flex-col gap-2 border-t border-border pt-3 md:col-span-2 md:flex-row md:items-center md:justify-end dark:border-gray-700">
        {confirmingDelete ? (
          <>
            <span className="text-sm text-gray-600 dark:text-gray-300">{t("positions.confirmDelete")}</span>
            <div className="flex items-center gap-2">
              <Button
                variant="danger"
                disabled={deletePosition.isPending}
                onClick={() =>
                  deletePosition.mutate(position.id, {
                    onSuccess: () => showToast(t("positions.deleted"), "success"),
                  })
                }
              >
                {t("common.delete")}
              </Button>
              <Button variant="ghost" onClick={() => setConfirmingDelete(false)}>
                {t("common.cancel")}
              </Button>
            </div>
          </>
        ) : (
          <Button variant="danger" onClick={() => setConfirmingDelete(true)}>
            {t("positions.delete")}
          </Button>
        )}
      </div>
    </div>
  );
}
