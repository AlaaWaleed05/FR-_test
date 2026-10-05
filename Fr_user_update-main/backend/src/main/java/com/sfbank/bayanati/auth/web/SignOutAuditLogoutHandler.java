package com.sfbank.bayanati.auth.web;

import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Audits {@code sign_out} when the identity is known. Sign-out is auditable only when it is
 * actually called — a closed browser produces no event, and with the accepted 30-minute idle
 * timeout the session simply expires unaudited after that. Recorded as a known gap, not solved here
 * (AD-002e research report §3.5(k)).
 */
public class SignOutAuditLogoutHandler implements LogoutHandler {

  private final AuthAuditRecorder auditRecorder;

  public SignOutAuditLogoutHandler(AuthAuditRecorder auditRecorder) {
    this.auditRecorder = auditRecorder;
  }

  @Override
  public void logout(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof OperatorUserDetails principal) {
      auditRecorder.signedOut(principal.userId());
    }
  }
}
