import "@testing-library/jest-dom";

// jsdom ships no matchMedia, which ThemeProvider queries for the "system" theme
// preference. Report "light" and accept (and ignore) change listeners.
if (typeof window !== "undefined" && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList;
}
