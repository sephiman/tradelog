import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCreateDataSource,
  useDataSources,
  useDeleteDataSource,
  useUpdateDataSource,
  type DataSource,
} from "@/api/dataSources";
import type { SourceKind } from "@/api/positions";
import { summarizeSyncAll, syncableSources, useSyncAll, useSyncOne } from "@/api/sync";
import { Badge, Button, Card, CardBody, CardHeader, Input, Select } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";
import { dateInputToIso, fmtDate, fmtDateTime } from "@/lib/format";
import {
  isApiKind,
  needsPassphrase,
  retirementOf,
  SOURCE_KINDS,
  SOURCE_LABELS,
  type Retirement,
} from "@/lib/sourceKinds";
import { ExchangeSetupHelp } from "./ExchangeSetupHelp";
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
  const [passphrase, setPassphrase] = useState("");
  const [syncFrom, setSyncFrom] = useState("");

  const isApi = isApiKind(kind);
  const wantsPassphrase = needsPassphrase(kind);

  const onCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!label.trim()) return;
    createMut.mutate(
      {
        kind,
        label: label.trim(),
        apiKey: isApi ? apiKey : undefined,
        apiSecret: isApi ? apiSecret : undefined,
        // Only for venues that use one, so switching the dropdown can't leave a stale value.
        passphrase: wantsPassphrase ? passphrase : undefined,
        syncFrom: isApi ? dateInputToIso(syncFrom) : undefined,
      },
      {
        onSuccess: () => {
          setLabel("");
          setApiKey("");
          setApiSecret("");
          setPassphrase("");
          setSyncFrom("");
        },
      },
    );
  };

  const onSyncAll = () =>
    syncAll.mutate(undefined, {
      onSuccess: (runs) => {
        const { key, params, tone } = summarizeSyncAll(runs, syncableSources(sources).length);
        showToast(t(key, params), tone);
      },
    });

  const hasApi = sources.some((s) => isApiKind(s.kind));

  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="min-w-0 font-semibold">{t("dataSources.title")} — {profileName}</h2>
        {hasApi && (
          <Button variant="secondary" className="shrink-0" disabled={syncAll.isPending} onClick={onSyncAll}>
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

        <form onSubmit={onCreate} className="space-y-3 border-t border-border pt-4">
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1">
              <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("dataSources.kind")}</span>
              <Select className="w-44" value={kind} onChange={(e) => setKind(e.target.value as SourceKind)}>
                {SOURCE_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {SOURCE_LABELS[k]}
                  </option>
                ))}
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
                {wantsPassphrase && (
                  <Input className="flex-1" type="password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} placeholder={t("dataSources.passphrase")} autoComplete="off" />
                )}
              </div>
              {wantsPassphrase && (
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("dataSources.passphraseHint")}</p>
              )}
              <label className="flex flex-col gap-1">
                <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("dataSources.syncFrom")}</span>
                <Input className="w-44" type="date" value={syncFrom} onChange={(e) => setSyncFrom(e.target.value)} />
              </label>
              <p className="text-xs text-gray-500 dark:text-gray-400">{t("dataSources.syncFromHint")}</p>
            </div>
          )}
          {/* Outside the API block on purpose: Quantfury has no key to paste but still needs its export steps. */}
          <ExchangeSetupHelp kind={kind} />
        </form>
      </CardBody>
    </Card>
  );
}

function SourceRow({ profileId, source, onDelete }: { profileId: string; source: DataSource; onDelete: () => void }) {
  const { t } = useTranslation();
  const syncOne = useSyncOne(profileId);
  const updateMut = useUpdateDataSource(profileId);
  const [editingKeys, setEditingKeys] = useState(false);
  const isApi = isApiKind(source.kind);
  const isDisabled = source.status === "DISABLED";
  const retirement = retirementOf(source.kind);

  const onToggleEnabled = () =>
    updateMut.mutate(
      { id: source.id, body: { status: isDisabled ? "ACTIVE" : "DISABLED" } },
      { onSuccess: () => showToast(t(isDisabled ? "dataSources.enabled" : "dataSources.disabledToast"), "success") },
    );

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
    <li className="rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center gap-3">
        <Badge tone="sky">{SOURCE_LABELS[source.kind]}</Badge>
        <span className="font-medium">{source.label}</span>
        <Badge tone={statusTone}>{statusLabel}</Badge>
        {source.status === "ERROR" && source.statusDetail && (
          <span className="text-xs text-red-600 dark:text-red-400">{source.statusDetail}</span>
        )}
        {isApi && (
          source.hasCredentials ? (
            <span className="text-xs text-green-700 dark:text-green-400">{t("dataSources.credentialsStored")}</span>
          ) : (
            <span className="text-xs text-amber-700 dark:text-amber-400">{t("dataSources.noCredentials")}</span>
          )
        )}
        <span className="text-xs text-gray-500 dark:text-gray-400">
          {t("dataSources.positions")}: {source.positionCount}
        </span>
        <span className="text-xs text-gray-500 dark:text-gray-400">
          {t("dataSources.lastSynced")}: {source.lastSyncedAt ? fmtDateTime(source.lastSyncedAt) : t("dataSources.never")}
        </span>
        {isApi && source.syncFrom && (
          <span className="text-xs text-gray-500 dark:text-gray-400">
            {t("dataSources.syncFromLabel")}: {fmtDate(source.syncFrom)}
          </span>
        )}
        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          {isApi && (
            <Button variant="ghost" onClick={() => setEditingKeys((v) => !v)}>
              {source.hasCredentials ? t("dataSources.rotate") : t("dataSources.addKeys")}
            </Button>
          )}
          {isApi && (
            <Button variant="ghost" disabled={updateMut.isPending} onClick={onToggleEnabled}>
              {isDisabled ? t("dataSources.enable") : t("dataSources.disable")}
            </Button>
          )}
          {isApi && !isDisabled && (
            <Button variant="secondary" disabled={syncOne.isPending} onClick={onSync}>{t("dataSources.sync")}</Button>
          )}
          <Button variant="ghost" onClick={onDelete}>{t("common.delete")}</Button>
        </div>
      </div>
      {retirement && <RetirementNotice kind={source.kind} retirement={retirement} />}
      {isApi && editingKeys && (
        <CredentialsEditor profileId={profileId} source={source} onDone={() => setEditingKeys(false)} />
      )}
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

/** Warns before a venue shuts down, then states the fact. Nothing is ever deleted. */
function RetirementNotice({ kind, retirement }: { kind: SourceKind; retirement: Retirement }) {
  const { t } = useTranslation();
  // Plain calendar date: a zone shift would show the wrong day either side of the closing date.
  const args = { venue: SOURCE_LABELS[kind], date: retirement.date };
  return retirement.closed ? (
    <p className="mt-3 rounded-md bg-gray-100 p-2 text-xs text-gray-600 dark:bg-surface-inset dark:text-gray-300">
      {t("dataSources.retirement.closed", args)}
    </p>
  ) : (
    <p className="mt-3 rounded-md bg-amber-50 p-2 text-xs text-amber-800 dark:bg-amber-950 dark:text-amber-300">
      {t("dataSources.retirement.closing", args)}
    </p>
  );
}

/**
 * Inline editor to set or rotate an API source's credentials without recreating it. Recreating would
 * cascade-delete the source's positions, so this is the safe path for expired keys and for attaching
 * keys to a credential-less source restored from a backup.
 */
function CredentialsEditor({ profileId, source, onDone }: { profileId: string; source: DataSource; onDone: () => void }) {
  const { t } = useTranslation();
  const updateMut = useUpdateDataSource(profileId);
  const [apiKey, setApiKey] = useState("");
  const [apiSecret, setApiSecret] = useState("");
  const [passphrase, setPassphrase] = useState("");

  // Rotation replaces the whole set: stored secrets never reach the browser, so nothing to merge into.
  const wantsPassphrase = needsPassphrase(source.kind);
  const complete = !!apiKey.trim() && !!apiSecret.trim() && (!wantsPassphrase || !!passphrase.trim());

  const onSave = () => {
    if (!complete) return;
    updateMut.mutate(
      {
        id: source.id,
        body: {
          apiKey: apiKey.trim(),
          apiSecret: apiSecret.trim(),
          passphrase: wantsPassphrase ? passphrase.trim() : undefined,
        },
      },
      {
        onSuccess: () => {
          showToast(t("dataSources.credentialsSaved"), "success");
          setApiKey("");
          setApiSecret("");
          setPassphrase("");
          onDone();
        },
        // Errors surface via the global mutation-cache toast with the API's specific message.
      },
    );
  };

  return (
    <div className="mt-3 rounded-md border border-dashed border-border-strong p-3">
      <p className="mb-2 text-xs text-amber-700 dark:text-amber-400">{t("dataSources.readOnlyHint")}</p>
      <div className="flex flex-wrap gap-3">
        <Input className="flex-1" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder={t("dataSources.apiKey")} autoComplete="off" />
        <Input className="flex-1" type="password" value={apiSecret} onChange={(e) => setApiSecret(e.target.value)} placeholder={t("dataSources.apiSecret")} autoComplete="off" />
        {wantsPassphrase && (
          <Input className="flex-1" type="password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} placeholder={t("dataSources.passphrase")} autoComplete="off" />
        )}
      </div>
      {wantsPassphrase && (
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("dataSources.passphraseHint")}</p>
      )}
      {/* Rotating a key means going back to the exchange, so the same instructions belong here too. */}
      <ExchangeSetupHelp kind={source.kind} className="mt-2" />
      <div className="mt-2 flex justify-end gap-2">
        <Button variant="ghost" onClick={onDone}>{t("common.cancel")}</Button>
        <Button disabled={updateMut.isPending || !complete} onClick={onSave}>
          {t("common.save")}
        </Button>
      </div>
    </div>
  );
}
