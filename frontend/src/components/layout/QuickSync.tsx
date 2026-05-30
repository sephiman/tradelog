import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { useDataSources } from "@/api/dataSources";
import { useSyncAll } from "@/api/sync";
import { Button, Modal, Select } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";
import { QuantfuryUploadCard } from "@/features/settings/QuantfuryUploadCard";

/**
 * Header split button: the main action syncs the active profile's API exchanges
 * (Bitunix/BingX) in one click; the caret menu also exposes the Quantfury PDF
 * import, which has no API and can only be updated by uploading a report.
 */
export function QuickSync() {
  const { t } = useTranslation();
  const { activeProfileId } = useActiveProfile();
  const { data: sources = [] } = useDataSources(activeProfileId);
  const syncAll = useSyncAll(activeProfileId ?? "");

  const [menuOpen, setMenuOpen] = useState(false);
  const [qfOpen, setQfOpen] = useState(false);
  const [qfSourceId, setQfSourceId] = useState("");
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, [menuOpen]);

  const quantfurySources = sources.filter((s) => s.kind === "QUANTFURY");
  const hasApi = sources.some((s) => s.kind === "BITUNIX" || s.kind === "BINGX");
  const hasQuantfury = quantfurySources.length > 0;

  // Nothing to sync or import yet — keep the header clean.
  if (!activeProfileId || sources.length === 0) return null;

  const onSyncExchanges = () => {
    setMenuOpen(false);
    syncAll.mutate(undefined, {
      onSuccess: (runs) => {
        const ins = runs.reduce((a, r) => a + r.inserted, 0);
        const upd = runs.reduce((a, r) => a + r.updated, 0);
        showToast(t("sync.synced", { inserted: ins, updated: upd }), "success");
      },
      onError: () => showToast(t("sync.failed"), "error"),
    });
  };

  const openQuantfury = () => {
    setMenuOpen(false);
    setQfSourceId(quantfurySources[0]?.id ?? "");
    setQfOpen(true);
  };

  return (
    <div ref={wrapRef} className="relative">
      <div className="inline-flex">
        <Button
          variant="secondary"
          className="rounded-r-none"
          disabled={!hasApi || syncAll.isPending}
          onClick={onSyncExchanges}
          title={hasApi ? undefined : t("quickSync.noExchanges")}
        >
          {syncAll.isPending ? t("quickSync.syncing") : t("quickSync.label")}
        </Button>
        <Button
          variant="secondary"
          aria-label={t("quickSync.more")}
          aria-expanded={menuOpen}
          className="rounded-l-none border-l-0 px-2"
          onClick={() => setMenuOpen((o) => !o)}
        >
          <span aria-hidden>▾</span>
        </Button>
      </div>

      {menuOpen && (
        <div className="absolute right-0 z-20 mt-1 w-60 rounded-md border border-border bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800">
          <MenuItem onClick={onSyncExchanges} disabled={!hasApi}>
            {t("quickSync.syncExchanges")}
          </MenuItem>
          <MenuItem onClick={openQuantfury} disabled={!hasQuantfury}>
            {t("quickSync.importQuantfury")}
          </MenuItem>
        </div>
      )}

      <Modal open={qfOpen} onClose={() => setQfOpen(false)} title={t("quantfury.title")}>
        {quantfurySources.length > 1 && (
          <Select className="mb-3" value={qfSourceId} onChange={(e) => setQfSourceId(e.target.value)}>
            {quantfurySources.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </Select>
        )}
        {qfSourceId && (
          <QuantfuryUploadCard key={qfSourceId} profileId={activeProfileId} dataSourceId={qfSourceId} />
        )}
      </Modal>
    </div>
  );
}

function MenuItem({ children, onClick, disabled }: { children: React.ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="block w-full px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent dark:text-gray-200 dark:hover:bg-gray-700"
    >
      {children}
    </button>
  );
}
