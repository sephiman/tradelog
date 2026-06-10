import { useEffect, useRef, useState } from "react";

/**
 * Auto-hide a sticky element on downward scroll and restore it on upward scroll.
 * Active only at viewports up to `maxWidth` (mobile) — on larger screens the
 * element always stays visible. Returns a ref to attach to the element and a
 * `hidden` flag to drive a transform.
 */
export function useHideOnScroll(maxWidth = 639) {
  const ref = useRef<HTMLDivElement>(null);
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const mql = window.matchMedia(`(max-width: ${maxWidth}px)`);

    // Nearest scrollable ancestor (the app shell's <main> in practice). Resolved
    // from computed overflow so it is stable even before content makes it scroll.
    let scroller: HTMLElement | null = el.parentElement;
    while (scroller) {
      const oy = getComputedStyle(scroller).overflowY;
      if (oy === "auto" || oy === "scroll") break;
      scroller = scroller.parentElement;
    }
    const target: HTMLElement | Window = scroller ?? window;
    const getY = () => (target === window ? window.scrollY : (target as HTMLElement).scrollTop);

    let lastY = getY();
    let ticking = false;

    const update = () => {
      ticking = false;
      const y = getY();
      const delta = y - lastY;
      if (Math.abs(delta) < 8) return; // ignore scroll jitter
      lastY = y;
      if (!mql.matches) {
        setHidden(false);
        return;
      }
      setHidden(delta > 0 && y > 80); // hide once scrolling down past the fold
    };
    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(update);
    };

    const onMediaChange = () => {
      if (!mql.matches) setHidden(false);
    };

    target.addEventListener("scroll", onScroll, { passive: true });
    mql.addEventListener("change", onMediaChange);
    return () => {
      target.removeEventListener("scroll", onScroll);
      mql.removeEventListener("change", onMediaChange);
    };
  }, [maxWidth]);

  return { ref, hidden };
}
