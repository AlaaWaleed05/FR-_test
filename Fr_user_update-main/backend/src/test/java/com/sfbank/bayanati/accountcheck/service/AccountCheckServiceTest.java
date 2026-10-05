package com.sfbank.bayanati.accountcheck.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.accountcheck.domain.AccountCheckContinuation;
import com.sfbank.bayanati.accountcheck.domain.AccountCheckOutcome;
import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import com.sfbank.bayanati.corebanking.domain.CoreBankingClient;
import com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException;
import com.sfbank.bayanati.corebanking.domain.RawExchange;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Stage 1a's behaviour, with both collaborators faked by hand. No Spring context, no database: the
 * point of putting this class in a `service` package is that it is testable this way.
 */
class AccountCheckServiceTest {

  /** Records what it was asked, and returns whatever result the test told it to. */
  private static final class RecordingCoreBankingClient implements CoreBankingClient {
    private final CoreBankingCheckResult result;
    private String accountNumber;
    private int calls;

    RecordingCoreBankingClient(CoreBankingCheckResult result) {
      this.result = result;
    }

    @Override
    public CoreBankingCheckResult check(String accountNumber) {
      this.accountNumber = accountNumber;
      this.calls++;
      return result;
    }
  }

  /** One written event, with the artifact it was written with (null for a plain append). */
  private record Written(AuditEvent event, AuditArtifact artifact) {}

  private static final class CapturingAuditEventWriter implements AuditEventWriter {
    private final List<Written> written = new ArrayList<>();

    @Override
    public long append(AuditEvent event) {
      written.add(new Written(event, null));
      return written.size();
    }

    @Override
    public long appendWithArtifact(AuditEvent event, AuditArtifact artifact) {
      written.add(new Written(event, artifact));
      return written.size();
    }

    AuditEvent only() {
      assertEquals(1, written.size(), "expected exactly one audit event, got " + written.size());
      return written.get(0).event();
    }
  }

  private static final class FailingAuditEventWriter implements AuditEventWriter {
    private final RuntimeException failure;

    FailingAuditEventWriter(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public long append(AuditEvent event) {
      throw failure;
    }

    @Override
    public long appendWithArtifact(AuditEvent event, AuditArtifact artifact) {
      throw failure;
    }
  }

  private static final byte[] REQUEST_BYTES =
      "{\"Account\":\"0000000001\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESPONSE_BYTES =
      "{\"Response_Code\":1,\"Response_Message\":\"Account Found\"}"
          .getBytes(StandardCharsets.UTF_8);

  private static RawExchange exchange(byte[] response) {
    return new RawExchange(REQUEST_BYTES, response, "application/json", 200);
  }

  private final CapturingAuditEventWriter auditWriter = new CapturingAuditEventWriter();

  /** No profile for any account by default — every existing test predates the existence check. */
  private final ProfileRepository profileRepository = mock(ProfileRepository.class);

  static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  {
    when(profileRepository.findExisting(any())).thenReturn(Optional.empty());
  }

  /** A stub-shaped client: a bare code, no raw exchange. */
  private AccountCheckService serviceReturning(int resultCode) {
    return service(
        new RecordingCoreBankingClient(CoreBankingCheckResult.withoutExchange(resultCode)));
  }

  /** An http-shaped client: code, message and the raw bytes. */
  private AccountCheckService serviceReturning(int resultCode, String message, byte[] response) {
    return service(
        new RecordingCoreBankingClient(
            new CoreBankingCheckResult(resultCode, message, exchange(response))));
  }

  private AccountCheckService service(CoreBankingClient client) {
    return new AccountCheckService(client, auditWriter, profileRepository, clock);
  }

  // --- outcomes -------------------------------------------------------------------------------

  @Test
  void aFoundAccountIsActiveAndProceeds() {
    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckOutcome.ACTIVE, result.outcome());
    assertEquals(AccountCheckContinuation.PROCEED, result.continuation());
    assertNotNull(result.requestId());
  }

  @Test
  void aNotFoundAccountIsInvalidAndRetryable() {
    AccountCheckResult result = serviceReturning(0).check("16", "9999999999");

    assertEquals(AccountCheckOutcome.INVALID, result.outcome());
    assertEquals(AccountCheckContinuation.RETRY, result.continuation());
  }

  @Test
  void onlyTheAccountNumberIsSentToTheCoreBankingSystem() {
    // OQ-024: the branch is profile data, never part of the check.
    RecordingCoreBankingClient client =
        new RecordingCoreBankingClient(CoreBankingCheckResult.withoutExchange(1));

    service(client).check("16", "0000000001");

    assertEquals("0000000001", client.accountNumber);
    assertEquals(1, client.calls);
  }

  @Test
  void theProfileExistenceCheckIsKeyedOnTheAccountNumberAlone() {
    // BL-032 / V0061: the account number is the customer's identity bank-wide; the branch they
    // selected is descriptive data. The same account checked under two different branches must
    // find the same profile -- and only the account number ever reaches the repository.
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(UUID.randomUUID(), "submitted", true)));

    AccountCheckResult atBranch16 = serviceReturning(1).check("16", "0000000001");
    AccountCheckResult atBranch22 = serviceReturning(1).check("22", "0000000001");

    assertEquals(AccountCheckContinuation.TERMINAL, atBranch16.continuation());
    assertEquals(AccountCheckContinuation.TERMINAL, atBranch22.continuation());
    verify(profileRepository, times(2)).findExisting("0000000001");
  }

  // --- the middleware's system error and unavailability (S3-02) --------------------------------

  @Test
  void theMiddlewaresSystemErrorIsAnOutageNotANotFoundAndIsAuditedWithItsReply() {
    byte[] reply =
        "{\"Response_Code\":-1,\"Response_Message\":\"System Error\"}"
            .getBytes(StandardCharsets.UTF_8);
    AccountCheckService service = serviceReturning(-1, "System Error", reply);

    CoreBankingUnavailableException thrown =
        assertThrows(
            CoreBankingUnavailableException.class, () -> service.check("16", "0000000001"));

    assertTrue(thrown.getMessage().contains("System Error"), thrown.getMessage());
    assertEquals(2, auditWriter.written.size(), "request event, then the attempt");
    Written attempt = auditWriter.written.get(1);
    assertEquals("account_check_attempted", attempt.event().eventType());
    assertTrue(
        attempt.event().payloadJson().contains("\"outcome\":\"SYSTEM_ERROR\""),
        attempt.event().payloadJson());
    assertTrue(
        attempt.event().payloadJson().contains("\"resultCode\":-1"), attempt.event().payloadJson());
    assertEquals("omni_check_response", attempt.artifact().kind());
    assertArrayEquals(reply, attempt.artifact().body());
    verifyNoInteractions(profileRepository);
  }

  @Test
  void aSystemErrorFromTheStubIsStillAnOutageJustWithoutArtifacts() {
    assertThrows(
        CoreBankingUnavailableException.class,
        () -> serviceReturning(-1).check("16", "0000000002"));

    AuditEvent event = auditWriter.only();
    assertTrue(event.payloadJson().contains("\"outcome\":\"SYSTEM_ERROR\""), event.payloadJson());
    assertNull(auditWriter.written.get(0).artifact());
  }

  @Test
  void anUnavailableMiddlewareIsAuditedAsCallFailedWithWhateverBytesArrived() {
    byte[] html = "<html>502</html>".getBytes(StandardCharsets.UTF_8);
    RawExchange partial = new RawExchange(REQUEST_BYTES, html, "text/html", 502);
    CoreBankingUnavailableException unavailable =
        new CoreBankingUnavailableException("non-JSON response body", partial);
    CoreBankingClient throwing =
        accountNumber -> {
          throw unavailable;
        };

    CoreBankingUnavailableException thrown =
        assertThrows(
            CoreBankingUnavailableException.class,
            () -> service(throwing).check("16", "0000000001"));

    assertSame(unavailable, thrown);
    assertEquals(2, auditWriter.written.size());
    Written request = auditWriter.written.get(0);
    assertEquals("account_check_requested", request.event().eventType());
    assertEquals("omni_check_request", request.artifact().kind());
    assertArrayEquals(REQUEST_BYTES, request.artifact().body());
    Written attempt = auditWriter.written.get(1);
    assertTrue(
        attempt.event().payloadJson().contains("\"outcome\":\"CALL_FAILED\""),
        attempt.event().payloadJson());
    assertTrue(
        attempt.event().payloadJson().contains("\"resultCode\":null"),
        attempt.event().payloadJson());
    assertTrue(
        attempt.event().payloadJson().contains("\"httpStatus\":502"),
        attempt.event().payloadJson());
    assertEquals("omni_check_response", attempt.artifact().kind());
    assertEquals("text/html", attempt.artifact().mediaType());
    assertArrayEquals(html, attempt.artifact().body());
  }

  @Test
  void noResponseAtAllStillAuditsTheRequestThatWasSent() {
    // Connect/read timeout: request bytes exist, response bytes do not.
    RawExchange requestOnly = new RawExchange(REQUEST_BYTES, null, null, 0);
    CoreBankingClient throwing =
        accountNumber -> {
          throw new CoreBankingUnavailableException("no response", requestOnly);
        };

    assertThrows(
        CoreBankingUnavailableException.class, () -> service(throwing).check("16", "0000000001"));

    assertEquals(2, auditWriter.written.size());
    assertEquals("omni_check_request", auditWriter.written.get(0).artifact().kind());
    Written attempt = auditWriter.written.get(1);
    assertNull(attempt.artifact(), "no response bytes, so no response artifact");
    assertTrue(
        attempt.event().payloadJson().contains("\"httpStatus\":null"),
        attempt.event().payloadJson());
    assertTrue(
        attempt.event().payloadJson().contains("\"outcome\":\"CALL_FAILED\""),
        attempt.event().payloadJson());
  }

  // --- the profile-existence branch (S3-07, S4-06) -- unchanged behaviour ----------------------

  @Test
  void anActiveAccountWithNoExistingProfileStillProceeds() {
    when(profileRepository.findExisting("0000000001")).thenReturn(Optional.empty());

    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckContinuation.PROCEED, result.continuation());
  }

  @Test
  void anActiveAccountWithAnIncompleteExistingProfileStillProceeds() {
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(UUID.randomUUID(), "in_progress", false)));

    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckContinuation.PROCEED, result.continuation());
  }

  @Test
  void anActiveAccountWithACompleteExistingProfileIsTerminal() {
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(UUID.randomUUID(), "submitted", true)));

    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckOutcome.ACTIVE, result.outcome());
    assertEquals(AccountCheckContinuation.TERMINAL, result.continuation());
  }

  @Test
  void anActiveAccountWithAnIncompletePhoneLockedProfileIsBlocked() {
    // BL-021: a relaunch during Stage 2's escalating phone lock (V0037, R-044) must surface it,
    // not silently resume PROCEED into a stage that will lock again.
    UUID profileId = UUID.randomUUID();
    Instant until = NOW.plusSeconds(600);
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(profileId, "in_progress", false)));
    when(profileRepository.currentPhoneLockUntil(profileId)).thenReturn(Optional.of(until));

    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckOutcome.ACTIVE, result.outcome());
    assertEquals(AccountCheckContinuation.BLOCKED, result.continuation());
    assertEquals(until, result.blockedUntil());
  }

  @Test
  void anExpiredPhoneLockStillProceedsWithNoBlockedUntil() {
    // Proves the isAfter(now) guard, not just presence/absence of the stored column value -- a
    // lock that has already lifted must not report BLOCKED.
    UUID profileId = UUID.randomUUID();
    Instant expired = NOW.minusSeconds(600);
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(profileId, "in_progress", false)));
    when(profileRepository.currentPhoneLockUntil(profileId)).thenReturn(Optional.of(expired));

    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckContinuation.PROCEED, result.continuation());
    assertNull(result.blockedUntil());
  }

  @Test
  void aTerminalProfileIsNeverCheckedForAPhoneLock() {
    // TERMINAL already wins outright (customer.md: "no re-entry, no supersede path") -- a phone
    // lock on a completed profile is meaningless and must not downgrade TERMINAL to BLOCKED.
    UUID profileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(profileId, "submitted", true)));

    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(AccountCheckContinuation.TERMINAL, result.continuation());
    assertNull(result.blockedUntil());
    verify(profileRepository, never()).currentPhoneLockUntil(any());
  }

  @Test
  void aPhoneLockCheckFailureIsAuditedBeforeItPropagates() {
    // NOTE fixed by @agent-reviewer, S4-06: originally this read had no audit coverage at all for
    // a DB failure, unlike its sibling findExisting() catch one block above -- same failure class,
    // same OUTCOME_PROFILE_CHECK_FAILED treatment now applies to both halves of the
    // profile-existence check.
    UUID profileId = UUID.randomUUID();
    IllegalStateException dbFailure = new IllegalStateException("connection reset");
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(profileId, "in_progress", false)));
    when(profileRepository.currentPhoneLockUntil(profileId)).thenThrow(dbFailure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> serviceReturning(1).check("16", "0000000001"));

    assertEquals(dbFailure, thrown);
    AuditEvent event = auditWriter.only();
    assertTrue(event.payloadJson().contains("\"outcome\":\"PROFILE_CHECK_FAILED\""));
    assertTrue(event.payloadJson().contains("\"profileStatus\":\"in_progress\""));
  }

  @Test
  void aProfileExistenceCheckFailureIsAuditedBeforeItPropagates() {
    IllegalStateException dbFailure = new IllegalStateException("connection reset");
    when(profileRepository.findExisting("0000000001")).thenThrow(dbFailure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> serviceReturning(1).check("16", "0000000001"));

    assertEquals(dbFailure, thrown);
    AuditEvent event = auditWriter.only();
    assertTrue(event.payloadJson().contains("\"outcome\":\"PROFILE_CHECK_FAILED\""));
    assertTrue(event.payloadJson().contains("\"resultCode\":1"), "the core result is kept");
  }

  @Test
  void theBlockedUntilTimestampIsRecordedOnTheAccountCheckAuditEvent() {
    UUID profileId = UUID.randomUUID();
    Instant until = NOW.plusSeconds(900);
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(profileId, "in_progress", false)));
    when(profileRepository.currentPhoneLockUntil(profileId)).thenReturn(Optional.of(until));

    serviceReturning(1).check("16", "0000000001");

    AuditEvent event = auditWriter.only();
    assertTrue(
        event.payloadJson().contains("\"blockedUntilIso\":\"" + until + "\""), event.payloadJson());
  }

  @Test
  void theProfileExistenceOutcomeIsAuditedOnlyOnTheActivePath() {
    serviceReturning(0).check("16", "9999999999"); // INVALID

    verifyNoInteractions(profileRepository);
    AuditEvent event = auditWriter.only();
    assertTrue(event.payloadJson().contains("\"profileStatus\":null"), event.payloadJson());
    assertTrue(event.payloadJson().contains("\"profileTerminal\":null"), event.payloadJson());
  }

  @Test
  void aTerminalProfileIsRecordedOnTheAccountCheckAuditEvent() {
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(UUID.randomUUID(), "approved", true)));

    serviceReturning(1).check("16", "0000000001");

    AuditEvent event = auditWriter.only();
    assertTrue(event.payloadJson().contains("\"profileStatus\":\"approved\""), event.payloadJson());
    assertTrue(event.payloadJson().contains("\"profileTerminal\":true"), event.payloadJson());
  }

  // --- the audit record ------------------------------------------------------------------------

  @Test
  void everyAttemptIsAuditedWithBranchAccountNumberResultCodeAndOutcome() {
    serviceReturning(0).check("16", "9999999999");

    AuditEvent event = auditWriter.only();

    assertEquals("account_check_attempted", event.eventType());
    assertEquals("customer", event.actorKind());
    // CanonicalJson sorts keys lexicographically (RFC 8785), not by insertion order. Against the
    // stub there is no HTTP exchange, so httpStatus and responseMessage are null.
    assertEquals(
        "{\"accountNumber\":\"9999999999\",\"blockedUntilIso\":null,\"branch\":\"16\","
            + "\"httpStatus\":null,\"outcome\":\"INVALID\",\"profileStatus\":null,"
            + "\"profileTerminal\":null,\"responseMessage\":null,\"resultCode\":0}",
        event.payloadJson());
  }

  @Test
  void aRealExchangeIsRecordedAsTwoEventsCarryingTheRawRequestAndResponse() {
    // PROJECT_PLAN.md Architecture: the raw CheckAccount request/response is a retained artifact.
    // audit_event.artifact_id is one FK, so one event per artifact: request first, then outcome,
    // under one requestId.
    AccountCheckResult result =
        serviceReturning(1, "Account Found", RESPONSE_BYTES).check("16", "0000000001");

    assertEquals(2, auditWriter.written.size());
    Written request = auditWriter.written.get(0);
    Written attempt = auditWriter.written.get(1);

    assertEquals("account_check_requested", request.event().eventType());
    assertEquals(result.requestId(), request.event().requestId());
    assertEquals("system", request.event().chainKind());
    assertEquals("account_check", request.event().chainSubject());
    assertEquals(
        "{\"accountNumber\":\"0000000001\",\"branch\":\"16\"}", request.event().payloadJson());
    assertEquals("omni_check_request", request.artifact().kind());
    assertEquals("application/json", request.artifact().mediaType());
    assertArrayEquals(REQUEST_BYTES, request.artifact().body());

    assertEquals("account_check_attempted", attempt.event().eventType());
    assertEquals(result.requestId(), attempt.event().requestId());
    assertEquals(
        "{\"accountNumber\":\"0000000001\",\"blockedUntilIso\":null,\"branch\":\"16\","
            + "\"httpStatus\":200,\"outcome\":\"ACTIVE\",\"profileStatus\":null,"
            + "\"profileTerminal\":null,\"responseMessage\":\"Account Found\",\"resultCode\":1}",
        attempt.event().payloadJson());
    assertEquals("omni_check_response", attempt.artifact().kind());
    assertEquals("application/json", attempt.artifact().mediaType());
    assertArrayEquals(RESPONSE_BYTES, attempt.artifact().body());
  }

  @Test
  void theStubWritesNoArtifactsBecauseItHasNoRealExchange() {
    // S3-01's reasoning stands: against a stub the "raw response" would be an invented value.
    serviceReturning(1).check("16", "0000000001");

    assertEquals(1, auditWriter.written.size(), "no request event without request bytes");
    assertNull(auditWriter.written.get(0).artifact());
  }

  @Test
  void anInvalidAttemptIsAuditedEvenThoughNoProfileIsCreated() {
    // The whole reason the journey's audit section calls this case out: not-found attempts are
    // what a misuse investigation reads, and none creates a profile.
    serviceReturning(0).check("16", "9999999999");

    AuditEvent event = auditWriter.only();

    assertNull(event.profileId(), "Stage 1a creates no profile");
    assertNull(event.sessionId(), "the session begins at Stage 1b");
    assertNull(event.actorId(), "the customer is unidentified until a channel is verified");
    assertTrue(event.payloadJson().contains("\"resultCode\":0"), event.payloadJson());
  }

  @Test
  void theAuditEventGoesToTheSeededSystemChain() {
    serviceReturning(1).check("16", "0000000001");

    AuditEvent event = auditWriter.only();
    assertEquals("system", event.chainKind());
    assertEquals("account_check", event.chainSubject());
  }

  @Test
  void theRequestIdOnTheResultIsTheOneRecordedOnTheAuditEvent() {
    // This is what makes the id the app is shown usable for finding the audit row.
    AccountCheckResult result = serviceReturning(1).check("16", "0000000001");

    assertEquals(result.requestId(), auditWriter.only().requestId());
  }

  @Test
  void eachCheckGetsItsOwnRequestId() {
    AccountCheckService service = serviceReturning(1);

    AccountCheckResult first = service.check("16", "0000000001");
    AccountCheckResult second = service.check("16", "0000000001");

    assertTrue(!first.requestId().equals(second.requestId()));
  }

  @Test
  void anUnrecognisedResultCodeStillFailsButIsAuditedFirst() {
    // The journey requires account-check ATTEMPTS recorded. A call that reached the middleware and
    // came back with a code outside its stated contract is an attempt -- and the anomalous one a
    // misuse or incident investigation is most likely to want. It must not vanish. And it is a
    // 500, not a 503: our understanding of the contract is wrong, which is a defect, not a
    // transient.
    byte[] reply = "{\"Response_Code\":7}".getBytes(StandardCharsets.UTF_8);

    assertThrows(
        IllegalArgumentException.class, () -> serviceReturning(7, null, reply).check("16", "1"));

    assertEquals(2, auditWriter.written.size());
    Written attempt = auditWriter.written.get(1);
    assertTrue(attempt.event().payloadJson().contains("\"resultCode\":7"));
    assertTrue(
        attempt.event().payloadJson().contains("\"outcome\":\"UNMAPPED\""),
        "the raw code is recorded, but it is NOT mapped to an outcome: "
            + attempt.event().payloadJson());
    assertArrayEquals(reply, attempt.artifact().body(), "the anomalous reply is kept verbatim");
  }

  @Test
  void aThrowingCoreBankingCallIsAuditedBeforeTheFailurePropagates() {
    CoreBankingClient throwing =
        accountNumber -> {
          throw new IllegalStateException("connection reset");
        };

    assertThrows(IllegalStateException.class, () -> service(throwing).check("16", "0000000001"));

    AuditEvent event = auditWriter.only();
    // Whether the call reached the core is unknowable from here, which is itself worth recording.
    assertTrue(event.payloadJson().contains("\"resultCode\":null"), event.payloadJson());
    assertTrue(event.payloadJson().contains("\"outcome\":\"CALL_FAILED\""), event.payloadJson());
  }

  // --- audit failures never mask the original failure ------------------------------------------

  @Test
  void anAuditFailureOnTheUnmappedPathDoesNotMaskTheOriginalFailure() {
    IllegalStateException auditFailure = new IllegalStateException("no audit chain");
    AccountCheckService service =
        new AccountCheckService(
            new RecordingCoreBankingClient(CoreBankingCheckResult.withoutExchange(7)),
            new FailingAuditEventWriter(auditFailure),
            profileRepository,
            clock);

    // The original failure -- the unrecognised result code -- is what the caller sees. The audit
    // failure is not discarded: it is attached as a suppressed exception, never silently lost.
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> service.check("16", "1"));
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals(auditFailure, thrown.getSuppressed()[0]);
  }

  @Test
  void anAuditFailureOnTheSystemErrorPathDoesNotMaskTheOutage() {
    IllegalStateException auditFailure = new IllegalStateException("no audit chain");
    AccountCheckService service =
        new AccountCheckService(
            new RecordingCoreBankingClient(CoreBankingCheckResult.withoutExchange(-1)),
            new FailingAuditEventWriter(auditFailure),
            profileRepository,
            clock);

    CoreBankingUnavailableException thrown =
        assertThrows(CoreBankingUnavailableException.class, () -> service.check("16", "1"));
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals(auditFailure, thrown.getSuppressed()[0]);
  }

  @Test
  void anAuditFailurePropagatesRatherThanReturningAnUnauditedOutcome() {
    IllegalStateException auditFailure = new IllegalStateException("no audit chain");
    AccountCheckService service =
        new AccountCheckService(
            new RecordingCoreBankingClient(CoreBankingCheckResult.withoutExchange(1)),
            new FailingAuditEventWriter(auditFailure),
            profileRepository,
            clock);

    // There is no "original" failure on this path -- the core banking call itself never throws,
    // only the audit write does -- so the audit failure is what propagates, unattached.
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> service.check("16", "0000000001"));
    assertEquals(auditFailure, thrown);
    assertEquals(0, thrown.getSuppressed().length);
  }

  @Test
  void anAuditFailureOnTheCallFailedPathDoesNotMaskTheOriginalFailure() {
    IllegalStateException coreFailure = new IllegalStateException("connection reset");
    CoreBankingClient throwing =
        accountNumber -> {
          throw coreFailure;
        };
    IllegalStateException auditFailure = new IllegalStateException("no audit chain");
    AccountCheckService service =
        new AccountCheckService(
            throwing, new FailingAuditEventWriter(auditFailure), profileRepository, clock);

    // Two different failures on the same path: the core banking call is what the customer's
    // retry actually depends on, so it is what propagates, with the audit failure suppressed
    // rather than replacing it.
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> service.check("16", "0000000001"));
    assertEquals(coreFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals(auditFailure, thrown.getSuppressed()[0]);
  }
}
