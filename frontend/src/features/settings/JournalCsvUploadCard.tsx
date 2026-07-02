import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useJournalCsvExecute, useJournalCsvPreview, type ImportWarning, type JournalCsvPreview } from "@/api/journalCsv";
import { asApiError } from "@/api/client";
import { Button, FieldError } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";
import { fmtDate, fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";
import { TradeCsvFormatHelp } from "./TradeCsvFormatHelp";

export function JournalCsvUploadCard({ profileId, dataSourceId }: { profileId: string; dataSourceId: string }) {
  const { t } = useTranslation();
  const fileRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<JournalCsvPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewMut = useJournalCsvPreview(profileId, dataSourceId);
  const executeMut = useJournalCsvExecute(profileId, dataSourceId);

  const reset = () => {
    setFile(null);
    setPreview(null);
    setError(null);
    if (fileRef.current) fileRef.current.value = "";
  };

  const onPreview = () => {
    if (!file) return;
    setError(null);
    previewMut.mutate(file, {
      onSuccess: setPreview,
      onError: (e) => setError(asApiError(e).message),
    });
  };

  const onExecute = () => {
    if (!file) return;
    executeMut.mutate(file, {
      onSuccess: (run) => {
        showToast(t("journalCsv.imported", { inserted: run.inserted, updated: run.updated }), "success");
        reset();
      },
      onError: (e) => setError(asApiError(e).message),
    });
  };

  return (
    <div className="rounded-md border border-dashed border-border p-3 dark:border-gray-600">
      <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("journalCsv.hint")}</p>
      <div className="flex flex-wrap items-center gap-2">
        <input
          ref={fileRef}
          type="file"
          accept="text/csv,.csv"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setPreview(null); }}
          className="w-full min-w-0 text-sm file:mr-3 file:rounded-md file:border-0 file:bg-gray-100 file:px-3 file:py-1.5 file:text-sm sm:w-auto dark:file:bg-gray-700 dark:file:text-gray-200"
        />
        <Button variant="secondary" disabled={!file || previewMut.isPending} onClick={onPreview}>
          {t("journalCsv.preview")}
        </Button>
        <Button disabled={!file || !preview || executeMut.isPending} onClick={onExecute}>
          {t("journalCsv.import")}
        </Button>
      </div>
      <FieldError message={error} />

      {preview && (
        <div className="mt-3 rounded-md bg-gray-50 p-3 text-sm dark:bg-gray-900/40">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            <Stat label={t("journalCsv.totalPositions")} value={String(preview.totalPositions)} />
            <Stat
              label={t("journalCsv.dateRange")}
              value={preview.dateFrom ? `${fmtDate(preview.dateFrom)} → ${fmtDate(preview.dateTo!)}` : "—"}
            />
            <Stat
              label={t("journalCsv.netPnl")}
              value={fmtUsd(preview.sumNetPnl, { sign: true })}
              className={pnlTone(preview.sumNetPnl)}
            />
            <Stat label={t("journalCsv.grossPnl")} value={fmtUsd(preview.sumRealizedPnl, { sign: true })} />
            <Stat label={t("journalCsv.fees")} value={fmtUsd(preview.sumFees)} />
          </div>
          {preview.symbols.length > 0 && (
            <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
              {t("journalCsv.symbols")}: {preview.symbols.join(", ")}
            </p>
          )}
          <Warnings warnings={preview.warnings} />
        </div>
      )}

      <TradeCsvFormatHelp />
    </div>
  );
}

function Warnings({ warnings }: { warnings: ImportWarning[] }) {
  const { t } = useTranslation();
  if (!warnings.length) return null;
  const headline = (w: ImportWarning): string => {
    if (w.code === "CLOSED_BEFORE_OPENED") return t("journalCsv.warnClosedBeforeOpened", { count: w.count });
    if (w.code === "OVERLAP_SUSPECTED") return t("journalCsv.warnOverlap", { count: w.count });
    return w.code;
  };
  return (
    <div className="mt-3 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
      <p className="mb-1 font-semibold">{t("journalCsv.warningsTitle")}</p>
      <ul className="space-y-2">
        {warnings.map((w) => (
          <li key={w.code}>
            <p>{headline(w)}</p>
            {w.rows.length > 0 && (
              <ul className="mt-1 space-y-0.5 pl-1">
                {w.rows.map((r, i) => (
                  <li key={r.row ?? i} className="tabular-nums">
                    {r.row != null && <span className="font-semibold">{t("journalCsv.warnRow", { row: r.row })} </span>}
                    {r.symbol} {r.side} · {fmtDate(r.openedAt)} → {fmtDate(r.closedAt)}
                  </li>
                ))}
                {w.count > w.rows.length && (
                  <li className="italic">{t("journalCsv.warnMore", { count: w.count - w.rows.length })}</li>
                )}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Stat({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <div>
      <p className="text-xs text-gray-500 dark:text-gray-400">{label}</p>
      <p className={cn("font-semibold tabular-nums", className)}>{value}</p>
    </div>
  );
}
