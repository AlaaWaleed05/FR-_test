package com.sfbank.bayanati.auth.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;

class AuthFailureAuditListenerTest {

  private final OperatorUserRepository operatorUserRepository = mock(OperatorUserRepository.class);
  private final AuthAuditRecorder auditRecorder = mock(AuthAuditRecorder.class);
  private final AuthFailureAuditListener listener =
      new AuthFailureAuditListener(operatorUserRepository, auditRecorder);

  @Test
  void looksUpTheAttemptedUsernameIndependentlyRatherThanTrustingTheEventType() {
    UUID matchedUserId = UUID.randomUUID();
    when(operatorUserRepository.findByUsername("s405.knownuser"))
        .thenReturn(
            Optional.of(
                new OperatorAccount(
                    matchedUserId,
                    "s405.knownuser",
                    "Known",
                    OperatorRole.VIEWER,
                    "{bcrypt}h",
                    false,
                    true)));

    listener.onAuthenticationFailure(
        new AuthenticationFailureBadCredentialsEvent(
            new UsernamePasswordAuthenticationToken("s405.knownuser", "wrong-password"),
            new BadCredentialsException("bad credentials")));

    verify(auditRecorder).signInFailed(true, matchedUserId);
  }

  @Test
  void unknownUsernameRecordsAccountExistsFalse() {
    when(operatorUserRepository.findByUsername("s405.unknownuser")).thenReturn(Optional.empty());

    listener.onAuthenticationFailure(
        new AuthenticationFailureBadCredentialsEvent(
            new UsernamePasswordAuthenticationToken("s405.unknownuser", "whatever"),
            new BadCredentialsException("bad credentials")));

    verify(auditRecorder).signInFailed(false, null);
  }
}
