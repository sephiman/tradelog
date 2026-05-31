import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCreateDataSource,
  useDataSources,
  useDeleteDataSource,
  type DataSource,
} from "@/api/dataSources";
import type { SourceKind } from "@/api/positions";
import { useSyncAll, useSyncOne } from "@/api/sync";
import { Badge, Button, Card, CardBody, CardHeader, Input, Select } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";
import { fmtDateTime } from "@/lib/format";
import { QuantfuryUploadCard } from "./QuantfuryUploadCard";
import { JournalCsvUploadCard } from "./JournalCsvUploadCard";

export function DataSourcesCard({ profileId, profileName }: { profileId: string; profileName: string }) {
  const { t } = useTranslation();
  const { data: sources = [] } = useDataSources(profileId);
  const createMut = useCreateDataSource(profileId);
  const deleteMut = useDeleteDataSource(profileId);
  const syncAll = useSyncAll(profileId);

  const [kind, setKind] = useState<SourceKind>("BITUNIX");
  const [label, setLabel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [apiSecret, setApiSecret] = useState("");

  const isApi = kind === "BITUNIX" || kind === "BINGX";

  const onCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!label.trim()) return;
    createMut.mutate(
      { kind, label: label.trim(), apiKey: isApi ? apiKey : undefined, apiSecret: isApi ? apiSecret : undefined },
      { onSuccess: () => { setLabel(""); setApiKey(""); setApiSecret(""); } },
    );
  };

  const onSyncAll = () =>
    syncAll.mutate(undefined, {
      onSuccess: (runs) => {
        const ins = runs.reduce((a, r) => a + r.inserted, 0);
        const upd = runs.reduce((a, r) => a + r.updated, 0);
        showToast(t("sync.synced", { inserted: ins, updated: upd }), "success");
      },
    });

  const hasApi = sources.some((s) => s.kind === "BITUNIX" || s.kind === "BINGX");

  return (
    <Card>
      <CardHeader className="flex items-center justify-between">
        <h2 className="font-semibold">{t("dataSources.title")} — {profileName}</h2>
        {hasApi && (
          <Button variant="secondary" disabled={syncAll.isPending} onClick={onSyncAll}>
            {t("dataSources.syncAll")}
          </Button>
        )}
      </CardHeader>
      <CardBody className="space-y-4">
        <ul className="space-y-3">
          {sources.map((s) => (
            <SourceRow key={s.id} profileId={profileId} source={s} onDelete={() => {
              if (confirm(t("dataSources.deleteConfirm"))) deleteMut.mutate(s.id);
            }} />
          ))}
          {sources.length === 0 && <li className="text-sm text-gray-500 dark:text-gray-400">{t("common.noData")}</li>}
        </ul>

        <form onSubmit={onCreate} className="space-y-3 border-t border-border pt-4 dark:border-gray-700">
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1">
              <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("dataSources.kind")}</span>
              <Select className="w-36" value={kind} onChange={(e) => setKind(e.target.value as SourceKind)}>
                <option value="BITUNIX">Bitunix</option>
                <option value="BINGX">BingX</option>
                <option value="QUANTFURY">Quantfury</option>
                <option value="JOURNAL_CSV">Journal CSV</option>
              </Select>
            </label>
            <label className="flex flex-1 flex-col gap-1">
              <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("dataSources.label")}</span>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder={kind} />
            </label>
            <Button type="submit" disabled={createMut.isPending || !label.trim()}>{t("dataSources.new")}</Button>
          </div>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            <span className="font-medium">{t("dataSources.coverageLabel")}:</span> {t(`dataSources.coverage.${kind}`)}
          </p>
          {isApi && (
            <div className="space-y-2">
              <p className="text-xs text-amber-700 dark:text-amber-400">{t("dataSources.readOnlyHint")}</p>
              <div className="flex flex-wrap gap-3">
                <Input className="flex-1" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder={t("dataSources.apiKey")} autoComplete="off" />
                <Input className="flex-1" type="password" value={apiSecret} onChange={(e) => setApiSecret(e.target.value)} placeholder={t("dataSources.apiSecret")} autoComplete="off" />
              </div>
            </div>
          )}
        </form>
      </CardBody>
    </Card>
  );
}

function SourceRow({ profileId, source, onDelete }: { profileId: string; source: DataSource; onDelete: () => void }) {
  const { t } = useTranslation();
  const syncOne = useSyncOne(profileId);
  const isApi = source.kind === "BITUNIX" || source.kind === "BINGX";

  const statusTone = source.status === "ACTIVE" ? "green" : source.status === "ERROR" ? "red" : "gray";
  const statusLabel =
    source.status === "ACTIVE" ? t("dataSources.active") : source.status === "ERROR" ? t("dataSources.error") : t("dataSources.disabled");

  const onSync = () =>
    syncOne.mutate(source.id, {
      onSuccess: (run) => {
        if (run.status === "ERROR") showToast(t("sync.failed"), "error");
        else showToast(t("sync.synced", { inserted: run.inserted, updated: run.updated }), "success");
      },
    });

  return (
    <li className="rounded-md border border-border p-3 dark:border-gray-700">
      <div className="flex flex-wrap items-center gap-3">
        <Badge tone="sky">{source.kind}</Badge>
        <span className="font-medium">{source.label}</span>
        <Badge tone={statusTone}>{statusLabel}</Badge>
        {source.status === "ERROR" && source.statusDetail && (
          <span className="text-xs text-red-600 dark:text-red-400">{source.statusDetail}</span>
        )}
        <span className="text-xs text-gray-500 dark:text-gray-400">
          {t("dataSources.positions")}: {source.positionCount}
        </span>
        <span className="text-xs text-gray-500 dark:text-gray-400">
          {t("dataSources.lastSynced")}: {source.lastSyncedAt ? fmtDateTime(source.lastSyncedAt) : t("dataSources.never")}
        </span>
        <div className="ml-auto flex items-center gap-2">
          {isApi && (
            <Button variant="secondary" disabled={syncOne.isPending} onClick={onSync}>{t("dataSources.sync")}</Button>
          )}
          <Button variant="ghost" onClick={onDelete}>{t("common.delete")}</Button>
        </div>
      </div>
      <p className="mt-2 text-xs text-gray-400 dark:text-gray-500">{t(`dataSources.coverage.${source.kind}`)}</p>
      {source.kind === "QUANTFURY" && (
        <div className="mt-3">
          <QuantfuryUploadCard profileId={profileId} dataSourceId={source.id} />
        </div>
      )}
      {source.kind === "JOURNAL_CSV" && (
        <div className="mt-3">
          <JournalCsvUploadCard profileId={profileId} dataSourceId={source.id} />
        </div>
      )}
    </li>
  );
}
