package com.sfbank.bayanati.auth.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.json.JsonMapper;

class SignInSuccessHandlerTest {

  private final OperatorUserRepository operatorUserRepository = mock(OperatorUserRepository.class);
  private final AuthAuditRecorder auditRecorder = mock(AuthAuditRecorder.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);
  private final SignInSuccessHandler handler =
      new SignInSuccessHandler(
          operatorUserRepository, auditRecorder, clock, JsonMapper.builder().build());

  @Test
  void recordsSignInAndWritesTheJsonBody() throws Exception {
    UUID userId = UUID.randomUUID();
    OperatorAccount account =
        new OperatorAccount(
            userId, "s405.success", "Success", OperatorRole.VIEWER, "{bcrypt}h", false, true);
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            new OperatorUserDetails(account),
            null,
            new OperatorUserDetails(account).getAuthorities());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

    verify(operatorUserRepository).recordSignIn(userId, clock.instant());
    verify(auditRecorder).signInSucceeded(userId);
    assertTrue(response.getStatus() == 200);
    String body = response.getContentAsString();
    assertTrue(body.contains("\"mustChangePassword\":false"));
    assertTrue(body.contains("\"role\":\"viewer\""));
  }

  @Test
  void nonOperatorPrincipalIsIgnoredSafely() throws Exception {
    // Boot's own default in-memory user, or any other principal type -- must not throw.
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(
            "someone-else", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

    assertTrue(response.getStatus() == 200);
    verify(operatorUserRepository, never()).recordSignIn(any(), any());
  }
}
