package com.sfbank.bayanati.auth.web;

import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit, replacing Spring's default {@code formLogin()} success handler — raw {@code
 * formLogin()} defaults to {@code SavedRequestAwareAuthenticationSuccessHandler}, a 302 redirect,
 * not what a JSON back-office API needs (AD-002e research report, gap found against the "Build it"
 * config sample). Writes {@code {"mustChangePassword":..., "role":...}} so the SPA routes to the
 * change-password screen directly rather than discovering the state from a string of 403s.
 */
public class SignInSuccessHandler implements AuthenticationSuccessHandler {

  private final OperatorUserRepository operatorUserRepository;
  private final AuthAuditRecorder auditRecorder;
  private final Clock clock;
  private final JsonMapper jsonMapper;

  public SignInSuccessHandler(
      OperatorUserRepository operatorUserRepository,
      AuthAuditRecorder auditRecorder,
      Clock clock,
      JsonMapper jsonMapper) {
    this.operatorUserRepository = operatorUserRepository;
    this.auditRecorder = auditRecorder;
    this.clock = clock;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    if (!(authentication.getPrincipal() instanceof OperatorUserDetails principal)) {
      response.setStatus(HttpServletResponse.SC_OK);
      return;
    }
    operatorUserRepository.recordSignIn(principal.userId(), clock.instant());
    // Written here, not an @EventListener, so the audit write happens deterministically as part
    // of handling this specific request rather than as a best-effort async reaction (AD-002e
    // research report §3.5(l)). This does NOT make the write failure-proof: by this point
    // AbstractAuthenticationProcessingFilter has already saved the SecurityContext to the
    // session, so a throw from signInSucceeded surfaces as a 500 to an already-authenticated
    // caller with no sign_in_succeeded record -- found under review, recorded rather than
    // silently implied away, and listed alongside its two siblings (unaudited sign-out, failed
    // sign-in audit growth) in docs/components/backoffice-auth.md's "Known gaps" section, not
    // only here.
    auditRecorder.signInSucceeded(principal.userId());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mustChangePassword", principal.mustChangePassword());
    body.put("role", principal.role().name().toLowerCase(Locale.ROOT));
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonMapper.writeValueAsString(body));
  }
}
