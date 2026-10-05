import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchMe, login as apiLogin, logout as apiLogout } from '../api/auth';
import { setUnauthorizedHandler } from '../api/http';
import type { LoginResponse, OperatorRole } from '../api/types';

export type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'must-change-password' }
  | { status: 'authenticated'; username: string; displayName: string; role: OperatorRole }
  // A /me failure that is neither 401 nor 403 (a 500, a network drop) -- distinct from
  // 'anonymous' so the UI doesn't lie about the session actually being signed out. Found under
  // review: without this, such a failure left `state` at 'loading' forever (the boot effect
  // discarded the rejection via `void probe()`, and every route guard renders a spinner for
  // 'loading' with no escape).
  | { status: 'error'; message: string };

export interface AuthContextValue {
  state: AuthState;
  login: (username: string, password: string) => Promise<LoginResponse>;
  logout: () => Promise<void>;
  /** Re-probes /me -- used after a successful password change (confirmed live that this needs no
   * new sign-in, docs/components/backoffice-auth.md) and as the retry action from the 'error'
   * state. Never throws: a failure here degrades to `state.status === 'error'` instead of
   * rejecting, since every caller of `refresh` is a UI action, not `login`'s own internal flow. */
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }): React.JSX.Element {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  const probe = useCallback(async () => {
    const outcome = await fetchMe(true);
    if (outcome.kind === 'anonymous') {
      setState({ status: 'anonymous' });
    } else if (outcome.kind === 'must-change-password') {
      setState({ status: 'must-change-password' });
    } else {
      setState({
        status: 'authenticated',
        username: outcome.me.username,
        displayName: outcome.me.displayName,
        role: outcome.me.role,
      });
    }
  }, []);

  const refresh = useCallback(async () => {
    try {
      await probe();
    } catch (error) {
      setState({
        status: 'error',
        message: error instanceof Error ? error.message : 'تعذر التحقق من حالة الجلسة.',
      });
    }
  }, [probe]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    setUnauthorizedHandler(() => setState({ status: 'anonymous' }));
    return () => setUnauthorizedHandler(null);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await apiLogin(username, password);
    if (result.mustChangePassword) {
      setState({ status: 'must-change-password' });
    } else {
      // The login response body doesn't carry displayName -- one /me round-trip fills it in.
      // Uses the throwing probe(), not refresh(): a failure here must reach LoginPage's own
      // catch (it already renders a specific error message), not silently become 'error' state
      // while the caller believes the awaited login() call is still pending success.
      await probe();
    }
    return result;
  }, [probe]);

  const logout = useCallback(async () => {
    // Always drop local state, even if the server call fails -- a failed sign-out is exactly the
    // moment the client should stop trusting its own session, not keep presenting it as valid.
    // Found under review: the previous `await apiLogout(); setState(...)` order left the UI
    // looking signed in, with no visible feedback, on any logout failure.
    try {
      await apiLogout();
    } finally {
      setState({ status: 'anonymous' });
      // POST /auth/logout EXPIRES the XSRF-TOKEN cookie server-side with no replacement (verified
      // live via curl: Set-Cookie: XSRF-TOKEN=; Expires=...). This SPA never reloads the page
      // between sign-out and the next sign-in, so with nothing else in between, the very next
      // unsafe request -- a login attempt on the screen this transitions to -- has no CSRF token
      // to send and gets a bare 403, not the expected 401 for wrong credentials. Found live via
      // Playwright: sign out, then sign in as a different account, POST /auth/login -> 403.
      // Any GET through chain 1 re-primes the cookie regardless of its own outcome (also verified
      // live), so this doesn't need to succeed as a real session check -- only to run.
      await fetchMe(true).catch(() => {});
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ state, login, logout, refresh }),
    [state, login, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
