import { useTranslation } from "react-i18next";

const HEADER =
  "symbol;side;opened_at;closed_at;quantity;entry_price;exit_price;realized_pnl;fees;funding;exchange;note";

const EXAMPLE = [
  HEADER,
  "BTC/USDT;long;2024-10-31;2024-11-06;1;21147.66;22514.18;;171.76;0;FTX;swing long",
  "SUI/USDT;short;2024-11-09;2024-11-09;3;6824.37;6808.25;;21.81;0;FTX;",
  "ETH/USDT;long;2024-11-29T14:00:00Z;2024-11-29T18:30:00Z;0.5;3200;3360;80;0;0;Bittrex;",
].join("\n");

const COLUMNS: { name: string; req: boolean }[] = [
  { name: "symbol", req: true },
  { name: "side", req: true },
  { name: "opened_at", req: true },
  { name: "closed_at", req: true },
  { name: "quantity", req: false },
  { name: "entry_price", req: true },
  { name: "exit_price", req: true },
  { name: "realized_pnl", req: false },
  { name: "fees", req: false },
  { name: "funding", req: false },
  { name: "exchange", req: false },
  { name: "note", req: false },
];

export function TradeCsvFormatHelp() {
  const { t } = useTranslation();
  return (
    <details className="mt-2 text-sm">
      <summary className="cursor-pointer select-none text-sky-600 dark:text-sky-400">
        {t("import.format.toggle")}
      </summary>
      <div className="mt-2 space-y-3 rounded border border-border bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-900/40">
        <p className="rounded border border-sky-300 bg-sky-50 p-2 text-xs text-sky-900 dark:border-sky-700 dark:bg-sky-900/30 dark:text-sky-100">
          {t("import.format.ai_hint")}
        </p>
        <p className="text-xs text-gray-600 dark:text-gray-300">{t("import.format.intro")}</p>

        <Section title={t("import.format.headers_label")}>
          <CodeBlock>{HEADER}</CodeBlock>
        </Section>

        <Section title={t("import.format.columns_label")}>
          <ul className="space-y-1">
            {COLUMNS.map((c) => (
              <li key={c.name}>
                <code className="font-mono text-xs">{c.name}</code>
                <span className="ml-1 text-xs text-gray-500 dark:text-gray-400">
                  ({c.req ? t("import.format.required") : t("import.format.optional")})
                </span>{" "}
                — <span className="text-xs">{t(`import.format.cols.${c.name}`)}</span>
              </li>
            ))}
          </ul>
        </Section>

        <p className="rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
          {t("import.format.pnlNote")}
        </p>
        <p className="rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
          {t("import.format.notionalNote")}
        </p>

        <Section title={t("import.format.example_label")}>
          <CodeBlock>{EXAMPLE}</CodeBlock>
        </Section>
      </div>
    </details>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{title}</p>
      {children}
    </div>
  );
}

function CodeBlock({ children }: { children: string }) {
  return (
    <pre className="overflow-x-auto rounded bg-gray-100 p-2 font-mono text-xs text-gray-800 dark:bg-gray-900 dark:text-gray-200">
      {children}
    </pre>
  );
}
