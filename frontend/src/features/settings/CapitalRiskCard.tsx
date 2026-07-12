import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useCapital, useUpdateCapital, type CapitalSettings } from "@/api/capital";
import { Button, Card, CardBody, CardHeader, Input, Label } from "@/components/ui/primitives";

/** Strip a backend NUMERIC string ("1000.00000000") down to an editable value ("1000"). */
function toInput(amount: string | undefined): string {
  if (!amount) return "";
  const n = Number(amount);
  return Number.isFinite(n) ? String(n) : amount;
}

function buildState(data: CapitalSettings) {
  const byExchange: Record<string, string> = {};
  for (const e of data.entries) byExchange[e.exchange] = toInput(e.amount);
  const amounts: Record<string, string> = {};
  for (const ex of data.knownExchanges) amounts[ex] = byExchange[ex] ?? "";
  return {
    amounts,
    pct1: toInput(data.riskPercents.pct1),
    pct2: toInput(data.riskPercents.pct2),
  };
}

/** Trading capital per exchange (USDT) + the two configurable risk percentages. */
export function CapitalRiskCard({ profileId }: { profileId: string }) {
  const { t } = useTranslation();
  const { data } = useCapital(profileId);
  const updateMut = useUpdateCapital(profileId);

  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [pct1, setPct1] = useState("");
  const [pct2, setPct2] = useState("");

  // Seed the form whenever the server data changes (load, profile switch, post-save refetch).
  useEffect(() => {
    if (!data) return;
    const s = buildState(data);
    setAmounts(s.amounts);
    setPct1(s.pct1);
    setPct2(s.pct2);
  }, [data]);

  if (!data) return null;

  const onSave = () => {
    updateMut.mutate(
      {
        entries: data.knownExchanges.map((ex) => ({
          exchange: ex,
          amount: amounts[ex]?.trim() ? amounts[ex].trim() : null,
        })),
        riskPercents: { pct1: pct1.trim() || "0", pct2: pct2.trim() || "0" },
      });
  };

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("settings.capital.title")}</h2>
      </CardHeader>
      <CardBody className="space-y-6">
        <div>
          <Label>{t("settings.capital.exchangesLabel")}</Label>
          <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("settings.capital.exchangesHint")}</p>
          {data.knownExchanges.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("settings.capital.noExchanges")}</p>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {data.knownExchanges.map((ex) => (
                <div key={ex}>
                  <Label htmlFor={`cap-${ex}`}>{ex}</Label>
                  <Input
                    id={`cap-${ex}`}
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
          )}
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

        <Button onClick={onSave} disabled={updateMut.isPending}>
          {t("common.save")}
        </Button>
      </CardBody>
    </Card>
  );
}
