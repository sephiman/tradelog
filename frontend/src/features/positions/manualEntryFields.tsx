import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { usePositionExchanges } from "@/api/positions";
import { isArchived, useTaxonomy, type TagGroup } from "@/api/taxonomy";
import { Input, Label, Select } from "@/components/ui/primitives";
import { useTagLabel } from "@/lib/tagLabel";

/** Fields both manual-entry forms need, each carrying logic worth stating once. */

/**
 * The tag group a hand-entered record is filed under, falling back to whatever group exists.
 * [ready] gates the forms: they read the record's current tag once, at mount, so opening one before
 * the taxonomy lands would show it as untagged.
 */
export function useOrigenGroup(): { group: TagGroup | undefined; ready: boolean } {
  const { data: taxonomy, isPending } = useTaxonomy();
  const group = useMemo(
    () => (taxonomy ?? []).find((g) => g.code === "origen") ?? (taxonomy ?? [])[0],
    [taxonomy],
  );
  return { group, ready: !isPending };
}

/** Venue field suggesting the spellings already in use, so a new row folds onto them. */
export function ExchangeField({
  id,
  profileId,
  value,
  onChange,
}: {
  id: string;
  profileId: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  const { data: exchanges = [] } = usePositionExchanges(profileId);
  const listId = `${id}-options`;
  return (
    <>
      <Input
        id={id}
        list={listId}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={t("trades.exchangePlaceholder")}
        autoComplete="off"
      />
      <datalist id={listId}>
        {exchanges.map((ex) => (
          <option key={ex} value={ex} />
        ))}
      </datalist>
    </>
  );
}

export function OrigenField({
  id,
  group,
  value,
  onChange,
}: {
  id: string;
  group: TagGroup;
  value: string;
  onChange: (tagId: string) => void;
}) {
  const { t } = useTranslation();
  const tagLabel = useTagLabel();
  return (
    <Select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t("common.none")}</option>
      {/* An archived tag stays offered while this record still carries it, so an edit never drops it. */}
      {group.tags
        .filter((tag) => !isArchived(tag) || tag.id === value)
        .map((tag) => (
          <option key={tag.id} value={tag.id}>{tagLabel(tag)}</option>
        ))}
    </Select>
  );
}

/**
 * Flips a field to its other reading — a size typed as notional instead of quantity, a grid's PnL
 * read as gross instead of net. Labelled with the reading it switches to, so it fits beside the
 * field's own label where a pair of tabs would not.
 */
export function SwitchReading({ to, onClick }: { to: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="text-xs font-medium text-primary hover:underline">
      ⇄ {to}
    </button>
  );
}

/** Label and control on one line, for a field whose label shares the row with [SwitchReading]. */
export function SwitchableField({
  htmlFor,
  label,
  switchTo,
  onSwitch,
  hint,
  children,
}: {
  htmlFor: string;
  label: string;
  switchTo: string;
  onSwitch: () => void;
  hint?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between gap-2">
        <Label htmlFor={htmlFor}>{label}</Label>
        <SwitchReading to={switchTo} onClick={onSwitch} />
      </div>
      {children}
      {hint && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{hint}</p>}
    </div>
  );
}

/** A form row: label above the control, matching the dialogs' grid. */
export function Field({
  htmlFor,
  label,
  hint,
  children,
}: {
  htmlFor?: string;
  label: React.ReactNode;
  hint?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div>
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {hint && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{hint}</p>}
    </div>
  );
}
