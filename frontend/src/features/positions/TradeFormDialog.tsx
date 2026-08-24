import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCreatePosition,
  usePositionExchanges,
  useUpdatePosition,
  type ManualPositionBody,
  type Position,
  type PositionSide,
} from "@/api/positions";
import { isArchived, useTaxonomy } from "@/api/taxonomy";
import { Button, FieldError, Input, Label, Modal, Select, Textarea } from "@/components/ui/primitives";
import { dateTimeInputToIso, fmtUsd, isoToDateTimeInput, pnlTone, toDecimal } from "@/lib/format";
import { useTagLabel } from "@/lib/tagLabel";
import { showToast } from "@/lib/toastBus";
import { cn } from "@/lib/cn";

interface FormState {
  symbol: string;
  side: PositionSide;
  openedAt: string;
  closedAt: string;
  qty: string;
  entryPrice: string;
  exitPrice: string;
  realizedPnl: string;
  fees: string;
  funding: string;
  exchange: string;
  note: string;
  tagId: string;
}

const EMPTY: FormState = {
  symbol: "",
  side: "LONG",
  openedAt: "",
  closedAt: "",
  qty: "1",
  entryPrice: "",
  exitPrice: "",
  realizedPnl: "",
  fees: "",
  funding: "",
  exchange: "",
  note: "",
  tagId: "",
};

// `toDecimal` falls back to 0 on anything unparseable, so validity is checked before parsing.
// The comma decimal mark is accepted here just as the CSV import accepts it.
const NUMERIC = /^-?\d+([.]\d+)?$/;
const normalize = (raw: string) => raw.trim().replace(",", ".");
const isNum = (raw: string) => NUMERIC.test(normalize(raw));
const num = (raw: string) => toDecimal(isNum(raw) ? normalize(raw) : "0");

/** Gross realized PnL from the leg prices — the same rule the backend applies when the field is left blank. */
function derivedRealized(f: FormState) {
  const qty = num(f.qty);
  const entry = num(f.entryPrice);
  const exit = num(f.exitPrice);
  return (f.side === "LONG" ? exit.minus(entry) : entry.minus(exit)).mul(qty);
}

export function TradeFormDialog({
  open,
  onClose,
  profileId,
  position,
}: {
  open: boolean;
  onClose: () => void;
  profileId: string;
  /** Present when editing a hand-added trade; absent when adding a new one. */
  position?: Position;
}) {
  const { t } = useTranslation();
  const tagLabel = useTagLabel();
  const { data: exchanges = [] } = usePositionExchanges(profileId);
  const { data: taxonomy = [] } = useTaxonomy();
  const origen = useMemo(() => taxonomy.find((g) => g.code === "origen") ?? taxonomy[0], [taxonomy]);
  const create = useCreatePosition(profileId);
  const update = useUpdatePosition(profileId);
  const editing = !!position;

  const [form, setForm] = useState<FormState>(EMPTY);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (position) {
      setForm({
        symbol: `${position.symbolBase}-${position.symbolQuote}`,
        side: position.side,
        openedAt: isoToDateTimeInput(position.openedAt),
        closedAt: isoToDateTimeInput(position.closedAt),
        qty: position.qty,
        entryPrice: position.entryPrice,
        exitPrice: position.exitPrice,
        realizedPnl: position.realizedPnl,
        fees: position.fees,
        funding: position.funding,
        exchange: position.exchange ?? "",
        note: position.note ?? "",
        tagId: origen ? position.tags.find((tg) => tg.groupId === origen.id)?.tagId ?? "" : "",
      });
    } else {
      setForm(EMPTY);
    }
  }, [open, position, origen]);

  const set = (patch: Partial<FormState>) => setForm((f) => ({ ...f, ...patch }));

  const realized = isNum(form.realizedPnl) ? num(form.realizedPnl) : derivedRealized(form);
  const net = realized.minus(num(form.fees)).minus(num(form.funding));

  const pending = create.isPending || update.isPending;
  const canSubmit =
    form.symbol.trim() !== "" &&
    !!form.openedAt &&
    !!form.closedAt &&
    // Mirrors the server's constraints, so the common mistakes never cost a round trip.
    num(form.qty).gt(0) &&
    num(form.entryPrice).gt(0) &&
    isNum(form.exitPrice) &&
    num(form.exitPrice).gte(0);

  const submit = () => {
    const openedAt = dateTimeInputToIso(form.openedAt);
    const closedAt = dateTimeInputToIso(form.closedAt);
    if (!openedAt || !closedAt) return;
    if (new Date(closedAt) < new Date(openedAt)) {
      setError(t("trades.closedBeforeOpened"));
      return;
    }
    setError(null);

    const body: ManualPositionBody = {
      symbol: form.symbol.trim(),
      side: form.side,
      openedAt,
      closedAt,
      qty: num(form.qty).toString(),
      entryPrice: num(form.entryPrice).toString(),
      exitPrice: num(form.exitPrice).toString(),
      // Left blank, the backend derives it from the prices — don't send the preview back as fact.
      ...(isNum(form.realizedPnl) ? { realizedPnl: num(form.realizedPnl).toString() } : {}),
      fees: num(form.fees).toString(),
      funding: num(form.funding).toString(),
      ...(form.exchange.trim() ? { exchange: form.exchange.trim() } : {}),
      ...(form.note.trim() ? { note: form.note.trim() } : {}),
      // Sent even when empty so clearing the tag on an edit actually removes it.
      ...(origen ? { tagGroupId: origen.id, tagId: form.tagId || null } : {}),
    };

    const done = { onSuccess: () => { showToast(t(editing ? "trades.updated" : "trades.added"), "success"); onClose(); } };
    if (editing) update.mutate({ positionId: position!.id, body }, done);
    else create.mutate(body, done);
  };

  return (
    <Modal open={open} onClose={onClose} title={t(editing ? "trades.editTitle" : "trades.addTitle")} className="max-w-2xl">
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <Label htmlFor="trade-symbol">{t("positions.symbol")}</Label>
            <Input
              id="trade-symbol"
              value={form.symbol}
              onChange={(e) => set({ symbol: e.target.value })}
              placeholder="SOL-USDT"
            />
          </div>
          <div>
            <Label htmlFor="trade-side">{t("positions.side")}</Label>
            <Select id="trade-side" value={form.side} onChange={(e) => set({ side: e.target.value as PositionSide })}>
              <option value="LONG">{t("positions.long")}</option>
              <option value="SHORT">{t("positions.short")}</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="trade-opened">{t("trades.openedAt")}</Label>
            <Input id="trade-opened" type="datetime-local" value={form.openedAt} onChange={(e) => set({ openedAt: e.target.value })} />
          </div>
          <div>
            <Label htmlFor="trade-closed">{t("trades.closedAt")}</Label>
            <Input id="trade-closed" type="datetime-local" value={form.closedAt} onChange={(e) => set({ closedAt: e.target.value })} />
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <div>
            <Label htmlFor="trade-qty">{t("positions.qty")}</Label>
            <Input id="trade-qty" inputMode="decimal" value={form.qty} onChange={(e) => set({ qty: e.target.value })} />
          </div>
          <div>
            <Label htmlFor="trade-entry">{t("positions.entry")}</Label>
            <Input id="trade-entry" inputMode="decimal" value={form.entryPrice} onChange={(e) => set({ entryPrice: e.target.value })} />
          </div>
          <div>
            <Label htmlFor="trade-exit">{t("positions.exit")}</Label>
            <Input id="trade-exit" inputMode="decimal" value={form.exitPrice} onChange={(e) => set({ exitPrice: e.target.value })} />
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <div>
            <Label htmlFor="trade-realized">{t("trades.realizedPnlOptional")}</Label>
            <Input
              id="trade-realized"
              inputMode="decimal"
              value={form.realizedPnl}
              onChange={(e) => set({ realizedPnl: e.target.value })}
              placeholder={derivedRealized(form).toDecimalPlaces(8).toString()}
            />
          </div>
          <div>
            <Label htmlFor="trade-fees">{t("positions.fees")}</Label>
            <Input id="trade-fees" inputMode="decimal" value={form.fees} onChange={(e) => set({ fees: e.target.value })} placeholder="0" />
          </div>
          <div>
            <Label htmlFor="trade-funding">{t("positions.funding")}</Label>
            <Input id="trade-funding" inputMode="decimal" value={form.funding} onChange={(e) => set({ funding: e.target.value })} placeholder="0" />
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <Label htmlFor="trade-exchange">{t("positions.exchange")}</Label>
            <Input
              id="trade-exchange"
              list="trade-exchange-options"
              value={form.exchange}
              onChange={(e) => set({ exchange: e.target.value })}
              placeholder={t("trades.exchangePlaceholder")}
            />
            <datalist id="trade-exchange-options">
              {exchanges.map((ex) => (
                <option key={ex} value={ex} />
              ))}
            </datalist>
          </div>
          {origen && (
            <div>
              <Label htmlFor="trade-origen">{origen.name}</Label>
              <Select id="trade-origen" value={form.tagId} onChange={(e) => set({ tagId: e.target.value })}>
                <option value="">{t("common.none")}</option>
                {origen.tags
                  .filter((tag) => !isArchived(tag) || tag.id === form.tagId)
                  .map((tag) => (
                    <option key={tag.id} value={tag.id}>{tagLabel(tag)}</option>
                  ))}
              </Select>
            </div>
          )}
        </div>

        <div>
          <Label htmlFor="trade-note">{t("positions.note")}</Label>
          <Textarea id="trade-note" rows={2} value={form.note} onChange={(e) => set({ note: e.target.value })} />
        </div>

        <div className="flex items-center justify-between rounded-md bg-gray-50 px-3 py-2 text-sm dark:bg-surface-inset">
          <span className="text-gray-500 dark:text-gray-400">{t("positions.netPnl")}</span>
          <span className={cn("font-medium tabular-nums", pnlTone(net.toString()))}>
            {fmtUsd(net.toString(), { sign: true })}
          </span>
        </div>

        <FieldError message={error} />

        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
          <Button disabled={!canSubmit || pending} onClick={submit}>
            {t(editing ? "common.save" : "trades.add")}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
