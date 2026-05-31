import { useTranslation } from "react-i18next";

function NavButtons({ label, onPrev, onNext }: { label: string; onPrev: () => void; onNext: () => void }) {
  const { t } = useTranslation();
  const btn = "rounded-md border border-border px-2 py-1 text-sm hover:bg-gray-50 dark:border-gray-600 dark:hover:bg-gray-700";
  return (
    <div className="flex items-center gap-2">
      <button type="button" className={btn} onClick={onPrev} aria-label={t("analytics.prev")}>
        ‹
      </button>
      <span className="min-w-32 text-center text-sm font-medium">{label}</span>
      <button type="button" className={btn} onClick={onNext} aria-label={t("analytics.next")}>
        ›
      </button>
    </div>
  );
}

/** Month navigator. `month` is 1–12. */
export function MonthNav({ year, month, onChange }: { year: number; month: number; onChange: (year: number, month: number) => void }) {
  const { i18n } = useTranslation();
  const label = new Intl.DateTimeFormat(i18n.language, { month: "long", year: "numeric" }).format(new Date(year, month - 1, 1));
  const shift = (delta: number) => {
    const d = new Date(year, month - 1 + delta, 1);
    onChange(d.getFullYear(), d.getMonth() + 1);
  };
  return <NavButtons label={label} onPrev={() => shift(-1)} onNext={() => shift(1)} />;
}

export function YearNav({ year, onChange }: { year: number; onChange: (year: number) => void }) {
  return <NavButtons label={String(year)} onPrev={() => onChange(year - 1)} onNext={() => onChange(year + 1)} />;
}
