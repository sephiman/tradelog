import { useState } from "react";
import { NavLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { Button, Select } from "@/components/ui/primitives";
import { Logo } from "@/components/ui/Logo";
import { QuickSync } from "./QuickSync";
import { UserMenu } from "./UserMenu";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  const { profiles, activeProfileId, setActiveProfileId } = useActiveProfile();
  const [navOpen, setNavOpen] = useState(false);

  const nav = [
    { to: "/dashboard", label: t("nav.dashboard") },
    { to: "/positions", label: t("nav.positions") },
    { to: "/capital", label: t("nav.capital") },
  ];

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-border bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-4 py-2 sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-3 sm:gap-y-2">
          <div className="flex items-center gap-3 sm:contents">
            <Logo className="h-7 w-auto shrink-0" />

            <div className="ml-auto flex shrink-0 items-center gap-1 sm:order-5 sm:ml-0">
              <Button
                variant="ghost"
                className="h-9 w-9 p-0 sm:hidden"
                aria-label={t("nav.menu")}
                aria-expanded={navOpen}
                aria-controls="main-nav"
                onClick={() => setNavOpen((o) => !o)}
              >
                <MenuIcon open={navOpen} />
              </Button>
              <UserMenu />
            </div>
          </div>

          {/* Collapsed behind the toggle on mobile, always inline on sm+. */}
          <nav
            id="main-nav"
            className={cn(
              "order-last flex-col gap-0.5 border-t border-border pt-2 sm:order-2 sm:flex sm:flex-row sm:flex-nowrap sm:items-center sm:gap-1 sm:border-t-0 sm:pt-0 dark:border-gray-700",
              navOpen ? "flex" : "hidden",
            )}
          >
            {nav.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
                onClick={() => setNavOpen(false)}
                className={({ isActive }) =>
                  cn(
                    "rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                    isActive
                      ? "bg-cyan-50 text-primary dark:bg-cyan-900/40 dark:text-cyan-300"
                      : "text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700",
                  )
                }
              >
                {n.label}
              </NavLink>
            ))}
          </nav>

          <div className="flex items-center gap-3 sm:order-4 sm:ml-auto">
            {profiles.length > 0 && (
              <Select
                className="min-w-0 flex-1 sm:w-44 sm:flex-none"
                value={activeProfileId ?? ""}
                onChange={(e) => setActiveProfileId(e.target.value)}
                aria-label={t("profiles.activeProfile")}
              >
                {profiles.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </Select>
            )}
            <QuickSync />
          </div>
        </div>
      </header>

      <main className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-6xl px-4 py-6">{children}</div>
      </main>
    </div>
  );
}

function MenuIcon({ open }: { open: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className="h-5 w-5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      aria-hidden
    >
      <path d={open ? "M6 6l12 12M18 6L6 18" : "M4 7h16M4 12h16M4 17h16"} />
    </svg>
  );
}
