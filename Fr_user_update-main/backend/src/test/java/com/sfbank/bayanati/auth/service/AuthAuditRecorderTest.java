package com.sfbank.bayanati.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two decided payload shapes (CLAUDE.md S4-05 task §2, AD-002e research §3.5(h)/(i)) — no
 * Spring context needed, per the S3-01 package rule. Both collaborators faked by hand, matching
 * {@code accountcheck.service.AccountCheckServiceTest}'s established convention.
 */
class AuthAuditRecorderTest {

  private static final class CapturingAuditEventWriter implements AuditEventWriter {
    private final List<AuditEvent> written = new ArrayList<>();

    @Override
    public long append(AuditEvent event) {
      written.add(event);
      return written.size();
    }

    @Override
    public long appendWithArtifact(AuditEvent event, AuditArtifact artifact) {
      throw new UnsupportedOperationException("not used by auth audit events");
    }
  }

  private static final class StubOperatorUserRepository implements OperatorUserRepository {
    private final List<UUID> chainsEnsured = new ArrayList<>();

    @Override
    public Optional<OperatorAccount> findByUsername(String username) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public Optional<OperatorAccount> findActiveById(UUID userId) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public UUID create(
        String username,
        String displayName,
        OperatorRole role,
        String passwordHash,
        UUID createdBy) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void updatePassword(UUID userId, String passwordHash, java.time.Instant changedAt) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void recordSignIn(UUID userId, java.time.Instant at) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void ensureOperatorChain(UUID userId) {
      chainsEnsured.add(userId);
    }
  }

  private final CapturingAuditEventWriter auditWriter = new CapturingAuditEventWriter();
  private final StubOperatorUserRepository operatorUserRepository =
      new StubOperatorUserRepository();
  private final AuthAuditRecorder recorder =
      new AuthAuditRecorder(auditWriter, operatorUserRepository);

  @Test
  void signInSucceededAuditsToTheOperatorsOwnChainWithTheUuidAsActorId() {
    UUID userId = UUID.randomUUID();
    recorder.signInSucceeded(userId);

    assertEquals(List.of(userId), operatorUserRepository.chainsEnsured);
    AuditEvent event = auditWriter.written.get(0);
    assertEquals("operator", event.chainKind());
    assertEquals(userId.toString(), event.chainSubject());
    assertEquals("operator", event.actorKind());
    assertEquals(userId.toString(), event.actorId());
    assertEquals("sign_in_succeeded", event.eventType());
  }

  @Test
  void failedSignInForAKnownUsernameRecordsAccountExistsTrueAndTheUuidNeverAUsername() {
    UUID matchedUserId = UUID.randomUUID();
    recorder.signInFailed(true, matchedUserId);

    AuditEvent event = auditWriter.written.get(0);
    assertEquals("system", event.chainKind());
    assertEquals("auth", event.chainSubject());
    assertEquals("system", event.actorKind());
    assertNull(event.actorId());
    assertTrue(event.payloadJson().contains("\"accountExists\":true"));
    assertTrue(event.payloadJson().contains(matchedUserId.toString()));
  }

  @Test
  void failedSignInForAnUnknownUsernameRecordsAccountExistsFalseAndNoUserId() {
    recorder.signInFailed(false, null);

    AuditEvent event = auditWriter.written.get(0);
    assertTrue(event.payloadJson().contains("\"accountExists\":false"));
    // "userId":null -- present as an explicit null, never a submitted username string.
    assertTrue(event.payloadJson().contains("\"userId\":null"));
    // No chain was ensured -- the system/auth chain is pre-seeded (V0058), never created by the
    // application.
    assertTrue(operatorUserRepository.chainsEnsured.isEmpty());
  }
}
