package com.sfbank.bayanati.auth.service;

import com.sfbank.bayanati.auth.domain.IncorrectCurrentPasswordException;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.domain.PasswordTooLongException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /api/v1/auth/password} — the forced first change AND a voluntary later change go
 * through this same method (AD-002e §3.4). Never audits the password itself, old or new, hashed or
 * not (CLAUDE.md no-secrets rule; audit.audit_event is append-only with 7-year retention).
 */
@Service
public class PasswordChangeService {

  /**
   * bcrypt's input limit. [UNVERIFIED in the research report whether 7.1 truncates or throws above
   * this] — enforcing it here at the boundary makes the question moot for this codebase.
   */
  static final int MAX_PASSWORD_BYTES = 72;

  private final OperatorUserRepository operatorUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthAuditRecorder auditRecorder;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public PasswordChangeService(
      OperatorUserRepository operatorUserRepository,
      PasswordEncoder passwordEncoder,
      AuthAuditRecorder auditRecorder,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.operatorUserRepository = operatorUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.auditRecorder = auditRecorder;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * @param currentHash the caller's own currently-stored hash (from the authenticated principal,
   *     never re-read here — the caller already has it from the SecurityContext)
   */
  public void changePassword(
      UUID userId, String currentHash, String currentPassword, String newPassword) {
    if (!passwordEncoder.matches(currentPassword, currentHash)) {
      throw new IncorrectCurrentPasswordException("current password does not match");
    }
    if (newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new PasswordTooLongException(
          "new password exceeds " + MAX_PASSWORD_BYTES + " bytes (bcrypt's input limit)");
    }
    String newHash = passwordEncoder.encode(newPassword);
    Instant now = clock.instant();
    // Same transaction as OperatorReviewService's own pattern (ManualCompletionService followed it
    // too, until AD-022 deleted it at S9-01): an audit
    // write that fails must not leave the state change committed with no record of it -- found by
    // review, since these two calls were previously two separate autocommit statements. Proven
    // live by OperatorAuthenticationIntegrationTest
    // #aFailingAuditWriteRollsBackThePasswordChangeTogetherNotPartially, revert-tested: reverting
    // this wrapper makes that test fail on a genuine assertion (the password hash changes even
    // though the audit write throws), not merely error out.
    transactionTemplate.executeWithoutResult(
        status -> {
          operatorUserRepository.updatePassword(userId, newHash, now);
          auditRecorder.passwordChanged(userId);
        });
  }
}
