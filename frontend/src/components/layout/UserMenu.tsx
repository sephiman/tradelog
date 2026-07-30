import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { useTheme, type ThemePreference } from "@/lib/theme";
import { Button, Select } from "@/components/ui/primitives";

export function UserMenu() {
  const { t } = useTranslation();
  const { user, logout, setLocale } = useAuth();
  const { theme, setTheme } = useTheme();
  const [open, setOpen] = useState(false);

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground"
        aria-haspopup="true"
        aria-expanded={open}
      >
        {user?.email?.[0]?.toUpperCase() ?? "?"}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-20 mt-2 w-64 rounded-lg border border-border bg-white p-3 shadow-lg dark:border-gray-700 dark:bg-gray-800">
            <p className="mb-2 truncate text-sm text-gray-500 dark:text-gray-400">{user?.email}</p>

            {/* Account-level destination, kept out of the main nav so that list stays short. */}
            <Link
              to="/settings"
              onClick={() => setOpen(false)}
              className="-mx-3 block px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-700"
            >
              {t("nav.settings")}
            </Link>

            <div className="-mx-3 my-2 border-t border-border dark:border-gray-700" />

            <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">{t("auth.language")}</label>
            <Select
              className="mb-3"
              value={user?.locale ?? "en"}
              onChange={(e) => void setLocale(e.target.value as "en" | "es")}
            >
              <option value="en">English</option>
              <option value="es">Español</option>
            </Select>

            <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">{t("auth.theme")}</label>
            <Select className="mb-3" value={theme} onChange={(e) => setTheme(e.target.value as ThemePreference)}>
              <option value="light">{t("auth.themeLight")}</option>
              <option value="dark">{t("auth.themeDark")}</option>
              <option value="system">{t("auth.themeSystem")}</option>
            </Select>

            <Button variant="secondary" className="w-full" onClick={() => void logout()}>
              {t("auth.logout")}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
