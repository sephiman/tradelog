import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";

/**
 * Shared error state for failed queries. Without it a failed fetch renders as an empty list,
 * indistinguishable from a genuinely empty account.
 */
export function QueryError({ onRetry, className }: { onRetry: () => void; className?: string }) {
  const { t } = useTranslation();
  return (
    <div className={cn("flex flex-col items-center gap-3 py-8", className)}>
      <p className="text-sm text-red-600 dark:text-red-400">{t("common.loadFailed")}</p>
      <Button variant="secondary" onClick={onRetry}>
        {t("common.retry")}
      </Button>
    </div>
  );
}
