import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  useBackfillSnapshots,
  useCapital,
  useUpdateCapitalSettings,
  type SnapshotFrequency,
} from "@/api/capital";
import { Button, Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";

const FREQUENCIES: SnapshotFrequency[] = ["DAILY", "WEEKLY", "MONTHLY"];

/** Strip a backend NUMERIC string ("1.000") down to an editable value ("1"). */
function toInput(amount: string | undefined): string {
  if (!amount) return "";
  const n = Number(amount);
  return Number.isFinite(n) ? String(n) : amount;
}

/**
 * Risk percentages and the snapshot-job frequency. The old per-exchange capital inputs are
 * superseded by the Capital page's adjustment history — this card links there instead.
 */
export function CapitalRiskCard({ profileId }: { profileId: string }) {
  const { t } = useTranslation();
  const { data } = useCapital(profileId);
  const updateMut = useUpdateCapitalSettings(profileId);
  const backfillMut = useBackfillSnapshots(profileId);

  const [pct1, setPct1] = useState("");
  const [pct2, setPct2] = useState("");
  const [frequency, setFrequency] = useState<SnapshotFrequency>("DAILY");

  // Seed the form whenever the server data changes (load, profile switch, post-save refetch).
  useEffect(() => {
    if (!data) return;
    setPct1(toInput(data.riskPercents.pct1));
    setPct2(toInput(data.riskPercents.pct2));
    setFrequency(data.snapshotFrequency);
  }, [data]);

  if (!data) return null;

  const onSave = () => {
    updateMut.mutate({
      riskPercents: { pct1: pct1.trim() || "0", pct2: pct2.trim() || "0" },
      snapshotFrequency: frequency,
    });
  };

  const onBackfill = () => {
    backfillMut.mutate(undefined, {
      onSuccess: (r) => showToast(t("settings.capital.backfillDone", { created: r.created, updated: r.updated })),
    });
  };

  const frequencyLabel = (f: SnapshotFrequency) => t(`settings.capital.frequency.${f.toLowerCase()}`);

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("settings.capital.title")}</h2>
      </CardHeader>
      <CardBody className="space-y-6">
        <div>
          <p className="text-sm text-gray-600 dark:text-gray-300">{t("settings.capital.historyHint")}</p>
          <Link to="/capital" className="mt-1 inline-block text-sm text-primary hover:underline">
            {t("settings.capital.manageLink")} →
          </Link>
        </div>

        <div>
          <Label>{t("settings.capital.riskLabel")}</Label>
          <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("settings.capital.riskHint")}</p>
          <div className="grid grid-cols-2 gap-3 sm:max-w-xs">
            <div>
              <Label htmlFor="risk-pct-1">{t("settings.capital.riskPct", { n: 1 })}</Label>
              <Input
                id="risk-pct-1"
                type="number"
                min="0"
                max="100"
                step="any"
                inputMode="decimal"
                value={pct1}
                onChange={(e) => setPct1(e.target.value)}
              />
            </div>
            <div>
              <Label htmlFor="risk-pct-2">{t("settings.capital.riskPct", { n: 2 })}</Label>
              <Input
                id="risk-pct-2"
                type="number"
                min="0"
                max="100"
                step="any"
                inputMode="decimal"
                value={pct2}
                onChange={(e) => setPct2(e.target.value)}
              />
            </div>
          </div>
        </div>

        <div>
          <Label htmlFor="snap-frequency">{t("settings.capital.frequencyLabel")}</Label>
          <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("settings.capital.frequencyHint")}</p>
          <Select
            id="snap-frequency"
            className="max-w-xs"
            value={frequency}
            onChange={(e) => setFrequency(e.target.value as SnapshotFrequency)}
          >
            {FREQUENCIES.map((f) => (
              <option key={f} value={f}>
                {frequencyLabel(f)}
              </option>
            ))}
          </Select>
          <div className="mt-3">
            <Button
              variant="secondary"
              onClick={onBackfill}
              disabled={backfillMut.isPending || !data.hasAnchors}
            >
              {backfillMut.isPending ? t("settings.capital.backfillRunning") : t("settings.capital.backfill")}
            </Button>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
              {data.hasAnchors ? t("settings.capital.backfillHint") : t("settings.capital.backfillNeedsAnchor")}
            </p>
          </div>
        </div>

        <p className="text-xs text-gray-500 dark:text-gray-400">
          {t("settings.capital.timeZoneNote", { tz: data.timeZone })}
        </p>

        <Button onClick={onSave} disabled={updateMut.isPending}>
          {t("common.save")}
        </Button>
      </CardBody>
    </Card>
  );
}
