import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface AuthState {
  sessionId: string | null;
  address: string | null;
  isAuthenticated: boolean;
  setSession: (sessionId: string, address: string) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      sessionId: null,
      address: null,
      isAuthenticated: false,
      setSession: (sessionId, address) => set({ sessionId, address, isAuthenticated: true }),
      logout: () => set({ sessionId: null, address: null, isAuthenticated: false }),
    }),
    {
      name: 'dripswap-auth-storage',
    }
  )
);
