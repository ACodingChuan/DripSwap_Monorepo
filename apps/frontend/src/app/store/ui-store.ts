import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

type UiStore = {
  theme: ThemePreference;
  resolvedTheme: ResolvedTheme;
  setTheme: (theme: ThemePreference) => void;
  setResolvedTheme: (theme: ResolvedTheme) => void;
};

export const useUiStore = create<UiStore>()(
  persist(
    (set) => ({
      theme: 'system',
      resolvedTheme: 'light',
      setTheme: (nextTheme) => set({ theme: nextTheme }),
      setResolvedTheme: (nextResolvedTheme) => set({ resolvedTheme: nextResolvedTheme }),
    }),
    { name: 'dripswap-ui' }
  )
);
