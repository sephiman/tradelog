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
        {/* On mobile this is a stack of two rows: the logo/nav/avatar row and
            the profile-picker/quick-sync row. On sm+ the first row's wrapper
            collapses (`sm:contents`) so every control joins one flex line in
            the original order. This keeps the avatar pinned top-right and the
            quick-sync button on its own row regardless of translation width. */}
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-4 py-2 sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-3 sm:gap-y-2">
          <div className="flex items-center gap-3 sm:contents">
            <Logo className="h-7 w-auto shrink-0" />

            <nav className="flex flex-wrap items-center gap-1 sm:order-2 sm:flex-nowrap">
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

            <div className="ml-auto shrink-0 sm:order-5 sm:ml-0">
              <UserMenu />
            </div>
          </div>

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
