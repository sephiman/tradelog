import { useState } from "react";
import { useTranslation } from "react-i18next";
import type Decimal from "decimal.js";
import {
  useCreatePosition,
  useUpdatePosition,
  type ManualPositionBody,
  type Position,
  type PositionSide,
} from "@/api/positions";
import type { TagGroup } from "@/api/taxonomy";
import { Button, FieldError, Input, Select, Textarea } from "@/components/ui/primitives";
import { dateTimeInputToIso, fmtNum, fmtUsd, isoToDateTimeInput, pnlTone, toDecimal } from "@/lib/format";
import { showToast } from "@/lib/toastBus";
import { cn } from "@/lib/cn";
import { qtyFromNotional, realizedFromPrices } from "./entryMath";
import { ExchangeField, Field, OrigenField, SwitchableField } from "./manualEntryFields";
import { isNum, num } from "./numericInput";
import { SymbolInput } from "./SymbolInput";

/** How the user prefers to express position size. Notional is the common one; quantity is stored either way. */
type SizeMode = "QTY" | "NOTIONAL";

interface FormState {
  symbol: string;
  side: PositionSide;
  openedAt: string;
  closedAt: string;
  sizeMode: SizeMode;
  qty: string;
  notional: string;
  entryPrice: string;
  exitPrice: string;
  realizedPnl: string;
  fees: string;
  funding: string;
  exchange: string;
  note: string;
  tagId: string;
}

/** `qty`'s column scale — a notional divided by a price can run longer than the database keeps. */
const QTY_SCALE = 18;

function initialState(position: Position | undefined, origenId: string | undefined): FormState {
  if (!position) {
    return {
      symbol: "", side: "LONG", openedAt: "", closedAt: "", sizeMode: "NOTIONAL",
      qty: "1", notional: "", entryPrice: "", exitPrice: "", realizedPnl: "",
      fees: "", funding: "", exchange: "", note: "", tagId: "",
    };
  }
  return {
    symbol: `${position.symbolBase}-${position.symbolQuote}`,
    side: position.side,
    openedAt: isoToDateTimeInput(position.openedAt),
    closedAt: isoToDateTimeInput(position.closedAt),
    // An edit starts from what is stored, and quantity is what is stored.
    sizeMode: "QTY",
    qty: position.qty ?? "1",
    notional: "",
    entryPrice: position.entryPrice ?? "",
    exitPrice: position.exitPrice ?? "",
    realizedPnl: position.realizedPnl,
    fees: position.fees,
    funding: position.funding,
    exchange: position.exchange ?? "",
    note: position.note ?? "",
    tagId: origenId ? position.tags.find((tg) => tg.groupId === origenId)?.tagId ?? "" : "",
  };
}

export function TradeForm({
  profileId,
  position,
  origen,
  onDone,
}: {
  profileId: string;
  /** Present when editing a hand-added trade; absent when adding a new one. */
  position?: Position;
  origen?: TagGroup;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const create = useCreatePosition(profileId);
  const update = useUpdatePosition(profileId);
  const editing = !!position;

  const [form, setForm] = useState<FormState>(() => initialState(position, origen?.id));
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<FormState>) => setForm((f) => ({ ...f, ...patch }));

  const entry = num(form.entryPrice);
  // Quantity is what gets stored; a notional is turned into one as soon as the entry price allows it.
  const qty: Decimal | null =
    form.sizeMode === "NOTIONAL"
      ? isNum(form.notional) ? qtyFromNotional(num(form.notional), entry)?.toDecimalPlaces(QTY_SCALE) ?? null : null
      : isNum(form.qty) ? num(form.qty) : null;

  const derivedRealized = realizedFromPrices(form.side, entry, num(form.exitPrice), qty ?? toDecimal(0));
  const realized = isNum(form.realizedPnl) ? num(form.realizedPnl) : derivedRealized;
  const net = realized.minus(num(form.fees)).minus(num(form.funding));

  const pending = create.isPending || update.isPending;
  const canSubmit =
    form.symbol.trim() !== "" &&
    !!form.openedAt &&
    !!form.closedAt &&
    // Mirrors the server's constraints, so the common mistakes never cost a round trip.
    qty !== null &&
    qty.gt(0) &&
    entry.gt(0) &&
    isNum(form.exitPrice) &&
    num(form.exitPrice).gte(0);

  const submit = () => {
    const openedAt = dateTimeInputToIso(form.openedAt);
    const closedAt = dateTimeInputToIso(form.closedAt);
    if (!openedAt || !closedAt || qty === null) return;
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
      qty: qty.toString(),
      entryPrice: entry.toString(),
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

    const done = { onSuccess: () => { showToast(t(editing ? "trades.updated" : "trades.added"), "success"); onDone(); } };
    if (editing) update.mutate({ positionId: position.id, body }, done);
    else create.mutate(body, done);
  };

  return (
    <div className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field htmlFor="trade-symbol" label={t("positions.symbol")}>
          <SymbolInput id="trade-symbol" profileId={profileId} value={form.symbol} onChange={(symbol) => set({ symbol })} />
        </Field>
        <Field htmlFor="trade-side" label={t("positions.side")}>
          <Select id="trade-side" value={form.side} onChange={(e) => set({ side: e.target.value as PositionSide })}>
            <option value="LONG">{t("positions.long")}</option>
            <option value="SHORT">{t("positions.short")}</option>
          </Select>
        </Field>
        <Field htmlFor="trade-opened" label={t("trades.openedAt")}>
          <Input id="trade-opened" type="datetime-local" value={form.openedAt} onChange={(e) => set({ openedAt: e.target.value })} />
        </Field>
        <Field htmlFor="trade-closed" label={t("trades.closedAt")}>
          <Input id="trade-closed" type="datetime-local" value={form.closedAt} onChange={(e) => set({ closedAt: e.target.value })} />
        </Field>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <SwitchableField
          htmlFor="trade-size"
          label={form.sizeMode === "QTY" ? t("positions.qty") : t("trades.notional")}
          switchTo={form.sizeMode === "QTY" ? t("trades.notional") : t("positions.qty")}
          onSwitch={() => set({ sizeMode: form.sizeMode === "QTY" ? "NOTIONAL" : "QTY" })}
          hint={
            form.sizeMode === "NOTIONAL" && (
              <>
                {t("positions.qty")}: <span className="tabular-nums">{qty ? fmtNum(qty.toString()) : "—"}</span>
              </>
            )
          }
        >
          {form.sizeMode === "QTY" ? (
            <Input id="trade-size" inputMode="decimal" value={form.qty} onChange={(e) => set({ qty: e.target.value })} />
          ) : (
            <Input
              id="trade-size"
              inputMode="decimal"
              value={form.notional}
              onChange={(e) => set({ notional: e.target.value })}
              placeholder="500"
            />
          )}
        </SwitchableField>
        <Field htmlFor="trade-entry" label={t("positions.entry")}>
          <Input id="trade-entry" inputMode="decimal" value={form.entryPrice} onChange={(e) => set({ entryPrice: e.target.value })} />
        </Field>
        <Field htmlFor="trade-exit" label={t("positions.exit")}>
          <Input id="trade-exit" inputMode="decimal" value={form.exitPrice} onChange={(e) => set({ exitPrice: e.target.value })} />
        </Field>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <Field htmlFor="trade-realized" label={t("trades.realizedPnlOptional")}>
          <Input
            id="trade-realized"
            inputMode="decimal"
            value={form.realizedPnl}
            onChange={(e) => set({ realizedPnl: e.target.value })}
            placeholder={derivedRealized.toDecimalPlaces(8).toString()}
          />
        </Field>
        <Field htmlFor="trade-fees" label={t("positions.fees")}>
          <Input id="trade-fees" inputMode="decimal" value={form.fees} onChange={(e) => set({ fees: e.target.value })} placeholder="0" />
        </Field>
        <Field htmlFor="trade-funding" label={t("positions.funding")}>
          <Input id="trade-funding" inputMode="decimal" value={form.funding} onChange={(e) => set({ funding: e.target.value })} placeholder="0" />
        </Field>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <Field htmlFor="trade-exchange" label={t("positions.exchange")}>
          <ExchangeField id="trade-exchange" profileId={profileId} value={form.exchange} onChange={(exchange) => set({ exchange })} />
        </Field>
        {origen && (
          <Field htmlFor="trade-origen" label={origen.name}>
            <OrigenField id="trade-origen" group={origen} value={form.tagId} onChange={(tagId) => set({ tagId })} />
          </Field>
        )}
      </div>

      <Field htmlFor="trade-note" label={t("positions.note")}>
        <Textarea id="trade-note" rows={2} value={form.note} onChange={(e) => set({ note: e.target.value })} />
      </Field>

      <div className="flex items-center justify-between rounded-md bg-gray-50 px-3 py-2 text-sm dark:bg-surface-inset">
        <span className="text-gray-500 dark:text-gray-400">{t("positions.netPnl")}</span>
        <span className={cn("font-medium tabular-nums", pnlTone(net.toString()))}>
          {fmtUsd(net.toString(), { sign: true })}
        </span>
      </div>

      <FieldError message={error} />

      <div className="flex justify-end gap-2 pt-1">
        <Button variant="ghost" onClick={onDone}>{t("common.cancel")}</Button>
        <Button disabled={!canSubmit || pending} onClick={submit}>
          {t(editing ? "common.save" : "trades.add")}
        </Button>
      </div>
    </div>
  );
}
