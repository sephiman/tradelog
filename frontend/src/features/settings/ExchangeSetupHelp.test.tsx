import { afterEach, describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import i18n from "@/i18n";
import { isApiKind, SOURCE_KINDS, SOURCE_LABELS } from "@/lib/sourceKinds";
import { ExchangeSetupHelp } from "./ExchangeSetupHelp";

/** Everything the dropdown offers except the CSV, which documents its own format in the upload card. */
const DOCUMENTED = SOURCE_KINDS.filter((k) => k !== "JOURNAL_CSV");

const toggle = () => screen.getByRole("button");

describe("ExchangeSetupHelp", () => {
  afterEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("starts collapsed and toggles the panel", () => {
    render(<ExchangeSetupHelp kind="BITUNIX" />);

    expect(toggle()).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("list")).toBeNull();

    fireEvent.click(toggle());
    expect(toggle()).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("list")).toBeInTheDocument();

    fireEvent.click(toggle());
    expect(screen.queryByRole("list")).toBeNull();
  });

  it("labels the panel it controls, so screen readers follow the expansion", () => {
    render(<ExchangeSetupHelp kind="OKX" />);
    fireEvent.click(toggle());

    expect(document.getElementById(toggle().getAttribute("aria-controls")!)).toBeInTheDocument();
  });

  it.each(DOCUMENTED)("gives %s numbered steps and a link to its API management", (kind) => {
    render(<ExchangeSetupHelp kind={kind} />);
    fireEvent.click(toggle());

    expect(screen.getAllByRole("listitem").length).toBeGreaterThanOrEqual(3);
    // Every venue links out somewhere official — the console, its own write-up, or both.
    const links = screen.getAllByRole("link");
    expect(links.length).toBeGreaterThan(0);
    for (const link of links) expect(link.getAttribute("href")).toMatch(/^https:\/\//);
    // The panel says which venue it is talking about, so a mis-set dropdown is obvious.
    expect(screen.getAllByText(new RegExp(SOURCE_LABELS[kind].replace(/\./g, "\\.")))).not.toHaveLength(0);
  });

  it.each(DOCUMENTED.filter(isApiKind))("shouts the read-only rule for %s", (kind) => {
    render(<ExchangeSetupHelp kind={kind} />);
    fireEvent.click(toggle());

    expect(screen.getByText(i18n.t("dataSources.setupHelp.readOnly"))).toBeInTheDocument();
    // Permissions are the point of the panel: the venue-specific line must be there, not just the banner.
    expect(screen.getByText(`${i18n.t("dataSources.setupHelp.permissionsLabel")}:`)).toBeInTheDocument();
  });

  it("drops the key-only sections for Quantfury, which has no API", () => {
    render(<ExchangeSetupHelp kind="QUANTFURY" />);
    fireEvent.click(toggle());

    expect(screen.queryByText(i18n.t("dataSources.setupHelp.readOnly"))).toBeNull();
    expect(screen.queryByText(`${i18n.t("dataSources.setupHelp.permissionsLabel")}:`)).toBeNull();
    expect(screen.getByText(/Trading History Report/)).toBeInTheDocument();
  });

  it("renders nothing for the Journal CSV source", () => {
    const { container } = render(<ExchangeSetupHelp kind="JOURNAL_CSV" />);

    expect(container).toBeEmptyDOMElement();
  });

  it("names the passphrase where the exchange asks for one", () => {
    render(<ExchangeSetupHelp kind="KUCOIN_FUTURES" />);
    fireEvent.click(toggle());

    expect(screen.getByText(`${i18n.t("dataSources.setupHelp.passphraseLabel")}:`)).toBeInTheDocument();
  });

  it("omits the passphrase section for exchanges that use none", () => {
    render(<ExchangeSetupHelp kind="BYBIT" />);
    fireEvent.click(toggle());

    expect(screen.queryByText(`${i18n.t("dataSources.setupHelp.passphraseLabel")}:`)).toBeNull();
  });

  it.each(DOCUMENTED)("keeps %s translated in Spanish, with no leftover emphasis markers", async (kind) => {
    await i18n.changeLanguage("es");
    const { container } = render(<ExchangeSetupHelp kind={kind} />);

    expect(toggle()).toHaveTextContent("¿Cómo consigo estas credenciales?");
    fireEvent.click(toggle());

    // A missing Spanish key would fall back to English; a stray "**" would mean the emphasis split broke.
    expect(container.textContent).not.toContain("**");
    expect(container.textContent).not.toContain("dataSources.setupHelp");
    expect(screen.getAllByRole("listitem").length).toBeGreaterThanOrEqual(3);
  });
});
