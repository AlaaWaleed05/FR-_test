package com.sfbank.bayanati.auth.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.web.OperatorIdentityArgumentResolver;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * No Spring context: {@code doFilter} (the public {@code OncePerRequestFilter} entry point, not the
 * protected {@code doFilterInternal} directly) against a plain {@link MockHttpServletRequest} and a
 * hand-set {@link SecurityContextHolder}.
 */
class OperatorIdentityFilterTest {

  private final OperatorUserRepository operatorUserRepository = mock(OperatorUserRepository.class);
  private final OperatorIdentityFilter filter = new OperatorIdentityFilter(operatorUserRepository);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void setsTheAttributeForAStillActiveViewer() throws Exception {
    UUID userId = UUID.randomUUID();
    authenticateAs(userId);
    when(operatorUserRepository.findActiveById(userId))
        .thenReturn(Optional.of(account(userId, OperatorRole.VIEWER)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    OperatorIdentity identity =
        (OperatorIdentity) request.getAttribute(OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE);
    assertEquals(userId.toString(), identity.operatorId());
    assertEquals(OperatorAccessLevel.VIEWER, identity.accessLevel());
  }

  @Test
  void mapsOperatorRoleToOperatorAccessLevel() throws Exception {
    UUID userId = UUID.randomUUID();
    authenticateAs(userId);
    when(operatorUserRepository.findActiveById(userId))
        .thenReturn(Optional.of(account(userId, OperatorRole.OPERATOR)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

    OperatorIdentity identity =
        (OperatorIdentity) request.getAttribute(OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE);
    assertEquals(OperatorAccessLevel.OPERATOR, identity.accessLevel());
  }

  @Test
  void setsNothingForADisabledAccountTheLiveSessionDisableEffect() throws Exception {
    UUID userId = UUID.randomUUID();
    authenticateAs(userId);
    // findActiveById is filtered to is_enabled = true -- a disabled account is simply absent.
    when(operatorUserRepository.findActiveById(userId)).thenReturn(Optional.empty());

    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

    assertNull(request.getAttribute(OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE));
  }

  @Test
  void mapsAdminRoleToOperatorAccessLevel() throws Exception {
    // AD-013 / BL-139: until 2026-09-13 this filter dropped an admin entirely and the test here
    // asserted the attribute stayed null. An admin now receives an identity at the OPERATOR access
    // level -- that is what carries them through the three service-side gates, each of which reads
    // `accessLevel() != OperatorAccessLevel.OPERATOR`. OperatorAccessLevel itself stays two-valued:
    // admin maps ONTO operator rather than becoming a third level (see its Javadoc).
    UUID userId = UUID.randomUUID();
    authenticateAs(userId);
    when(operatorUserRepository.findActiveById(userId))
        .thenReturn(Optional.of(account(userId, OperatorRole.ADMIN)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

    OperatorIdentity identity =
        (OperatorIdentity) request.getAttribute(OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE);
    assertEquals(OperatorAccessLevel.OPERATOR, identity.accessLevel());
    // The access level can no longer tell an admin from an operator -- both are OPERATOR. The role
    // string is what keeps them apart, and it exists for the audit trail alone. See
    // OperatorAuditPayload, and R-054 for why that distinction is load-bearing.
    assertEquals("admin", identity.actorRole());
  }

  @Test
  void setsNothingWhenUnauthenticated() throws Exception {
    SecurityContextHolder.clearContext();
    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

    assertNull(request.getAttribute(OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE));
  }

  private static void authenticateAs(UUID userId) {
    OperatorUserDetails principal = new OperatorUserDetails(account(userId, OperatorRole.VIEWER));
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
  }

  private static OperatorAccount account(UUID userId, OperatorRole role) {
    return new OperatorAccount(
        userId, "s405.filtertest", "Filter Test", role, "{bcrypt}hash", false, true);
  }
}
