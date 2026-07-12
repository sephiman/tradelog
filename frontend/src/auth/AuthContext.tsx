import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { apiClient, seedCsrf } from "@/api/client";
import i18n from "@/i18n";
import { setDisplayTimeZone } from "@/lib/format";

export interface Me {
  id: string;
  email: string;
  locale: "en" | "es";
  timeZone: string;
}

interface AuthContextValue {
  user: Me | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, locale: "en" | "es") => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  setLocale: (locale: "en" | "es") => Promise<void>;
  setTimeZone: (timeZone: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function syncI18n(locale: string) {
  if (i18n.language !== locale) void i18n.changeLanguage(locale);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  // Keep the shared date formatters on the account's timezone; runs before any page renders
  // because RequireAuth gates children on `loading`.
  useEffect(() => {
    setDisplayTimeZone(user?.timeZone);
  }, [user]);

  const refresh = useCallback(async () => {
    try {
      const me = await apiClient.get<Me>("/auth/me");
      setUser(me.data);
      syncI18n(me.data.locale);
    } catch {
      setUser(null);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      // A failed CSRF seed (backend down, network blip) must still resolve the loading state,
      // otherwise the app hangs on the loading screen forever.
      try {
        await seedCsrf();
        await refresh();
      } catch {
        setUser(null);
      } finally {
        setLoading(false);
      }
    })();
  }, [refresh]);

  const login = useCallback(async (email: string, password: string) => {
    await seedCsrf();
    const res = await apiClient.post<Me>("/auth/login", { email, password });
    setUser(res.data);
    syncI18n(res.data.locale);
  }, []);

  const register = useCallback(async (email: string, password: string, locale: "en" | "es") => {
    await seedCsrf();
    const res = await apiClient.post<Me>("/auth/register", { email, password, locale });
    setUser(res.data);
    syncI18n(res.data.locale);
  }, []);

  const logout = useCallback(async () => {
    await apiClient.post("/auth/logout");
    setUser(null);
    await seedCsrf();
  }, []);

  const setLocale = useCallback(async (locale: "en" | "es") => {
    const res = await apiClient.patch<Me>("/auth/me", { locale });
    setUser(res.data);
    syncI18n(locale);
  }, []);

  const setTimeZone = useCallback(async (timeZone: string) => {
    const res = await apiClient.patch<Me>("/auth/me", { timeZone });
    setUser(res.data);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, login, register, logout, refresh, setLocale, setTimeZone }),
    [user, loading, login, register, logout, refresh, setLocale, setTimeZone],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
