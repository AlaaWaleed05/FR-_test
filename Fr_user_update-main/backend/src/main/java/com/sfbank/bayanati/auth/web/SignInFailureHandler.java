package com.sfbank.bayanati.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Explicit, replacing Spring's default {@code formLogin()} failure handler (same reasoning as
 * {@link SignInSuccessHandler} — the default is a 302 redirect). 401 with no discriminating body:
 * whether the account existed must never be visible to the client, only to the audit trail (via
 * {@link AuthFailureAuditListener}, which runs independently of this handler and is where the
 * {@code accountExists} audit write actually happens).
 */
public class SignInFailureHandler implements AuthenticationFailureHandler {

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }
}
