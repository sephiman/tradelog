import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";

export type ViewKey = "all" | "summary" | "performance" | "behavior" | "streaks" | "pairs" | "fees" | "capital";

export const VIEW_KEYS: ViewKey[] = ["all", "summary", "performance", "behavior", "streaks", "pairs", "fees", "capital"];

export function ViewTabs({ value, onChange }: { value: ViewKey; onChange: (v: ViewKey) => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("analytics.viewsLabel")}>
      {VIEW_KEYS.map((k) => (
        <button
          key={k}
          type="button"
          role="tab"
          aria-selected={value === k}
          onClick={() => onChange(k)}
          className={cn(
            "inline-flex items-center rounded-full border px-3 py-1 text-sm transition-colors",
            value === k
              ? "border-violet-500 bg-violet-500 text-white shadow-sm hover:bg-violet-600 dark:border-violet-500 dark:bg-violet-600 dark:hover:bg-violet-500"
              : "border-border bg-white text-gray-700 hover:bg-violet-50 hover:text-violet-700 dark:bg-gray-700 dark:text-gray-200 dark:border-gray-600 dark:hover:bg-gray-600",
          )}
        >
          {t(`analytics.tab.${k}`)}
        </button>
      ))}
    </div>
  );
}
