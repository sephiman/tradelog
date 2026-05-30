import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { apiClient, seedCsrf } from "@/api/client";
import i18n from "@/i18n";

export interface Me {
  id: string;
  email: string;
  locale: "en" | "es";
}

interface AuthContextValue {
  user: Me | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, locale: "en" | "es") => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  setLocale: (locale: "en" | "es") => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function syncI18n(locale: string) {
  if (i18n.language !== locale) void i18n.changeLanguage(locale);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

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
      await seedCsrf();
      await refresh();
      setLoading(false);
    })();
  }, [refresh]);

  const login = useCallback(async (email: string, password: string) => {
    await seedCsrf();
    const res = await apiClient.post<Me>("/auth/login", { email, password, rememberMe: true });
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

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, login, register, logout, refresh, setLocale }),
    [user, loading, login, register, logout, refresh, setLocale],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
