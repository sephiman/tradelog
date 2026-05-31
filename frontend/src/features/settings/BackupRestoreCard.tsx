import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { asApiError } from "@/api/client";
import { downloadBackup, importBackup, InvalidBackupFileError, type ImportSummary } from "@/api/backup";
import { Button, Card, CardBody, CardHeader, Modal } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";

/**
 * Whole-account backup. Download produces a portable JSON file (no API keys); restore REPLACES all of
 * the user's data with the file, so it's gated behind an explicit confirmation. After a successful
 * restore the app reloads, since profiles, taxonomy and positions have all changed underfoot.
 */
export function BackupRestoreCard() {
  const { t } = useTranslation();
  const fileRef = useRef<HTMLInputElement>(null);
  const [downloading, setDownloading] = useState(false);
  const [pending, setPending] = useState<File | null>(null);
  const [restoring, setRestoring] = useState(false);
  const [summary, setSummary] = useState<ImportSummary | null>(null);

  const onDownload = async () => {
    setDownloading(true);
    try {
      await downloadBackup();
    } catch (e) {
      showToast(asApiError(e).message, "error");
    } finally {
      setDownloading(false);
    }
  };

  const onPick = (file: File | null) => {
    if (fileRef.current) fileRef.current.value = "";
    if (file) setPending(file);
  };

  const onConfirmRestore = async () => {
    if (!pending) return;
    setRestoring(true);
    try {
      const result = await importBackup(pending);
      setPending(null);
      setSummary(result);
    } catch (e) {
      setPending(null);
      showToast(e instanceof InvalidBackupFileError ? t("backup.invalidFile") : asApiError(e).message, "error");
    } finally {
      setRestoring(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("backup.title")}</h2>
      </CardHeader>
      <CardBody className="space-y-4">
        <p className="text-sm text-gray-600 dark:text-gray-300">{t("backup.description")}</p>

        <div className="flex flex-wrap items-center gap-3">
          <Button variant="secondary" disabled={downloading} onClick={() => void onDownload()}>
            {t("backup.download")}
          </Button>
          <Button variant="secondary" onClick={() => fileRef.current?.click()}>
            {t("backup.restore")}
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={(e) => onPick(e.target.files?.[0] ?? null)}
          />
        </div>
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("backup.restoreHint")}</p>
      </CardBody>

      <Modal open={!!pending} onClose={() => !restoring && setPending(null)} title={t("backup.restore")}>
        <div className="space-y-4">
          <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
            {t("backup.replaceWarning")}
          </div>
          {pending && <p className="text-sm text-gray-600 dark:text-gray-300">{pending.name}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" disabled={restoring} onClick={() => setPending(null)}>
              {t("common.cancel")}
            </Button>
            <Button variant="danger" disabled={restoring} onClick={() => void onConfirmRestore()}>
              {t("backup.confirmReplace")}
            </Button>
          </div>
        </div>
      </Modal>

      <Modal open={!!summary} onClose={() => window.location.reload()} title={t("backup.restoredTitle")}>
        {summary && (
          <div className="space-y-4">
            <p className="text-sm text-gray-700 dark:text-gray-200">
              {t("backup.imported", { profiles: summary.profiles, positions: summary.positions })}
            </p>
            <div className="flex justify-end">
              <Button onClick={() => window.location.reload()}>{t("backup.reload")}</Button>
            </div>
          </div>
        )}
      </Modal>
    </Card>
  );
}
