import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useProfiles, type Profile } from "@/api/profiles";

const KEY = "tl.activeProfile";

interface ActiveProfileValue {
  profiles: Profile[];
  isLoading: boolean;
  activeProfileId: string | null;
  activeProfile: Profile | null;
  setActiveProfileId: (id: string) => void;
}

const Ctx = createContext<ActiveProfileValue | undefined>(undefined);

export function ActiveProfileProvider({ children }: { children: ReactNode }) {
  const { data: profiles = [], isLoading } = useProfiles();
  const [activeId, setActiveId] = useState<string | null>(() => localStorage.getItem(KEY));

  useEffect(() => {
    if (profiles.length === 0) return;
    if (!activeId || !profiles.some((p) => p.id === activeId)) {
      const next = profiles[0].id;
      localStorage.setItem(KEY, next);
      setActiveId(next);
    }
  }, [profiles, activeId]);

  const setActiveProfileId = (id: string) => {
    localStorage.setItem(KEY, id);
    setActiveId(id);
  };

  const activeProfile = profiles.find((p) => p.id === activeId) ?? null;

  const value = useMemo<ActiveProfileValue>(
    () => ({ profiles, isLoading, activeProfileId: activeProfile?.id ?? null, activeProfile, setActiveProfileId }),
    [profiles, isLoading, activeProfile],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useActiveProfile(): ActiveProfileValue {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useActiveProfile must be used within ActiveProfileProvider");
  return ctx;
}
