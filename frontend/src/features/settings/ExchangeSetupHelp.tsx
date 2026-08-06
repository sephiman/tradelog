import { Fragment, useId, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { SourceKind } from "@/api/positions";
import { isApiKind, SOURCE_LABELS } from "@/lib/sourceKinds";

/** Where each venue's key is created, plus its own write-up. In code, not i18n: URLs aren't translated. */
const SETUP_LINKS: Partial<Record<SourceKind, { console?: string; docs?: string }>> = {
  BITUNIX: {
    console: "https://www.bitunix.com/account/apiManagement",
    docs: "https://support.bitunix.com/hc/en-us/sections/46018902238489-API-Management",
  },
  // BingX documents its API but not the key-creation screen, so the console link is all there is.
  BINGX: { console: "https://bingx.com/en/accounts/api" },
  BITMART: {
    console: "https://www.bitmart.com/en-US/api-config",
    docs: "https://bitmart.zendesk.com/hc/en-us/articles/17855610619803-API-Key-Creation-and-Broker-Trading-Guide",
  },
  BINANCE_FUTURES: {
    console: "https://www.binance.com/en/my/settings/api-management",
    docs: "https://www.binance.com/en/support/faq/360002502072",
  },
  BYBIT: {
    console: "https://www.bybit.com/app/user/api-management",
    docs: "https://www.bybit.com/en/help-center/article/How-to-create-your-API-key",
  },
  OKX: { console: "https://www.okx.com/account/my-api", docs: "https://www.okx.com/help/api-faq" },
  BITGET: {
    console: "https://www.bitget.com/account/newapi",
    docs: "https://www.bitget.com/support/articles/360038968251-API-Creation-Guide",
  },
  BITGET_CLASSIC: {
    console: "https://www.bitget.com/account/newapi",
    docs: "https://www.bitget.com/support/articles/360038968251-API-Creation-Guide",
  },
  KRAKEN_FUTURES: {
    console: "https://futures.kraken.com/trade/settings/api",
    docs: "https://support.kraken.com/articles/360022839451-how-to-create-an-api-key-for-kraken-derivatives",
  },
  KRAKEN_SPOT: {
    console: "https://pro.kraken.com/app/settings/api",
    docs: "https://support.kraken.com/articles/how-to-create-an-api-key-on-kraken-pro",
  },
  // Gate's key page sits behind the account menu and has no stable deep link; its guide has the path.
  GATEIO_FUTURES: { docs: "https://www.gate.com/help/guide/faq/17521/how-to-utilize-api" },
  MEXC_FUTURES: {
    console: "https://www.mexc.com/user/openapi",
    docs: "https://www.mexc.com/announcements/article/mexc-sub-account-api-key-setup-manual-17827791534686",
  },
  KUCOIN_FUTURES: {
    console: "https://www.kucoin.com/account/api",
    docs: "https://www.kucoin.com/support/360015102174",
  },
  QUANTFURY: {
    console: "https://trading.quantfury.com/trading_history",
    docs: "https://help.quantfury.com/en/articles/5448773-trading-history",
  },
  // JOURNAL_CSV is deliberately absent: its own format reference lives in the upload card.
};

/** One-time setup instructions for the selected source, collapsed until asked for. */
export function ExchangeSetupHelp({ kind, className }: { kind: SourceKind; className?: string }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const panelId = useId();

  const links = SETUP_LINKS[kind];
  if (!links) return null;

  const venue = SOURCE_LABELS[kind];
  const base = `dataSources.setupHelp.venues.${kind}`;
  const has = (leaf: string) => i18n.exists(`${base}.${leaf}`);
  // Written as a JSON array per venue, since the step count varies from three to six.
  const steps = t(`${base}.steps`, { returnObjects: true }) as unknown as string[];
  // Quantfury is a file import: no key, so no permissions to get wrong and no secret to lose.
  const keyed = isApiKind(kind);

  return (
    <div className={className}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-controls={panelId}
        className="text-sm text-primary"
      >
        {open ? t("dataSources.setupHelp.hide") : t("dataSources.setupHelp.show")}
      </button>
      {open && (
        <div
          id={panelId}
          className="mt-2 space-y-3 rounded-md border border-border bg-gray-50 p-3 text-sm dark:bg-surface-inset"
        >
          <p className="font-semibold text-gray-900 dark:text-gray-100">
            {t(keyed ? "dataSources.setupHelp.title" : "dataSources.setupHelp.titleFile", { venue })}
          </p>

          {keyed && (
            // The one line that matters most, so it sits above the steps and outshouts them.
            <div className="rounded-md border-2 border-amber-500 bg-amber-100 p-2 dark:border-amber-600 dark:bg-amber-900/40">
              <p className="font-semibold text-amber-900 dark:text-amber-100">
                {t("dataSources.setupHelp.readOnly")}
              </p>
            </div>
          )}

          <ol className="list-decimal space-y-1.5 pl-5 text-gray-600 dark:text-gray-300">
            {steps.map((step, i) => (
              <li key={i}>{withEmphasis(step)}</li>
            ))}
          </ol>

          {keyed && <Fact label={t("dataSources.setupHelp.permissionsLabel")} text={t(`${base}.permissions`)} />}
          {has("passphrase") && (
            <Fact label={t("dataSources.setupHelp.passphraseLabel")} text={t(`${base}.passphrase`)} />
          )}
          {has("ip") && <Fact label={t("dataSources.setupHelp.ipLabel")} text={t(`${base}.ip`)} />}
          {keyed && <Fact label={t("dataSources.setupHelp.secretLabel")} text={t(`${base}.secret`)} />}
          {has("note") && <Fact label={t("dataSources.setupHelp.noteLabel")} text={t(`${base}.note`)} />}

          <div className="flex flex-wrap gap-x-4 gap-y-1 border-t border-border pt-3">
            {links.console && (
              <a className="text-primary" href={links.console} target="_blank" rel="noreferrer">
                {t(keyed ? "dataSources.setupHelp.openConsole" : "dataSources.setupHelp.openConsoleFile", { venue })}
              </a>
            )}
            {links.docs && (
              <a className="text-primary" href={links.docs} target="_blank" rel="noreferrer">
                {t("dataSources.setupHelp.openDocs", { venue })}
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/** A labelled line of the setup that is not a step: permissions, passphrase, IP, the secret, caveats. */
function Fact({ label, text }: { label: string; text: string }) {
  return (
    <p className="text-gray-600 dark:text-gray-300">
      <span className="font-medium text-gray-900 dark:text-gray-100">{label}:</span> {withEmphasis(text)}
    </p>
  );
}

/** Bolds `**…**` runs: the dashboard labels the user hunts for read better than quoted strings. */
function withEmphasis(text: string): ReactNode {
  return text.split("**").map((part, i) =>
    i % 2 === 1 ? (
      <strong key={i} className="font-semibold text-gray-900 dark:text-gray-100">
        {part}
      </strong>
    ) : (
      <Fragment key={i}>{part}</Fragment>
    ),
  );
}
