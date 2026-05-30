import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuantfuryExecute, useQuantfuryPreview, type QuantfuryPreview } from "@/api/quantfury";
import { asApiError } from "@/api/client";
import { Button, FieldError } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";
import { fmtDate, fmtUsd, pnlTone } from "@/lib/format";
import { cn } from "@/lib/cn";

export function QuantfuryUploadCard({ profileId, dataSourceId }: { profileId: string; dataSourceId: string }) {
  const { t } = useTranslation();
  const fileRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<QuantfuryPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewMut = useQuantfuryPreview(profileId, dataSourceId);
  const executeMut = useQuantfuryExecute(profileId, dataSourceId);

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
        showToast(t("quantfury.imported", { inserted: run.inserted, updated: run.updated }), "success");
        reset();
      },
      onError: (e) => setError(asApiError(e).message),
    });
  };

  return (
    <div className="rounded-md border border-dashed border-border p-3 dark:border-gray-600">
      <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("quantfury.hint")}</p>
      <div className="flex flex-wrap items-center gap-2">
        <input
          ref={fileRef}
          type="file"
          accept="application/pdf,.pdf"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setPreview(null); }}
          className="text-sm file:mr-3 file:rounded-md file:border-0 file:bg-gray-100 file:px-3 file:py-1.5 file:text-sm dark:file:bg-gray-700 dark:file:text-gray-200"
        />
        <Button variant="secondary" disabled={!file || previewMut.isPending} onClick={onPreview}>
          {t("quantfury.preview")}
        </Button>
        <Button disabled={!file || !preview || executeMut.isPending} onClick={onExecute}>
          {t("quantfury.import")}
        </Button>
      </div>
      <FieldError message={error} />

      {preview && (
        <div className="mt-3 rounded-md bg-gray-50 p-3 text-sm dark:bg-gray-900/40">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            <Stat label={t("quantfury.totalPositions")} value={String(preview.totalPositions)} />
            <Stat
              label={t("quantfury.sumPnl")}
              value={fmtUsd(preview.sumRealizedPnl, { sign: true })}
              className={pnlTone(preview.sumRealizedPnl)}
            />
            <Stat
              label={t("quantfury.dateRange")}
              value={preview.dateFrom ? `${fmtDate(preview.dateFrom)} → ${fmtDate(preview.dateTo!)}` : "—"}
            />
          </div>
          <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
            {t("quantfury.symbols")}: {preview.symbols.join(", ")}
          </p>
        </div>
      )}
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
