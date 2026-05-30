import { Navigate, Route, Routes } from "react-router-dom";
import { RequireAuth } from "@/auth/RequireAuth";
import { ActiveProfileProvider } from "@/features/profiles/ActiveProfile";
import { AppShell } from "@/components/layout/AppShell";
import { ToastHost } from "@/components/ui/ToastHost";
import { LoginPage } from "@/auth/LoginPage";
import { RegisterPage } from "@/auth/RegisterPage";
import { DashboardPage } from "@/features/dashboard/DashboardPage";
import { PositionsPage } from "@/features/positions/PositionsPage";
import { SettingsPage } from "@/features/settings/SettingsPage";

export default function App() {
  return (
    <>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <ActiveProfileProvider>
                <AppShell>
                  <Routes>
                    <Route index element={<Navigate to="/dashboard" replace />} />
                    <Route path="dashboard" element={<DashboardPage />} />
                    <Route path="positions" element={<PositionsPage />} />
                    <Route path="settings" element={<SettingsPage />} />
                    <Route path="*" element={<Navigate to="/dashboard" replace />} />
                  </Routes>
                </AppShell>
              </ActiveProfileProvider>
            </RequireAuth>
          }
        />
      </Routes>
      <ToastHost />
    </>
  );
}
