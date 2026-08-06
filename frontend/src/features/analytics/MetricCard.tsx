import { useEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { InfoTooltip } from "./InfoTooltip";

function MaximizeIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
    </svg>
  );
}

/** A dashboard card: title, an optional info tooltip, and a body. Used by every metric/chart. */
export function MetricCard({
  title,
  info,
  action,
  children,
  className,
  allowExpand = true,
}: {
  title: ReactNode;
  info?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  allowExpand?: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    if (!expanded) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setExpanded(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [expanded]);

  return (
    <>
      <Card className={className}>
        <CardHeader className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <h3 className="font-semibold">{title}</h3>
            {info && <InfoTooltip text={info} />}
          </div>
          <div className="flex items-center gap-2">
            {action}
            {allowExpand && (
              <button
                type="button"
                onClick={() => setExpanded(true)}
                title={t("analytics.maximize")}
                aria-label={t("analytics.maximize")}
                className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-surface-raised dark:hover:text-gray-200 transition-colors"
              >
                <MaximizeIcon />
              </button>
            )}
          </div>
        </CardHeader>
        <CardBody>{children}</CardBody>
      </Card>

      {expanded && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 sm:p-6"
          onMouseDown={() => setExpanded(false)}
        >
          <div
            role="dialog"
            aria-modal="true"
            className="flex flex-col w-full max-w-6xl h-[85vh] rounded-xl border border-border bg-white shadow-2xl dark:bg-surface overflow-hidden"
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-border p-4">
              <div className="flex items-center gap-2">
                <h3 className="text-lg font-semibold">{title}</h3>
                {info && <InfoTooltip text={info} />}
              </div>
              <div className="flex items-center gap-3">
                {action}
                <button
                  type="button"
                  onClick={() => setExpanded(false)}
                  title={t("common.close")}
                  aria-label={t("common.close")}
                  className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-surface-raised transition-colors"
                >
                  ✕
                </button>
              </div>
            </div>
            <div className="flex-1 min-h-0 p-4 sm:p-6 w-full overflow-y-auto [&>div]:!h-full [&_.h-44]:!h-full [&_.md\:h-72]:!h-full [&_.md\:h-80]:!h-full [&_.h-72]:!h-full [&_.h-80]:!h-full">
              {children}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

