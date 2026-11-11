import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useAiAnalysisRuns, useAiOn, useDeleteAiAnalysisRun, useMonthlyRoiYears, useStartAiAnalysis } from "@/api/ai";
import type { AiCitation, AiInsufficientItem, AiObservation, AiRun } from "@/api/ai";
import { useCapital, useRoi } from "@/api/capital";
import { asApiError } from "@/api/client";
import { Card, CardBody } from "@/components/ui/primitives";
import { fmtDateTime, isoToDateInput } from "@/lib/format";
import { cn } from "@/lib/cn";
import { zonedParts } from "./compute";
import { anonymizeTags, buildAiStylePayload, MIN_BUCKET_SAMPLE, resolveTagLabels } from "./aiPayload";
import { InfoTooltip } from "./InfoTooltip";
import type { ClosedPosition } from "@/api/analytics";
import type { Tag } from "@/api/taxonomy";
import type { DateRange } from "./useAnalyticsFilters";

/**
 * The "AI analysis" tab: sends the dashboard's own aggregates for the current filter state to the
 * configured provider and shows the observations. Runs are persisted per profile, so past analyses
 * stay expandable and comparable as filters and history change.
 */
export function AiAnalysisView({
  profileId,
  rows,
  period,
  range,
  exchange,
  origenTagId,
  origenTags,
  timeZone,
}: {
  profileId: string | null;
  rows: ClosedPosition[];
  period: string;
  range: DateRange;
  exchange: string;
  origenTagId: string;
  origenTags: Tag[];
  timeZone: string;
}) {
  const { t } = useTranslation();
  const settings = useAiOn();
  const runs = useAiAnalysisRuns(profileId, !!settings);
  const { data: capital } = useCapital(profileId);
  const { data: roi } = useRoi(profileId, range.from?.toISOString(), range.to?.toISOString(), exchange);
  const years = useMemo(
    () => [...new Set(rows.map((r) => zonedParts(r.closedAt, timeZone).year))].sort((a, b) => a - b),
    [rows, timeZone],
  );
  const monthlyRoi = useMonthlyRoiYears(profileId, years, exchange);
  const start = useStartAiAnalysis(profileId);
  const del = useDeleteAiAnalysisRun(profileId);
  const [openIds, setOpenIds] = useState<Set<string>>(new Set());

  if (!settings) {
    return (
      <Card>
        <CardBody className="py-12 text-center text-sm text-gray-500 dark:text-gray-400">
          {t("analytics.ai.disabled")}
        </CardBody>
      </Card>
    );
  }

  const enough = rows.length >= MIN_BUCKET_SAMPLE;
  const scopeFrom = range.from ? isoToDateInput(range.from.toISOString()) : null;
  const scopeTo = range.to ? isoToDateInput(range.to.toISOString()) : null;

  const run = () => {
    const payload = buildAiStylePayload({
      rows,
      scope: {
        periodPreset: period,
        rangeFrom: scopeFrom,
        rangeTo: scopeTo,
        exchange,
        origen: origenTagId === "ALL" ? null : (anonymizeTags(origenTags).get(origenTagId) ?? null),
        timeZone,
      },
      origenTags,
      capital,
      roi,
      monthlyRoi,
    });
    start.mutate(payload);
  };

  const stored = runs.data ?? [];
  const [latest, ...history] = stored;

  return (
    <div className="space-y-6">
      <Card>
        <CardBody className="space-y-3">
          <div className="flex items-center gap-2">
            <h3 className="font-semibold">{t("analytics.ai.title")}</h3>
            <InfoTooltip text={t("analytics.ai.info", { model: settings.model, host: settings.providerHost })} />
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-gray-500 dark:text-gray-400">
            <ScopeChip label={`${t(`analytics.period.${period}`)}${scopeFrom ? ` · ${scopeFrom} → ${scopeTo}` : ""}`} />
            <ScopeChip label={`${t("analytics.exchangeLabel")}: ${exchange === "ALL" ? t("analytics.allExchanges") : exchange}`} />
            {origenTagId !== "ALL" && <ScopeChip label={`${t("analytics.origenLabel")}: ${origenLabelOf(origenTagId)}`} />}
            <ScopeChip label={`${t("analytics.trades")}: ${rows.length}`} />
          </div>
          <div className="flex items-center gap-3">
            <button
              type="button"
              className="inline-flex items-center rounded-md px-4 py-2 text-sm font-medium text-white bg-violet-600 hover:bg-violet-700 focus:outline-none focus:ring-2 focus:ring-violet-500 focus:ring-offset-1 disabled:opacity-50 disabled:cursor-not-allowed"
              disabled={!enough || start.isPending || runs.data?.some((r) => r.status === "RUNNING")}
              onClick={run}
            >
              {start.isPending ? t("analytics.ai.starting") : t("analytics.ai.analyze")}
            </button>
            {!enough && (
              <span className="text-sm text-gray-500 dark:text-gray-400">
                {t("analytics.ai.minSample", { min: MIN_BUCKET_SAMPLE, count: rows.length })}
              </span>
            )}
          </div>
          {start.error && <p className="text-sm text-red-600 dark:text-red-400">{asApiError(start.error).message}</p>}
        </CardBody>
      </Card>

      {latest && <RunCard run={latest} defaultOpen onDelete={del.mutate} />}
      {history.length > 0 && (
        <div className="space-y-2">
          <h4 className="text-sm font-semibold text-gray-500 dark:text-gray-400">
            {t("analytics.ai.historyTitle", { count: history.length })}
          </h4>
          {history.map((r) => (
            <RunCard
              key={r.id}
              run={r}
              defaultOpen={openIds.has(r.id)}
              onToggle={() =>
                setOpenIds((prev) => {
                  const next = new Set(prev);
                  if (next.has(r.id)) next.delete(r.id);
                  else next.add(r.id);
                  return next;
                })
              }
              onDelete={del.mutate}
            />
          ))}
        </div>
      )}
      {stored.length === 0 && !runs.isLoading && (
        <p className="text-center text-sm text-gray-500 dark:text-gray-400">{t("analytics.ai.noRuns")}</p>
      )}
    </div>
  );

  // The chip names the user's own tag, straight from the taxonomy — no run history needed.
  function origenLabelOf(tagId: string): string {
    return origenTags.find((t) => t.id === tagId)?.name ?? tagId;
  }

  function ScopeChip({ label }: { label: string }) {
    return <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600 dark:bg-surface-raised dark:text-gray-300">{label}</span>;
  }
}

/** One persisted analysis: collapsed summary row in history, expanded observations when open. */
function RunCard({
  run,
  defaultOpen,
  onToggle,
  onDelete,
}: {
  run: AiRun;
  defaultOpen: boolean;
  onToggle?: () => void;
  onDelete: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(defaultOpen);
  const toggle = () => {
    setOpen(!open);
    onToggle?.();
  };
  const ungrounded = run.citationsUngrounded ?? 0;

  return (
    <Card>
      <CardBody className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <button type="button" onClick={toggle} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-left">
            <span className="font-medium">{fmtDateTime(run.createdAt)}</span>
            <ScopeBadges run={run} />
            {run.status === "COMPLETED" && (
              <span
                className={cn(
                  "rounded px-2 py-0.5 text-xs",
                  ungrounded === 0
                    ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
                    : "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
                )}
              >
                {ungrounded === 0
                  ? t("analytics.ai.groundedOk")
                  : t("analytics.ai.groundedOff", { off: ungrounded, total: run.citationsTotal ?? 0 })}
              </span>
            )}
          </button>
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-400">{run.model}</span>
            <button
              type="button"
              title={t("common.delete")}
              onClick={() => {
                if (window.confirm(t("analytics.ai.confirmDelete"))) onDelete(run.id);
              }}
              className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-red-600 dark:hover:bg-surface-raised"
            >
              ✕
            </button>
          </div>
        </div>

        {run.status === "RUNNING" && (
          <p role="status" className="text-sm text-gray-500 dark:text-gray-400">
            {t("analytics.ai.running")}
          </p>
        )}
        {run.status === "FAILED" && (
          <p className="text-sm text-red-600 dark:text-red-400">
            {t("analytics.ai.failed")}: {t(`analytics.ai.error.${run.errorCode ?? "INTERNAL_ERROR"}`, { defaultValue: run.errorCode ?? "" })}
          </p>
        )}
        {run.status === "INTERRUPTED" && (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.ai.interrupted")}</p>
        )}

        {open && run.status === "COMPLETED" && (
          <>
            {run.summary && (
              <div className="rounded-md border border-violet-200 bg-violet-50/60 p-3.5 text-sm leading-relaxed text-gray-800 dark:border-violet-900/40 dark:bg-violet-950/25 dark:text-gray-200">
                <p className="text-xs font-semibold uppercase tracking-wider text-violet-700 dark:text-violet-300">
                  {t("analytics.ai.summaryTitle")}
                </p>
                <p className="mt-1">{resolveTagLabels(run.summary, run.tagNames)}</p>
              </div>
            )}
            {run.improvements?.length > 0 && (
              <div className="rounded-lg border border-amber-200 bg-amber-50/50 p-4 dark:border-amber-900/40 dark:bg-amber-950/20">
                <div className="flex items-center gap-2">
                  <span className="text-base" aria-hidden="true">🎯</span>
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-amber-800 dark:text-amber-300">
                    {t("analytics.ai.improvementsTitle")}
                  </h4>
                </div>
                <ul className="mt-3 space-y-3">
                  {run.improvements.map((item, i) => (
                    <li key={i} className="space-y-1">
                      <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                        {resolveTagLabels(item.title, run.tagNames)}
                      </p>
                      <p className="text-sm leading-relaxed text-gray-700 dark:text-gray-300">
                        {resolveTagLabels(item.detail, run.tagNames)}
                      </p>
                      {item.citations?.length > 0 && <Citations citations={item.citations} />}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {run.observations.length > 0 && (
              <ul className="space-y-4">
                {run.observations.map((obs, i) => (
                  <Observation key={i} obs={obs} tagNames={run.tagNames} />
                ))}
              </ul>
            )}
            {run.insufficientSample.length > 0 && (
              <div className="rounded-md border border-border bg-gray-50 p-3 dark:bg-surface-raised">
                <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  {t("analytics.ai.insufficientTitle")}
                </p>
                <ul className="mt-2 space-y-2">
                  {run.insufficientSample.map((item, i) => (
                    <InsufficientItemView key={i} item={item} tagNames={run.tagNames} />
                  ))}
                </ul>
              </div>
            )}
            {(run.inputTokens !== null || run.latencyMs !== null) && (
              <p className="text-xs text-gray-400">
                {t("analytics.ai.usage", {
                  input: run.inputTokens ?? 0,
                  output: run.outputTokens ?? 0,
                  seconds: Math.round((run.latencyMs ?? 0) / 100) / 10,
                })}
              </p>
            )}
          </>
        )}
      </CardBody>
    </Card>
  );
}

function ScopeBadges({ run }: { run: AiRun }) {
  const { t } = useTranslation();
  const range = run.scope.rangeFrom ? `${run.scope.rangeFrom} → ${run.scope.rangeTo}` : t("analytics.period.all");
  const origen = run.scope.origenLabel
    ? run.tagNames[run.scope.origenLabel] ?? run.scope.origenLabel
    : t("analytics.allOrigens");
  return (
    <>
      <Badge>{t(`analytics.period.${run.scope.periodPreset}`, { defaultValue: run.scope.periodPreset })} · {range}</Badge>
      <Badge>
        {run.scope.exchange === "ALL" ? t("analytics.allExchanges") : run.scope.exchange} · {origen}
      </Badge>
      <Badge>
        {t("analytics.trades")}: {run.scope.sampleSize}
      </Badge>
    </>
  );
}
function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600 dark:bg-surface-raised dark:text-gray-300">
      {children}
    </span>
  );
}

function Observation({ obs, tagNames }: { obs: AiObservation; tagNames: Record<string, string> }) {
  const { t } = useTranslation();
  return (
    <li className="space-y-1">
      <p className="text-sm leading-snug">{resolveTagLabels(obs.claim, tagNames)}</p>
      <div className="flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
        {obs.confidence && <ConfidenceBadge value={obs.confidence} />}
        {obs.sampleSize !== null && (
          <span>
            {t("analytics.trades")}: {obs.sampleSize}
          </span>
        )}
      </div>
      {obs.citations.length > 0 && <Citations citations={obs.citations} />}
    </li>
  );
}

function InsufficientItemView({ item, tagNames }: { item: AiInsufficientItem; tagNames: Record<string, string> }) {
  return (
    <li className="space-y-1">
      <p className="text-sm font-medium">{resolveTagLabels(item.topic, tagNames)}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{resolveTagLabels(item.reason, tagNames)}</p>
      {item.citations.length > 0 && <Citations citations={item.citations} />}
    </li>
  );
}

function Citations({ citations }: { citations: AiCitation[] }) {
  return (
    <ul className="flex flex-wrap gap-1.5">
      {citations.map((c, i) => (
        <li
          key={i}
          title={c.grounded ? undefined : "This value does not match the payload"}
          className={cn(
            "rounded border px-1.5 py-0.5 text-xs tabular-nums",
            c.grounded
              ? "border-emerald-200 text-emerald-700 dark:border-emerald-800 dark:text-emerald-300"
              : "border-red-300 text-red-600 line-through dark:border-red-800 dark:text-red-400",
          )}
        >
          <code>{c.path}</code> = {fmtValue(c.value)}
        </li>
      ))}
    </ul>
  );
}

function ConfidenceBadge({ value }: { value: string }) {
  const { t } = useTranslation();
  const v = value.toLowerCase();
  const tone = v.startsWith("alta") || v.startsWith("high")
    ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
    : v.startsWith("media") || v.startsWith("medium")
      ? "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
      : "bg-gray-100 text-gray-600 dark:bg-surface-raised dark:text-gray-300";
  return (
    <span className={cn("rounded px-1.5 py-0.5", tone)}>
      {t(`analytics.ai.confidence.${v}`, { defaultValue: value })}
    </span>
  );
}

function fmtValue(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number") return String(value);
  return JSON.stringify(value);
}
