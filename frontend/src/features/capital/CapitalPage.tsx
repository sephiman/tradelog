import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { NoActiveProfile } from "@/features/profiles/NoActiveProfile";
import {
  useAdjustments,
  useCapital,
  useCapitalSnapshots,
  useDeleteAdjustment,
  useDeleteSnapshotDay,
  usePatchAdjustment,
  useSaveAdjustments,
  type SnapshotValue,
} from "@/api/capital";
import { Button, Card, CardBody, CardHeader, Input, Label } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";
import { fmtUsd, todayInDisplayZone } from "@/lib/format";

const pad2 = (n: number) => String(n).padStart(2, "0");

/** Today as "YYYY-MM-DD" in the account's time zone (day boundaries are the user's, not UTC). */
function todayStr(): string {
  const { y, m, d } = todayInDisplayZone();
  return `${y}-${pad2(m)}-${pad2(d)}`;
}

function daysAgoStr(days: number): string {
  const { y, m, d } = todayInDisplayZone();
  const t = new Date(Date.UTC(y, m - 1, d));
  t.setUTCDate(t.getUTCDate() - days);
  return `${t.getUTCFullYear()}-${pad2(t.getUTCMonth() + 1)}-${pad2(t.getUTCDate())}`;
}

/** Backend NUMERIC string ("1000.00000000") → editable input value ("1000"). */
function toInput(amount: string | null | undefined): string {
  if (!amount) return "";
  const n = Number(amount);
  return Number.isFinite(n) ? String(n) : amount;
}

const thClass = "py-2 pr-3 text-left text-xs font-medium text-gray-500 dark:text-gray-400";
const thRight = "py-2 pr-3 text-right text-xs font-medium text-gray-500 dark:text-gray-400";

export function CapitalPage() {
  const { t } = useTranslation();
  const { activeProfileId } = useActiveProfile();
  if (!activeProfileId) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-bold">{t("capital.title")}</h1>
        <NoActiveProfile />
      </div>
    );
  }
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("capital.title")}</h1>
      <EstimatedCard profileId={activeProfileId} />
      <AdjustmentsCard profileId={activeProfileId} />
      <SnapshotsCard profileId={activeProfileId} />
    </div>
  );
}

/** Estimated capital right now: latest anchor per exchange + net PnL of trades closed since. */
function EstimatedCard({ profileId }: { profileId: string }) {
  const { t } = useTranslation();
  const { data } = useCapital(profileId);
  if (!data) return null;

  const anchored = data.entries.filter((e) => e.amount !== null);

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("capital.estimated.title")}</h2>
      </CardHeader>
      <CardBody className="space-y-4">
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("capital.estimated.hint")}</p>
        {!data.hasAnchors ? (
          <p className="py-4 text-center text-sm text-gray-500 dark:text-gray-400">{t("capital.estimated.empty")}</p>
        ) : (
          <>
            <div>
              <div className="text-xs text-gray-500 dark:text-gray-400">{t("capital.estimated.total")}</div>
              <div className="text-2xl font-semibold tabular-nums">{fmtUsd(data.total)}</div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[28rem] text-sm">
                <thead>
                  <tr className="border-b border-border dark:border-gray-700">
                    <th className={thClass}>{t("capital.estimated.exchange")}</th>
                    <th className={thRight}>{t("capital.estimated.estimate")}</th>
                    <th className={cn(thRight, "pr-0")}>{t("capital.estimated.anchor")}</th>
                  </tr>
                </thead>
                <tbody>
                  {anchored.map((e) => (
                    <tr key={e.exchange} className="border-b border-border last:border-0 dark:border-gray-700">
                      <td className="py-2 pr-3">{e.exchange}</td>
                      <td className="py-2 pr-3 text-right tabular-nums">{fmtUsd(e.amount!)}</td>
                      <td className="py-2 text-right text-xs tabular-nums text-gray-500 dark:text-gray-400">
                        {t("capital.estimated.anchorValue", { amount: fmtUsd(e.anchorAmount!), date: e.anchorDate })}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("capital.timeZoneNote", { tz: data.timeZone })}</p>
      </CardBody>
    </Card>
  );
}

/** The anchor series: add a dated balance per exchange; edit or delete existing entries. */
function AdjustmentsCard({ profileId }: { profileId: string }) {
  const { t } = useTranslation();
  const { data: overview } = useCapital(profileId);
  const { data: adjustments = [] } = useAdjustments(profileId);
  const save = useSaveAdjustments(profileId);
  const patch = usePatchAdjustment(profileId);
  const del = useDeleteAdjustment(profileId);

  const [date, setDate] = useState(todayStr);
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState<{ id: string; date: string; amount: string } | null>(null);

  const exchanges = overview?.knownExchanges ?? [];
  const filled = exchanges.filter((ex) => amounts[ex]?.trim());

  const onAdd = async () => {
    if (!date || filled.length === 0) return;
    await save.mutateAsync({ date, entries: filled.map((ex) => ({ exchange: ex, amount: amounts[ex].trim() })) });
    setAmounts({});
  };

  const onSaveEdit = async () => {
    if (!editing || !editing.date) return;
    await patch.mutateAsync({
      id: editing.id,
      date: editing.date,
      amount: editing.amount.trim() ? editing.amount.trim() : undefined,
    });
    setEditing(null);
  };

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("capital.adjustments.title")}</h2>
      </CardHeader>
      <CardBody className="space-y-5">
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("capital.adjustments.hint")}</p>

        {!overview?.hasAnchors && (
          <p className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200">
            {t("capital.adjustments.firstMandatory")}
          </p>
        )}

        <div className="space-y-3 rounded-md border border-border p-3 dark:border-gray-700">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 md:grid-cols-3">
            <div>
              <Label htmlFor="adj-date">{t("capital.adjustments.date")}</Label>
              <Input id="adj-date" type="date" max={todayStr()} value={date} onChange={(e) => setDate(e.target.value)} />
            </div>
            {exchanges.map((ex) => (
              <div key={ex}>
                <Label htmlFor={`adj-${ex}`}>{ex}</Label>
                <Input
                  id={`adj-${ex}`}
                  type="number"
                  min="0"
                  step="any"
                  inputMode="decimal"
                  placeholder="0.00"
                  value={amounts[ex] ?? ""}
                  onChange={(e) => setAmounts((prev) => ({ ...prev, [ex]: e.target.value }))}
                />
              </div>
            ))}
          </div>
          <p className="text-xs text-gray-500 dark:text-gray-400">{t("capital.adjustments.addHint")}</p>
          <Button onClick={() => void onAdd()} disabled={save.isPending || !date || filled.length === 0}>
            {t("capital.adjustments.add")}
          </Button>
        </div>

        {adjustments.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("capital.adjustments.none")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[28rem] text-sm">
              <thead>
                <tr className="border-b border-border dark:border-gray-700">
                  <th className={thClass}>{t("capital.adjustments.date")}</th>
                  <th className={thClass}>{t("capital.adjustments.exchange")}</th>
                  <th className={thRight}>{t("capital.adjustments.amount")}</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {adjustments.map((a) =>
                  editing?.id === a.id ? (
                    <tr key={a.id} className="border-b border-border last:border-0 dark:border-gray-700">
                      <td className="py-2 pr-3">
                        <Input
                          type="date"
                          max={todayStr()}
                          className="w-40"
                          value={editing.date}
                          onChange={(e) => setEditing({ ...editing, date: e.target.value })}
                        />
                      </td>
                      <td className="py-2 pr-3">{a.exchange}</td>
                      <td className="py-2 pr-3 text-right">
                        <Input
                          type="number"
                          min="0"
                          step="any"
                          inputMode="decimal"
                          className="ml-auto w-32 text-right"
                          value={editing.amount}
                          onChange={(e) => setEditing({ ...editing, amount: e.target.value })}
                        />
                      </td>
                      <td className="py-2 text-right">
                        <div className="flex justify-end gap-1">
                          <Button variant="secondary" onClick={() => void onSaveEdit()} disabled={patch.isPending}>
                            {t("common.save")}
                          </Button>
                          <Button variant="ghost" onClick={() => setEditing(null)}>
                            {t("common.cancel")}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ) : (
                    <tr key={a.id} className="border-b border-border last:border-0 dark:border-gray-700">
                      <td className="py-2 pr-3 tabular-nums">{a.date}</td>
                      <td className="py-2 pr-3">{a.exchange}</td>
                      <td className="py-2 pr-3 text-right tabular-nums">{fmtUsd(a.amount)}</td>
                      <td className="py-2 text-right">
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            onClick={() => setEditing({ id: a.id, date: a.date, amount: toInput(a.amount) })}
                          >
                            {t("capital.adjustments.edit")}
                          </Button>
                          <Button
                            variant="ghost"
                            onClick={() => {
                              if (window.confirm(t("capital.adjustments.deleteConfirm"))) del.mutate(a.id);
                            }}
                          >
                            {t("common.delete")}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ),
                )}
              </tbody>
            </table>
          </div>
        )}
      </CardBody>
    </Card>
  );
}

/** The stored daily series, editable: a committed edit turns that day into an anchor. */
function SnapshotsCard({ profileId }: { profileId: string }) {
  const { t } = useTranslation();
  const [from, setFrom] = useState(() => daysAgoStr(30));
  const [to, setTo] = useState(todayStr);
  const { data } = useCapitalSnapshots(profileId, from || undefined, to || undefined);
  const { data: overview } = useCapital(profileId);
  const save = useSaveAdjustments(profileId);
  const deleteDay = useDeleteSnapshotDay(profileId);

  // New exchanges appear as a column even before they have any snapshot value.
  const exchanges = useMemo(() => {
    const set = new Set<string>([...(data?.exchanges ?? []), ...(overview?.knownExchanges ?? [])]);
    return [...set].sort();
  }, [data, overview]);

  const days = useMemo(() => [...(data?.days ?? [])].reverse(), [data]);

  const commit = (dayDate: string, exchange: string, input: HTMLInputElement, original: SnapshotValue | undefined) => {
    const next = input.value.trim();
    const orig = toInput(original?.amount ?? null);
    if (next === orig) {
      input.value = orig;
      return;
    }
    if (next === "") {
      // Clearing a manual value reverts the day to auto-carried; a blank auto cell stays as it was.
      if (original?.manual) save.mutate({ date: dayDate, entries: [{ exchange, amount: null }] });
      else input.value = orig;
      return;
    }
    if (!Number.isFinite(Number(next)) || Number(next) < 0) {
      input.value = orig;
      return;
    }
    save.mutate({ date: dayDate, entries: [{ exchange, amount: next }] });
  };

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("capital.snapshots.title")}</h2>
      </CardHeader>
      <CardBody className="space-y-4">
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("capital.snapshots.hint")}</p>

        <div className="flex flex-wrap items-end gap-3">
          <div>
            <Label htmlFor="snap-from">{t("capital.snapshots.from")}</Label>
            <Input id="snap-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div>
            <Label htmlFor="snap-to">{t("capital.snapshots.to")}</Label>
            <Input id="snap-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
        </div>

        {days.length === 0 ? (
          <p className="py-4 text-center text-sm text-gray-500 dark:text-gray-400">{t("capital.snapshots.empty")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border dark:border-gray-700">
                  <th className={thClass}>{t("capital.snapshots.date")}</th>
                  {exchanges.map((ex) => (
                    <th key={ex} className={thRight}>
                      {ex}
                    </th>
                  ))}
                  <th className={thRight}>{t("capital.snapshots.total")}</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {days.map((day) => {
                  const total = day.values.reduce((acc, v) => acc + Number(v.amount), 0);
                  return (
                    <tr key={day.date} className="border-b border-border last:border-0 dark:border-gray-700">
                      <td className="py-1.5 pr-3 tabular-nums">{day.date}</td>
                      {exchanges.map((ex) => {
                        const value = day.values.find((v) => v.exchange === ex);
                        return (
                          <td key={ex} className="py-1.5 pr-3 text-right">
                            <Input
                              key={`${day.date}-${ex}-${value?.amount ?? ""}-${value?.manual ?? false}`}
                              type="number"
                              min="0"
                              step="any"
                              inputMode="decimal"
                              placeholder="0"
                              className={cn(
                                "ml-auto w-28 px-2 py-1 text-right",
                                value?.manual && "border-amber-400 dark:border-amber-500",
                              )}
                              title={value?.manual ? t("capital.snapshots.manualTitle") : undefined}
                              defaultValue={toInput(value?.amount ?? null)}
                              onBlur={(e) => commit(day.date, ex, e.target, value)}
                            />
                          </td>
                        );
                      })}
                      <td className="py-1.5 pr-3 text-right font-medium tabular-nums">{fmtUsd(total)}</td>
                      <td className="py-1.5 text-right">
                        <Button
                          variant="ghost"
                          className="px-2 py-1"
                          aria-label={t("capital.snapshots.deleteDay")}
                          disabled={deleteDay.isPending}
                          onClick={() => {
                            if (window.confirm(t("capital.snapshots.deleteDayConfirm"))) deleteDay.mutate(day.date);
                          }}
                        >
                          {t("common.delete")}
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </CardBody>
    </Card>
  );
}
