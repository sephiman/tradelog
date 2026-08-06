import { beforeEach, describe, expect, it } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { ThemeProvider, useTheme, type ThemePreference } from "@/lib/theme";

const PREFERENCES: ThemePreference[] = ["light", "dark", "oled", "system"];

/** Surfaces the resolved theme and lets a test switch the preference. */
function ThemeProbe() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  return (
    <>
      <span data-testid="pref">{theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      {PREFERENCES.map((p) => (
        <button key={p} onClick={() => setTheme(p)}>
          {p}
        </button>
      ))}
    </>
  );
}

function mockSystemPrefersDark(prefersDark: boolean) {
  window.matchMedia = ((query: string) =>
    ({
      matches: prefersDark,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList) as typeof window.matchMedia;
}

// This runner exposes no localStorage (ThemeProvider tolerates that), so stand one in to exercise
// the persistence path a browser actually takes.
function installMemoryStorage() {
  const data = new Map<string, string>();
  const storage = {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    removeItem: (k: string) => void data.delete(k),
    clear: () => data.clear(),
    key: (i: number) => [...data.keys()][i] ?? null,
    get length() {
      return data.size;
    },
  } as Storage;
  Object.defineProperty(globalThis, "localStorage", { value: storage, configurable: true, writable: true });
}

const root = () => document.documentElement.classList;
const resolved = () => screen.getByTestId("resolved").textContent;
const pick = (preference: ThemePreference) => act(() => screen.getByRole("button", { name: preference }).click());

describe("ThemeProvider", () => {
  beforeEach(() => {
    installMemoryStorage();
    document.documentElement.className = "";
    mockSystemPrefersDark(false);
  });

  it("marks OLED as a dark variant so every dark: utility keeps applying", () => {
    render(<ThemeProvider><ThemeProbe /></ThemeProvider>);

    pick("oled");

    expect(root().contains("dark")).toBe(true);
    expect(root().contains("oled")).toBe(true);
    expect(resolved()).toBe("oled");
  });

  it("drops both classes when switching from OLED back to light", () => {
    render(<ThemeProvider><ThemeProbe /></ThemeProvider>);

    pick("oled");
    pick("light");

    expect(root().contains("dark")).toBe(false);
    expect(root().contains("oled")).toBe(false);
  });

  it("keeps OLED off the plain dark theme", () => {
    render(<ThemeProvider><ThemeProbe /></ThemeProvider>);

    pick("dark");

    expect(root().contains("dark")).toBe(true);
    expect(root().contains("oled")).toBe(false);
  });

  it("resolves system to the plain dark theme, never OLED", () => {
    mockSystemPrefersDark(true);
    render(<ThemeProvider><ThemeProbe /></ThemeProvider>);

    pick("system");

    expect(resolved()).toBe("dark");
    expect(root().contains("oled")).toBe(false);
  });

  it("restores a stored OLED preference", () => {
    localStorage.setItem("theme", "oled");

    render(<ThemeProvider><ThemeProbe /></ThemeProvider>);

    expect(screen.getByTestId("pref").textContent).toBe("oled");
    expect(root().contains("oled")).toBe(true);
  });
});
