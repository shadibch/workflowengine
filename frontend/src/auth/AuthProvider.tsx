import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  User,
  UserManager,
  WebStorageStateStore,
  type UserManagerSettings,
} from 'oidc-client-ts';

import { useAuthStore } from '../app/store';

export interface AuthUser {
  id?: string;
  username: string;
  email?: string;
  roles: string[];
  permissions: string[];
  tenantId: string;
  locale: string;
  timezone: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isInitializing: boolean;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  getAccessToken: () => string | null;
  hasPermission: (permission: string) => boolean;
  hasRole: (role: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const env = import.meta.env;

// Realms in Keycloak 26 are reached at /realms/<realm>; the OIDC discovery
// metadata is served from there.
const authority = `${env.VITE_KEYCLOAK_URL}/realms/${env.VITE_KEYCLOAK_REALM}`;

const settings: UserManagerSettings = {
  authority,
  client_id: env.VITE_KEYCLOAK_CLIENT_ID,
  redirect_uri: `${window.location.origin}/login`,
  post_logout_redirect_uri: `${window.location.origin}/login`,
  response_type: 'code',
  scope: 'openid profile email',
  automaticSilentRenew: true,
  includeIdTokenInSilentRenew: true,
  silent_redirect_uri: `${window.location.origin}/login/silent`,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
};

const userManager = new UserManager(settings);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [oidcUser, setOidcUser] = useState<User | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);
  const [profile, setProfile] = useState<AuthUser | null>(null);

  const setSession = useAuthStore((state) => state.setSession);

  // Bootstrap: restore a stored session, or, if we just returned from Keycloak,
  // pick the authorization code off the callback URL. Either way the app only
  // renders UI once we know which state we are in.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const stored = await userManager.getUser();
        if (stored && !stored.expired) {
          if (cancelled) return;
          setOidcUser(stored);
          return;
        }

        // No stored session - this may be the redirect_back with ?code&state.
        try {
          const redirected = await userManager.signinRedirectCallback();
          if (!cancelled && redirected && !redirected.expired) {
            setOidcUser(redirected);
            window.history.replaceState({}, document.title, '/');
          }
        } catch {
          // No pending redirect; the user is simply not signed in.
        }
      } catch {
        // Storage read failed; degrade to anonymous.
      } finally {
        if (!cancelled) setIsInitializing(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Load the database-resolved profile once we have an OIDC session. Roles and
  // permissions always come from the database via /api/system/me; the token is
  // only proof of who you are.
  useEffect(() => {
    if (!oidcUser?.access_token) {
      setProfile(null);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const response = await fetch('/api/system/me', {
          headers: { Authorization: `Bearer ${oidcUser.access_token}` },
        });
        if (response.ok && !cancelled) {
          setProfile((await response.json()) as AuthUser);
        }
      } catch {
        // Keep the OIDC session alive even if the backend is momentarily down.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [oidcUser]);

  // Mirror the session into the non-React store consumed by the fetch layer.
  const isAuthenticated = Boolean(oidcUser?.access_token) && !oidcUser?.expired;
  useEffect(() => {
    setSession({
      accessToken: oidcUser?.access_token ?? null,
      profile,
      isAuthenticated,
      isInitializing,
    });
  }, [oidcUser, profile, isAuthenticated, isInitializing, setSession]);

  const login = useCallback(async () => userManager.signinRedirect(), []);

  const logout = useCallback(async () => {
    await userManager.signoutRedirect();
    setOidcUser(null);
    setProfile(null);
  }, []);

  const getAccessToken = useCallback(() => oidcUser?.access_token ?? null, [oidcUser]);

  const context = useMemo<AuthContextValue>(() => {
    const permissions = profile?.permissions ?? [];
    const roles = profile?.roles ?? [];
    return {
      user: profile,
      isAuthenticated,
      isInitializing,
      login,
      logout,
      getAccessToken,
      hasPermission: (p) => permissions.includes(p),
      hasRole: (r) => roles.includes(r),
    };
  }, [profile, isAuthenticated, isInitializing, login, logout, getAccessToken]);

  return <AuthContext.Provider value={context}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used within <AuthProvider>');
  }
  return value;
}