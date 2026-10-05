import type { OperatorRole } from '../api/types';

/**
 * The back-office role ladder, client side. AD-013 (2026-09-13, BL-139) made it a hierarchy:
 * viewer views; operator does everything a viewer does plus approve, reject, print and export;
 * admin does everything an operator does plus create and manage back-office users. Manual
 * completion was in that list until AD-022 removed it (S9-01, 2026-09-16).
 *
 * This exists so the ladder is written ONCE. Before BL-139 the profile screens tested
 * `role === 'operator'` inline at three separate call sites, which is exactly the shape that
 * silently drops a role when the model grows — adding admin meant finding all three. The backend
 * expresses the same hierarchy in one place for the same reason (`OperatorUserDetails`'s authority
 * mapping grants an admin ROLE_OPERATOR and ROLE_VIEWER, so no Spring Security rule mentions
 * admin at all).
 *
 * These are presentation guards only, never the enforcement point. Every action they reveal is
 * authorised again server-side on the request itself — a hidden button is a courtesy, not a
 * control.
 */

/** May approve, reject, print and export: operator and admin, not viewer. */
export function canOperate(role: OperatorRole): boolean {
  return role === 'operator' || role === 'admin';
}

/** May reach the profile screens at all. Every signed-in back-office role can, since AD-013. */
export function canViewProfiles(role: OperatorRole): boolean {
  return role === 'viewer' || role === 'operator' || role === 'admin';
}

/**
 * The roles `RequireRole` admits to the profile screens. A constant rather than a literal at the
 * route, so this list and {@link canViewProfiles} cannot drift apart — spelling the ladder twice
 * is what left `admin` off the route in the first place. `RequireRole` takes an array, not a
 * predicate, which is why this exists alongside the function rather than instead of it.
 */
export const PROFILE_SCREEN_ROLES: OperatorRole[] = (
  ['viewer', 'operator', 'admin'] as OperatorRole[]
).filter(canViewProfiles);

/** May create and manage back-office users — admin alone. The `/admin` surface. */
export function canManageUsers(role: OperatorRole): boolean {
  return role === 'admin';
}
