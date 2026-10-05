import { describe, expect, it } from 'vitest';
import { PROFILE_SCREEN_ROLES, canManageUsers, canOperate, canViewProfiles } from './capabilities';

describe('the back-office role ladder (AD-013)', () => {
  it('lets operator and admin act, but not viewer', () => {
    expect(canOperate('viewer')).toBe(false);
    expect(canOperate('operator')).toBe(true);
    // The BL-139 change. Asserted explicitly because an admin returning false here is precisely
    // the bug this build fixed: the approve/reject buttons simply never rendered.
    expect(canOperate('admin')).toBe(true);
  });

  it('lets every signed-in role reach the profile screens', () => {
    expect(canViewProfiles('viewer')).toBe(true);
    expect(canViewProfiles('operator')).toBe(true);
    expect(canViewProfiles('admin')).toBe(true);
  });

  it('admits all three roles to the profile screens, admin included', () => {
    // This is the list App.tsx hands RequireRole. Omitting 'admin' here is precisely the defect
    // BL-139 fixed, and asserting the constant rather than a literal in the route is what makes
    // that omission catchable at all.
    expect(PROFILE_SCREEN_ROLES).toContain('admin');
    expect([...PROFILE_SCREEN_ROLES].sort()).toEqual(['admin', 'operator', 'viewer']);
  });

  it('reserves user management to admin alone', () => {
    // AD-013's "plus" half: the one power an operator does NOT inherit upward.
    expect(canManageUsers('viewer')).toBe(false);
    expect(canManageUsers('operator')).toBe(false);
    expect(canManageUsers('admin')).toBe(true);
  });
});
