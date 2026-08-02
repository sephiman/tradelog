import { InfoTooltip } from "./InfoTooltip";

/**
 * The benchmark legend: one toggle per benchmark, all off until the user opts in. Presentational
 * only — each chart decides which benchmarks are usable for what it is drawing (a year with no
 * data, a range the series does not reach) and passes that in already resolved.
 */
export interface BenchmarkChipItem {
  key: string;
  label: string;
  color: string;
  /** False greys the chip out: selecting it would promise a line that cannot be drawn. */
  usable: boolean;
  /** Shown on hover when not usable, explaining which kind of "no data" this is. */
  unavailableTitle?: string;
}

export function BenchmarkChips({
  items,
  selected,
  onToggle,
  note,
}: {
  items: BenchmarkChipItem[];
  selected: string[];
  onToggle: (key: string) => void;
  /** Caveat about what the lines mean, kept behind the row's info icon so it costs no height. */
  note?: string;
}) {
  if (items.length === 0) return null;

  return (
    <div className="mb-3 flex shrink-0 flex-wrap items-center gap-1.5">
      {items.map((item) => {
        const active = selected.includes(item.key);
        return (
          <button
            key={item.key}
            type="button"
            disabled={!item.usable}
            aria-pressed={active}
            onClick={() => onToggle(item.key)}
            title={item.usable ? undefined : item.unavailableTitle}
            className={[
              "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs transition-colors",
              !item.usable
                ? "cursor-not-allowed border-border bg-gray-50 text-gray-400 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-500"
                : active
                  ? "border-gray-400 bg-gray-100 font-medium text-gray-900 dark:border-gray-500 dark:bg-gray-700 dark:text-gray-100"
                  : "border-border bg-white text-gray-600 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700",
            ].join(" ")}
          >
            <span
              className="inline-block h-2 w-2 shrink-0 rounded-full"
              style={
                item.usable
                  ? { backgroundColor: active ? item.color : "transparent", boxShadow: `inset 0 0 0 1.5px ${item.color}` }
                  : { backgroundColor: "transparent", boxShadow: "inset 0 0 0 1.5px currentColor" }
              }
            />
            {item.label}
          </button>
        );
      })}
      {note && <InfoTooltip text={note} />}
    </div>
  );
}
