import { apiFetch, ApiError } from './http';
import type { LoginResponse, MeResponse } from './types';

/**
 * Outcome of the boot-time (and post-401) session probe. `GET /api/v1/auth/me` requires
 * `hasAnyRole("VIEWER","ADMIN")` (`SecurityConfiguration`) — viewer, operator (which carries both
 * `ROLE_OPERATOR` and `ROLE_VIEWER`) and admin all satisfy that, so the ONLY principal excluded is
 * one holding solely `ROLE_PASSWORD_CHANGE_REQUIRED`. That makes the three possible outcomes here
 * exhaustive and unambiguous: 200 -> a real, usable session; 401 -> no session at all; 403 -> a
 * session exists but must still change its password. Verified live against the running backend
 * for all three (see the session report).
 */
export type MeOutcome =
  | { kind: 'authenticated'; me: MeResponse }
  | { kind: 'anonymous' }
  | { kind: 'must-change-password' };

export async function fetchMe(suppressUnauthorizedHandler = false): Promise<MeOutcome> {
  try {
    const me = await apiFetch<MeResponse>('/auth/me', { suppressUnauthorizedHandler });
    return { kind: 'authenticated', me };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      return { kind: 'anonymous' };
    }
    if (error instanceof ApiError && error.status === 403) {
      return { kind: 'must-change-password' };
    }
    throw error;
  }
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  // Spring's formLogin processing URL reads request.getParameter(...), not a JSON body.
  return apiFetch<LoginResponse>('/auth/login', {
    method: 'POST',
    formBody: { username, password },
  });
}

export async function logout(): Promise<void> {
  await apiFetch<void>('/auth/logout', { method: 'POST', suppressUnauthorizedHandler: true });
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  // A 401 here means "wrong current password" (auth.web.PasswordChangeController ->
  // IncorrectCurrentPasswordException), with the session still fully valid -- not a session loss.
  // Without suppressUnauthorizedHandler the global handler would drop state to 'anonymous' and
  // RequireMustChangePassword would bounce the operator to /login before ChangePasswordPage's own
  // catch ever got to show "كلمة المرور الحالية غير صحيحة." Found under review (same reasoning
  // logout() already applies to its own 401-shaped failure modes).
  await apiFetch<void>('/auth/password', {
    method: 'POST',
    body: { currentPassword, newPassword },
    suppressUnauthorizedHandler: true,
  });
}
