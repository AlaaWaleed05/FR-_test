package com.sfbank.bayanati.auth.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class SignOutAuditLogoutHandlerTest {

  private final AuthAuditRecorder auditRecorder = mock(AuthAuditRecorder.class);
  private final SignOutAuditLogoutHandler handler = new SignOutAuditLogoutHandler(auditRecorder);

  @Test
  void auditsSignOutWhenIdentityIsKnown() {
    UUID userId = UUID.randomUUID();
    OperatorAccount account =
        new OperatorAccount(
            userId, "s405.logout", "Logout", OperatorRole.VIEWER, "{bcrypt}h", false, true);
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            new OperatorUserDetails(account), null, java.util.List.of());

    handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(), authentication);

    verify(auditRecorder).signedOut(userId);
  }

  @Test
  void doesNothingWhenAuthenticationIsNull() {
    handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(), null);

    verify(auditRecorder, never()).signedOut(org.mockito.ArgumentMatchers.any());
  }
}
