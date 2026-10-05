package com.sfbank.bayanati.liveness.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.liveness.domain.FaceMatchAlreadyPassedException;
import com.sfbank.bayanati.liveness.domain.InvalidFaceSessionException;
import com.sfbank.bayanati.liveness.domain.LivenessAttemptBudget;
import com.sfbank.bayanati.liveness.domain.LivenessBlockReason;
import com.sfbank.bayanati.liveness.domain.LivenessRepository;
import com.sfbank.bayanati.liveness.domain.LivenessState;
import com.sfbank.bayanati.liveness.domain.LivenessTemporarilyBlockedException;
import com.sfbank.bayanati.liveness.domain.NoAcceptedIdentityCycleException;
import com.sfbank.bayanati.liveness.domain.ProfileNotEditableException;
import com.sfbank.bayanati.liveness.domain.ReferenceImage;
import com.sfbank.bayanati.liveness.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.IssuedAccessToken;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.ParsedFaceResult;
import com.sfbank.bayanati.uqudo.domain.ParsedIncompleteFaceResult;
import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@code LivenessService}'s orchestration, every collaborator mocked -- no Spring context, no
 * database (CLAUDE.md's business-logic testability rule). Mirrors {@code IdentityScanServiceTest}'s
 * shape. Every test here regression-guards a defect {@code @agent-reviewer} found in this class's
 * first draft.
 */
class LivenessServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

  /**
   * The access token's own life (~1800s). Deliberately LONGER than FACE_SESSION_LIFETIME, so a test
   * that asserts usableUntil is telling the two deadlines apart rather than agreeing by accident.
   */
  private static final Instant TOKEN_EXPIRY = NOW.plusSeconds(1800);

  private static IssuedAccessToken issuedToken(String value) {
    return new IssuedAccessToken(value, TOKEN_EXPIRY);
  }

  private static final UUID PROFILE_ID = UUID.randomUUID();

  private final LivenessRepository livenessRepository = mock(LivenessRepository.class);
  private final ProfileRepository profileRepository = mock(ProfileRepository.class);
  private final UqudoClient uqudoClient = mock(UqudoClient.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final LivenessService service =
      new LivenessService(
          livenessRepository,
          profileRepository,
          uqudoClient,
          auditEventWriter,
          clock,
          transactionManager,
          3);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
    when(auditEventWriter.appendWithArtifact(any(), any())).thenReturn(1L);
  }

  // ---- issueFaceSessionToken: the block ----

  @Test
  void issueFaceSessionTokenStillBlockedIsRejected() {
    Instant blockedUntil = NOW.plusSeconds(3600);
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "blocked_liveness", false, 5, blockedUntil, null, null, null, 0)));

    LivenessTemporarilyBlockedException thrown =
        assertThrows(
            LivenessTemporarilyBlockedException.class,
            () -> service.issueFaceSessionToken(PROFILE_ID));
    assertEquals(blockedUntil, thrown.blockedUntil());
    verify(uqudoClient, never()).createFaceSession(any());
  }

  @Test
  void issueFaceSessionTokenResumesAfterBlockExpires() {
    UUID cycleId = UUID.randomUUID();
    Instant blockedUntilInThePast = NOW.minusSeconds(60);
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "blocked_liveness", false, 5, blockedUntilInThePast, null, cycleId, null, 0)));
    when(livenessRepository.currentAcceptedCycleReferenceImage(PROFILE_ID))
        .thenReturn(Optional.of(new ReferenceImage(cycleId, new byte[] {1, 2, 3})));
    when(uqudoClient.createFaceSession(any())).thenReturn("face-session-resumed");
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-resumed"));

    FaceSessionIssuance issuance = service.issueFaceSessionToken(PROFILE_ID);

    assertEquals("token-resumed", issuance.accessToken());
    verify(livenessRepository).resumeFromLivenessBlock(eq(PROFILE_ID), eq(NOW), anyLong());
  }

  // ---- BL-039 Slice B: the lifetime cap, and the orphaned budget check ----

  @Test
  void issueFaceSessionTokenAtTheLifetimeCapAppliesTheExistingBlockFromInProgress() {
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    0,
                    null,
                    null,
                    cycleId,
                    null,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));

    LivenessTemporarilyBlockedException blocked =
        assertThrows(
            LivenessTemporarilyBlockedException.class,
            () -> service.issueFaceSessionToken(PROFILE_ID));

    assertEquals(NOW.plus(LivenessService.LIVENESS_BLOCK_DURATION), blocked.blockedUntil());
    verify(livenessRepository)
        .applyLivenessBlock(
            eq(PROFILE_ID),
            eq(NOW.plus(LivenessService.LIVENESS_BLOCK_DURATION)),
            eq(NOW),
            anyLong());
    // Refused in phase 1, so neither Uqudo operation this path would otherwise spend is made.
    verify(uqudoClient, never()).createFaceSession(any());
    verify(uqudoClient, never()).issueAccessToken();
    verify(livenessRepository, never()).incrementFaceTokensMinted(any());
  }

  /**
   * The cap-before-lift ordering, as on the scan side: nothing is written, nothing is reapplied.
   */
  @Test
  void theFaceCapIsCheckedBeforeTheExpiredBlockIsLifted() {
    Instant expired = NOW.minusSeconds(1);
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "blocked_liveness",
                    false,
                    5,
                    expired,
                    null,
                    UUID.randomUUID(),
                    null,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));

    LivenessTemporarilyBlockedException blocked =
        assertThrows(
            LivenessTemporarilyBlockedException.class,
            () -> service.issueFaceSessionToken(PROFILE_ID));

    assertEquals(expired, blocked.blockedUntil());
    verify(livenessRepository, never()).resumeFromLivenessBlock(any(), any(), anyLong());
    verify(livenessRepository, never()).applyLivenessBlock(any(), any(), any(), anyLong());
  }

  /**
   * {@code LivenessAttemptBudget.canAttempt} existed since S3-13 and was called from no production
   * code at all. Wired in at token issuance, where its own Javadoc says it belongs.
   */
  @Test
  void issueFaceSessionTokenRefusesAnExhaustedBudgetFromInProgress() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    LivenessAttemptBudget.LIMIT,
                    null,
                    null,
                    UUID.randomUUID(),
                    null,
                    0)));

    assertThrows(
        LivenessTemporarilyBlockedException.class, () -> service.issueFaceSessionToken(PROFILE_ID));
    verify(uqudoClient, never()).createFaceSession(any());
  }

  @Test
  void aSuccessfulFaceIssuanceCountsExactlyOneMint() {
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(new LivenessState("in_progress", false, 0, null, null, cycleId, null, 0)));
    when(livenessRepository.currentAcceptedCycleReferenceImage(PROFILE_ID))
        .thenReturn(Optional.of(new ReferenceImage(cycleId, new byte[] {1, 2, 3})));
    when(uqudoClient.createFaceSession(any())).thenReturn("face-session-minted");
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-minted"));

    service.issueFaceSessionToken(PROFILE_ID);

    // One mint, though this path spends TWO Uqudo operations: what is capped is the token.
    verify(livenessRepository, times(1)).incrementFaceTokensMinted(PROFILE_ID);
  }

  /**
   * BLOCKER found by {@code @agent-reviewer} against the first Slice B draft. The cap branch
   * applied {@code blocked_liveness} from whatever non-terminal status the profile happened to
   * hold, and {@code issueFaceSessionToken} guards only terminality — so {@code blocked_scan},
   * {@code abandoned} and {@code awaiting_registry} all reached {@code applyLivenessBlock}. V0020
   * defines no transition into {@code blocked_liveness} from any of them, so the trigger would
   * raise 23514 and the customer would get a 500 instead of a 409. BL-043's defect, through a new
   * door.
   */
  @Test
  void theFaceCapNeverAppliesABlockFromANonInProgressStatus() {
    for (String status : new String[] {"blocked_scan", "abandoned", "awaiting_registry"}) {
      LivenessRepository repo = mock(LivenessRepository.class);
      AuditEventWriter writer = mock(AuditEventWriter.class);
      when(writer.append(any())).thenReturn(1L);
      LivenessService isolated =
          new LivenessService(
              repo,
              profileRepository,
              uqudoClient,
              writer,
              Clock.fixed(NOW, ZoneOffset.UTC),
              transactionManager,
              3);
      when(repo.lockAndGetLivenessState(PROFILE_ID))
          .thenReturn(
              Optional.of(
                  new LivenessState(
                      status,
                      false,
                      0,
                      null,
                      null,
                      UUID.randomUUID(),
                      null,
                      LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));

      assertThrows(
          LivenessTemporarilyBlockedException.class,
          () -> isolated.issueFaceSessionToken(PROFILE_ID),
          status + " must still be refused");
      verify(repo, never()).applyLivenessBlock(any(), any(), any(), anyLong());
      verify(repo, never()).incrementFaceTokensMinted(any());
    }
  }

  /**
   * BL-121, S8-15. The cap arm's non-{@code in_progress} branch used to set the SAME flag pair the
   * genuine budget-exhaustion arm sets, and that pair's single handler audits {@code
   * liveness_budget_exhausted} — so a lifetime-cap refusal was recorded in the append-only audit
   * trail as a budget exhaustion, which it is not. The scan side never had the bug: {@code
   * IdentityScanService.issueToken} carries a distinct {@code capRefused} and audits {@code
   * scan_token_cap_reached}.
   *
   * <p>This asserts the audit REASON, not the wire, because the wire was never wrong here — both
   * refusals are a 409 {@code LIVENESS_BLOCKED} and still are. What was wrong is the only record
   * anyone can read afterwards, and the trail is append-only, so every row already written under
   * the wrong reason stays wrong forever. That is why this is its own defect and not part of
   * BL-118.
   *
   * <p>No test referenced either event type before this one — verified by grepping {@code
   * backend/src/test} for both strings — so the defect was completely unguarded.
   */
  @Test
  void aCapRefusalAgainstANonInProgressProfileIsAuditedAsACapNotABudgetExhaustion() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "abandoned",
                    false,
                    0,
                    null,
                    null,
                    UUID.randomUUID(),
                    null,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));

    LivenessTemporarilyBlockedException blocked =
        assertThrows(
            LivenessTemporarilyBlockedException.class,
            () -> service.issueFaceSessionToken(PROFILE_ID));

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertTrue(
        captor.getValue().payloadJson().contains("liveness_token_cap_reached"),
        "a cap refusal must be audited as a cap: " + captor.getValue().payloadJson());
    assertFalse(
        captor.getValue().payloadJson().contains("liveness_budget_exhausted"),
        "a cap refusal must NOT be audited as a budget exhaustion");
    // BL-118: and it names itself on the wire, on the unchanged LIVENESS_BLOCKED code.
    assertEquals(LivenessBlockReason.LIFETIME_CAP, blocked.blockReason());
  }

  /**
   * BL-118. The cap and the budget both answer {@code LIVENESS_BLOCKED} — Stage 10 has no second
   * document to switch to, so there is no {@code SCAN_TYPE_EXHAUSTED} equivalent here — and until
   * S8-15 that was all the app could ever learn. The reason rides alongside the unchanged code.
   */
  @Test
  void theLifetimeCapAndTheSpentBudgetAreDistinguishableOnTheWire() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    0,
                    null,
                    null,
                    UUID.randomUUID(),
                    null,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));
    assertEquals(
        LivenessBlockReason.LIFETIME_CAP,
        assertThrows(
                LivenessTemporarilyBlockedException.class,
                () -> service.issueFaceSessionToken(PROFILE_ID))
            .blockReason());

    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    LivenessAttemptBudget.LIMIT,
                    null,
                    null,
                    UUID.randomUUID(),
                    null,
                    0)));
    assertEquals(
        LivenessBlockReason.BUDGET_EXHAUSTED,
        assertThrows(
                LivenessTemporarilyBlockedException.class,
                () -> service.issueFaceSessionToken(PROFILE_ID))
            .blockReason());
  }

  /**
   * BL-114(a). {@code usableUntil} must be the FACE SESSION's deadline, not the access token's.
   * Uqudo deletes a face session and its reference image 600 s after creation, while the token
   * lives about 1800 s — so a client-side reuse rule reasoned from the token would be wrong by
   * roughly three times, and reusing a dead face session surfaces as an error the screen charges an
   * attempt for. {@code TOKEN_EXPIRY} is deliberately the later of the two here, so this assertion
   * fails if the service ever returns the token's expiry instead.
   */
  @Test
  void faceSessionIssuanceIsUsableUntilTheFaceSessionDiesNotUntilTheTokenDoes() {
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(new LivenessState("in_progress", false, 0, null, null, cycleId, null, 0)));
    when(livenessRepository.currentAcceptedCycleReferenceImage(PROFILE_ID))
        .thenReturn(Optional.of(new ReferenceImage(cycleId, new byte[] {1, 2, 3})));
    when(uqudoClient.createFaceSession(any())).thenReturn("face-session-1");
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-1"));

    FaceSessionIssuance issuance = service.issueFaceSessionToken(PROFILE_ID);

    assertEquals(NOW.plus(LivenessService.FACE_SESSION_LIFETIME), issuance.usableUntil());
    assertTrue(
        issuance.usableUntil().isBefore(TOKEN_EXPIRY),
        "the face session must die before the token, or this test proves nothing");
  }

  /**
   * The second BLOCKER from the same pass. Both new branches ran ABOVE the already-passed guard, so
   * a customer who had COMPLETED Stage 10 on their last allowed mint could be pushed into {@code
   * blocked_liveness} by any later token request — and that block never lifted, because the cap
   * branch's already-blocked arm returned the stored deadline before any expiry check ran, while
   * {@code SubmissionService.submit} requires {@code in_progress}. The app would route them to a
   * submit that could never succeed.
   */
  @Test
  void aProfileThatAlreadyPassedIsNeverBlockedByTheCap() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    0,
                    null,
                    null,
                    UUID.randomUUID(),
                    true,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));

    assertThrows(
        FaceMatchAlreadyPassedException.class, () -> service.issueFaceSessionToken(PROFILE_ID));

    verify(livenessRepository, never()).applyLivenessBlock(any(), any(), any(), anyLong());
    verify(livenessRepository, never()).incrementFaceTokensMinted(any());
  }

  /**
   * The concurrency hole the first draft left, also found by {@code @agent-reviewer}. Phase 1
   * commits and releases the row lock before {@code createFaceSession}, so N concurrent callers at
   * {@code cap - 1} all passed phase 1 on the same read and all reached the increment. The scan
   * side has no equivalent hole because its check, mint and pending-session write share one
   * transaction under {@code FOR UPDATE OF p}.
   *
   * <p>Simulated by returning a phase-1 state below the cap and a phase-2 state at it — which is
   * exactly what the losing caller of a real race reads.
   */
  @Test
  void aConcurrentMintThatCrossesTheCapIsRefusedInTheSecondTransaction() {
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    0,
                    null,
                    null,
                    cycleId,
                    null,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP - 1)))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress",
                    false,
                    0,
                    null,
                    null,
                    cycleId,
                    null,
                    LivenessAttemptBudget.LIFETIME_TOKEN_CAP)));
    when(livenessRepository.currentAcceptedCycleReferenceImage(PROFILE_ID))
        .thenReturn(Optional.of(new ReferenceImage(cycleId, new byte[] {1, 2, 3})));
    when(uqudoClient.createFaceSession(any())).thenReturn("face-session-raced-cap");

    assertThrows(
        LivenessTemporarilyBlockedException.class, () -> service.issueFaceSessionToken(PROFILE_ID));

    verify(livenessRepository, never()).incrementFaceTokensMinted(any());
    verify(livenessRepository, never()).recordPendingFaceSession(any(), any());
    verify(uqudoClient, never()).issueAccessToken();
  }

  @Test
  void issueFaceSessionTokenRejectsAPurgedReferenceImageAsNoAcceptedCycleNotA500() {
    // Found by @agent-reviewer: a NULL imageBytes() on an otherwise-present ReferenceImage means
    // app.purge_abandoned_artifacts() (S5-06) has nulled the body -- a real, expected outcome
    // (a reactivated abandoned profile), not the invariant-violation case (an entirely missing
    // row, still IllegalStateException). Must surface as the same "go rescan" 409 family as
    // no-accepted-cycle, never a raw 500.
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(new LivenessState("in_progress", false, 0, null, null, cycleId, null, 0)));
    when(livenessRepository.currentAcceptedCycleReferenceImage(PROFILE_ID))
        .thenReturn(Optional.of(new ReferenceImage(cycleId, null)));

    assertThrows(
        NoAcceptedIdentityCycleException.class, () -> service.issueFaceSessionToken(PROFILE_ID));
    verify(uqudoClient, never()).createFaceSession(any());
  }

  @Test
  void issueFaceSessionTokenDoesNotResumeABlockAppliedBetweenPhases() {
    // Regression: phase 1 (before the external createFaceSession call) sees no block; a
    // concurrent 5th failed attempt applies a brand-new 24h block before phase 2 re-locks. The
    // first draft resumed unconditionally on "status is blocked_liveness" in phase 2, silently
    // clearing a block this very request never actually waited out.
    UUID cycleId = UUID.randomUUID();
    LivenessState notYetBlocked =
        new LivenessState("in_progress", false, 4, null, null, cycleId, null, 0);
    Instant freshBlockUntil = NOW.plusSeconds(3600);
    LivenessState blockedDuringExternalCall =
        new LivenessState("blocked_liveness", false, 5, freshBlockUntil, null, cycleId, null, 0);
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(Optional.of(notYetBlocked))
        .thenReturn(Optional.of(blockedDuringExternalCall));
    when(livenessRepository.currentAcceptedCycleReferenceImage(PROFILE_ID))
        .thenReturn(Optional.of(new ReferenceImage(cycleId, new byte[] {1, 2, 3})));
    when(uqudoClient.createFaceSession(any())).thenReturn("face-session-raced");

    LivenessTemporarilyBlockedException thrown =
        assertThrows(
            LivenessTemporarilyBlockedException.class,
            () -> service.issueFaceSessionToken(PROFILE_ID));
    assertEquals(freshBlockUntil, thrown.blockedUntil());
    verify(livenessRepository, never()).resumeFromLivenessBlock(any(), any(), anyLong());
    verify(livenessRepository, never()).recordPendingFaceSession(any(), any());
    verify(uqudoClient, never()).issueAccessToken();
  }

  @Test
  void issueFaceSessionTokenAlreadyPassedIsRejected() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, null, UUID.randomUUID(), true, 0)));

    assertThrows(
        FaceMatchAlreadyPassedException.class, () -> service.issueFaceSessionToken(PROFILE_ID));
    verify(uqudoClient, never()).createFaceSession(any());
  }

  // ---- reportLivenessTerminated: the BLOCKER guard ----

  @Test
  void reportLivenessTerminatedWhileAwaitingRegistryIsRejectedWithoutMutatingCounters() {
    // BLOCKER regression: this endpoint requires no accepted cycle and no issued token, so a
    // profile paused at awaiting_registry (stage 9, unrelated to stage 10) could otherwise have
    // this method mutate liveness_attempts and eventually fire an illegal
    // awaiting_registry -> blocked_liveness transition V0020 does not define.
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "awaiting_registry", false, 0, null, "face-session-1", null, null, 0)));

    assertThrows(
        RegistryReviewPendingException.class,
        () -> service.reportLivenessTerminated(PROFILE_ID, "face-session-1", "USER_CANCEL", null));
    verify(livenessRepository, never()).applyLivenessAttempt(any());
    verify(livenessRepository, never()).applyLivenessBlock(any(), any(), any(), anyLong());
  }

  @Test
  void reportLivenessTerminatedWithMismatchedFaceSessionIsRejected() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, "the-real-session", null, null, 0)));

    assertThrows(
        InvalidFaceSessionException.class,
        () ->
            service.reportLivenessTerminated(
                PROFILE_ID, "a-different-session", "USER_CANCEL", null));
    verify(livenessRepository, never()).applyLivenessAttempt(any());
  }

  @Test
  void reportLivenessTerminatedCountsAndBlocksAtTheLimit() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState("in_progress", false, 4, null, "face-session-1", null, null, 0)));

    service.reportLivenessTerminated(PROFILE_ID, "face-session-1", "USER_CANCEL", null);

    verify(livenessRepository).applyLivenessAttempt(PROFILE_ID);
    verify(livenessRepository)
        .applyLivenessBlock(eq(PROFILE_ID), eq(NOW.plusSeconds(86400)), eq(NOW), anyLong());
  }

  @Test
  void reportLivenessTerminatedWhileTerminalIsRejected() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(new LivenessState("submitted", true, 0, null, null, null, null, 0)));

    assertThrows(
        ProfileNotEditableException.class,
        () -> service.reportLivenessTerminated(PROFILE_ID, "any-session", "USER_CANCEL", null));
  }

  // ---- submitFaceResult: exp and the lost fraud signal ----

  @Test
  void expiredFaceArtifactIsNotCountedAgainstTheBudget() {
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, "face-session-1", cycleId, null, 0)));
    when(uqudoClient.verifyAndParseFaceSession(any(), any()))
        .thenThrow(new ArtifactExpiredException("exp has passed"));

    assertThrows(
        ArtifactExpiredException.class,
        () -> service.submitFaceResult(PROFILE_ID, "face-session-1", "jws"));

    verify(livenessRepository, never()).applyLivenessAttempt(any());
    verify(livenessRepository, never()).applyLivenessBlock(any(), any(), any(), anyLong());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("face_artifact_expired", captor.getValue().eventType());
  }

  @Test
  void auditTrailUnavailableStillRecordsMatchAndMatchLevel() {
    // Regression: the first draft recorded only jti and the failure reason here, silently
    // discarding match/matchLevel -- the exact fraud signal R-016 and customer.md Stage 10 say
    // must never be lost, even though the image itself could not be retrieved.
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, "face-session-1", cycleId, null, 0)));
    ParsedFaceResult parsed =
        new ParsedFaceResult("jti-1", "face-session-1", false, 2, null, "audit-1", "sha256:x");
    when(uqudoClient.verifyAndParseFaceSession(any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any()))
        .thenThrow(new ImageUnavailableException("audit-1"));

    assertThrows(
        com.sfbank.bayanati.liveness.domain.AuditTrailImageUnavailableException.class,
        () -> service.submitFaceResult(PROFILE_ID, "face-session-1", "jws"));

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(1)).append(captor.capture());
    assertEquals("liveness_audit_trail_unavailable", captor.getValue().eventType());
    assertTrue(captor.getValue().payloadJson().contains("\"match\":false"));
    assertTrue(captor.getValue().payloadJson().contains("\"matchLevel\":2"));
    // Deliberately not counted against the budget -- a system-timing fact, not a face-match
    // quality problem, same as stage 8's images-unavailable case.
    verify(livenessRepository, never()).applyLivenessAttempt(any());
  }

  @Test
  void retriedUploadOfTheSameJwsDoesNotSpendASecondAttempt() {
    // Regression (@agent-reviewer, S3-13 second pass): customer.md Stage 10 requires a dropped
    // upload's retry not repeat the check -- "a returned JWS is retained and the upload retried".
    // FaceJwsAlreadyAcceptedException only fires across DIFFERENT cycles (uqudo_jti's UNIQUE
    // constraint); a retry against the SAME cycle silently upserts and, without this guard, would
    // count a second time against the budget for one real-world attempt.
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 3, null, "face-session-1", cycleId, null, 0)));
    when(livenessRepository.currentFaceResultJti(cycleId)).thenReturn(Optional.of("jti-1"));
    ParsedFaceResult parsed =
        new ParsedFaceResult("jti-1", "face-session-1", false, 1, null, "audit-1", "sha256:x");
    when(uqudoClient.verifyAndParseFaceSession(any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {1, 2, 3});

    FaceResultOutcome outcome = service.submitFaceResult(PROFILE_ID, "face-session-1", "jws");

    assertFalse(outcome.passed());
    verify(livenessRepository, never()).applyLivenessAttempt(any());
    verify(livenessRepository, never()).applyLivenessBlock(any(), any(), any(), anyLong());
    verify(profileRepository).touchLastActivity(eq(PROFILE_ID), eq(NOW));
  }

  @Test
  void passingResultTouchesActivityAndDoesNotClearTheReferenceImage() {
    // S5-06: AD-004 retains the portrait permanently in app.artifact_ref as sanctioned evidence,
    // like every other artifact kind -- there is no longer anything to clear on a pass.
    // LivenessRepository.clearFaceReferenceImage was removed along with V0041/V0042 (see
    // RISKS.md R-047), so the guarantee is now structural: the method does not exist on the
    // interface, so there is nothing this test -- or any caller -- could invoke even by mistake.
    // This test used to verify that clearing call; it now just proves the pass still completes
    // normally (activity touched, no attempt/block applied) with that call gone.
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, "face-session-1", cycleId, null, 0)));
    ParsedFaceResult parsed =
        new ParsedFaceResult("jti-1", "face-session-1", true, 5, null, "audit-1", "sha256:x");
    when(uqudoClient.verifyAndParseFaceSession(any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {1, 2, 3});

    FaceResultOutcome outcome = service.submitFaceResult(PROFILE_ID, "face-session-1", "jws");

    assertTrue(outcome.passed());
    verify(profileRepository).touchLastActivity(eq(PROFILE_ID), eq(NOW));
    verify(livenessRepository, never()).applyLivenessAttempt(any());
    verify(uqudoClient).purgeSession("face-session-1");
  }

  @Test
  void failingResultBelowThresholdCountsAgainstTheBudgetButPassesAudit() {
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, "face-session-1", cycleId, null, 0)));
    ParsedFaceResult parsed =
        new ParsedFaceResult("jti-1", "face-session-1", true, 2, null, "audit-1", "sha256:x");
    when(uqudoClient.verifyAndParseFaceSession(any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {1, 2, 3});

    FaceResultOutcome outcome = service.submitFaceResult(PROFILE_ID, "face-session-1", "jws");

    assertFalse(outcome.passed(), "matchLevel 2 is below the configured threshold of 3");
    verify(livenessRepository).applyLivenessAttempt(PROFILE_ID);
  }

  // ---- S1-02 defect 2: purge by data.sessionId, never by jti ----

  @Test
  void purgeUsesTheFaceSessionIdNeverTheJti() {
    // Observed live at S1-02: DELETE /api/v1/info/{id} returns 204 for ANY UUID. Purging by the
    // face JWS's jti returned 204 and left the audit-trail image downloadable; purging by
    // data.sessionId returned 204 and the image was gone. The S3-13 code purged by jti -- a
    // silent no-op on a privacy control (R-001). A test asserting only "purge was called" passes
    // against either version; this one asserts the ID PASSED, with jti and sessionId distinct.
    UUID cycleId = UUID.randomUUID();
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState(
                    "in_progress", false, 0, null, "face-session-1", cycleId, null, 0)));
    ParsedFaceResult parsed =
        new ParsedFaceResult(
            "3d7c2c1e-jti-not-the-session", "face-session-1", true, 5, null, "audit-1", "sha256:x");
    when(uqudoClient.verifyAndParseFaceSession(any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {1, 2, 3});

    service.submitFaceResult(PROFILE_ID, "face-session-1", "jws");

    verify(uqudoClient).purgeSession("face-session-1");
    verify(uqudoClient, never()).purgeSession("3d7c2c1e-jti-not-the-session");
  }

  // ---- BL-028: the partial JWS on the terminated path ----

  @Test
  void terminatedWithAVerifiedPartialJwsStoresItAsAnArtifactAndRecordsTheMatchDetail() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState("in_progress", false, 0, null, "face-session-1", null, null, 0)));
    when(uqudoClient.verifyAndParseIncompleteFaceSession("partial-jws", "face-session-1"))
        .thenReturn(
            new ParsedIncompleteFaceResult(
                "jti-partial", "face-session-1", false, 1, null, "audit-2", "sha256:y"));

    service.reportLivenessTerminated(
        PROFILE_ID,
        "face-session-1",
        "SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS",
        "partial-jws");

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<AuditArtifact> artifact = ArgumentCaptor.forClass(AuditArtifact.class);
    verify(auditEventWriter).appendWithArtifact(event.capture(), artifact.capture());
    verify(auditEventWriter, never()).append(any());
    assertEquals("liveness_attempt_terminated", event.getValue().eventType());
    assertTrue(event.getValue().payloadJson().contains("\"partialJwsStatus\":\"verified\""));
    assertTrue(event.getValue().payloadJson().contains("\"partialMatch\":false"));
    assertTrue(event.getValue().payloadJson().contains("\"partialMatchLevel\":1"));
    assertEquals("uqudo_face_jws", artifact.getValue().kind());
    assertEquals("partial-jws", new String(artifact.getValue().body(), StandardCharsets.UTF_8));
    // Still one countable attempt, exactly as without a partial JWS.
    verify(livenessRepository).applyLivenessAttempt(PROFILE_ID);
    // Evidence, not an outcome: no face_result row, no image download, no purge.
    verify(livenessRepository, never())
        .upsertFaceResult(any(), any(), any(), anyBoolean(), anyInt(), anyInt(), any());
    verify(uqudoClient, never()).downloadImage(any(), any());
    verify(uqudoClient, never()).purgeSession(any());
  }

  @Test
  void terminatedWithAPartialJwsLackingAFaceObjectStillStoresTheArtifact() {
    // [UNVERIFIED] whether Uqudo's partial artifact carries match detail -- the accepted worst
    // case is an audit record without it, never a lost attempt or a lost artifact.
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState("in_progress", false, 0, null, "face-session-1", null, null, 0)));
    when(uqudoClient.verifyAndParseIncompleteFaceSession(any(), any()))
        .thenReturn(
            new ParsedIncompleteFaceResult(
                "jti-partial", "face-session-1", null, null, null, null, null));

    service.reportLivenessTerminated(PROFILE_ID, "face-session-1", "USER_CANCEL", "partial-jws");

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).appendWithArtifact(event.capture(), any());
    assertTrue(event.getValue().payloadJson().contains("\"partialJwsStatus\":\"verified\""));
    assertTrue(event.getValue().payloadJson().contains("\"partialMatch\":null"));
    verify(livenessRepository).applyLivenessAttempt(PROFILE_ID);
  }

  @Test
  void terminatedWithARejectedPartialJwsStillCountsTheAttemptAndKeepsTheArtifact() {
    // Found under review: the first cut dropped the artifact on rejection and copied the
    // exception message into the payload. The raw JWS is the evidence BL-028 exists to capture
    // (stored like a rejected scan JWS), and the message can quote client-controlled header
    // bytes that CanonicalJson would refuse mid-transaction -- so only a fixed category is
    // recorded.
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState("in_progress", false, 0, null, "face-session-1", null, null, 0)));
    when(uqudoClient.verifyAndParseIncompleteFaceSession(any(), any()))
        .thenThrow(new JwsVerificationException("malformed JWS: Unsupported JWS algorithm X "));

    service.reportLivenessTerminated(PROFILE_ID, "face-session-1", "USER_CANCEL", "bad-jws");

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<AuditArtifact> artifact = ArgumentCaptor.forClass(AuditArtifact.class);
    verify(auditEventWriter).appendWithArtifact(event.capture(), artifact.capture());
    verify(auditEventWriter, never()).append(any());
    String payload = event.getValue().payloadJson();
    assertTrue(payload.contains("\"partialJwsStatus\":\"rejected\""), payload);
    assertTrue(payload.contains("\"partialJwsReason\":\"verification_failed\""), payload);
    assertFalse(payload.contains("Unsupported"), "never the exception text: " + payload);
    assertEquals("bad-jws", new String(artifact.getValue().body(), StandardCharsets.UTF_8));
    verify(livenessRepository).applyLivenessAttempt(PROFILE_ID);
  }

  @Test
  void terminatedWithAPartialJwsThatBreaksTheParserStillRecordsTheTermination() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState("in_progress", false, 0, null, "face-session-1", null, null, 0)));
    when(uqudoClient.verifyAndParseIncompleteFaceSession(any(), any()))
        .thenThrow(new IllegalStateException("some unexpected parser failure"));

    service.reportLivenessTerminated(PROFILE_ID, "face-session-1", "USER_CANCEL", "odd-jws");

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).appendWithArtifact(event.capture(), any());
    assertTrue(event.getValue().payloadJson().contains("\"partialJwsReason\":\"parser_failure\""));
    verify(livenessRepository).applyLivenessAttempt(PROFILE_ID);
  }

  @Test
  void terminatedWithoutAPartialJwsNeverTouchesTheParser() {
    when(livenessRepository.lockAndGetLivenessState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new LivenessState("in_progress", false, 0, null, "face-session-1", null, null, 0)));

    service.reportLivenessTerminated(PROFILE_ID, "face-session-1", "USER_CANCEL", null);

    verify(uqudoClient, never()).verifyAndParseIncompleteFaceSession(any(), any());
    verify(auditEventWriter).append(any());
    verify(auditEventWriter, never()).appendWithArtifact(any(), any());
  }
}
