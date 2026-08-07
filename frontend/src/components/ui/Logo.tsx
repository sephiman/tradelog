import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useTheme } from "@/lib/theme";
import { cn } from "@/lib/cn";

const LOGO_SRC = {
  light: "/logo-light.png",
  dark: "/logo-dark.png",
  oled: "/logo-oled.png",
} as const;

/**
 * Renders the Trade Log wordmark, picking the artwork that matches the currently
 * resolved theme (the dark logo has white text for dark backgrounds, the light
 * logo has dark text for light backgrounds). All three have a real alpha channel,
 * so OLED's copy now differs only in the artwork's own tones, not its canvas.
 *
 * Pass [to] to make the wordmark a link — used by the app shell to send the
 * logo home. Left off on the auth screens, whose logo must stay inert rather
 * than point at a route behind RequireAuth.
 */
export function Logo({
  className,
  to,
  onClick,
}: {
  className?: string;
  to?: string;
  onClick?: () => void;
}) {
  const { resolvedTheme } = useTheme();
  const { t } = useTranslation();
  const src = LOGO_SRC[resolvedTheme];

  // Linked, the anchor's aria-label names the destination, so the image itself
  // drops out of the accessibility tree instead of being announced twice.
  if (to) {
    return (
      <Link
        to={to}
        onClick={onClick}
        aria-label={t("nav.home")}
        className="inline-flex shrink-0 rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <img src={src} alt="" className={cn("select-none", className)} />
      </Link>
    );
  }

  return <img src={src} alt="Trade Log" className={cn("select-none", className)} />;
}
