package com.sfbank.bayanati.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The authority mapping this whole feature's authorization rules depend on (AD-002e §3.4) — no
 * Spring context needed, per the S3-01 package rule.
 */
class OperatorUserDetailsTest {

  private static OperatorAccount account(OperatorRole role, boolean mustChangePassword) {
    return new OperatorAccount(
        UUID.randomUUID(),
        "s405.fixture",
        "Fixture",
        role,
        "{bcrypt}hash",
        mustChangePassword,
        true);
  }

  private static Set<String> authorities(OperatorAccount account) {
    return new OperatorUserDetails(account)
        .getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
  }

  @Test
  void mustChangePasswordGrantsOnlyThatRoleRegardlessOfActualRole() {
    assertEquals(
        Set.of("ROLE_PASSWORD_CHANGE_REQUIRED"), authorities(account(OperatorRole.VIEWER, true)));
    assertEquals(
        Set.of("ROLE_PASSWORD_CHANGE_REQUIRED"), authorities(account(OperatorRole.OPERATOR, true)));
    assertEquals(
        Set.of("ROLE_PASSWORD_CHANGE_REQUIRED"), authorities(account(OperatorRole.ADMIN, true)));
  }

  @Test
  void viewerGetsOnlyViewer() {
    assertEquals(Set.of("ROLE_VIEWER"), authorities(account(OperatorRole.VIEWER, false)));
  }

  @Test
  void operatorGetsBothOperatorAndViewer() {
    // The coarse gate on /api/v1/operator/** is hasRole("VIEWER") -- an operator granted only
    // ROLE_OPERATOR would 403 before ever reaching a controller. This is the single most
    // important line in this whole feature; see the class Javadoc.
    assertEquals(
        Set.of("ROLE_OPERATOR", "ROLE_VIEWER"), authorities(account(OperatorRole.OPERATOR, false)));
  }

  @Test
  void adminGetsAdminOperatorAndViewer() {
    // AD-013 (2026-09-13): the ladder is a hierarchy and admin sits on top, so admin carries every
    // authority an operator carries. ROLE_VIEWER is what clears the coarse hasRole("VIEWER") gate
    // on /api/v1/operator/**; ROLE_OPERATOR is what clears the specific rules above it
    // (approve, reject, print, export -- and manual-complete, until AD-022 deleted it at S9-01).
    // Granting ADMIN alone -- which is what this test
    // asserted until BL-139 -- is what made an authenticated admin 403 on every operator endpoint.
    assertEquals(
        Set.of("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER"),
        authorities(account(OperatorRole.ADMIN, false)));
  }

  @Test
  void isEnabledReflectsIsEnabledDirectly() {
    OperatorAccount disabled =
        new OperatorAccount(
            UUID.randomUUID(),
            "s405.disabled",
            "Fixture",
            OperatorRole.VIEWER,
            "{bcrypt}hash",
            false,
            false);
    assertFalse(new OperatorUserDetails(disabled).isEnabled());
  }

  @Test
  void credentialsNonExpiredIsAlwaysTrueEvenWhenMustChangePassword() {
    // Deliberately not modelled as false -- AD-002e §3.4: that would fail authentication outright,
    // leaving no session in which to change anything.
    assertTrue(
        new OperatorUserDetails(account(OperatorRole.VIEWER, true)).isCredentialsNonExpired());
  }
}
