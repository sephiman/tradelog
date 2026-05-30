import { NavLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { Select } from "@/components/ui/primitives";
import { Logo } from "@/components/ui/Logo";
import { QuickSync } from "./QuickSync";
import { UserMenu } from "./UserMenu";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  const { profiles, activeProfileId, setActiveProfileId } = useActiveProfile();

  const nav = [
    { to: "/dashboard", label: t("nav.dashboard") },
    { to: "/positions", label: t("nav.positions") },
    { to: "/settings", label: t("nav.settings") },
  ];

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-border bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-2">
          <Logo className="h-7 w-auto" />

          <nav className="flex items-center gap-1">
            {nav.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
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

          <div className="ml-auto flex items-center gap-3">
            {profiles.length > 0 && (
              <Select
                className="w-44"
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
            <UserMenu />
          </div>
        </div>
      </header>

      <main className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-6xl px-4 py-6">{children}</div>
      </main>
    </div>
  );
}
