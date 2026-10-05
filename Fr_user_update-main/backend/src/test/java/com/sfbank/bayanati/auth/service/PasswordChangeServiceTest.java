package com.sfbank.bayanati.auth.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sfbank.bayanati.auth.domain.IncorrectCurrentPasswordException;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.domain.PasswordTooLongException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * No Spring context, real {@link BCryptPasswordEncoder} (cheap enough for a unit test at the
 * default work factor, and the point is to exercise real bcrypt matching, not a fake one).
 */
class PasswordChangeServiceTest {

  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final OperatorUserRepository operatorUserRepository = mock(OperatorUserRepository.class);
  private final AuthAuditRecorder auditRecorder = mock(AuthAuditRecorder.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final PasswordChangeService service =
      new PasswordChangeService(
          operatorUserRepository, passwordEncoder, auditRecorder, clock, transactionManager);

  @Test
  void correctCurrentPasswordUpdatesHashAndAudits() {
    UUID userId = UUID.randomUUID();
    String currentHash = passwordEncoder.encode("correct-horse-battery-staple");

    service.changePassword(userId, currentHash, "correct-horse-battery-staple", "new-password-1");

    verify(operatorUserRepository).updatePassword(eq(userId), any(), eq(clock.instant()));
    verify(auditRecorder).passwordChanged(userId);
  }

  @Test
  void wrongCurrentPasswordIsRefusedBeforeAnyWrite() {
    UUID userId = UUID.randomUUID();
    String currentHash = passwordEncoder.encode("correct-horse-battery-staple");

    assertThrows(
        IncorrectCurrentPasswordException.class,
        () -> service.changePassword(userId, currentHash, "wrong-password", "new-password-1"));

    verify(operatorUserRepository, never()).updatePassword(any(), any(), any());
    verify(auditRecorder, never()).passwordChanged(any());
  }

  @Test
  void newPasswordOver72BytesIsRejected() {
    UUID userId = UUID.randomUUID();
    String currentHash = passwordEncoder.encode("correct-horse-battery-staple");
    // Arabic is 2 bytes/character in UTF-8 -- 40 characters already exceeds 72 bytes.
    String tooLong = "أ".repeat(40);

    assertThrows(
        PasswordTooLongException.class,
        () -> service.changePassword(userId, currentHash, "correct-horse-battery-staple", tooLong));

    verify(operatorUserRepository, never()).updatePassword(any(), any(), any());
  }
}
