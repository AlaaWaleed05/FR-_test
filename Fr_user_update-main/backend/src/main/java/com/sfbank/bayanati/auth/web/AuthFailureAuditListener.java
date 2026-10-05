package com.sfbank.bayanati.auth.web;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.stereotype.Component;

/**
 * Records every failed sign-in — product-owner decision #2 (CLAUDE.md S4-05 task §2): the audit
 * payload carries {@code accountExists} plus the matched {@code user_id}, NEVER the submitted
 * username.
 *
 * <p>Listens on the abstract event type deliberately, not a specific subtype: {@code
 * UsernameNotFoundException} and {@code BadCredentialsException} both publish the same {@code
 * AuthenticationFailureBadCredentialsEvent} (AD-002e research report §3.5(l) — {@code
 * DaoAuthenticationProvider}'s default {@code hideUserNotFoundExceptions=true} collapses them, and
 * even the un-hidden case maps to the same event per Spring Security's own default publisher
 * mapping), so the event type can never tell "no such user" from "wrong password" apart. This
 * listener therefore never trusts the event's own type or exception — it does its OWN {@link
 * OperatorUserRepository#findByUsername} lookup, independent of whatever {@code
 * DaoAuthenticationProvider} decided internally, which is the only sound way to fill {@code
 * accountExists} honestly. Fires uniformly for every failure subtype (bad credentials, disabled
 * account, etc.) — the response the CLIENT sees stays generic regardless (see {@link
 * SignInFailureHandler}); only this audit write differs.
 */
@Component
public class AuthFailureAuditListener {

  private final OperatorUserRepository operatorUserRepository;
  private final AuthAuditRecorder auditRecorder;

  public AuthFailureAuditListener(
      OperatorUserRepository operatorUserRepository, AuthAuditRecorder auditRecorder) {
    this.operatorUserRepository = operatorUserRepository;
    this.auditRecorder = auditRecorder;
  }

  @EventListener(AbstractAuthenticationFailureEvent.class)
  public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
    // Local only -- read once to perform the lookup below, never passed to the recorder, never
    // logged, never persisted in any form. The recorder receives only accountExists + userId.
    String attemptedUsername = event.getAuthentication().getName();
    Optional<OperatorAccount> account = operatorUserRepository.findByUsername(attemptedUsername);
    auditRecorder.signInFailed(
        account.isPresent(), account.map(OperatorAccount::userId).orElse(null));
  }
}
