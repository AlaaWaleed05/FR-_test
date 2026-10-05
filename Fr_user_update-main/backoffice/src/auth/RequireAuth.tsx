import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Alert, Button, Spin } from 'antd';
import { useAuth } from './AuthContext';
import type { OperatorRole } from '../api/types';

/**
 * `data-testid`, and the four guards' tests query THAT rather than antd's `.ant-spin` class
 * (S9-02). The spinner itself is unchanged and still antd: this file is not one of the two
 * screens AD-021 drops antd from, and widening the drop here was not the task. What the test id
 * buys is that the tests stop asserting on a vendor's internal class name, which antd may rename
 * in any minor and which says nothing about what this screen is FOR.
 */
function LoadingScreen(): React.JSX.Element {
  return (
    <div
      data-testid="session-loading"
      style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}
    >
      <Spin size="large" />
    </div>
  );
}

/** Rendered for `state.status === 'error'` (a /me failure that is neither 401 nor 403) instead of
 * guessing a redirect -- there is no route that is obviously correct for "the session status
 * itself is unknown", and silently treating it as anonymous would misinform an operator who is
 * actually still signed in. Found under review: previously this case left every guard stuck on
 * `LoadingScreen` forever, with no message and no way out. */
function SessionErrorScreen({ message }: { message: string }): React.JSX.Element {
  const { refresh } = useAuth();
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
      <Alert
        type="error"
        showIcon
        style={{ maxWidth: 420 }}
        message="تعذر التحقق من حالة الجلسة"
        description={message}
        action={
          <Button size="small" onClick={() => void refresh()}>
            إعادة المحاولة
          </Button>
        }
      />
    </div>
  );
}

/** Guards a back-office screen: redirects to sign-in, to the forced password-change screen, or to
 * `/` (never a blank page) if the signed-in role isn't in `roles`. Preserves the attempted location
 * on the sign-in redirect so a re-login after a mid-session 401 returns to the same place.
 *
 * Since AD-013 (BL-139) the role exclusion bites in only ONE direction: `/admin` is admin-only,
 * while every signed-in role reaches the profile screens. This comment previously said the
 * opposite — "an admin hitting `/profiles` lands here and is sent to `/admin`, never shown the
 * operator screen" — quoting an operator.md line that AD-013 has since reversed. */
export function RequireRole({
  roles,
  children,
}: {
  roles: OperatorRole[];
  children: ReactNode;
}): React.JSX.Element {
  const { state } = useAuth();
  const location = useLocation();

  if (state.status === 'loading') return <LoadingScreen />;
  if (state.status === 'error') return <SessionErrorScreen message={state.message} />;
  if (state.status === 'anonymous') {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  if (state.status === 'must-change-password') {
    return <Navigate to="/change-password" replace />;
  }
  if (!roles.includes(state.role)) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

/** Guards `/login`: an already-signed-in principal never sees the sign-in form again. */
export function RequireAnonymous({ children }: { children: ReactNode }): React.JSX.Element {
  const { state } = useAuth();
  if (state.status === 'loading') return <LoadingScreen />;
  if (state.status === 'error') return <SessionErrorScreen message={state.message} />;
  if (state.status === 'must-change-password') return <Navigate to="/change-password" replace />;
  if (state.status === 'authenticated') return <Navigate to="/" replace />;
  return <>{children}</>;
}

/** Guards `/change-password`: only reachable mid-forced-change, never offers navigation
 * elsewhere (the task's own requirement) by simply not existing as a reachable route otherwise. */
export function RequireMustChangePassword({ children }: { children: ReactNode }): React.JSX.Element {
  const { state } = useAuth();
  if (state.status === 'loading') return <LoadingScreen />;
  if (state.status === 'error') return <SessionErrorScreen message={state.message} />;
  if (state.status === 'anonymous') return <Navigate to="/login" replace />;
  if (state.status === 'authenticated') return <Navigate to="/" replace />;
  return <>{children}</>;
}

/** `/` itself: routes each session state to where it actually belongs, so nothing needs its own
 * "home page" logic duplicated. */
export function RootRedirect(): React.JSX.Element {
  const { state } = useAuth();
  if (state.status === 'loading') return <LoadingScreen />;
  if (state.status === 'error') return <SessionErrorScreen message={state.message} />;
  if (state.status === 'anonymous') return <Navigate to="/login" replace />;
  if (state.status === 'must-change-password') return <Navigate to="/change-password" replace />;
  if (state.role === 'admin') return <Navigate to="/admin" replace />;
  return <Navigate to="/profiles" replace />;
}
