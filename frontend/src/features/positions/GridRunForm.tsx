import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCreateGridRun,
  useUpdateGridRun,
  type GridPnlBasis,
  type GridRunBody,
  type Position,
  type PositionSide,
} from "@/api/positions";
import type { TagGroup } from "@/api/taxonomy";
import { Button, FieldError, Input, Select, Textarea } from "@/components/ui/primitives";
import { dateTimeInputToIso, fmtNum, fmtUsd, isoToDateTimeInput, pnlTone } from "@/lib/format";
import { showToast } from "@/lib/toastBus";
import { cn } from "@/lib/cn";
import { gridRoi, gridVolume, grossFromNet, netFromGross } from "./entryMath";
import { ExchangeField, Field, OrigenField, SwitchableField } from "./manualEntryFields";
import { isNum, num, optionalNum } from "./numericInput";
import { SymbolInput } from "./SymbolInput";

interface FormState {
  symbol: string;
  side: PositionSide;
  exchange: string;
  openedAt: string;
  closedAt: string;
  pnl: string;
  pnlBasis: GridPnlBasis;
  fees: string;
  funding: string;
  leverage: string;
  investment: string;
  volume: string;
  // Volume-calculator inputs, all of them on the venue's own bot detail screen. Not stored: what the
  // run keeps is the volume they produce, which is why it stays editable as a plain value.
  matchedOrders: string;
  sizePerGrid: string;
  referencePrice: string;
  note: string;
  tagId: string;
}

/** The calculator's own inputs are never stored, so they start empty on an edit as on an insert. */
const NO_CALCULATOR = { matchedOrders: "", sizePerGrid: "", referencePrice: "" };

function initialState(position: Position | undefined, origenId: string | undefined): FormState {
  if (!position) {
    return {
      symbol: "", side: "LONG", exchange: "", openedAt: "", closedAt: "",
      pnl: "", pnlBasis: "NET", fees: "", funding: "", leverage: "", investment: "",
      volume: "", ...NO_CALCULATOR, note: "", tagId: "",
    };
  }
  return {
    symbol: `${position.symbolBase}-${position.symbolQuote}`,
    side: position.side,
    exchange: position.exchange ?? "",
    openedAt: isoToDateTimeInput(position.openedAt),
    closedAt: isoToDateTimeInput(position.closedAt),
    // Net is what the run is judged on, and the stored figures reproduce it exactly.
    pnl: position.netPnl,
    pnlBasis: "NET",
    fees: position.fees,
    funding: position.funding,
    leverage: position.leverage ?? "",
    investment: position.investment ?? "",
    volume: position.volume ?? "",
    ...NO_CALCULATOR,
    note: position.note ?? "",
    tagId: origenId ? position.tags.find((tg) => tg.groupId === origenId)?.tagId ?? "" : "",
  };
}

export function GridRunForm({
  profileId,
  position,
  origen,
  onDone,
}: {
  profileId: string;
  /** Present when editing a hand-added grid run; absent when adding a new one. */
  position?: Position;
  origen?: TagGroup;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const create = useCreateGridRun(profileId);
  const update = useUpdateGridRun(profileId);
  const editing = !!position;

  const [form, setForm] = useState<FormState>(() => initialState(position, origen?.id));
  const [showOptional, setShowOptional] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<FormState>) => setForm((f) => ({ ...f, ...patch }));

  const fees = num(form.fees);
  const funding = num(form.funding);
  const pnl = num(form.pnl);
  // Whichever figure was typed, both readings are shown — that is what makes the toggle safe to use.
  const gross = form.pnlBasis === "GROSS" ? pnl : grossFromNet(pnl, fees, funding);
  const net = form.pnlBasis === "GROSS" ? netFromGross(pnl, fees, funding) : pnl;

  const computedVolume = gridVolume(num(form.matchedOrders), num(form.sizePerGrid), num(form.referencePrice));
  // A typed volume wins over the calculator, for pasting the venue's own total instead.
  const volume = optionalNum(form.volume) ?? computedVolume;
  const leverage = optionalNum(form.leverage);
  const investment = optionalNum(form.investment);
  const roi = investment ? gridRoi(net, investment) : null;

  const pending = create.isPending || update.isPending;
  const canSubmit =
    form.symbol.trim() !== "" &&
    form.exchange.trim() !== "" &&
    !!form.openedAt &&
    !!form.closedAt &&
    isNum(form.pnl);

  const submit = () => {
    const openedAt = dateTimeInputToIso(form.openedAt);
    const closedAt = dateTimeInputToIso(form.closedAt);
    if (!openedAt || !closedAt) return;
    if (new Date(closedAt) < new Date(openedAt)) {
      setError(t("trades.closedBeforeOpened"));
      return;
    }
    setError(null);

    const body: GridRunBody = {
      symbol: form.symbol.trim(),
      side: form.side,
      exchange: form.exchange.trim(),
      openedAt,
      closedAt,
      pnl: pnl.toString(),
      pnlBasis: form.pnlBasis,
      fees: fees.toString(),
      funding: funding.toString(),
      // Omitted rather than zeroed: a run with no volume contributes nothing to the statistic.
      ...(volume?.gt(0) ? { volume: volume.toString() } : {}),
      ...(leverage?.gt(0) ? { leverage: leverage.toString() } : {}),
      ...(investment?.gt(0) ? { investment: investment.toString() } : {}),
      ...(form.note.trim() ? { note: form.note.trim() } : {}),
      // Sent even when empty so clearing the tag on an edit actually removes it.
      ...(origen ? { tagGroupId: origen.id, tagId: form.tagId || null } : {}),
    };

    const done = { onSuccess: () => { showToast(t(editing ? "grids.updated" : "grids.added"), "success"); onDone(); } };
    if (editing) update.mutate({ positionId: position.id, body }, done);
    else create.mutate(body, done);
  };

  return (
    <div className="space-y-3">
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("grids.intro")}</p>

      <div className="grid gap-3 sm:grid-cols-2">
        <Field htmlFor="grid-symbol" label={t("positions.symbol")}>
          <SymbolInput id="grid-symbol" profileId={profileId} value={form.symbol} onChange={(symbol) => set({ symbol })} />
        </Field>
        <Field htmlFor="grid-side" label={t("positions.side")}>
          <Select id="grid-side" value={form.side} onChange={(e) => set({ side: e.target.value as PositionSide })}>
            <option value="LONG">{t("positions.long")}</option>
            <option value="SHORT">{t("positions.short")}</option>
          </Select>
        </Field>
        <Field htmlFor="grid-opened" label={t("trades.openedAt")}>
          <Input id="grid-opened" type="datetime-local" value={form.openedAt} onChange={(e) => set({ openedAt: e.target.value })} />
        </Field>
        <Field htmlFor="grid-closed" label={t("trades.closedAt")}>
          <Input id="grid-closed" type="datetime-local" value={form.closedAt} onChange={(e) => set({ closedAt: e.target.value })} />
        </Field>
        <Field htmlFor="grid-exchange" label={t("positions.exchange")}>
          <ExchangeField id="grid-exchange" profileId={profileId} value={form.exchange} onChange={(exchange) => set({ exchange })} />
        </Field>
        <SwitchableField
          htmlFor="grid-pnl"
          label={form.pnlBasis === "NET" ? t("grids.netProfit") : t("grids.realizedPnl")}
          switchTo={t(form.pnlBasis === "NET" ? "grids.grossShort" : "grids.netShort")}
          onSwitch={() => set({ pnlBasis: form.pnlBasis === "NET" ? "GROSS" : "NET" })}
          hint={t(form.pnlBasis === "NET" ? "grids.netHint" : "grids.grossHint")}
        >
          <Input
            id="grid-pnl"
            inputMode="decimal"
            value={form.pnl}
            onChange={(e) => set({ pnl: e.target.value })}
            placeholder={form.pnlBasis === "NET" ? "23.16" : "26.87"}
          />
        </SwitchableField>
      </div>

      <button
        type="button"
        onClick={() => setShowOptional((v) => !v)}
        aria-expanded={showOptional}
        className="text-sm font-medium text-primary hover:underline"
      >
        {showOptional ? "▲" : "▼"} {t("grids.moreDetail")}
      </button>

      {showOptional && (
        <div className="space-y-3 rounded-md border border-border p-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field htmlFor="grid-fees" label={t("grids.tradingFees")}>
              <Input id="grid-fees" inputMode="decimal" value={form.fees} onChange={(e) => set({ fees: e.target.value })} placeholder="0" />
            </Field>
            <Field htmlFor="grid-funding" label={t("positions.funding")}>
              <Input id="grid-funding" inputMode="decimal" value={form.funding} onChange={(e) => set({ funding: e.target.value })} placeholder="0" />
            </Field>
            <Field htmlFor="grid-leverage" label={t("grids.leverage")}>
              <Input id="grid-leverage" inputMode="decimal" value={form.leverage} onChange={(e) => set({ leverage: e.target.value })} placeholder="10" />
            </Field>
            <Field htmlFor="grid-investment" label={t("grids.investment")}>
              <Input id="grid-investment" inputMode="decimal" value={form.investment} onChange={(e) => set({ investment: e.target.value })} placeholder="250" />
            </Field>
          </div>

          <div>
            <h4 className="text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">{t("grids.volumeCalculator")}</h4>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("grids.volumeCalculatorHint")}</p>
            <div className="mt-2 grid gap-3 sm:grid-cols-3">
              <Field htmlFor="grid-orders" label={t("grids.matchedOrders")}>
                <Input id="grid-orders" inputMode="decimal" value={form.matchedOrders} onChange={(e) => set({ matchedOrders: e.target.value })} />
              </Field>
              <Field htmlFor="grid-size" label={t("grids.sizePerGrid")}>
                <Input id="grid-size" inputMode="decimal" value={form.sizePerGrid} onChange={(e) => set({ sizePerGrid: e.target.value })} />
              </Field>
              <Field htmlFor="grid-ref-price" label={t("grids.referencePrice")}>
                <Input id="grid-ref-price" inputMode="decimal" value={form.referencePrice} onChange={(e) => set({ referencePrice: e.target.value })} />
              </Field>
            </div>
            <div className="mt-3">
              <Field
                htmlFor="grid-volume"
                label={t("positions.volume")}
                hint={volume ? undefined : t("grids.noVolumeHint")}
              >
                <Input
                  id="grid-volume"
                  inputMode="decimal"
                  value={form.volume}
                  onChange={(e) => set({ volume: e.target.value })}
                  placeholder={computedVolume ? fmtNum(computedVolume.toString(), 2) : "—"}
                />
              </Field>
            </div>
          </div>

          {origen && (
            <Field htmlFor="grid-origen" label={origen.name}>
              <OrigenField id="grid-origen" group={origen} value={form.tagId} onChange={(tagId) => set({ tagId })} />
            </Field>
          )}

          <Field htmlFor="grid-note" label={t("positions.note")}>
            <Textarea id="grid-note" rows={2} value={form.note} onChange={(e) => set({ note: e.target.value })} />
          </Field>
        </div>
      )}

      <dl className="grid gap-x-4 gap-y-1 rounded-md bg-gray-50 px-3 py-2 text-sm sm:grid-cols-2 dark:bg-surface-inset">
        <Summary label={t("positions.grossPnl")} value={fmtUsd(gross.toString(), { sign: true })} tone={pnlTone(gross.toString())} />
        <Summary label={t("positions.netPnl")} value={fmtUsd(net.toString(), { sign: true })} tone={pnlTone(net.toString())} />
        <Summary label={t("positions.volume")} value={volume ? fmtUsd(volume.toString()) : "—"} />
        <Summary label={t("grids.roi")} value={roi ? `${roi.toDecimalPlaces(2).toString()}%` : "—"} />
      </dl>

      <FieldError message={error} />

      <div className="flex justify-end gap-2 pt-1">
        <Button variant="ghost" onClick={onDone}>{t("common.cancel")}</Button>
        <Button disabled={!canSubmit || pending} onClick={submit}>
          {t(editing ? "common.save" : "grids.add")}
        </Button>
      </div>
    </div>
  );
}

function Summary({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className="text-gray-500 dark:text-gray-400">{label}</dt>
      <dd className={cn("font-medium tabular-nums", tone)}>{value}</dd>
    </div>
  );
}
