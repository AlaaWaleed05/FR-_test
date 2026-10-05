package com.sfbank.bayanati.identityscan.service;

import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.domain.RegistryExchange;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
import com.sfbank.bayanati.identityscan.domain.AcceptedScanSnapshot;
import com.sfbank.bayanati.identityscan.domain.ActiveRegistryContext;
import com.sfbank.bayanati.identityscan.domain.DocumentTypes;
import com.sfbank.bayanati.identityscan.domain.IdentityScanRejectedException;
import com.sfbank.bayanati.identityscan.domain.IdentityScanRepository;
import com.sfbank.bayanati.identityscan.domain.ImagesUnavailableForAcceptanceException;
import com.sfbank.bayanati.identityscan.domain.InvalidScanSessionException;
import com.sfbank.bayanati.identityscan.domain.JwsAlreadyAcceptedException;
import com.sfbank.bayanati.identityscan.domain.NoActiveRegistryReviewException;
import com.sfbank.bayanati.identityscan.domain.ProfileNotEditableException;
import com.sfbank.bayanati.identityscan.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.identityscan.domain.ScanArtifact;
import com.sfbank.bayanati.identityscan.domain.ScanAttemptBudget;
import com.sfbank.bayanati.identityscan.domain.ScanBlockReason;
import com.sfbank.bayanati.identityscan.domain.ScanImageKinds;
import com.sfbank.bayanati.identityscan.domain.ScanState;
import com.sfbank.bayanati.identityscan.domain.ScanTemporarilyBlockedException;
import com.sfbank.bayanati.identityscan.domain.ScanTypeExhaustedException;
import com.sfbank.bayanati.identityscan.domain.UnknownProfileException;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.IssuedAccessToken;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import com.sfbank.bayanati.uqudo.domain.ParsedImage;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stages 8 and 9 — the Uqudo scan chain and the Civil Registry review
 * (docs/journeys/customer.md).
 *
 * <p><strong>The ordering rule (R-012/R-021), concretely:</strong> {@link #submitScan} calls {@link
 * UqudoClient#verifyAndParse} and then {@link UqudoClient#downloadImage} for every image
 * <em>before</em> opening the transaction that persists acceptance. A verified JWS whose images are
 * gone throws {@link ImagesUnavailableForAcceptanceException} with nothing written to {@code
 * app.identity_cycle}/{@code app.scan_result}/{@code app.artifact_ref} — the accept decision comes
 * strictly after the download, never after verification alone.
 *
 * <p><strong>The Civil Registry lookup happens inside the same external-calls-first
 * ordering</strong> as the image downloads: customer.md Stage 8's own numbered chain queries the
 * registry (step 5) before writing the profile (step 6), so {@link #submitScan} calls {@link
 * CivilRegistryClient#lookup} after the images download and before opening the one transaction that
 * persists the cycle, the scan result, the artifact references, and the registry result together.
 *
 * <p><strong>Uqudo's session purge happens strictly after that transaction commits</strong> —
 * purging before the images are durably stored would destroy the only copy of the evidence if the
 * transaction then rolled back.
 *
 * <p>Every rejection follows the same audit-then-throw shape {@code ContactChannelsService}/{@code
 * DataEntryService} already established: a rejection discovered before any transaction opens is
 * audited immediately (nothing to roll back yet); a rejection discovered while re-checking under a
 * lock is audited only <em>after</em> the (otherwise empty) transaction commits, never inside the
 * callback that would then throw.
 */
@Service
public class IdentityScanService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_SCAN_TOKEN_ISSUED = "scan_token_issued";

  /**
   * BL-039 Slice B. An AUDIT event type, not a wire code — the customer still sees {@code
   * SCAN_BLOCKED}. It exists so the cap is monitorable: if real customers start reaching it, this
   * is the event that says so, and the DECISION's recorded trade-off is that the NUMBER gets
   * revisited rather than the design.
   */
  static final String EVENT_SCAN_TOKEN_CAP_REACHED = "scan_token_cap_reached";

  static final String EVENT_SCAN_CANCELLED = "scan_cancelled";
  static final String EVENT_SCAN_JWS_REJECTED = "scan_jws_rejected";
  static final String EVENT_SCAN_IMAGES_UNAVAILABLE = "scan_images_unavailable";
  static final String EVENT_SCAN_IMAGE_INTEGRITY_FAILED = "scan_image_integrity_failed";
  static final String EVENT_SCAN_ARTIFACT_EXPIRED = "scan_artifact_expired";
  static final String EVENT_SCAN_ACCEPTED = "scan_accepted";
  static final String EVENT_REGISTRY_LOOKUP_COMPLETED = "registry_lookup_completed";

  /**
   * The event that carries the raw {@code civil_registry_request} artifact (AD-002b build, the
   * S3-02 two-events-one-chain pattern). {@code audit.audit_event.artifact_id} is a single FK, so
   * the request and response bytes need one event each; this one is written immediately before
   * {@link #EVENT_REGISTRY_LOOKUP_COMPLETED}, on the same chain and under the same {@code
   * requestId}, and only when a real HTTP exchange happened — the stub has no request bytes. Its
   * payload carries the byte count only: the request body IS the national number, which must never
   * enter the permanently hash-chained {@code payload_json} (the artifact body, by contrast, is
   * purgeable).
   */
  static final String EVENT_REGISTRY_LOOKUP_REQUESTED = "registry_lookup_requested";

  static final String REGISTRY_REQUEST_ARTIFACT_KIND = "civil_registry_request";
  static final String REGISTRY_RESPONSE_ARTIFACT_KIND = "civil_registry_response";
  static final String EVENT_REGISTRY_REVIEW_ACCEPTED = "registry_review_accepted";
  static final String EVENT_REGISTRY_REVIEW_WRONG_NUMBER = "registry_review_wrong_number";
  static final String EVENT_REGISTRY_REVIEW_WRONG_DETAILS = "registry_review_wrong_details";
  static final String EVENT_IDENTITY_SCAN_REJECTED = "identity_scan_rejected";

  /** customer.md Stage 8: "Either budget exhausted -> temporary block ... 24 hours." */
  static final Duration SCAN_BLOCK_DURATION = Duration.ofHours(24);

  private static final String REGISTRY_STATE_OK = "ok";
  private static final String REGISTRY_STATE_NOT_FOUND = "not_found";
  private static final String REGISTRY_STATE_UNREACHABLE = "unreachable";

  private final IdentityScanRepository identityScanRepository;
  private final ProfileRepository profileRepository;
  private final UqudoClient uqudoClient;
  private final CivilRegistryClient civilRegistryClient;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public IdentityScanService(
      IdentityScanRepository identityScanRepository,
      ProfileRepository profileRepository,
      UqudoClient uqudoClient,
      CivilRegistryClient civilRegistryClient,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.identityScanRepository = identityScanRepository;
    this.profileRepository = profileRepository;
    this.uqudoClient = uqudoClient;
    this.civilRegistryClient = civilRegistryClient;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  // ---- Stage 8: token issuance ----

  public TokenIssuance issueToken(UUID profileId, String appDocumentType) {
    if (!DocumentTypes.isValid(appDocumentType)) {
      throw new IdentityScanRejectedException("documentType must be 'passport' or 'national_id'");
    }
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();

    String[] pendingSessionId = new String[1];
    String[] pendingNonce = new String[1];
    boolean[] terminalRejected = {false};
    boolean[] registryReviewPending = {false};
    Instant[] stillBlockedUntil = new Instant[1];
    Instant[] capBlockedUntil = new Instant[1];
    boolean[] capRefused = {false};
    Instant[] capRefusedUntil = new Instant[1];
    boolean[] typeExhausted = {false};

    transactionTemplate.executeWithoutResult(
        status -> {
          ScanState state =
              identityScanRepository
                  .lockAndGetScanState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));

          if (state.terminal()) {
            terminalRejected[0] = true;
            return;
          }
          if ("awaiting_registry".equals(state.status())) {
            // A new scan attempt must not start while a prior one's registry lookup is still
            // pending -- among other things, it would let a countable failed attempt fire an
            // illegal awaiting_registry -> blocked_scan transition (V0020 has no such pair; found
            // by @agent-reviewer).
            registryReviewPending[0] = true;
            return;
          }

          // BL-039 Slice B: the lifetime mint cap, checked BEFORE the expired-block lift below.
          // The ordering is the requirement, not an accident. Checked after the lift, a capped
          // profile whose block had just expired would be resumed (blocked_scan -> in_progress,
          // one status-history row, every counter zeroed) and then immediately re-blocked
          // (in_progress -> blocked_scan, a second row) -- two rows recording a round trip the
          // customer never made, in the table customer.md's "there are no silent state changes"
          // rule exists to keep trustworthy.
          //
          // So: already blocked and over the cap -> refuse with the deadline already on the row
          // and write NOTHING. in_progress and over the cap -> apply the block now, which is legal
          // precisely because the status IS in_progress (applyScanBlock's insertHistory hard-codes
          // that from-status -- BL-043).
          if (ScanAttemptBudget.tokenCapReached(state.scanTokensMinted())) {
            // Applying the block is legal ONLY from in_progress, and the check is on the status
            // rather than on "not blocked_scan" because V0020 defines no transition into
            // blocked_scan from blocked_liveness or abandoned either -- both reachable here
            // (Stage 10's purged-reference-image route sends a customer back to Stage 8, and the
            // abandonment sweep parks profiles at abandoned). applyScanBlock's insertHistory
            // hard-codes 'in_progress' as its from-status, so calling it from any of the three
            // would raise 23514 and turn this 409 into a 500 -- BL-043's defect, re-entered
            // through a new door. Found by @agent-reviewer.
            //
            // A separate boolean carries this branch rather than a null check on the instant: for
            // an abandoned profile scan_blocked_until is legitimately null, and the handler
            // null-guards it into the generic block copy.
            if (!"in_progress".equals(state.status())) {
              capRefused[0] = true;
              capRefusedUntil[0] = state.scanBlockedUntil();
              return;
            }
            Map<String, Object> capPayload = new LinkedHashMap<>();
            capPayload.put("documentType", appDocumentType);
            capPayload.put("scanTokensMinted", state.scanTokensMinted());
            capPayload.put("cap", ScanAttemptBudget.LIFETIME_TOKEN_CAP);
            long capEventId =
                auditEventWriter.append(
                    simpleEvent(profileId, requestId, EVENT_SCAN_TOKEN_CAP_REACHED, capPayload));
            capBlockedUntil[0] = now.plus(SCAN_BLOCK_DURATION);
            identityScanRepository.applyScanBlock(profileId, capBlockedUntil[0], now, capEventId);
            return;
          }

          boolean resuming = false;
          if ("blocked_scan".equals(state.status())) {
            if (state.scanBlockedUntil() == null || state.scanBlockedUntil().isAfter(now)) {
              stillBlockedUntil[0] = state.scanBlockedUntil();
              return;
            }
            resuming = true;
          }

          int attemptsForType = resuming ? 0 : state.attemptsFor(appDocumentType);
          if (!ScanAttemptBudget.canAttempt(attemptsForType)) {
            typeExhausted[0] = true;
            return;
          }

          String sessionId = UUID.randomUUID().toString();
          String nonce = UUID.randomUUID().toString();

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("documentType", appDocumentType);
          payload.put("sessionId", sessionId);
          payload.put("resumedFromBlock", resuming);
          long eventId =
              auditEventWriter.append(
                  new AuditEvent(
                      CHAIN_KIND,
                      profileId.toString(),
                      EVENT_SCAN_TOKEN_ISSUED,
                      "customer",
                      null,
                      profileId,
                      profileId,
                      requestId,
                      CanonicalJson.object(payload)));

          // Persisted so submitScan validates the returned JWS's claims against what the backend
          // actually issued, never against what the request merely claims (found by
          // @agent-reviewer: without this, an attacker holding any valid JWS could bind it to an
          // arbitrary profile by echoing that JWS's own jti/nonce back in the request).
          identityScanRepository.recordPendingSession(profileId, sessionId, nonce);
          // BL-039 Slice B. Inside the issuance transaction, so every refusal above -- terminal,
          // registry-pending, still-blocked, cap-reached, type-exhausted -- counts nothing. What
          // this bounds is the mint itself, which is what F-5 found unbounded: before it, a
          // customer could request tokens forever without ever spending an attempt, and nothing
          // counted, blocked or rate-limited any of it.
          identityScanRepository.incrementScanTokensMinted(profileId);

          if (resuming) {
            identityScanRepository.resumeFromScanBlock(profileId, now, eventId);
          } else {
            profileRepository.touchLastActivity(profileId, now);
          }

          pendingSessionId[0] = sessionId;
          pendingNonce[0] = nonce;
        });

    if (terminalRejected[0]) {
      auditRejection(profileId, requestId, "profile_already_complete", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if (registryReviewPending[0]) {
      auditRejection(profileId, requestId, "registry_review_pending", null);
      throw new RegistryReviewPendingException(
          "profile " + profileId + " is awaiting a Civil Registry retry");
    }
    if (stillBlockedUntil[0] != null) {
      auditRejection(
          profileId, requestId, "scan_temporarily_blocked", stillBlockedUntil[0].toString());
      throw new ScanTemporarilyBlockedException(stillBlockedUntil[0]);
    }
    // BL-039 Slice B. The EXISTING block response, deliberately: the app already renders it with a
    // countdown and already tells the customer to try later or visit a branch, which is the right
    // advice for someone who has spent their lifetime allowance. No new code, screen or copy.
    if (capBlockedUntil[0] != null) {
      auditRejection(profileId, requestId, "scan_token_cap_reached", capBlockedUntil[0].toString());
      throw new ScanTemporarilyBlockedException(capBlockedUntil[0], ScanBlockReason.LIFETIME_CAP);
    }
    if (capRefused[0]) {
      auditRejection(
          profileId,
          requestId,
          "scan_token_cap_reached",
          capRefusedUntil[0] == null ? null : capRefusedUntil[0].toString());
      throw new ScanTemporarilyBlockedException(capRefusedUntil[0], ScanBlockReason.LIFETIME_CAP);
    }
    if (typeExhausted[0]) {
      auditRejection(profileId, requestId, "scan_type_exhausted", appDocumentType);
      throw new ScanTypeExhaustedException(appDocumentType);
    }

    // Outside the already-committed transaction -- an unbounded external call must not hold the
    // app.profile row lock (found by @agent-reviewer: the first draft called this inside the
    // transaction above).
    IssuedAccessToken accessToken = uqudoClient.issueAccessToken();
    // BL-114(a): the token's own expiry IS this issuance's deadline on the scan stage -- the
    // sessionId/nonce minted above carry none of their own. Contrast LivenessService, where the
    // 600s face session dies long before the token does.
    return new TokenIssuance(
        accessToken.value(),
        pendingSessionId[0],
        pendingNonce[0],
        appDocumentType,
        accessToken.expiresAt());
  }

  // ---- Stage 8: scan submission ----

  public ScanDisplayPayload submitScan(
      UUID profileId, String sessionId, String nonce, String appDocumentType, String jws) {
    if (!DocumentTypes.isValid(appDocumentType)) {
      throw new IdentityScanRejectedException("documentType must be 'passport' or 'national_id'");
    }
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();
    String uqudoDocumentType = DocumentTypes.toUqudoDocumentType(appDocumentType);

    // Checked before any external call, exactly like ContactChannelsService/DataEntryService check
    // profile existence before their own send loops -- an unknown or terminal profile should not
    // spend a real Uqudo verification, still less a Civil Registry lookup.
    ScanState preCheck =
        identityScanRepository
            .lockAndGetScanState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (preCheck.terminal()) {
      auditRejection(profileId, requestId, "profile_already_complete", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    // A stale pending session minted before the profile was blocked must not let the 24h block be
    // bypassed by simply relaunching the SDK with the same sessionId/nonce (found by
    // @agent-reviewer's second pass: pending_scan_session_id/nonce are deliberately never cleared,
    // so without this check a customer could submit a fresh JWS through an already-blocked
    // session).
    if ("blocked_scan".equals(preCheck.status())) {
      auditRejection(
          profileId,
          requestId,
          "scan_temporarily_blocked",
          String.valueOf(preCheck.scanBlockedUntil()));
      throw new ScanTemporarilyBlockedException(preCheck.scanBlockedUntil());
    }
    if ("awaiting_registry".equals(preCheck.status())) {
      auditRejection(profileId, requestId, "registry_review_pending", null);
      throw new RegistryReviewPendingException(
          "profile " + profileId + " is awaiting a Civil Registry retry");
    }
    // Validated against what the backend actually issued (recorded by issueToken), never trusted
    // from the request alone — found by @agent-reviewer: without this, the sessionId/nonce
    // "expected" values handed to verifyAndParse could be read straight out of any valid JWS an
    // attacker holds, making the whole check circular.
    if (!sessionId.equals(preCheck.pendingScanSessionId())
        || !nonce.equals(preCheck.pendingScanNonce())) {
      auditRejection(profileId, requestId, "invalid_scan_session", null);
      throw new InvalidScanSessionException(
          "sessionId/nonce do not match what issueToken issued for this profile");
    }

    ParsedEnrolmentResult parsed;
    try {
      parsed =
          uqudoClient.verifyAndParse(
              jws, preCheck.pendingScanSessionId(), preCheck.pendingScanNonce(), uqudoDocumentType);
    } catch (ArtifactExpiredException expired) {
      // NOT counted against the retry budget (uqudo-sdk.md's design rule, R-012/R-021) -- a
      // stale clock, not a scan-quality problem. Audited without recordFailedAttempt's counter
      // increment.
      auditEventWriter.append(
          simpleEvent(
              profileId,
              requestId,
              EVENT_SCAN_ARTIFACT_EXPIRED,
              Map.of("documentType", appDocumentType)));
      throw expired;
    } catch (JwsVerificationException rejected) {
      recordFailedAttempt(profileId, requestId, appDocumentType, EVENT_SCAN_JWS_REJECTED, jws);
      throw rejected;
    }

    // BL-034 -- the customer's own retry of an upload whose acknowledgement was lost. customer.md
    // stage 13 (l.1149-1151) has the app retain the returned enrolment JWS and retry the UPLOAD
    // rather than repeat the capture; before this check that retry always failed and the customer
    // was told to rescan, spending a real Uqudo operation and one of their limited per-type
    // attempts on a scan that had already landed.
    //
    // Placed HERE, above the image download, rather than inside the accept transaction where stage
    // 10's equivalent sits (LivenessService's currentFaceResultJti, S3-13): purgeSession below runs
    // as soon as the first attempt commits, whether or not the client ever received the response,
    // so by the time a retry arrives Uqudo's images are usually already gone and the retry dies in
    // the download loop -- BEFORE any transaction opens. An in-transaction check would be dead code
    // on the real retry path. The DuplicateKeyException catch further down is NOT replaced: this
    // read runs unlocked, so two simultaneous retries can both miss it, and it remains the guard
    // against a genuine global replay.
    //
    // Only an 'ok' registry state short-circuits. Where the first attempt's lookup did not succeed
    // the profile is awaiting_registry and the pre-check above has already thrown -- that variant
    // needs its own assessment and is BL-035, deliberately not handled here.
    Optional<AcceptedScanSnapshot> acceptedSnapshot =
        identityScanRepository.currentAcceptedScanSnapshot(profileId);
    if (acceptedSnapshot.isPresent()
        && acceptedSnapshot.get().uqudoJti().equals(parsed.jti())
        && REGISTRY_STATE_OK.equals(acceptedSnapshot.get().registryState())) {
      AcceptedScanSnapshot snapshot = acceptedSnapshot.get();
      // The EXISTING cycle, rebuilt from storage, and nothing else: no second image download, no
      // second Civil Registry call, no new cycle, no second hash-chained scan_accepted event, no
      // second purge, and no draw from the retry budget. No audit event is written either -- the
      // retry changes no state and evidences network conditions rather than identity, while
      // payload_json is permanently hash-chained; the same reasoning the Stage 9 review-image
      // endpoint already records for itself. The first attempt's scan_accepted remains the record
      // of acceptance.
      //
      // A DIFFERENT jti falls through to the unchanged path below, where supersede-and-insert opens
      // a new cycle -- the legitimate "scan a different document" flow -- and V0008's UNIQUE
      // constraint still rejects a genuine duplicate.
      //
      // touchLastActivity is deliberately NOT called here, unlike the first attempt's ok path
      // below (found by @agent-reviewer). last_activity_at feeds the 90-day abandonment sweep
      // (app.purge_abandoned_artifacts(), V0055), and the accepted scan this retry is
      // acknowledging already refreshed it moments earlier when it committed -- so the clock is
      // not at risk, and a write on a path whose whole point is that it changes nothing would be
      // the larger surprise. Revisit if a retry can ever arrive long after its first attempt.
      return buildDisplayPayload(profileId, snapshot);
    }

    Map<String, byte[]> downloadedByImageId = new LinkedHashMap<>();
    try {
      for (ParsedImage image : parsed.images()) {
        downloadedByImageId.put(
            image.uqudoImageId(),
            uqudoClient.downloadImage(image.uqudoImageId(), image.checksum()));
      }
    } catch (ImageUnavailableException imagesGone) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("documentType", appDocumentType);
      payload.put("jti", parsed.jti());
      payload.put("reason", imagesGone.getMessage());
      auditEventWriter.append(
          new AuditEvent(
              CHAIN_KIND,
              profileId.toString(),
              EVENT_SCAN_IMAGES_UNAVAILABLE,
              "customer",
              null,
              profileId,
              profileId,
              requestId,
              CanonicalJson.object(payload)));
      // Deliberately NOT counted against the retry budget (uqudo-sdk.md's design rule): a
      // system-timing fact, not a scan-quality problem.
      throw new ImagesUnavailableForAcceptanceException(
          "scan verified but images are no longer available: " + imagesGone.getMessage());
    } catch (ImageIntegrityException corrupted) {
      // Unlike images-unavailable, a checksum mismatch IS a scan-quality/integrity concern
      // (uqudo-sdk.md: "a mismatch is a hard failure") -- counted against the budget like a JWS
      // rejection, not exempted like a timing fact.
      recordFailedAttempt(
          profileId, requestId, appDocumentType, EVENT_SCAN_IMAGE_INTEGRITY_FAILED, jws);
      throw corrupted;
    }

    RegistryOutcomeInternal registryOutcome = queryRegistry(parsed.identityNumber());

    UUID[] cycleIdHolder = new UUID[1];
    boolean[] becameIneligible = {false};

    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            ScanState state =
                identityScanRepository
                    .lockAndGetScanState(profileId)
                    .orElseThrow(
                        () -> new UnknownProfileException("no profile with id " + profileId));
            if (state.terminal()) {
              becameIneligible[0] = true;
              return;
            }

            UUID cycleId = identityScanRepository.insertAcceptedCycle(profileId, now);
            cycleIdHolder[0] = cycleId;
            identityScanRepository.insertScanResult(cycleId, parsed, now);
            // Stage 10's LivenessService reads the portrait back out of this same artifact_ref
            // row (kind='portrait_uqudo') via app.artifact_read() -- AD-004 closed at S5-06, so
            // this loop is now the only place the portrait bytes need to be written; there is no
            // separate transient bridge column any more (V0041/V0042, retired -- see R-047).
            //
            // Raw capture frames (doc_front_frame/doc_back_frame) are downloaded and
            // checksum-verified like every other image -- the accept-after-download ordering
            // rule (R-012/R-021) applies uniformly -- but their BYTES are deliberately not
            // persisted (AD-004, docs/components/persistence.md): they roughly double the
            // volume for no evidentiary gain, since the cropped document (doc_front/doc_back)
            // is what the JWS actually attests to. The row itself, its checksum and its metadata
            // are still written, matching S3-12's pre-existing behaviour.
            for (ParsedImage image : parsed.images()) {
              byte[] content = downloadedByImageId.get(image.uqudoImageId());
              byte[] storedBody = isCaptureFrame(image.kind()) ? null : content;
              identityScanRepository.insertArtifactRef(
                  cycleId,
                  image.kind(),
                  image.uqudoImageId(),
                  image.checksum(),
                  opaqueStorageKey(),
                  "image/jpeg",
                  content.length,
                  sha256(content),
                  storedBody,
                  now);
            }

            // The raw JWS is a hash-chained AUDIT ARTIFACT (audit.audit_artifact), never inlined
            // into payload_json: payload_json is permanently hash-chained and never erasable. Only
            // jti goes into the payload -- deliberately NOT identityNumber, which is the customer's
            // national number: putting it here would defeat the exact purge property this fix
            // exists for (found by @agent-reviewer's second pass, which caught the first fix's own
            // comment claiming "only jti and sha256" while the code still inlined the national
            // number). It already lives in app.scan_result.identity_number, the app schema's own
            // (separately purgeable) copy.
            Map<String, Object> acceptedPayload = new LinkedHashMap<>();
            acceptedPayload.put("documentType", appDocumentType);
            acceptedPayload.put("jti", parsed.jti());
            auditEventWriter.appendWithArtifact(
                new AuditEvent(
                    CHAIN_KIND,
                    profileId.toString(),
                    EVENT_SCAN_ACCEPTED,
                    "customer",
                    null,
                    profileId,
                    profileId,
                    requestId,
                    CanonicalJson.object(acceptedPayload)),
                new AuditArtifact(
                    "uqudo_scan_jws", "application/jose", jws.getBytes(StandardCharsets.UTF_8)));

            identityScanRepository.insertRegistryResult(
                cycleId,
                registryOutcome.state(),
                now,
                1,
                registryOutcome.result().orElse(null),
                registryOutcome.identityNumberReturned());

            if (registryOutcome.registryPortraitBytes() != null) {
              identityScanRepository.insertArtifactRef(
                  cycleId,
                  "portrait_registry",
                  null,
                  null,
                  opaqueStorageKey(),
                  "image/jpeg",
                  registryOutcome.registryPortraitBytes().length,
                  sha256(registryOutcome.registryPortraitBytes()),
                  registryOutcome.registryPortraitBytes(),
                  now);
            }

            long registryEventId =
                auditRegistryLookup(profileId, requestId, registryOutcome, false);

            if (!REGISTRY_STATE_OK.equals(registryOutcome.state())) {
              identityScanRepository.transitionToAwaitingRegistry(profileId, now, registryEventId);
            } else {
              profileRepository.touchLastActivity(profileId, now);
            }
          });
    } catch (DuplicateKeyException alreadyAccepted) {
      // app.scan_result.uqudo_jti's UNIQUE constraint (V0008) — the global replay guard
      // uqudo-sdk.md calls for. Without this catch the constraint violation surfaces as an
      // unmapped 500 instead of a clean rejection (found by @agent-reviewer).
      //
      // Deliberately DuplicateKeyException, NOT the broader DataIntegrityViolationException:
      // this transaction also runs FK inserts and V0020's CHECK-constraint-backed status
      // transition guard, both of which Spring also translates to DataIntegrityViolationException
      // subtypes. Catching the broad type would mis-report a real state-machine defect (a
      // rejected illegal transition) as "this JWS was already accepted" (found by
      // @agent-reviewer's second pass).
      throw new JwsAlreadyAcceptedException(
          "this JWS (jti=" + parsed.jti() + ") has already been accepted");
    }

    if (becameIneligible[0]) {
      auditRejection(profileId, requestId, "profile_became_complete_during_processing", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " completed while this scan submission was in flight");
    }

    // Strictly after the transaction above has committed, never before -- if that transaction had
    // instead rolled back, this line does not run. Since AD-004 closed (S5-06), "durably stored"
    // means the actual bytes, in app.artifact_ref.body, committed in the transaction above -- so
    // this purge is no longer evidence destruction with no backend copy behind it for most images;
    // it is exactly the deliberate privacy control it was always meant to be (R-001: we run on
    // FIB's borrowed tenant), now backed by a real durable original. The one exception is the raw
    // capture frames (isCaptureFrame(), never given a body -- see this loop's own comment above):
    // for those, this call is still the last copy going away, same as before AD-004 closed.
    //
    // The id: on an ENROLMENT JWS jti == the session id the backend minted (observed at S1-02), so
    // purging by jti is correct here -- unlike the face session, where jti is a separate UUID and
    // LivenessService must purge by data.sessionId instead.
    uqudoClient.purgeSession(parsed.jti());

    return buildDisplayPayload(
        profileId,
        cycleIdHolder[0],
        appDocumentType,
        parsed.identityNumber(),
        registryOutcome.state(),
        registryOutcome.result().orElse(null));
  }

  /**
   * AD-002b's three journey states, straight from the port: a found record is {@code ok}, any other
   * answer is {@code not_found}, no answer is {@code unreachable}. The portrait bytes come decoded
   * from the adapter (an undecodable {@code PHOTOGRAPH} is null there, never a throw here).
   */
  private RegistryOutcomeInternal queryRegistry(String identityNumber) {
    try {
      RegistryLookup lookup = civilRegistryClient.lookup(identityNumber);
      return new RegistryOutcomeInternal(
          lookup.found() ? REGISTRY_STATE_OK : REGISTRY_STATE_NOT_FOUND,
          Optional.ofNullable(lookup.record()),
          lookup.found() ? lookup.record().photograph() : null,
          lookup.identityNumberReturned(),
          lookup.reason(),
          lookup.exchange());
    } catch (RegistryUnreachableException unreachable) {
      return new RegistryOutcomeInternal(
          REGISTRY_STATE_UNREACHABLE,
          Optional.empty(),
          null,
          null,
          RegistryLookup.NO_RESPONSE,
          unreachable.exchange());
    }
  }

  // ---- Stage 8: cancel ----

  public void cancelScan(UUID profileId, String appDocumentType) {
    if (!DocumentTypes.isValid(appDocumentType)) {
      throw new IdentityScanRejectedException("documentType must be 'passport' or 'national_id'");
    }
    UUID requestId = UUID.randomUUID();
    // A cheap early rejection for the common case -- the real guard against a terminal profile
    // having its retry-budget columns mutated is recordFailedAttempt's own re-check under lock
    // immediately below, since this read's lock is released the instant it returns (autocommit)
    // and cannot by itself close the race (found by @agent-reviewer).
    ScanState state =
        identityScanRepository
            .lockAndGetScanState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (state.terminal()) {
      auditRejection(profileId, requestId, "profile_already_complete", null);
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    recordFailedAttempt(profileId, requestId, appDocumentType, EVENT_SCAN_CANCELLED, null);
  }

  /**
   * Shared by the SDK-cancel and JWS-rejection paths, and by Stage 9's "wrong number" outcome —
   * every case customer.md counts against the stage 8 retry budget. Re-locks {@code app.profile}
   * (the caller may already have read state before external calls) and re-checks terminality under
   * that lock — the actual guard against a terminal profile's retry-budget columns being mutated by
   * a request that raced a status change, not the caller's own pre-check.
   */
  private void recordFailedAttempt(
      UUID profileId, UUID requestId, String appDocumentType, String eventType, String jws) {
    Instant now = clock.instant();
    boolean[] becameIneligible = {false};
    Instant[] activeBlockUntil = new Instant[1];
    boolean[] registryReviewPendingNow = {false};
    boolean[] typeExhausted = {false};
    transactionTemplate.executeWithoutResult(
        status -> {
          ScanState state =
              identityScanRepository
                  .lockAndGetScanState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (state.terminal()) {
            becameIneligible[0] = true;
            return;
          }
          // A countable attempt only ever applies from in_progress. Neither blocked_scan (found
          // reachable via cancelScan, which -- unlike submitScan/issueToken -- was never gated on
          // it) nor awaiting_registry (found reachable the same way: cancelScan needs no pending
          // session, so gating only issueToken/submitScan left this path open) may have this
          // method mutate the retry-budget columns or fire a transition V0020 does not define.
          // Found by @agent-reviewer's second pass.
          if ("blocked_scan".equals(state.status())) {
            activeBlockUntil[0] = state.scanBlockedUntil();
            return;
          }
          if ("awaiting_registry".equals(state.status())) {
            registryReviewPendingNow[0] = true;
            return;
          }
          // BL-039. The budget is checked HERE, under the same lock that spends it -- not only at
          // token issuance, where it used to sit alone. /cancel needs no pending session at all,
          // and a JWS rejection could be re-posted through a session that was never cleared, so
          // before this an issued token absorbed an unbounded number of failed attempts and the
          // per-type limit was blown past in silence. Refusing here (counting nothing) is what
          // makes customer.md's document-switch fallback actually fire: SCAN_TYPE_EXHAUSTED sends
          // the customer to the other document with a fresh budget, rather than letting them
          // over-spend on this one and land on the 24-hour block with the other untouched.
          if (!ScanAttemptBudget.canAttempt(state.attemptsFor(appDocumentType))) {
            typeExhausted[0] = true;
            return;
          }

          int newForType = state.attemptsFor(appDocumentType) + 1;
          int newTotal = state.scanAttemptsTotal() + 1;
          boolean blockTriggered = ScanAttemptBudget.blockTriggered(newTotal);

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("documentType", appDocumentType);
          payload.put("attemptsForTypeAfter", newForType);
          payload.put("attemptsTotalAfter", newTotal);
          payload.put("blockTriggered", blockTriggered);
          AuditEvent event =
              new AuditEvent(
                  CHAIN_KIND,
                  profileId.toString(),
                  eventType,
                  "customer",
                  null,
                  profileId,
                  profileId,
                  requestId,
                  CanonicalJson.object(payload));
          // The rejected JWS is stored the same way an accepted one is -- as an artifact, never
          // inlined into the permanently hash-chained payload_json (AuditArtifact's Javadoc).
          long eventId =
              jws == null
                  ? auditEventWriter.append(event)
                  : auditEventWriter.appendWithArtifact(
                      event,
                      new AuditArtifact(
                          "uqudo_scan_jws",
                          "application/jose",
                          jws.getBytes(StandardCharsets.UTF_8)));

          identityScanRepository.applyScanAttempt(profileId, appDocumentType);
          // BL-039: one token, one attempt. The try is spent, so the session that backed it is
          // spent with it -- a further post through the same sessionId/nonce is now
          // INVALID_SCAN_SESSION rather than a second free draw. Deliberately NOT done on the
          // accepted path, where BL-034's upload retry re-posts this very triple.
          identityScanRepository.consumePendingScanSession(profileId);
          // A cycle row that never reached acceptance -- the schema's own record of "an attempt
          // happened and went nowhere" (app.identity_cycle.state='abandoned', V0008), matching the
          // three-way state model the accept path already uses (active/superseded).
          identityScanRepository.insertAbandonedCycle(profileId, now);

          if (blockTriggered) {
            identityScanRepository.applyScanBlock(
                profileId, now.plus(SCAN_BLOCK_DURATION), now, eventId);
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
          profileId, requestId, "scan_temporarily_blocked", activeBlockUntil[0].toString());
      throw new ScanTemporarilyBlockedException(activeBlockUntil[0]);
    }
    if (registryReviewPendingNow[0]) {
      auditRejection(profileId, requestId, "registry_review_pending", null);
      throw new RegistryReviewPendingException(
          "profile " + profileId + " is awaiting a Civil Registry retry");
    }
    // BL-039. Same reason string issueToken already emits for the same refusal, so the two spend
    // points and the issuance point are one story in the audit trail. On submitScan's
    // JWS-rejection path this replaces the JwsVerificationException the caller was about to
    // rethrow -- reachable only when a concurrent spend exhausted the type after this token was
    // issued, and "no attempts remain for this document type" is the more actionable of the two.
    if (typeExhausted[0]) {
      auditRejection(profileId, requestId, "scan_type_exhausted", appDocumentType);
      throw new ScanTypeExhaustedException(appDocumentType);
    }
  }

  // ---- Stage 9 ----

  /**
   * Stage 9's display payload for the profile's active cycle, rebuilt from storage — S5-11.
   *
   * <p><strong>Read-only.</strong> No Civil Registry call, no write, no status transition, no draw
   * from the retry budget, and no row lock. Until this existed the payload was obtainable only as
   * the response body of {@code POST /scan-result} or {@code POST /registry-review/retry}, so a
   * customer resuming into Stage 9 (customer.md Stage 13, "same app, local state present, backend
   * reachable — reconcile silently and land on the recorded step") could not re-render the screen
   * without firing a fresh lookup that overwrote the stored result.
   *
   * <p>No audit event is written, for the reason {@link #reviewImage} already records: a customer
   * re-reading their own scan evidences nothing about identity, the screen re-reads freely, and
   * {@code payload_json} is permanently hash-chained.
   *
   * <p>A registry result that is not yet {@code ok} is <strong>not</strong> an error here. It comes
   * back as a {@code 200} whose {@code registryReady} is {@code false} — the flag that exists for
   * exactly this, keyed on the same predicate as {@code REGISTRY_NOT_READY} and already used this
   * way by both mutating producers. Returning a conflict to a customer checking their own paused
   * status would make the pause screen unreachable except through an error path. {@code
   * REGISTRY_PENDING} (the profile's {@code awaiting_registry} status) and {@code
   * REGISTRY_NOT_READY} ({@code registry_result.state}) stay distinct and stay on the mutating
   * paths; neither is thrown from here.
   *
   * <p>Guard order differs from {@code retryRegistryLookup}'s deliberately, and S5-07 should know
   * it: this method checks terminality first and the active cycle second, while the four Stage 9
   * <em>actions</em> resolve the active cycle first (that is what {@code requireActiveReview} does,
   * unchanged since S3-12). So a terminal profile whose active cycle carries no {@code scan_result}
   * answers {@code PROFILE_TERMINAL} here and {@code STATE_CONFLICT} there. Both are correct
   * advice; a read should say "your journey is over" before "re-sync", and an action should refuse
   * on the same grounds its siblings do.
   *
   * @throws UnknownProfileException no such profile — {@code 404}, the contract every other
   *     endpoint in this controller already has
   * @throws ProfileNotEditableException the journey is over — {@code PROFILE_TERMINAL}
   * @throws NoActiveRegistryReviewException no active cycle, or an active cycle with no scan result
   *     yet (the customer is at Stage 8, not Stage 9) — {@code STATE_CONFLICT}, which tells the app
   *     to re-sync through the Stage 13 resume rather than show a pause
   */
  public ScanDisplayPayload currentReviewPayload(UUID profileId) {
    ScanState state =
        identityScanRepository
            .readScanState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (state.terminal()) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    AcceptedScanSnapshot snapshot =
        identityScanRepository
            .currentAcceptedScanSnapshot(profileId)
            .orElseThrow(() -> NoActiveRegistryReviewException.noActiveCycle(profileId));
    return buildDisplayPayload(profileId, snapshot);
  }

  public ScanDisplayPayload retryRegistryLookup(UUID profileId) {
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();
    ActiveReview review = requireActiveReview(profileId);
    ActiveRegistryContext context = review.context();

    // Two guards this method lacked entirely until S5-11. Both are checked BEFORE queryRegistry
    // below, so neither costs a live Civil Registry call.
    //
    // Terminality. markCycleAccepted does not change identity_cycle.state, so an accepted,
    // submitted, approved or rejected profile still has an ACTIVE cycle and requireActiveReview
    // finds it happily. Without this, a stray retry against a finished journey re-queried the
    // registry and overwrote its stored result. Every sibling path already refuses a terminal
    // profile (issueToken, submitScan, recordFailedAttempt); this one never did.
    if (review.state().terminal()) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    // An already-ok result is never re-queried. updateRegistryResultOnRetry below writes EVERY
    // name/DOB/address column from `fields`, and queryRegistry leaves those null for a not_found
    // or unreachable outcome -- so one retry during a registry outage used to blank a verified
    // Civil Registry record, which the back office reads (operator.domain.ProfileView). The retry
    // budget is not the protection here: Stage 9's retry is deliberately budget-free.
    //
    // A short-circuit, not a throw, mirroring BL-034's treatment of the same shape of redundant
    // call: the caller asked for the current registry result and there is a good one stored, so
    // hand back exactly what a resume read would return. The sibling actions' `registryState ==
    // ok` guard is deliberately NOT used here -- it means "require ok", and retry exists for the
    // non-ok case (customer.md Stage 9, "Civil Registry unreachable or returning nothing").
    if (REGISTRY_STATE_OK.equals(context.registryState())) {
      return currentReviewPayload(profileId);
    }

    RegistryOutcomeInternal outcome = queryRegistry(context.identityNumber());

    boolean[] becameTerminal = {false};
    boolean[] alreadyOkUnderLock = {false};
    transactionTemplate.executeWithoutResult(
        status -> {
          // Re-locks app.profile and re-reads the registry state FRESH, under that lock, rather
          // than trusting the pre-transaction `context` read from before the (external, unbounded)
          // registry call above: two concurrent retries would otherwise both see the stale
          // "not yet ok" state and both fire transitionBackToInProgressFromRegistry, writing two
          // history rows for one real transition (found by @agent-reviewer's second pass). The row
          // lock serialises the second call behind the first's commit, so its re-read here sees
          // the first call's already-applied change.
          ScanState fresh =
              identityScanRepository
                  .lockAndGetScanState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          // The terminality guard again, under the lock -- the pre-transaction check above was
          // read before the unbounded registry call, so a profile that reached a terminal status
          // during that call would otherwise still have its registry_result overwritten here. This
          // is BL-038's defect (2) in its narrow race form, and recordFailedAttempt already
          // establishes the shape: re-lock, re-check terminal, set a flag, write nothing, and
          // throw only after the (empty) transaction has committed (found by @agent-reviewer).
          if (fresh.terminal()) {
            becameTerminal[0] = true;
            return;
          }
          ActiveRegistryContext freshContext =
              identityScanRepository
                  .currentActiveRegistryContext(profileId)
                  .orElseThrow(() -> NoActiveRegistryReviewException.noActiveCycle(profileId));

          // BL-044. The already-ok short-circuit above reads the PRE-transaction `context`, taken
          // before the unbounded registry call, so two retries that both start while the state is
          // not-ok both pass it and both query the registry. The row lock serialises them here,
          // and this is where the loser has to notice that the winner already stored a verified
          // result. Without it, updateRegistryResultOnRetry below ran unconditionally: the loser
          // wrote its own outcome over the winner's -- and because that method writes EVERY
          // name/DOB/address column from `fields`, which queryRegistry leaves null for a not_found
          // or unreachable outcome, a registry that flapped blanked a verified record the back
          // office reads. If instead both came back ok, the second insertArtifactRef below
          // violated app.artifact_ref's UNIQUE (cycle_id, kind) (V0008) as an unmapped 500.
          //
          // BL-038's sequential short-circuit stays exactly as it was; this closes the race form
          // it does not reach.
          boolean supersededByConcurrentRetry =
              REGISTRY_STATE_OK.equals(freshContext.registryState());

          if (!supersededByConcurrentRetry) {
            identityScanRepository.updateRegistryResultOnRetry(
                freshContext.cycleId(),
                outcome.state(),
                now,
                freshContext.attempts() + 1,
                outcome.result().orElse(null),
                outcome.identityNumberReturned());
            if (outcome.registryPortraitBytes() != null) {
              identityScanRepository.insertArtifactRef(
                  freshContext.cycleId(),
                  "portrait_registry",
                  null,
                  null,
                  opaqueStorageKey(),
                  "image/jpeg",
                  outcome.registryPortraitBytes().length,
                  sha256(outcome.registryPortraitBytes()),
                  outcome.registryPortraitBytes(),
                  now);
            }
          }
          // Deliberately BEFORE the BL-044 return, not after it. The losing retry really did send
          // the national number to the Civil Registry and really did get an answer back; that
          // outbound exchange is audited whether or not its result is stored. Returning above this
          // line would leave a real exchange with no registry_lookup event and no
          // civil_registry_request artifact -- BL-040's class of evidence loss, introduced by this
          // fix rather than found by it (@agent-reviewer).
          long eventId = auditRegistryLookup(profileId, requestId, outcome, true);
          if (supersededByConcurrentRetry) {
            alreadyOkUnderLock[0] = true;
            return;
          }
          // Guarded by the PREVIOUS state as re-read under the lock just now, not the
          // pre-transaction `context`: without this, a redundant or concurrent retry call would
          // still fire this transition, writing a status-history row for an
          // awaiting_registry -> in_progress move that never actually happened (found by
          // @agent-reviewer).
          //
          // The first half now looks dead -- BL-044's guard above returns whenever freshContext is
          // ok, so this can only be reached when it is not. KEEP IT. It is the only thing standing
          // between a duplicate awaiting_registry -> in_progress transition and its history row,
          // and tidying it away makes this method strictly worse than it was if BL-044's guard is
          // ever refactored out -- which is exactly the failure mode BL-042 and BL-043 exist to
          // record. (It is also a proxy for a status this method already holds under the lock;
          // guarding on fresh.status() would be the accurate test. Filed as BL-048, not done here.)
          if (!REGISTRY_STATE_OK.equals(freshContext.registryState())
              && REGISTRY_STATE_OK.equals(outcome.state())) {
            identityScanRepository.transitionBackToInProgressFromRegistry(profileId, now, eventId);
          }
        });

    if (becameTerminal[0]) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " reached a terminal status while the retry was in flight");
    }
    // BL-044: a concurrent retry won the row lock and stored a verified result. Answer with what is
    // actually in storage -- the same value the pre-transaction short-circuit above returns, so the
    // two paths are indistinguishable to the caller -- rather than with this call's own `outcome`,
    // which was never written and may be a not_found/unreachable from a registry that flapped.
    if (alreadyOkUnderLock[0]) {
      return currentReviewPayload(profileId);
    }
    return buildDisplayPayload(
        profileId,
        context.cycleId(),
        DocumentTypes.fromUqudoDocumentType(context.uqudoDocumentType()),
        context.identityNumber(),
        outcome.state(),
        outcome.result().orElse(null));
  }

  public void acceptRegistryReview(UUID profileId) {
    ActiveReview review = requireActiveReview(profileId);
    ActiveRegistryContext context = review.context();
    // BL-042. markCycleAccepted does not change identity_cycle.state, so a submitted, approved or
    // rejected profile still has an ACTIVE cycle whose registry_result.state is ok -- the
    // precondition below is satisfied on a finished journey and requireActiveReview finds it
    // happily. Unguarded, this method COMMITTED on a terminal profile: it rewrote
    // identity_cycle.accepted_at to now on a cycle the operator had already approved and appended
    // a registry_review_accepted event attributed to the customer, falsifying the evidence on a
    // finished journey quietly, because V0020's trigger only fires on a status CHANGE and this
    // path changes no status. Terminality is checked before the registry-ready guard so the more
    // accurate of the two errors wins. Same shape and same position as retryRegistryLookup's,
    // which S5-11 gave to that method and not to its three siblings.
    if (review.state().terminal()) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if (!REGISTRY_STATE_OK.equals(context.registryState())) {
      throw NoActiveRegistryReviewException.registryNotReady(profileId);
    }
    Instant now = clock.instant();
    boolean[] becameTerminal = {false};
    transactionTemplate.executeWithoutResult(
        status -> {
          // Re-locks app.profile as the transaction's FIRST statement, before the audit-chain
          // write below -- same reasoning as reportWrongDetails's identical fix, just above:
          // requireActiveReview's lock was released with its own (unwrapped) statement, and
          // markCycleAccepted below locks app.identity_cycle, which reportWrongNumber's
          // supersedeActiveCycleIfAny (same table) can also lock -- taking the audit-chain lock
          // first here would invert this codebase's required lock order and could deadlock
          // against a concurrent reportWrongNumber call on the same profile.
          ScanState state =
              identityScanRepository
                  .lockAndGetScanState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          // BL-042's guard again, under the lock. The pre-transaction check above was made against
          // requireActiveReview's read, whose lock is released with that call's own unwrapped
          // statement -- and app.profile has real concurrent writers (operator approve/reject,
          // S4-02 manual completion, submission). recordFailedAttempt's own comment names this
          // under-lock re-check as the actual guard, the pre-check being only an early exit. Sets
          // a flag, writes nothing, and throws after the (empty) transaction has committed.
          if (state.terminal()) {
            becameTerminal[0] = true;
            return;
          }

          auditEventWriter.append(
              simpleEvent(profileId, UUID.randomUUID(), EVENT_REGISTRY_REVIEW_ACCEPTED, Map.of()));
          identityScanRepository.markCycleAccepted(context.cycleId(), now);
        });

    if (becameTerminal[0]) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " reached a terminal status while the acceptance was in flight");
    }
  }

  public Optional<Instant> reportWrongNumber(UUID profileId) {
    ActiveReview review = requireActiveReview(profileId);
    ActiveRegistryContext context = review.context();
    // BL-042, as in acceptRegistryReview just above. Unguarded, this method COMMITTED on a
    // terminal profile: supersedeActiveCycleIfAny and applyScanAttempt change no status, so
    // V0020's trigger never fired, and a completed profile's identity cycle was marked superseded
    // with its scan-attempt counters moved. Superseding is not cosmetic --
    // state='active' AND accepted_at IS NOT NULL is exactly what JdbcLivenessRepository and
    // JdbcSubmissionRepository gate on, so for a submitted-not-yet-approved profile the link
    // between the profile and its verified scan was gone. Worse, if the increment happened to
    // reach 6, applyScanBlock then attempted submitted -> blocked_scan, V0020 raised 23514, and
    // the whole call rolled back as an unmapped 500 instead -- so the outcome depended on a
    // counter. The endpoint surface is unauthenticated by design (R-051).
    if (review.state().terminal()) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if (!REGISTRY_STATE_OK.equals(context.registryState())) {
      // Otherwise reachable while awaiting_registry -- customer.md Stage 9's actions are never
      // offered until the registry data is actually visible (same guard as
      // acceptRegistryReview/reportWrongDetails). Unguarded, this call could supersede the
      // profile's only identity_cycle while the profile is still paused, leaving no active cycle
      // and no path back in (found by @agent-reviewer's second pass).
      throw NoActiveRegistryReviewException.registryNotReady(profileId);
    }
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();
    String appDocumentType = DocumentTypes.fromUqudoDocumentType(context.uqudoDocumentType());

    boolean[] blockTriggered = {false};
    Instant[] blockedUntil = new Instant[1];
    boolean[] becameTerminal = {false};
    boolean[] scanBlockedNow = {false};
    Instant[] activeBlockUntil = new Instant[1];
    boolean[] registryReviewPendingNow = {false};
    boolean[] typeExhausted = {false};

    transactionTemplate.executeWithoutResult(
        status -> {
          ScanState state =
              identityScanRepository
                  .lockAndGetScanState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          // BL-042's guard again, under the lock -- see acceptRegistryReview for why the
          // pre-transaction check is not sufficient on its own.
          if (state.terminal()) {
            becameTerminal[0] = true;
            return;
          }
          // BL-043. This method spends a stage-8 retry attempt, exactly as recordFailedAttempt
          // does, but never went through it and so never inherited its two status re-checks.
          // blocked_scan is NOT terminal (V0005), so the guard above does not cover it, and the
          // state is reachable without any race: from Stage 9 the customer goes back to Stage 8,
          // requests a token and cancels the SDK until the total hits 6. None of that touches the
          // active ok cycle -- recordFailedAttempt inserts an ABANDONED cycle at the next seq --
          // and currentReviewPayload serves the Stage 9 payload to a blocked_scan profile happily
          // (it checks terminal() alone), so the app lands back here. "The national number is
          // wrong" from there incremented the budget past its own limits and, because
          // scanAttemptsTotal() was still 6, blockTriggered(7) was true, so applyScanBlock ran
          // again: the status was already blocked_scan, V0020's trigger saw no change and passed,
          // scan_blocked_until was pushed out ANOTHER 24 hours, and insertHistory -- whose
          // from-status is hard-coded 'in_progress' -- wrote a profile_status_history row for a
          // transition that never occurred. A 48-hour block on a mandated update, and a falsified
          // row in the table customer.md's "there are no silent state changes" rule exists to make
          // trustworthy. Returns before supersedeActiveCycleIfAny, so the active ok cycle survives
          // and the customer keeps a path back in once the block lifts.
          if ("blocked_scan".equals(state.status())) {
            scanBlockedNow[0] = true;
            activeBlockUntil[0] = state.scanBlockedUntil();
            return;
          }
          // Parity with recordFailedAttempt's third check rather than a defect anyone can reach
          // today: the registry-ready guard above already refuses unless the ACTIVE cycle is ok,
          // and a profile only reaches awaiting_registry with a not-ok active cycle. Kept because
          // this method's whole defect was inheriting three checks and getting none of them, and
          // an unreachable branch is the cheaper half of that bargain. Its test proves the guard,
          // NOT a reachable defect -- see the session report.
          if ("awaiting_registry".equals(state.status())) {
            registryReviewPendingNow[0] = true;
            return;
          }
          // BL-039. This method is the second spending path -- the one the ticket never named --
          // and like recordFailedAttempt it inherited no budget check at all. Placed BEFORE
          // supersedeActiveCycleIfAny deliberately: a refusal must leave the active ok cycle
          // standing, exactly as BL-043's blocked_scan guard above already does, or the customer
          // loses their verified scan to a call that then declined to spend anything.
          if (!ScanAttemptBudget.canAttempt(state.attemptsFor(appDocumentType))) {
            typeExhausted[0] = true;
            return;
          }
          identityScanRepository.supersedeActiveCycleIfAny(profileId, now);
          int newTotal = state.scanAttemptsTotal() + 1;
          blockTriggered[0] = ScanAttemptBudget.blockTriggered(newTotal);

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("documentType", appDocumentType);
          payload.put("blockTriggered", blockTriggered[0]);
          long eventId =
              auditEventWriter.append(
                  simpleEvent(profileId, requestId, EVENT_REGISTRY_REVIEW_WRONG_NUMBER, payload));

          identityScanRepository.applyScanAttempt(profileId, appDocumentType);
          // BL-039, as in recordFailedAttempt: the try is spent, so the session that backed it is
          // spent with it. Stage 9 is reached only through an accepted scan, so the session being
          // cleared here is the one that scan was issued under -- and this call has just
          // superseded that cycle, so no BL-034 retry of it can still be wanted.
          identityScanRepository.consumePendingScanSession(profileId);
          if (blockTriggered[0]) {
            blockedUntil[0] = now.plus(SCAN_BLOCK_DURATION);
            identityScanRepository.applyScanBlock(profileId, blockedUntil[0], now, eventId);
          } else {
            profileRepository.touchLastActivity(profileId, now);
          }
        });

    // The three refusals, thrown after the (empty) transaction has committed --
    // recordFailedAttempt's
    // shape, including its audit half: this surface is unauthenticated by design (R-051), so a
    // repeatedly refused wrong-number has to leave a trace. The terminal case follows Stage 9's own
    // precedent (retryRegistryLookup) and is not separately audited.
    if (becameTerminal[0]) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " reached a terminal status while the report was in flight");
    }
    if (scanBlockedNow[0]) {
      auditRejection(
          profileId,
          requestId,
          "scan_temporarily_blocked",
          activeBlockUntil[0] == null ? null : activeBlockUntil[0].toString());
      // The ORIGINAL expiry, never now + 24h: reporting a wrong number during a live block must not
      // move the moment it lifts. A separate boolean carries the branch rather than a null check on
      // the instant, so a blocked_scan row whose scan_blocked_until is somehow null still refuses
      // here instead of falling through to a silent empty success.
      throw new ScanTemporarilyBlockedException(activeBlockUntil[0]);
    }
    if (registryReviewPendingNow[0]) {
      auditRejection(profileId, requestId, "registry_review_pending", null);
      throw new RegistryReviewPendingException(
          "profile " + profileId + " is awaiting a Civil Registry retry");
    }
    // BL-039. New on this route: POST /registry-review/wrong-number can now answer 409
    // SCAN_TYPE_EXHAUSTED. The controller already maps it (no new code) and Stage 9 already routes
    // it back to the scan (no new screen and no new copy) -- the customer arrives at the
    // document-switch offer instead of silently over-spending a budget that is already gone.
    if (typeExhausted[0]) {
      auditRejection(profileId, requestId, "scan_type_exhausted", appDocumentType);
      throw new ScanTypeExhaustedException(appDocumentType);
    }

    return Optional.ofNullable(blockedUntil[0]);
  }

  public void reportWrongDetails(UUID profileId) {
    ActiveReview review = requireActiveReview(profileId);
    ActiveRegistryContext context = review.context();
    // BL-042, as in the two siblings above. This is the one of the three that V0020 did catch, and
    // it caught it wrongly: submitted -> terminated_registry_mismatch is in no
    // app.status_transition
    // row, so the trigger raised 23514, the transaction rolled back, and the customer got an
    // UNMAPPED 500 where every sibling path answers PROFILE_TERMINAL (409). Nothing was corrupted;
    // the error contract S5-07's Stage 9 screens are being built against was simply wrong, which is
    // why this slice runs before them.
    if (review.state().terminal()) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if (!REGISTRY_STATE_OK.equals(context.registryState())) {
      // Otherwise reachable while the profile is awaiting_registry, which would fire an illegal
      // awaiting_registry -> terminated_registry_mismatch transition V0020 does not define
      // (found by @agent-reviewer) -- the customer can only judge the registry's details once
      // they are actually visible, matching acceptRegistryReview's own guard.
      throw NoActiveRegistryReviewException.registryNotReady(profileId);
    }
    Instant now = clock.instant();
    boolean[] becameTerminal = {false};
    transactionTemplate.executeWithoutResult(
        status -> {
          // Re-locks app.profile as the transaction's FIRST statement, before the audit-chain
          // write below -- the lock requireActiveReview took above was released with that
          // call's own (unwrapped) statement. Taking the audit-chain lock first here would
          // invert this codebase's lock order (the app.profile row lock before the audit-chain
          // lock -- see operator.domain.ReviewRepository#lockAndReadStatus and
          // docs/components/persistence.md "Lock ordering for operator writers"), exactly how
          // S4-01's approve()/reject() deadlocked (40P01) under review, and how S4-02's manual
          // completion -- a legal in_progress writer taking the same two locks in the required
          // order -- could deadlock against this method otherwise. Every sibling method in this
          // class re-locks inside its own transaction the same way (reportWrongNumber,
          // retryRegistryLookup, acceptRegistryReview) -- this method and acceptRegistryReview
          // were the two exceptions found under review; both are now fixed the same way.
          ScanState state =
              identityScanRepository
                  .lockAndGetScanState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          // BL-042's guard again, under the lock -- see acceptRegistryReview for why the
          // pre-transaction check is not sufficient on its own.
          if (state.terminal()) {
            becameTerminal[0] = true;
            return;
          }

          long eventId =
              auditEventWriter.append(
                  simpleEvent(
                      profileId, UUID.randomUUID(), EVENT_REGISTRY_REVIEW_WRONG_DETAILS, Map.of()));
          identityScanRepository.transitionToTerminatedMismatch(profileId, now, eventId);
        });

    if (becameTerminal[0]) {
      throw new ProfileNotEditableException(
          "profile " + profileId + " reached a terminal status while the report was in flight");
    }
  }

  /**
   * The profile's locked {@link ScanState} and its active {@link ActiveRegistryContext}, read
   * together for every Stage 9 action.
   *
   * <p>Returns both rather than only the context (S5-11): the read of {@code app.profile} was
   * always made here, and its {@code terminal} flag was simply discarded, which is how {@code
   * retryRegistryLookup} came to have no terminality guard at all. Nothing else about this method
   * changed — the three sibling actions take {@code .context()} and behave exactly as before.
   */
  private ActiveReview requireActiveReview(UUID profileId) {
    ScanState state =
        identityScanRepository
            .lockAndGetScanState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    ActiveRegistryContext context =
        identityScanRepository
            .currentActiveRegistryContext(profileId)
            .orElseThrow(() -> NoActiveRegistryReviewException.noActiveCycle(profileId));
    return new ActiveReview(state, context);
  }

  /** {@link #requireActiveReview}'s two reads, carried together. */
  private record ActiveReview(ScanState state, ActiveRegistryContext context) {}

  // ---- shared helpers ----

  /**
   * Stage 9's display payload built from a stored {@link AcceptedScanSnapshot} — the one shape
   * every "answer from storage, do not call anything" path needs. Three callers: BL-034's
   * recognised re-upload, S5-11's read-only resume endpoint, and S5-11's already-ok retry
   * short-circuit.
   */
  private ScanDisplayPayload buildDisplayPayload(UUID profileId, AcceptedScanSnapshot snapshot) {
    return buildDisplayPayload(
        profileId,
        snapshot.cycleId(),
        DocumentTypes.fromUqudoDocumentType(snapshot.uqudoDocumentType()),
        snapshot.identityNumber(),
        snapshot.registryState(),
        snapshot.registryFields());
  }

  /**
   * Stage 9's display payload. Takes the registry state and fields directly rather than a {@link
   * RegistryOutcomeInternal} so BL-034's recognised-retry path can build the identical payload out
   * of {@code app.registry_result} instead of re-running a lookup that already succeeded.
   */
  private ScanDisplayPayload buildDisplayPayload(
      UUID profileId,
      UUID cycleId,
      String appDocumentType,
      String nationalNumber,
      String registryState,
      RegistryLookupResult fields) {
    boolean ready = REGISTRY_STATE_OK.equals(registryState);
    return new ScanDisplayPayload(
        profileId,
        cycleId,
        appDocumentType,
        nationalNumber,
        ready,
        fields == null ? null : fields.nameArGiven(),
        fields == null ? null : fields.nameArFather(),
        fields == null ? null : fields.nameArGrandfather(),
        fields == null ? null : fields.nameArGreatGrandfather(),
        fields == null ? null : fields.nameArMother(),
        fields == null ? null : fields.nameArMotherFather(),
        fields == null ? null : fields.nameArMotherGrandfather(),
        fields == null ? null : fields.nameArMotherGreatGrandfather(),
        fields == null ? null : fields.firstNamesEn(),
        fields == null ? null : fields.lastNameEn(),
        fields == null ? null : fields.sexRegistry(),
        fields == null ? null : fields.dateOfBirth(),
        fields == null ? null : fields.rawAddressAr(),
        // Which images Stage 9 can actually fetch, so the screen requests only what exists: a
        // passport has no doc_back, and portrait_registry appears only once the lookup succeeded.
        identityScanRepository.activeCycleArtifactKinds(profileId).stream()
            .filter(ScanImageKinds::isCustomerViewable)
            .toList());
  }

  /**
   * One Stage 9 review image. Read-only, and outside the Stage 9 state machine on purpose: this
   * answers "show me what was scanned", not "what may this profile do next", so it does not lock
   * the profile, does not touch the retry budget and cannot change a status.
   *
   * <p>No audit event is written. operator.md's "viewing an image is its own audit event" is a
   * control over which <em>operator</em> looked at whose document; a customer looking at their own
   * scan is not that, and the review screen re-renders and retries freely — an event per fetch
   * would add hash-chained volume with no evidentiary value. Product-owner decision, 2026-09-04.
   *
   * @return empty when the kind is not customer-viewable, there is no active cycle, no artifact of
   *     that kind, or its body has been purged. The caller renders all four as "not found": telling
   *     them apart would leak whether a given profile id exists.
   */
  public Optional<ScanArtifact> reviewImage(UUID profileId, String kind) {
    if (!ScanImageKinds.isCustomerViewable(kind)) {
      return Optional.empty();
    }
    return identityScanRepository.activeCycleArtifact(profileId, kind);
  }

  private void auditRejection(UUID profileId, UUID requestId, String reason, String detail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    payload.put("detail", detail);
    auditEventWriter.append(
        simpleEvent(profileId, requestId, EVENT_IDENTITY_SCAN_REJECTED, payload));
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

  /**
   * Writes the request-artifact event (only when there are request bytes, i.e. never against the
   * stub) and then the completed event, response artifact attached when there are response bytes.
   * Both under the same {@code requestId}, in that order, so the chain reads request-then-outcome —
   * S3-02's shape. Called inside the caller's transaction, where the completed event has always
   * lived: the lookup is one leg of a multi-row state change (the {@code registry_result} row, the
   * registry portrait, the {@code awaiting_registry} transition), so a rollback discards the
   * evidence together with the state it describes, and the customer's retry re-runs the lookup.
   *
   * <p>The payload never carries a value from the exchange — no national number, no field — because
   * {@code payload_json} is permanently hash-chained. Only the classification and its sizes: the
   * raw bytes live in the (purgeable) artifacts.
   *
   * @return the completed event's id, for the status-transition history row
   */
  private long auditRegistryLookup(
      UUID profileId, UUID requestId, RegistryOutcomeInternal outcome, boolean retried) {
    RegistryExchange exchange = outcome.exchange();
    if (exchange != null && exchange.requestBody() != null) {
      Map<String, Object> requestPayload = new LinkedHashMap<>();
      requestPayload.put("requestBytes", exchange.requestBody().length);
      auditEventWriter.appendWithArtifact(
          registryEvent(profileId, requestId, EVENT_REGISTRY_LOOKUP_REQUESTED, requestPayload),
          new AuditArtifact(
              REGISTRY_REQUEST_ARTIFACT_KIND, "application/json", exchange.requestBody()));
    }

    boolean responded = exchange != null && exchange.responseBody() != null;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("state", outcome.state());
    payload.put("retried", retried);
    payload.put("reason", outcome.reason());
    payload.put("httpStatus", responded ? exchange.httpStatus() : null);
    payload.put("responseMediaType", responded ? exchange.responseMediaType() : null);
    payload.put("responseBytes", responded ? exchange.responseBody().length : null);
    AuditEvent completed =
        registryEvent(profileId, requestId, EVENT_REGISTRY_LOOKUP_COMPLETED, payload);
    if (responded && exchange.responseBody().length > 0) {
      return auditEventWriter.appendWithArtifact(
          completed,
          new AuditArtifact(
              REGISTRY_RESPONSE_ARTIFACT_KIND,
              exchange.responseMediaType() == null
                  ? "application/octet-stream"
                  : exchange.responseMediaType(),
              exchange.responseBody()));
    }
    return auditEventWriter.append(completed);
  }

  private static AuditEvent registryEvent(
      UUID profileId, UUID requestId, String eventType, Map<String, Object> payload) {
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        eventType,
        "system",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  /**
   * doc_front_frame/doc_back_frame -- Uqudo's raw capture frames, distinct from the cropped
   * doc_front/doc_back documents. AD-004 (docs/components/persistence.md) deliberately excludes
   * these from body storage: they would roughly double the volume for no evidentiary gain, since
   * the cropped document is what the JWS actually attests to.
   */
  private static boolean isCaptureFrame(String kind) {
    return ParsedImage.DOC_FRONT_FRAME.equals(kind) || ParsedImage.DOC_BACK_FRAME.equals(kind);
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

  /**
   * One lookup's journey state plus the evidence the audit and {@code app.registry_result} keep:
   * the returned identity number (also on a mismatch), the classification reason, and the raw
   * exchange (null against the stub).
   */
  private record RegistryOutcomeInternal(
      String state,
      Optional<RegistryLookupResult> result,
      byte[] registryPortraitBytes,
      String identityNumberReturned,
      String reason,
      RegistryExchange exchange) {}
}
