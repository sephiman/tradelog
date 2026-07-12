import { Suspense, lazy, type ComponentType } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RequireAuth } from "@/auth/RequireAuth";
import { ActiveProfileProvider } from "@/features/profiles/ActiveProfile";
import { AppShell } from "@/components/layout/AppShell";
import { ToastHost } from "@/components/ui/ToastHost";
import { ErrorBoundary } from "@/components/ui/ErrorBoundary";
import { LoginPage } from "@/auth/LoginPage";
import { RegisterPage } from "@/auth/RegisterPage";

// Lazy routes keep the heavy pages (the dashboard alone pulls in recharts) off the initial
// login-screen load; each page becomes its own chunk fetched on first navigation.
const RELOADED_FLAG = "tl.chunk-reloaded";

// A chunk fetch can fail when a redeploy replaced the content-hashed assets while the SPA was
// open (or on a network blip). Reload once to pick up the new asset manifest; if the import
// still fails after a fresh load, surface the error boundary rather than reload-looping.
function lazyPage(load: () => Promise<{ default: ComponentType }>) {
  return lazy(() =>
    load().then(
      (m) => {
        sessionStorage.removeItem(RELOADED_FLAG);
        return m;
      },
      (err: unknown) => {
        if (sessionStorage.getItem(RELOADED_FLAG)) throw err;
        sessionStorage.setItem(RELOADED_FLAG, "1");
        window.location.reload();
        return new Promise<never>(() => {}); // the reload takes over
      },
    ),
  );
}

const AnalyticsPage = lazyPage(() =>
  import("@/features/analytics/AnalyticsPage").then((m) => ({ default: m.AnalyticsPage })),
);
const PositionsPage = lazyPage(() =>
  import("@/features/positions/PositionsPage").then((m) => ({ default: m.PositionsPage })),
);
const SettingsPage = lazyPage(() =>
  import("@/features/settings/SettingsPage").then((m) => ({ default: m.SettingsPage })),
);

function PageLoading() {
  const { t } = useTranslation();
  return (
    <div className="flex justify-center py-16 text-gray-500 dark:text-gray-400">{t("common.loading")}</div>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <ActiveProfileProvider>
                <AppShell>
                  <Suspense fallback={<PageLoading />}>
                    <Routes>
                      <Route index element={<Navigate to="/dashboard" replace />} />
                      <Route path="dashboard" element={<AnalyticsPage />} />
                      <Route path="positions" element={<PositionsPage />} />
                      <Route path="settings" element={<SettingsPage />} />
                      <Route path="*" element={<Navigate to="/dashboard" replace />} />
                    </Routes>
                  </Suspense>
                </AppShell>
              </ActiveProfileProvider>
            </RequireAuth>
          }
        />
      </Routes>
      <ToastHost />
    </ErrorBoundary>
  );
}
