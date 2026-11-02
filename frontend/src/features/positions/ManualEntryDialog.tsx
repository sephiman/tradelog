import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { Position } from "@/api/positions";
import { Chip, Modal } from "@/components/ui/primitives";
import { GridRunForm } from "./GridRunForm";
import { TradeForm } from "./TradeForm";
import { useOrigenGroup } from "./manualEntryFields";

type EntryMode = "trade" | "grid";

const MODES: EntryMode[] = ["trade", "grid"];

/**
 * The two ways to record something by hand. A grid-bot run is not a trade — no single entry or exit
 * price, no quantity — so it gets its own form rather than a trade form with unfillable fields.
 * Editing opens the form the record belongs to; its kind cannot be changed afterwards.
 */
export function ManualEntryDialog({
  open,
  onClose,
  profileId,
  position,
}: {
  open: boolean;
  onClose: () => void;
  profileId: string;
  /** Present when editing a hand-added record; absent when adding a new one. */
  position?: Position;
}) {
  const { t } = useTranslation();
  const { group: origen, ready } = useOrigenGroup();
  const [chosen, setChosen] = useState<EntryMode>("trade");
  const mode: EntryMode = position ? (position.kind === "GRID_BOT" ? "grid" : "trade") : chosen;
  const titleKey = mode === "grid" ? "grids" : "trades";
  const isSyncedOrImported = position && !position.manual;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t(`${titleKey}.${position ? "editTitle" : "addTitle"}`)}
      className="max-w-2xl"
    >
      <div className="space-y-3">
        {isSyncedOrImported && (
          <div className="flex items-start gap-2.5 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900 dark:border-amber-900/50 dark:bg-amber-950/40 dark:text-amber-200">
            <span className="shrink-0 text-base leading-none">⚠️</span>
            <div>
              <p className="font-medium">{t("positions.editSyncedWarningTitle")}</p>
              <p className="mt-0.5 text-amber-800 dark:text-amber-300">
                {t("positions.editSyncedWarningBody")}
              </p>
            </div>
          </div>
        )}

        {!position && (
          <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("trades.entryModes")}>
            {MODES.map((m) => (
              <Chip key={m} role="tab" aria-selected={mode === m} active={mode === m} onClick={() => setChosen(m)}>
                {t(`${m === "grid" ? "grids" : "trades"}.modeTab`)}
              </Chip>
            ))}
          </div>
        )}

        {/* The forms read the record's current tag once, at mount, so they wait for the taxonomy. */}
        {!ready ? (
          <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
        ) : mode === "grid" ? (
          <GridRunForm profileId={profileId} position={position} origen={origen} onDone={onClose} />
        ) : (
          <TradeForm profileId={profileId} position={position} origen={origen} onDone={onClose} />
        )}
      </div>
    </Modal>
  );
}
