import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { NoActiveProfile } from "@/features/profiles/NoActiveProfile";
import {
  useClearTag,
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
import { cn } from "@/lib/cn";
import { fmtDateTime, fmtNum, fmtUsd, pnlTone } from "@/lib/format";

export function PositionsPage() {
  const { t } = useTranslation();
  const { activeProfileId } = useActiveProfile();
  const [filters, setFilters] = useState<PositionFilters>({ sort: "closed_desc", page: 0, size: 50 });
  const { data, isLoading } = usePositions(activeProfileId, filters);
  const { data: exchanges = [] } = usePositionExchanges(activeProfileId);
  const { data: taxonomy = [] } = useTaxonomy();
  const origen = useMemo(() => taxonomy.find((g) => g.code === "origen") ?? taxonomy[0], [taxonomy]);

  if (!activeProfileId) return <NoActiveProfile />;

  const set = (patch: Partial<PositionFilters>) => setFilters((f) => ({ ...f, ...patch, page: 0 }));

  const page = data?.page ?? 0;
  const size = data?.size ?? filters.size ?? 50;
  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / size));
  const goToPage = (p: number) => setFilters((f) => ({ ...f, page: p }));

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t("positions.title")}</h1>

      <Card>
        <CardBody className="flex flex-wrap items-end gap-3">
          <FilterField label={t("positions.symbol")}>
            <Input className="w-32" value={filters.symbol ?? ""} onChange={(e) => set({ symbol: e.target.value })} placeholder="BTC" />
          </FilterField>
          <FilterField label={t("positions.side")}>
            <Select className="w-28" value={filters.side ?? ""} onChange={(e) => set({ side: e.target.value as PositionFilters["side"] })}>
              <option value="">{t("common.all")}</option>
              <option value="LONG">{t("positions.long")}</option>
              <option value="SHORT">{t("positions.short")}</option>
            </Select>
          </FilterField>
          <FilterField label={t("positions.source")}>
            <Select className="w-32" value={filters.source ?? ""} onChange={(e) => set({ source: e.target.value as PositionFilters["source"] })}>
              <option value="">{t("common.all")}</option>
              <option value="BITUNIX">Bitunix</option>
              <option value="BINGX">BingX</option>
              <option value="QUANTFURY">Quantfury</option>
            </Select>
          </FilterField>
          {exchanges.length > 0 && (
            <FilterField label={t("positions.exchange")}>
              <Select className="w-36" value={filters.exchange ?? ""} onChange={(e) => set({ exchange: e.target.value })}>
                <option value="">{t("positions.allExchanges")}</option>
                {exchanges.map((ex) => (
                  <option key={ex} value={ex}>{ex}</option>
                ))}
              </Select>
            </FilterField>
          )}
          {origen && origen.tags.length > 0 && (
            <FilterField label={t("positions.origen")}>
              <Select className="w-36" value={filters.tagId ?? ""} onChange={(e) => set({ tagId: e.target.value })}>
                <option value="">{t("common.all")}</option>
                {origen.tags.map((tag) => (
                  <option key={tag.id} value={tag.id}>{tag.name}</option>
                ))}
              </Select>
            </FilterField>
          )}
          <FilterField label={t("positions.sort")}>
            <Select className="w-40" value={filters.sort} onChange={(e) => set({ sort: e.target.value })}>
              <option value="closed_desc">{t("positions.closed")} ↓</option>
              <option value="closed_asc">{t("positions.closed")} ↑</option>
              <option value="pnl_desc">{t("positions.pnl")} ↓</option>
              <option value="pnl_asc">{t("positions.pnl")} ↑</option>
            </Select>
          </FilterField>
          <Button variant="ghost" onClick={() => setFilters({ sort: "closed_desc", page: 0, size: 50 })}>
            {t("positions.clearFilters")}
          </Button>
        </CardBody>
      </Card>

      <Card>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs uppercase text-gray-500 dark:border-gray-700 dark:text-gray-400">
              <tr>
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
              {isLoading ? (
                <tr><td colSpan={11} className="py-8 text-center text-gray-500">{t("common.loading")}</td></tr>
              ) : (data?.items.length ?? 0) === 0 ? (
                <tr><td colSpan={11} className="py-8 text-center text-gray-500 dark:text-gray-400">{t("positions.noPositions")}</td></tr>
              ) : (
                data!.items.map((p) => (
                  <PositionRow key={p.id} profileId={activeProfileId} position={p} origen={origen} />
                ))
              )}
            </tbody>
          </table>
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

function FilterField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{label}</span>
      {children}
    </label>
  );
}

function PositionRow({ profileId, position, origen }: { profileId: string; position: Position; origen?: TagGroup }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const setTag = useSetTag(profileId);
  const clearTag = useClearTag(profileId);

  const currentTagId = origen ? position.tags.find((tg) => tg.groupId === origen.id)?.tagId ?? "" : "";

  const onTagChange = (tagId: string) => {
    if (!origen) return;
    if (tagId === "") clearTag.mutate({ positionId: position.id, groupId: origen.id });
    else setTag.mutate({ positionId: position.id, groupId: origen.id, tagId });
  };

  return (
    <>
      <tr className="border-b border-border last:border-0 dark:border-gray-700">
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
          {origen && (
            <Select className="w-32" value={currentTagId} onChange={(e) => onTagChange(e.target.value)}>
              <option value="">{t("common.none")}</option>
              {origen.tags.map((tag) => (
                <option key={tag.id} value={tag.id}>{tag.name}</option>
              ))}
            </Select>
          )}
        </td>
        <td className="text-right">
          <button type="button" onClick={() => setExpanded((v) => !v)} className="px-2 text-gray-500 hover:text-primary">
            {position.note ? "📝" : ""} {expanded ? "▲" : "▼"}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="bg-gray-50 dark:bg-gray-900/40">
          <td colSpan={11} className="p-4">
            <ExpandedPanel profileId={profileId} position={position} />
          </td>
        </tr>
      )}
    </>
  );
}

function ExpandedPanel({ profileId, position }: { profileId: string; position: Position }) {
  const { t } = useTranslation();
  const { data: detail, isLoading } = usePositionDetail(profileId, position.id);
  const setNote = useSetNote(profileId);
  const [note, setNote_] = useState(position.note ?? "");

  return (
    <div className="grid gap-6 md:grid-cols-2">
      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">{t("positions.operations")}</h3>
        {isLoading ? (
          <p className="text-sm text-gray-500">{t("common.loading")}</p>
        ) : (detail?.fills.length ?? 0) === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">—</p>
        ) : (
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
        )}
        <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-gray-500 dark:text-gray-400">
          <span>{t("positions.grossPnl")}: {fmtUsd(position.realizedPnl, { sign: true })}</span>
          <span>{t("positions.fees")}: {fmtUsd(position.fees)}</span>
          <span>{t("positions.funding")}: {fmtUsd(position.funding)}</span>
          <span>{t("positions.netPnl")}: {fmtUsd(position.netPnl, { sign: true })}</span>
        </div>
      </div>

      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">{t("positions.note")}</h3>
        <Textarea rows={4} value={note} onChange={(e) => setNote_(e.target.value)} placeholder={t("positions.addNote")} />
        <Button
          className="mt-2"
          variant="secondary"
          disabled={setNote.isPending}
          onClick={() => setNote.mutate({ positionId: position.id, note: note.trim() || null })}
        >
          {t("common.save")}
        </Button>
      </div>
    </div>
  );
}
