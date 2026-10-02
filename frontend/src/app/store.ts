import { create } from 'zustand';
import type { AuthUser } from '../auth/AuthProvider';

interface AuthState {
  accessToken: string | null;
  profile: AuthUser | null;
  isAuthenticated: boolean;
  isInitializing: boolean;
  setSession: (args: {
    accessToken: string | null;
    profile: AuthUser | null;
    isAuthenticated: boolean;
    isInitializing: boolean;
  }) => void;
}

/**
 * Non-component auth state. Components read `useAuth()`, but the fetch layer
 * (which lives outside React) reads this store so it can attach the bearer
 * token without violating the rules of hooks.
 */
export const useAuthStore = create<AuthState>()((set) => ({
  accessToken: null,
  profile: null,
  isAuthenticated: false,
  isInitializing: true,
  setSession: (session) => set(session),
}));

export const getAccessToken = () => useAuthStore.getState().accessToken;