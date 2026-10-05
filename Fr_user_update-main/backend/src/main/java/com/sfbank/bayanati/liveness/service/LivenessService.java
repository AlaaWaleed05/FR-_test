package com.sfbank.bayanati.liveness.service;

import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.liveness.domain.AuditTrailImageUnavailableException;
import com.sfbank.bayanati.liveness.domain.FaceJwsAlreadyAcceptedException;
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
import com.sfbank.bayanati.liveness.domain.UnknownProfileException;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.IssuedAccessToken;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.ParsedFaceResult;
import com.sfbank.bayanati.uqudo.domain.ParsedIncompleteFaceResult;
import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stage 10 — liveness and face matching (docs/journeys/customer.md). Structurally mirrors
 * {@code identityscan.service.IdentityScanService}: external calls (Uqudo) happen outside any
 * transaction that holds {@code app.profile}'s row lock, a rejection discovered before any
 * transaction opens is audited immediately, and a rejection discovered under a re-check lock is
 * audited only after the (otherwise empty) transaction commits.
 *
 * <p><strong>The two failure channels, concretely:</strong> {@link #submitFaceResult} handles a
 * face session that produced a signed JWS — liveness already passed inside the SDK the moment any
 * JWS exists, and {@code match}/{@code matchLevel} answer only the face-match question. {@link
 * #reportLivenessTerminated} handles the other channel: the SDK threw to the app with no JWS at
 * all, whether from an explicit cancel or from exhausting its own internal liveness retries. Both
 * paths share the same attempt budget and the same 24h block (customer.md: "Same budget as
 * liveness, no separate block").
 */
@Service
public class LivenessService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_LIVENESS_TOKEN_ISSUED = "liveness_token_issued";

  /**
   * BL-039 Slice B. AUDIT event types, not wire codes — the customer still sees {@code
   * LIVENESS_BLOCKED} for both. They exist so the two new refusals are distinguishable in the
   * trail: one is a profile that has minted its lifetime allowance, the other a budget the block
   * should already have caught.
   */
  static final String EVENT_LIVENESS_TOKEN_CAP_REACHED = "liveness_token_cap_reached";

  static final String EVENT_LIVENESS_BUDGET_EXHAUSTED = "liveness_budget_exhausted";
  static final String EVENT_FACE_JWS_REJECTED = "face_jws_rejected";
  static final String EVENT_FACE_ARTIFACT_EXPIRED = "face_artifact_expired";
  static final String EVENT_LIVENESS_AUDIT_TRAIL_UNAVAILABLE = "liveness_audit_trail_unavailable";
  static final String EVENT_FACE_MATCH_EVALUATED = "face_match_evaluated";
  static final String EVENT_LIVENESS_ATTEMPT_TERMINATED = "liveness_attempt_terminated";
  static final String EVENT_LIVENESS_REJECTED = "liveness_rejected";

  /** BL-028: the only three {@code partialJwsReason} values -- categories, never free text. */
  static final String PARTIAL_REJECTED_VERIFICATION = "verification_failed";

  static final String PARTIAL_REJECTED_EXPIRED = "expired";
  static final String PARTIAL_REJECTED_PARSER_FAILURE = "parser_failure";

  /**
   * customer.md Stage 10: "Budget exhausted -> temporary block on this stage only ... 24 hours."
   */
  static final Duration LIVENESS_BLOCK_DURATION = Duration.ofHours(24);

  /**
   * How long a created face session and its reference image survive at Uqudo before they are
   * auto-deleted (S8-15, BL-114(a)). 600 s, from two independent sources: the Face API's own
   * documentation ("Session and image auto-deleted after 10 minutes") and S1-02's measured face JWS
   * {@code exp - iat} on the live tenant. It is NOT derived from the access token's ~1800 s life,
   * and must not be — the face session is the earlier deadline by roughly three times, which is
   * exactly what makes {@code FaceSessionIssuance.usableUntil} worth computing server-side.
   */
  static final Duration FACE_SESSION_LIFETIME = Duration.ofSeconds(600);

  private final LivenessRepository livenessRepository;
  private final ProfileRepository profileRepository;
  private final UqudoClient uqudoClient;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final int faceMatchMinimumLevel;

  public LivenessService(
      LivenessRepository livenessRepository,
      ProfileRepository profileRepository,
      UqudoClient uqudoClient,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${fru.identity.face-match-minimum-level:3}") int faceMatchMinimumLevel) {
    this.livenessRepository = livenessRepository;
    this.profileRepository = profileRepository;
    this.uqudoClient = uqudoClient;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.faceMatchMinimumLevel = faceMatchMinimumLevel;
  }

  // ---- token issuance ----

  public FaceSessionIssuance issueFaceSessionToken(UUID profileId) {
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();

    UUID[] cycleIdHolder = new UUID[1];
    byte[][] referenceBytesHolder = new byte[1][];
    boolean[] terminalRejected = {false};
    Instant[] stillBlockedUntil = new Instant[1];
    boolean[] alreadyPassed = {false};
    boolean[] noAcceptedCycle = {false};
    boolean[] referenceImageUnavailable = {false};
    Instant[] capBlockedUntil = new Instant[1];
    Instant[] budgetBlockedUntil = new Instant[1];
    // A separate boolean, not a null check on the instant: for an abandoned or blocked_scan
    // profile liveness_blocked_until is legitimately null, and the handler null-guards it into
    // the generic block copy.
    boolean[] budgetRefused = {false};
    Instant[] budgetRefusedUntil = new Instant[1];
    // BL-121, S8-15. These two used to BE budgetRefused/budgetRefusedUntil. The cap arm below and
    // the budget arm below it both set that one pair, and its single handler audits
    // "liveness_budget_exhausted" -- so every cap refusal against a non-in_progress profile was
    // recorded in the append-only audit trail as a budget exhaustion, which it is not. The scan
    // side never had this: IdentityScanService.issueToken carries a distinct capRefused and audits
    // "scan_token_cap_reached". The reuse reads as accidental rather than chosen -- the comment
    // above describes the pair purely as a null-guard mechanism, with nothing about which refusal
    // it represents.
    boolean[] capRefused = {false};
    Instant[] capRefusedUntil = new Instant[1];

    transactionTemplate.executeWithoutResult(
        status -> {
          LivenessState state =
              livenessRepository
                  .lockAndGetLivenessState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));

          if (state.terminal()) {
            terminalRejected[0] = true;
            return;
          }
          // The LIVE-block refusal keeps its original position, first after terminality: a
          // customer inside an unexpired block is told so, exactly as before Slice B. Only the
          // block-LIFT moves below the cap check, which is all the cap-before-lift rule requires.
          // Splitting the two this way leaves every pre-existing error precedence untouched.
          if ("blocked_liveness".equals(state.status())
              && (state.livenessBlockedUntil() == null
                  || state.livenessBlockedUntil().isAfter(now))) {
            stillBlockedUntil[0] = state.livenessBlockedUntil();
            return;
          }
          // Moved ABOVE the two BL-039 Slice B branches below (found by @agent-reviewer). A
          // profile that has already PASSED face-match must never be pushed into blocked_liveness
          // by a cap it has finished spending: that block would never lift, because the cap
          // branch's already-blocked arm returns the stored deadline before any expiry check runs,
          // while SubmissionService.submit requires in_progress and journeyPointer routes the
          // customer to SUBMIT -- a submit that could never succeed, the same permanent strand
          // reportLivenessTerminated's own guard exists to prevent.
          if (Boolean.TRUE.equals(state.facePassed())) {
            alreadyPassed[0] = true;
            return;
          }
          // BL-039 Slice B: the lifetime mint cap, checked BEFORE the block-expiry fall-through
          // just below, for the reason IdentityScanService.issueToken records at length -- lifting
          // a block only to reapply it writes two profile_status_history rows for a round trip the
          // customer never made.
          //
          // Applying the block is legal ONLY from in_progress. The guard is on that status rather
          // than on "not blocked_liveness" because V0020 defines no transition into
          // blocked_liveness from blocked_scan or abandoned either, and both are reachable here:
          // liveness_attempts is never zeroed by JdbcProfileRepository's reactivate-from-abandoned
          // statement, and a customer can exhaust the scan budget after a liveness block lapses.
          // applyLivenessBlock's insertHistory hard-codes 'in_progress' as its from-status, so
          // calling it from any other status would raise 23514 and turn this 409 into a 500 --
          // BL-043's defect through a new door. Found by @agent-reviewer.
          if (LivenessAttemptBudget.tokenCapReached(state.faceTokensMinted())) {
            if (!"in_progress".equals(state.status())) {
              // BL-121: this is a CAP refusal, and until S8-15 it set the budget pair.
              capRefused[0] = true;
              capRefusedUntil[0] = state.livenessBlockedUntil();
              return;
            }
            Map<String, Object> capPayload = new LinkedHashMap<>();
            capPayload.put("faceTokensMinted", state.faceTokensMinted());
            capPayload.put("cap", LivenessAttemptBudget.LIFETIME_TOKEN_CAP);
            long capEventId =
                auditEventWriter.append(
                    simpleEvent(
                        profileId, requestId, EVENT_LIVENESS_TOKEN_CAP_REACHED, capPayload));
            capBlockedUntil[0] = now.plus(LIVENESS_BLOCK_DURATION);
            livenessRepository.applyLivenessBlock(profileId, capBlockedUntil[0], now, capEventId);
            return;
          }

          // Reaching here as blocked_liveness means the deadline has passed -- the live-block
          // refusal above already returned otherwise. This is the LIFT, and it is below the cap
          // check on purpose. Confirmed again under lock in the second phase below.
          boolean resuming = "blocked_liveness".equals(state.status());
          // BL-039 Slice B: LivenessAttemptBudget.canAttempt was written at S3-13 and called from
          // no production code at all -- its own Javadoc says "a new attempt (token issuance) may
          // start", which is here.
          //
          // NOT merely defence in depth, as this comment first claimed and @agent-reviewer
          // corrected: in_progress with liveness_attempts >= LIMIT is a REAL state, because
          // JdbcProfileRepository.REACTIVATE_FROM_ABANDONED moves abandoned -> in_progress without
          // zeroing the counter. A customer who exhausted Stage 10, was abandoned by the sweep and
          // later re-entered arrives here with a spent budget and an unblocked status. Before this
          // check they were handed a token and got one more attempt; now they are blocked -- and
          // that block is the way OUT rather than a dead end, because resumeFromLivenessBlock
          // zeroes liveness_attempts when they come back. Refusing without writing would strand
          // them permanently instead, since nothing else ever clears that counter. The
          // reactivation gap itself is filed as BL-067.
          //
          // The !resuming guard is load-bearing, not defensive. liveness_attempts is zeroed by
          // resumeFromLivenessBlock, which runs in the SECOND transaction below -- so at this
          // point a customer who has legitimately waited out their block still reads 5 attempts.
          // Without the guard every post-block resume would be refused, permanently. Mirrors
          // IdentityScanService.issueToken's own resuming-aware read of its own counter. Proven by
          // revert-restore: dropping it fails issueFaceSessionTokenResumesAfterBlockExpires.
          if (!resuming && !LivenessAttemptBudget.canAttempt(state.livenessAttempts())) {
            if (!"in_progress".equals(state.status())) {
              budgetRefused[0] = true;
              budgetRefusedUntil[0] = state.livenessBlockedUntil();
              return;
            }
            Map<String, Object> budgetPayload = new LinkedHashMap<>();
            budgetPayload.put("livenessAttempts", state.livenessAttempts());
            budgetPayload.put("limit", LivenessAttemptBudget.LIMIT);
            long budgetEventId =
                auditEventWriter.append(
                    simpleEvent(
                        profileId, requestId, EVENT_LIVENESS_BUDGET_EXHAUSTED, budgetPayload));
            budgetBlockedUntil[0] = now.plus(LIVENESS_BLOCK_DURATION);
            livenessRepository.applyLivenessBlock(
                profileId, budgetBlockedUntil[0], now, budgetEventId);
            return;
          }

          if (state.acceptedCycleId() == null) {
            noAcceptedCycle[0] = true;
            return;
          }

          Optional<ReferenceImage> reference =
              livenessRepository.currentAcceptedCycleReferenceImage(profileId);
          if (reference.isEmpty()) {
            // Should not happen: acceptedCycleId is non-null (active + accepted), and accepting a
            // scan and inserting its portrait_uqudo artifact_ref row happen in the same
            // transaction (IdentityScanService.submitScan) -- so a row should always exist here.
            // A genuine invariant violation, not a normal rejection -- surfaces as 500.
            throw new IllegalStateException(
                "profile " + profileId + " has an accepted identity cycle with no reference image");
          }
          if (reference.get().imageBytes() == null) {
            // Unlike an entirely missing row (above), a NULL body on an existing row is a real,
            // expected outcome since S5-06: app.purge_abandoned_artifacts() nulls it for a
            // profile abandoned over 90 days, and abandoned -> in_progress reactivation
            // (ContactChannelsService, Stage 1b re-entry) can then bring the customer straight
            // back to this same accepted cycle with nothing left to run liveness against. A
            // defined "go rescan" outcome, not a 500 -- found by @agent-reviewer.
            referenceImageUnavailable[0] = true;
            return;
          }
          cycleIdHolder[0] = reference.get().cycleId();
          referenceBytesHolder[0] = reference.get().imageBytes();
        });

    if (terminalRejected[0]) {
      auditRejection(profileId, requestId, "profile_already_complete", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if (stillBlockedUntil[0] != null) {
      auditRejection(
          profileId, requestId, "liveness_temporarily_blocked", stillBlockedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(stillBlockedUntil[0]);
    }
    // BL-039 Slice B. Both reuse the EXISTING block response -- there is no second document to
    // switch to at Stage 10, so the 24-hour block IS the answer, and the app already renders it.
    if (capBlockedUntil[0] != null) {
      auditRejection(
          profileId, requestId, "liveness_token_cap_reached", capBlockedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(
          capBlockedUntil[0], LivenessBlockReason.LIFETIME_CAP);
    }
    // BL-121: its own arm since S8-15, so a cap refusal is audited as one. It sits beside the cap
    // arm above rather than beside the budget arm below, because that is what it is.
    if (capRefused[0]) {
      auditRejection(
          profileId,
          requestId,
          "liveness_token_cap_reached",
          capRefusedUntil[0] == null ? null : capRefusedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(
          capRefusedUntil[0], LivenessBlockReason.LIFETIME_CAP);
    }
    if (budgetBlockedUntil[0] != null) {
      auditRejection(
          profileId, requestId, "liveness_budget_exhausted", budgetBlockedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(
          budgetBlockedUntil[0], LivenessBlockReason.BUDGET_EXHAUSTED);
    }
    if (budgetRefused[0]) {
      auditRejection(
          profileId,
          requestId,
          "liveness_budget_exhausted",
          budgetRefusedUntil[0] == null ? null : budgetRefusedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(
          budgetRefusedUntil[0], LivenessBlockReason.BUDGET_EXHAUSTED);
    }
    if (alreadyPassed[0]) {
      auditRejection(profileId, requestId, "face_match_already_passed", null);
      throw new FaceMatchAlreadyPassedException(
          "profile " + profileId + " has already passed face-match for its active identity cycle");
    }
    if (noAcceptedCycle[0]) {
      auditRejection(profileId, requestId, "no_accepted_identity_cycle", null);
      throw NoAcceptedIdentityCycleException.noAcceptedCycle(profileId);
    }
    if (referenceImageUnavailable[0]) {
      auditRejection(profileId, requestId, "reference_image_unavailable", null);
      throw NoAcceptedIdentityCycleException.referenceImageUnavailable(profileId);
    }

    // Outside the already-committed transaction -- an unbounded external call must not hold the
    // app.profile row lock (the same discipline IdentityScanService's issueToken was reviewed
    // into at S3-12).
    // BL-114(a): the face session starts dying HERE, not where usableUntil is computed below.
    // Found by @agent-reviewer: reading the clock after this call and the token call would hand the
    // app a deadline later than the session's real death by however long those two took -- normally
    // a second or two, but bounded by nothing when Uqudo is slow.
    Instant faceSessionCreatedAt = clock.instant();
    String faceSessionId = uqudoClient.createFaceSession(referenceBytesHolder[0]);

    boolean[] becameIneligible = {false};
    Instant[] becameBlockedUntil = new Instant[1];
    // Carried by a boolean rather than by its instant: the caller that actually crossed the cap
    // may have taken the refuse-without-writing arm, leaving liveness_blocked_until null, and a
    // null-checked instant would then fall through to a SUCCESSFUL issuance -- the precise
    // outcome this re-check exists to prevent.
    boolean[] capRefusedLate = {false};
    Instant[] capRefusedLateUntil = new Instant[1];
    transactionTemplate.executeWithoutResult(
        status -> {
          LivenessState state =
              livenessRepository
                  .lockAndGetLivenessState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (state.terminal() || Boolean.TRUE.equals(state.facePassed())) {
            becameIneligible[0] = true;
            return;
          }
          // BL-039 Slice B, re-checked here and not only in phase 1 (found by @agent-reviewer).
          // Unlike the scan side -- where the cap check, the mint and the pending-session write
          // all sit in ONE transaction holding FOR UPDATE OF p, so concurrent callers serialise --
          // this path's first transaction COMMITS and releases the lock before createFaceSession.
          // Without this re-check, N concurrent requests at faceTokensMinted = cap - 1 all pass
          // phase 1 on the same read and all reach the increment below, minting N tokens where one
          // was allowed. The overshoot would be bounded only by request concurrency, on an
          // endpoint that is unauthenticated by design (R-051) and whose whole purpose here is a
          // hard cost cap. Refuse without minting; no block is applied, because whichever caller
          // crossed the cap first has already applied it.
          if (LivenessAttemptBudget.tokenCapReached(state.faceTokensMinted())) {
            capRefusedLate[0] = true;
            capRefusedLateUntil[0] = state.livenessBlockedUntil();
            return;
          }
          // Re-read fresh under this lock, not trusted from before the external
          // createFaceSession call above: a concurrent 5th failed attempt could have applied a
          // brand-new 24h block in that window, and resuming unconditionally on "status is
          // blocked_liveness" would silently clear a block this very token request never
          // actually waited out (found live: RESUME_FROM_LIVENESS_BLOCK has no WHERE
          // liveness_blocked_until <= now guard of its own).
          if ("blocked_liveness".equals(state.status())
              && (state.livenessBlockedUntil() == null
                  || state.livenessBlockedUntil().isAfter(now))) {
            becameBlockedUntil[0] = state.livenessBlockedUntil();
            return;
          }

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("faceSessionId", faceSessionId);
          long eventId =
              auditEventWriter.append(
                  simpleEvent(profileId, requestId, EVENT_LIVENESS_TOKEN_ISSUED, payload));

          // Persisted so submitFaceResult validates the returned JWS's jti against what the
          // backend actually issued, never against what the request merely claims.
          livenessRepository.recordPendingFaceSession(profileId, faceSessionId);
          // BL-039 Slice B. In the transaction that actually commits the mint, so every refusal in
          // the first phase counts nothing. One increment, though this path spent two Uqudo
          // operations: what is capped is the token.
          livenessRepository.incrementFaceTokensMinted(profileId);

          if ("blocked_liveness".equals(state.status())) {
            livenessRepository.resumeFromLivenessBlock(profileId, now, eventId);
          } else {
            profileRepository.touchLastActivity(profileId, now);
          }
        });

    if (becameIneligible[0]) {
      auditRejection(profileId, requestId, "profile_became_complete_during_processing", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " completed while this token request was in flight");
    }
    if (becameBlockedUntil[0] != null) {
      auditRejection(
          profileId, requestId, "liveness_temporarily_blocked", becameBlockedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(becameBlockedUntil[0]);
    }
    if (capRefusedLate[0]) {
      auditRejection(
          profileId,
          requestId,
          "liveness_token_cap_reached",
          capRefusedLateUntil[0] == null ? null : capRefusedLateUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(
          capRefusedLateUntil[0], LivenessBlockReason.LIFETIME_CAP);
    }

    IssuedAccessToken accessToken = uqudoClient.issueAccessToken();
    // BL-114(a). The EARLIER of the two deadlines, not the token's. Uqudo deletes the face session
    // and its reference image FACE_SESSION_LIFETIME after it was CREATED -- which is why the base
    // is faceSessionCreatedAt, captured before createFaceSession, and not the clock here: reading
    // the clock at this point would overstate the deadline by however long createFaceSession and
    // the token mint took, which nothing bounds (found by @agent-reviewer, S8-15). The session dies
    // in roughly a third of the token's life, so the token expiry alone would overstate the window
    // by about 20 minutes. Computed here because this is the only place both deadlines are known.
    Instant faceSessionDeadline = faceSessionCreatedAt.plus(FACE_SESSION_LIFETIME);
    Instant usableUntil =
        faceSessionDeadline.isBefore(accessToken.expiresAt())
            ? faceSessionDeadline
            : accessToken.expiresAt();
    return new FaceSessionIssuance(accessToken.value(), faceSessionId, usableUntil);
  }

  // ---- face-session result (a JWS was returned -- liveness already passed inside the SDK) ----

  public FaceResultOutcome submitFaceResult(
      UUID profileId, String expectedFaceSessionId, String jws) {
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();

    LivenessState preCheck =
        livenessRepository
            .lockAndGetLivenessState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (preCheck.terminal()) {
      auditRejection(profileId, requestId, "profile_already_complete", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if ("blocked_liveness".equals(preCheck.status())) {
      auditRejection(
          profileId,
          requestId,
          "liveness_temporarily_blocked",
          String.valueOf(preCheck.livenessBlockedUntil()));
      throw new LivenessTemporarilyBlockedException(preCheck.livenessBlockedUntil());
    }
    if (Boolean.TRUE.equals(preCheck.facePassed())) {
      auditRejection(profileId, requestId, "face_match_already_passed", null);
      throw new FaceMatchAlreadyPassedException(
          "profile " + profileId + " has already passed face-match for its active identity cycle");
    }
    if (preCheck.acceptedCycleId() == null) {
      auditRejection(profileId, requestId, "no_accepted_identity_cycle", null);
      throw NoAcceptedIdentityCycleException.noAcceptedCycle(profileId);
    }
    if (!expectedFaceSessionId.equals(preCheck.pendingFaceSessionId())) {
      auditRejection(profileId, requestId, "invalid_face_session", null);
      throw new InvalidFaceSessionException(
          "faceSessionId does not match what issueFaceSessionToken issued for this profile");
    }

    ParsedFaceResult parsed;
    try {
      parsed = uqudoClient.verifyAndParseFaceSession(jws, preCheck.pendingFaceSessionId());
    } catch (ArtifactExpiredException expired) {
      // NOT counted against the retry budget (uqudo-sdk.md's design rule, R-012/R-021) -- a
      // stale clock, not a liveness/face-match quality problem. Audited without
      // recordFailedAttempt's counter increment, mirroring IdentityScanService.submitScan.
      auditEventWriter.append(
          simpleEvent(
              profileId,
              requestId,
              EVENT_FACE_ARTIFACT_EXPIRED,
              Map.of("faceSessionId", expectedFaceSessionId)));
      throw expired;
    } catch (JwsVerificationException rejected) {
      recordFailedAttempt(
          profileId,
          requestId,
          EVENT_FACE_JWS_REJECTED,
          Map.of("faceSessionId", expectedFaceSessionId));
      throw rejected;
    }

    byte[] auditTrailBytes = null;
    if (parsed.auditTrailImageId() != null) {
      try {
        auditTrailBytes =
            uqudoClient.downloadImage(parsed.auditTrailImageId(), parsed.auditTrailChecksum());
      } catch (ImageUnavailableException imageGone) {
        // match/matchLevel are recorded here even though the audit-trail image is gone: a
        // verified JWS with match=false is customer.md's strongest fraud signal, and R-016
        // requires it survive regardless of what happens to the audit-trail image afterward --
        // found live: the first cut of this catch block recorded only jti and the failure
        // reason, silently discarding the one signal this whole exception hierarchy exists to
        // preserve.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jti", parsed.jti());
        payload.put("faceSessionId", parsed.sessionId());
        payload.put("match", parsed.match());
        payload.put("matchLevel", parsed.matchLevel());
        payload.put("faceError", parsed.faceError());
        payload.put("thresholdApplied", faceMatchMinimumLevel);
        payload.put("reason", imageGone.getMessage());
        // Deliberately NOT counted against the retry budget (same R-012/R-021-style ordering
        // discipline as stage 8): a system-timing fact, not a liveness/face-match quality problem.
        auditEventWriter.append(
            simpleEvent(profileId, requestId, EVENT_LIVENESS_AUDIT_TRAIL_UNAVAILABLE, payload));
        throw new AuditTrailImageUnavailableException(
            "face match verified but the audit-trail image is no longer available: "
                + imageGone.getMessage());
      } catch (ImageIntegrityException corrupted) {
        recordFailedAttempt(
            profileId,
            requestId,
            EVENT_FACE_JWS_REJECTED,
            Map.of("faceSessionId", expectedFaceSessionId));
        throw corrupted;
      }
    }

    boolean passed = parsed.match() && parsed.matchLevel() >= faceMatchMinimumLevel;
    byte[] finalAuditTrailBytes = auditTrailBytes;

    boolean[] becameIneligible = {false};
    Instant[] blockedUntilHolder = new Instant[1];
    Instant[] alreadyBlockedUntil = new Instant[1];

    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            LivenessState state =
                livenessRepository
                    .lockAndGetLivenessState(profileId)
                    .orElseThrow(
                        () -> new UnknownProfileException("no profile with id " + profileId));
            if (state.terminal()) {
              becameIneligible[0] = true;
              return;
            }
            // Found live at S3-13's own second review pass, the same defect class as
            // issueFaceSessionToken's phase-2 fix: verifyAndParseFaceSession and the image
            // download above both run unlocked, so a concurrent reportLivenessTerminated could
            // exhaust the budget and apply a fresh 24h block in that window. Proceeding anyway
            // would either re-fire applyLivenessBlock (V0020's guard is a no-op on
            // status==status, but insertHistory would still write a false
            // in_progress -> blocked_liveness row and silently extend the deadline) or, on a
            // pass, strand the profile blocked_liveness with passed=true -- the same dead end
            // reportLivenessTerminated's own new facePassed guard exists to prevent.
            if ("blocked_liveness".equals(state.status())) {
              alreadyBlockedUntil[0] = state.livenessBlockedUntil();
              return;
            }

            UUID cycleId = state.acceptedCycleId();
            // customer.md Stage 10: "a returned JWS is retained and the upload retried rather
            // than repeating the check" -- a retried upload of the exact same JWS (a dropped
            // response, not a fresh attempt) must not spend a second draw from the budget. Found
            // live at S3-13's own second review pass: FaceJwsAlreadyAcceptedException only fires
            // across DIFFERENT cycles (uqudo_jti's UNIQUE constraint), since a retry against the
            // SAME cycle upserts silently.
            boolean isRetryOfSameJti =
                livenessRepository
                    .currentFaceResultJti(cycleId)
                    .map(existingJti -> existingJti.equals(parsed.jti()))
                    .orElse(false);
            livenessRepository.upsertFaceResult(
                cycleId,
                parsed.jti(),
                expectedFaceSessionId,
                parsed.match(),
                parsed.matchLevel(),
                faceMatchMinimumLevel,
                now);
            if (finalAuditTrailBytes != null) {
              livenessRepository.upsertFaceAuditTrailArtifact(
                  cycleId,
                  parsed.auditTrailImageId(),
                  parsed.auditTrailChecksum(),
                  opaqueStorageKey(),
                  "image/jpeg",
                  finalAuditTrailBytes.length,
                  sha256(finalAuditTrailBytes),
                  finalAuditTrailBytes,
                  now);
            }

            // The raw JWS is a hash-chained AUDIT ARTIFACT, never inlined into payload_json --
            // same discipline stage 8's scan_accepted event uses. Recorded here whether the match
            // passed or failed: customer.md requires face_match_evaluated for BOTH, since a
            // failure is the strongest fraud signal the journey produces.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("match", parsed.match());
            payload.put("matchLevel", parsed.matchLevel());
            // Undocumented vendor field (null on every real result seen so far) -- surfaced into
            // the audit record so an operator or a later investigation can see it, never read
            // for any decision (R-034 quarantine).
            payload.put("faceError", parsed.faceError());
            payload.put("thresholdApplied", faceMatchMinimumLevel);
            payload.put("jti", parsed.jti());
            payload.put("faceSessionId", parsed.sessionId());
            long eventId =
                auditEventWriter.appendWithArtifact(
                    new AuditEvent(
                        CHAIN_KIND,
                        profileId.toString(),
                        EVENT_FACE_MATCH_EVALUATED,
                        "customer",
                        null,
                        profileId,
                        profileId,
                        requestId,
                        CanonicalJson.object(payload)),
                    new AuditArtifact(
                        "uqudo_face_jws",
                        "application/jose",
                        jws.getBytes(StandardCharsets.UTF_8)));

            if (passed) {
              // No longer clears the reference image on pass (V0041/V0042 dropped, S5-06):
              // AD-004 retains the portrait permanently in app.artifact_ref as sanctioned
              // evidence, the same as every other artifact kind -- there is nothing left here
              // to clear. See RISKS.md R-047.
              profileRepository.touchLastActivity(profileId, now);
            } else if (isRetryOfSameJti) {
              profileRepository.touchLastActivity(profileId, now);
            } else {
              int newTotal = state.livenessAttempts() + 1;
              boolean blockTriggered = LivenessAttemptBudget.blockTriggered(newTotal);
              livenessRepository.applyLivenessAttempt(profileId);
              if (blockTriggered) {
                blockedUntilHolder[0] = now.plus(LIVENESS_BLOCK_DURATION);
                livenessRepository.applyLivenessBlock(
                    profileId, blockedUntilHolder[0], now, eventId);
              } else {
                profileRepository.touchLastActivity(profileId, now);
              }
            }
          });
    } catch (DuplicateKeyException alreadyAccepted) {
      // app.face_result.uqudo_jti's UNIQUE constraint (V0008) -- reachable only across two
      // different identity cycles claiming the same jti, since a retry against the SAME cycle
      // upserts. Mirrors JdbcAuditEventWriter/IdentityScanService's own narrow-catch discipline:
      // DuplicateKeyException specifically, not the broader DataIntegrityViolationException, so an
      // unrelated constraint failure is not mis-reported as "already accepted".
      throw new FaceJwsAlreadyAcceptedException(
          "this JWS (jti=" + parsed.jti() + ") has already been accepted");
    }

    if (becameIneligible[0]) {
      auditRejection(profileId, requestId, "profile_became_complete_during_processing", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " completed while this result submission was in flight");
    }
    if (alreadyBlockedUntil[0] != null) {
      auditRejection(
          profileId, requestId, "liveness_temporarily_blocked", alreadyBlockedUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(alreadyBlockedUntil[0]);
    }

    if (finalAuditTrailBytes != null) {
      // Strictly after the transaction above has committed -- see UqudoClient.purgeSession's
      // own Javadoc for why this call happens at all.
      //
      // By data.sessionId, NEVER by jti. Observed live at S1-02 (2026-09-03): DELETE
      // /api/v1/info/{id} returns 204 for any UUID; with the face JWS's jti it returned 204 and
      // the audit-trail image was still downloadable afterwards; with the Face Session id it
      // returned 204 and the image was gone at once. The S3-13 version of this line purged by
      // jti -- a silent no-op that left every customer's face audit-trail image on FIB's tenant
      // (R-001) until Uqudo's own 600 s expiry. Enrolments are unaffected (jti == session id).
      uqudoClient.purgeSession(parsed.sessionId());
    }

    return new FaceResultOutcome(passed, parsed.matchLevel(), blockedUntilHolder[0]);
  }

  // ---- no JWS was produced: cancel, or the SDK exhausted its own internal liveness retries ----

  /**
   * @param partialJws BL-028: the signed partial artifact a terminated session hands back when
   *     {@code returnDataForIncompleteSession()} is enabled, or {@code null} when the SDK returned
   *     none. Verified and stored as an audit artifact on the {@code liveness_attempt_terminated}
   *     event; whatever {@code face.match}/{@code matchLevel}/{@code error} it carries goes into
   *     the payload. A partial JWS that fails verification is still stored as the artifact (the
   *     same rule {@code IdentityScanService} applies to a rejected scan JWS), recorded as rejected
   *     with a fixed reason category, and the termination is still counted exactly as without one —
   *     the attempt itself is the primary fact; a bad optional artifact must not lose it, nor may
   *     its failure text reach the audit payload. Nothing here writes {@code app.face_result},
   *     downloads an image, or purges: the Face Session self-deletes at 600 s and the partial
   *     artifact is evidence, not an outcome.
   */
  public void reportLivenessTerminated(
      UUID profileId, String faceSessionId, String sdkErrorCode, String partialJws) {
    UUID requestId = UUID.randomUUID();
    // A cheap early rejection for the common case -- the real guard against a terminal profile's
    // retry-budget columns being mutated is recordFailedAttempt's own re-check under lock,
    // mirroring IdentityScanService.cancelScan.
    LivenessState state =
        livenessRepository
            .lockAndGetLivenessState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (state.terminal()) {
      auditRejection(profileId, requestId, "profile_already_complete", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    // Validated against what issueFaceSessionToken actually recorded, never trusted from the
    // request alone -- also the guard that keeps this endpoint unreachable before any token has
    // ever been issued (pending_face_session_id is null until then), closing the pre-stage-10
    // path recordFailedAttempt's own awaiting_registry check exists to guard.
    if (!faceSessionId.equals(state.pendingFaceSessionId())) {
      auditRejection(profileId, requestId, "invalid_face_session", null);
      throw new InvalidFaceSessionException(
          "faceSessionId does not match what issueFaceSessionToken issued for this profile");
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("faceSessionId", faceSessionId);
    payload.put("sdkErrorCode", sdkErrorCode);

    AuditArtifact partialArtifact = null;
    if (partialJws != null) {
      // The raw partial JWS is hash-chained whether or not it verifies -- the same rule
      // IdentityScanService.recordFailedAttempt applies to a REJECTED scan JWS. For BL-028/R-016
      // the artifact IS the evidence the feature exists to capture; if our own binding or
      // verification is what is wrong, discarding the only copy would be the worst outcome
      // (found under review).
      partialArtifact =
          new AuditArtifact(
              "uqudo_face_jws", "application/jose", partialJws.getBytes(StandardCharsets.UTF_8));
      // Verified outside any transaction, like every other UqudoClient call in this class --
      // bound to the session id issueFaceSessionToken actually recorded, never the request's.
      try {
        ParsedIncompleteFaceResult partial =
            uqudoClient.verifyAndParseIncompleteFaceSession(
                partialJws, state.pendingFaceSessionId());
        payload.put("partialJwsStatus", "verified");
        payload.put("partialJti", partial.jti());
        // Each may be null: whether Uqudo's partial artifact carries the match detail at all is
        // [UNVERIFIED] (BL-028) -- an audit record without it is the accepted worst case.
        payload.put("partialMatch", partial.match());
        payload.put("partialMatchLevel", partial.matchLevel());
        payload.put("partialFaceError", partial.faceError());
      } catch (ArtifactExpiredException expired) {
        payload.put("partialJwsStatus", "rejected");
        payload.put("partialJwsReason", PARTIAL_REJECTED_EXPIRED);
      } catch (JwsVerificationException rejected) {
        payload.put("partialJwsStatus", "rejected");
        payload.put("partialJwsReason", PARTIAL_REJECTED_VERIFICATION);
      } catch (RuntimeException unexpected) {
        // The termination is the primary fact and must be recorded whatever the optional
        // artifact does to the parser.
        payload.put("partialJwsStatus", "rejected");
        payload.put("partialJwsReason", PARTIAL_REJECTED_PARSER_FAILURE);
      }
      // Deliberately a fixed category, never rejected.getMessage(): the parser wraps library
      // text that can quote client-controlled header bytes (an `alg` with a control character
      // survives LivenessController.clean, since a compact JWS is pure base64url), and
      // CanonicalJson rejects control characters INSIDE recordFailedAttempt's transaction --
      // which would roll the whole termination record back (found under review).
    }
    recordFailedAttempt(
        profileId, requestId, EVENT_LIVENESS_ATTEMPT_TERMINATED, payload, partialArtifact);
  }

  // ---- shared helpers ----

  /**
   * Shared by the JWS-rejection, audit-trail-integrity-failure and SDK-termination paths — every
   * case customer.md counts against the stage 10 retry budget with no {@code face_result} row to
   * write. Re-locks {@code app.profile} and re-checks terminality/blocked status under that lock,
   * mirroring {@code IdentityScanService#recordFailedAttempt}.
   */
  private void recordFailedAttempt(
      UUID profileId, UUID requestId, String eventType, Map<String, Object> extraPayload) {
    recordFailedAttempt(profileId, requestId, eventType, extraPayload, null);
  }

  /**
   * @param artifact the raw JWS to hash-chain alongside the event, or {@code null} when none exists
   *     — the same shape {@code IdentityScanService#recordFailedAttempt} uses for a rejected scan
   *     JWS; here it carries BL-028's partial face-session artifact
   */
  private void recordFailedAttempt(
      UUID profileId,
      UUID requestId,
      String eventType,
      Map<String, Object> extraPayload,
      AuditArtifact artifact) {
    Instant now = clock.instant();
    boolean[] becameIneligible = {false};
    Instant[] activeBlockUntil = new Instant[1];
    boolean[] registryReviewPendingNow = {false};
    boolean[] alreadyPassedNow = {false};

    transactionTemplate.executeWithoutResult(
        status -> {
          LivenessState state =
              livenessRepository
                  .lockAndGetLivenessState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (state.terminal()) {
            becameIneligible[0] = true;
            return;
          }
          // A countable attempt only ever applies from in_progress. awaiting_registry is
          // otherwise reachable here (reportLivenessTerminated needs no accepted cycle and no
          // issued token), which would mutate the retry-budget columns or fire an illegal
          // awaiting_registry -> blocked_liveness transition V0020 does not define -- the same
          // defect class @agent-reviewer found in this method's stage-8 counterpart at S3-12.
          if ("blocked_liveness".equals(state.status())) {
            activeBlockUntil[0] = state.livenessBlockedUntil();
            return;
          }
          if ("awaiting_registry".equals(state.status())) {
            registryReviewPendingNow[0] = true;
            return;
          }
          // Found live at S3-13's own second review pass: pending_face_session_id is never
          // cleared (V0044), so a client holding a session id that already PASSED could call
          // reportLivenessTerminated (or replay a failed submitFaceResult) five times and drive
          // an already-complete stage 10 into blocked_liveness, from which
          // issueFaceSessionToken's own facePassed guard then refuses to resume it forever --
          // permanently stranding a profile stage 10 had already finished.
          if (Boolean.TRUE.equals(state.facePassed())) {
            alreadyPassedNow[0] = true;
            return;
          }

          int newTotal = state.livenessAttempts() + 1;
          boolean blockTriggered = LivenessAttemptBudget.blockTriggered(newTotal);

          Map<String, Object> payload = new LinkedHashMap<>(extraPayload);
          payload.put("attemptsAfter", newTotal);
          payload.put("blockTriggered", blockTriggered);
          AuditEvent event = simpleEvent(profileId, requestId, eventType, payload);
          long eventId =
              artifact == null
                  ? auditEventWriter.append(event)
                  : auditEventWriter.appendWithArtifact(event, artifact);

          livenessRepository.applyLivenessAttempt(profileId);
          if (blockTriggered) {
            livenessRepository.applyLivenessBlock(
                profileId, now.plus(LIVENESS_BLOCK_DURATION), now, eventId);
          } else {
            profileRepository.touchLastActivity(profileId, now);
          }
        });

    if (becameIneligible[0]) {
      auditRejection(profileId, requestId, "profile_became_complete_during_processing", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " completed while this attempt was in flight");
    }
    if (activeBlockUntil[0] != null) {
      auditRejection(
          profileId, requestId, "liveness_temporarily_blocked", activeBlockUntil[0].toString());
      throw new LivenessTemporarilyBlockedException(activeBlockUntil[0]);
    }
    if (registryReviewPendingNow[0]) {
      auditRejection(profileId, requestId, "registry_review_pending", null);
      throw new RegistryReviewPendingException(
          "profile " + profileId + " is awaiting a Civil Registry retry");
    }
    if (alreadyPassedNow[0]) {
      auditRejection(profileId, requestId, "face_match_already_passed", null);
      throw new FaceMatchAlreadyPassedException(
          "profile " + profileId + " has already passed face-match for its active identity cycle");
    }
  }

  private void auditRejection(UUID profileId, UUID requestId, String reason, String detail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    payload.put("detail", detail);
    auditEventWriter.append(simpleEvent(profileId, requestId, EVENT_LIVENESS_REJECTED, payload));
  }

  private static AuditEvent simpleEvent(
      UUID profileId, UUID requestId, String eventType, Map<String, Object> payload) {
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        eventType,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private static String opaqueStorageKey() {
    // AD-004 is closed (S5-06): bytes live in this row's own body column. This id is DEAD, not
    // reserved: R-046 closed at BL-075 (2026-09-13) and its addressing scheme needs no stored key
    // -- the operator image endpoint addresses an artifact by its own artifact_ref_id. Still
    // written because the column is NOT NULL; deliberately not dropped, which is its own migration.
    return "artifact:" + UUID.randomUUID();
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
