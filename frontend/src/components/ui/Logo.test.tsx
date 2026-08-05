import { afterEach, describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigationType } from "react-router-dom";
import { ThemeProvider } from "@/lib/theme";
import { Logo } from "@/components/ui/Logo";
import i18n from "@/i18n";

/** Surfaces the router's current path and how it was reached (PUSH vs REPLACE). */
function LocationProbe() {
  const { pathname } = useLocation();
  const navigationType = useNavigationType();
  return (
    <>
      <span data-testid="pathname">{pathname}</span>
      <span data-testid="nav-type">{navigationType}</span>
    </>
  );
}

function renderLogo(initialPath: string, props: { to?: string } = { to: "/dashboard" }) {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ThemeProvider>
        <Logo {...props} className="h-7 w-auto shrink-0" />
        <LocationProbe />
      </ThemeProvider>
    </MemoryRouter>,
  );
}

const homeLink = () => screen.getByRole("link", { name: i18n.t("nav.home") });
const pathname = () => screen.getByTestId("pathname").textContent;
const navType = () => screen.getByTestId("nav-type").textContent;

describe("Logo", () => {
  afterEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("navigates from a deep page to home without a full page reload", () => {
    renderLogo("/positions");
    expect(pathname()).toBe("/positions");

    fireEvent.click(homeLink());

    expect(pathname()).toBe("/dashboard");
    expect(navType()).toBe("PUSH");
  });

  it("replaces instead of pushing when already on home, so history gains no duplicate entry", () => {
    renderLogo("/dashboard");

    fireEvent.click(homeLink());

    expect(pathname()).toBe("/dashboard");
    expect(navType()).toBe("REPLACE");
  });

  it("is a natively focusable link with an accessible label", () => {
    renderLogo("/settings");
    const link = homeLink();

    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", "/dashboard");
    // No tabindex override: the anchor keeps native tab-order and Enter activation.
    expect(link).not.toHaveAttribute("tabindex");

    link.focus();
    expect(link).toHaveFocus();
  });

  it("labels the link in the active locale", async () => {
    await i18n.changeLanguage("es");
    renderLogo("/settings");

    expect(screen.getByRole("link", { name: "Inicio de Trade Log" })).toBeInTheDocument();
  });

  it("stays an inert image when no destination is given, as on the auth screens", () => {
    renderLogo("/login", {});

    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByRole("img", { name: "Trade Log" })).toBeInTheDocument();
  });
});
