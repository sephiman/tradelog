import { useTheme } from "@/lib/theme";
import { cn } from "@/lib/cn";

/**
 * Renders the Trade Log wordmark, picking the light or dark artwork to match
 * the currently resolved theme (the dark logo has white text for dark
 * backgrounds, the light logo has dark text for light backgrounds).
 */
export function Logo({ className }: { className?: string }) {
  const { resolvedTheme } = useTheme();
  const src = resolvedTheme === "dark" ? "/logo-dark.png" : "/logo-light.png";
  return <img src={src} alt="Trade Log" className={cn("select-none", className)} />;
}
