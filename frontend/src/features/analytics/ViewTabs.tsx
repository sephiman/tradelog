import { useTranslation } from "react-i18next";
import { Chip } from "@/components/ui/primitives";

export type ViewKey = "all" | "summary" | "performance" | "behavior" | "streaks" | "pairs" | "fees";

export const VIEW_KEYS: ViewKey[] = ["all", "summary", "performance", "behavior", "streaks", "pairs", "fees"];

export function ViewTabs({ value, onChange }: { value: ViewKey; onChange: (v: ViewKey) => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("analytics.viewsLabel")}>
      {VIEW_KEYS.map((k) => (
        <Chip key={k} active={value === k} onClick={() => onChange(k)}>
          {t(`analytics.tab.${k}`)}
        </Chip>
      ))}
    </div>
  );
}
