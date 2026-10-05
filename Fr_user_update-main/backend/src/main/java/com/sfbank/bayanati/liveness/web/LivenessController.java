package com.sfbank.bayanati.liveness.web;

import com.sfbank.bayanati.liveness.domain.AuditTrailImageUnavailableException;
import com.sfbank.bayanati.liveness.domain.FaceJwsAlreadyAcceptedException;
import com.sfbank.bayanati.liveness.domain.FaceMatchAlreadyPassedException;
import com.sfbank.bayanati.liveness.domain.InvalidFaceSessionException;
import com.sfbank.bayanati.liveness.domain.LivenessRejectedException;
import com.sfbank.bayanati.liveness.domain.LivenessTemporarilyBlockedException;
import com.sfbank.bayanati.liveness.domain.NoAcceptedIdentityCycleException;
import com.sfbank.bayanati.liveness.domain.ProfileNotEditableException;
import com.sfbank.bayanati.liveness.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.liveness.domain.UnknownProfileException;
import com.sfbank.bayanati.liveness.service.FaceResultOutcome;
import com.sfbank.bayanati.liveness.service.FaceSessionIssuance;
import com.sfbank.bayanati.liveness.service.LivenessService;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stage 10's endpoints (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/liveness")
public class LivenessController {

  static final int MAX_CODE_LENGTH = 64;

  /**
   * Mirrors {@code IdentityScanController.MAX_JWS_LENGTH} — a compact JWS is three base64url
   * segments.
   */
  static final int MAX_JWS_LENGTH = 1_048_576;

  private final LivenessService livenessService;

  public LivenessController(LivenessService livenessService) {
    this.livenessService = livenessService;
  }

  @PostMapping("/token")
  public FaceTokenResponse token(@RequestBody FaceTokenRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    FaceSessionIssuance issuance = run(() -> livenessService.issueFaceSessionToken(profileId));
    return new FaceTokenResponse(
        profileId.toString(),
        issuance.accessToken(),
        issuance.faceSessionId(),
        issuance.usableUntil().toString());
  }

  @PostMapping("/result")
  public FaceResultResponse result(@RequestBody FaceResultRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String faceSessionId = clean(request.faceSessionId(), "faceSessionId", MAX_CODE_LENGTH);
    String jws = clean(request.jws(), "jws", MAX_JWS_LENGTH);

    FaceResultOutcome outcome =
        run(() -> livenessService.submitFaceResult(profileId, faceSessionId, jws));
    return FaceResultResponse.from(profileId.toString(), outcome);
  }

  @PostMapping("/terminated")
  public AckResponse terminated(@RequestBody LivenessTerminatedRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String faceSessionId = clean(request.faceSessionId(), "faceSessionId", MAX_CODE_LENGTH);
    String sdkErrorCode = clean(request.sdkErrorCode(), "sdkErrorCode", MAX_CODE_LENGTH);
    // BL-028: optional. Absent/blank means the SDK returned no partial artifact; present, it gets
    // the same hygiene as /result's jws before the service verifies it.
    String partialJws =
        request.partialJws() == null || request.partialJws().isBlank()
            ? null
            : clean(request.partialJws(), "partialJws", MAX_JWS_LENGTH);

    run(
        () -> {
          livenessService.reportLivenessTerminated(
              profileId, faceSessionId, sdkErrorCode, partialJws);
          return null;
        });
    return new AckResponse(profileId.toString());
  }

  /**
   * {@code 404} unknown profile, bare {@code 400} for a request the client got wrong.
   *
   * <p>S5-13 applied BL-033's and BL-037's treatment to this stage. The eight {@code 409}
   * conflicts, and the two {@code 400}s that spend an attempt, are deliberately NOT caught here:
   * they travel out of the controller method as their own types so the {@code @ExceptionHandler}
   * methods below can give each a distinguishable {@code code}. Catching them here would collapse
   * them again and leave those handlers unreachable.
   *
   * <p>{@link LivenessRejectedException} stays in this catch and gets no code. It is unreachable —
   * the type is declared and caught but has no throw site anywhere in the codebase (verified at
   * S5-13). Minting a code for a path nothing can reach would document a screen that can never be
   * shown; removing the type is a cleanup S5-13 was not scoped to make.
   */
  private static <T> T run(Supplier<T> call) {
    try {
      return call.get();
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    } catch (LivenessRejectedException | InvalidFaceSessionException rejected) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.getMessage());
    }
  }

  // ---- Stage 10 conflicts (S5-13, following BL-033) --------------------------------------------
  //
  // All eight stay 409: the status is what the app's existing mapper switches on, and moving any
  // of them would break it. What changes is that the body now carries a machine-readable `code`,
  // so a client can tell "you already passed, go to stage 11" from "wait 24 hours" from "rescan".
  //
  // Why ProblemDetail and not ResponseStatusException's reason: the reason string is discarded
  // before it reaches the wire on this Spring Boot version -- proven live at BL-033 against a real
  // 409 from IdentityScanController, capture in docs/sessions/2026-09-04-bl033-error-contract.md.
  // A ProblemDetail extension property does render, as a top-level member.
  //
  // `detail` is a fixed generic string per code, never the exception's own message: those messages
  // embed the profile id, and AD-002a's rule is that the wire carries a code, never a message.
  //
  // The helpers at the foot of this class are duplicated from IdentityScanController rather than
  // shared, matching how parseProfileId/clean are already duplicated across all four customer
  // controllers -- CLAUDE.md makes features the top-level cut, and a shared `web` package would be
  // neither a feature nor an outbound integration.

  /**
   * The journey is over (approved/rejected/submitted/mismatch) -- the app re-runs the launch check.
   */
  @ExceptionHandler(ProfileNotEditableException.class)
  ResponseEntity<ProblemDetail> profileTerminal(ProfileNotEditableException e) {
    return conflict("PROFILE_TERMINAL", "this profile can no longer be edited");
  }

  /** The stage-10 budget is spent -- 24-hour block, with the moment it lifts. */
  @ExceptionHandler(LivenessTemporarilyBlockedException.class)
  ResponseEntity<ProblemDetail> livenessBlocked(LivenessTemporarilyBlockedException e) {
    ProblemDetail problem =
        problemDetail("LIVENESS_BLOCKED", "liveness checking is temporarily unavailable");
    // Null-guarded for the same reason IdentityScanController.scanBlocked is: the field is
    // nullable at construction, and an unguarded toString() here would turn this 409 into a 500.
    // A client that gets no blockedUntil falls back to its generic block copy.
    Instant blockedUntil = e.blockedUntil();
    if (blockedUntil != null) {
      problem.setProperty("blockedUntil", blockedUntil.toString());
    }
    // BL-118, mirroring IdentityScanController.scanBlocked. Additive on the UNCHANGED
    // LIVENESS_BLOCKED code; omitted entirely when null. See LivenessBlockReason.
    if (e.blockReason() != null) {
      problem.setProperty("blockReason", e.blockReason().name());
    }
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  /**
   * Face-match already passed for this cycle -- the app moves on to Stage 11, it does not retry.
   */
  @ExceptionHandler(FaceMatchAlreadyPassedException.class)
  ResponseEntity<ProblemDetail> alreadyPassed(FaceMatchAlreadyPassedException e) {
    return conflict("LIVENESS_ALREADY_PASSED", "face-match has already passed for this profile");
  }

  /**
   * Two conditions, two codes -- the same split BL-033 made for {@code
   * NoActiveRegistryReviewException}.
   *
   * <p>{@code RESCAN_REQUIRED}: the accepted cycle exists but its reference portrait was purged
   * (S5-06), so liveness has nothing to match against and the customer must scan again. {@code
   * STATE_CONFLICT}: no accepted cycle at all, which a correct client cannot reach -- the app
   * re-syncs through the Stage 13 resume.
   */
  @ExceptionHandler(NoAcceptedIdentityCycleException.class)
  ResponseEntity<ProblemDetail> noAcceptedCycle(NoAcceptedIdentityCycleException e) {
    return e.rescanRequired()
        ? conflict("RESCAN_REQUIRED", "a fresh document scan is required before liveness")
        : conflict("STATE_CONFLICT", "this action does not match the profile's current state");
  }

  /** A genuine replay, or a concurrent retry that lost the race -- re-sync via Stage 13. */
  @ExceptionHandler(FaceJwsAlreadyAcceptedException.class)
  ResponseEntity<ProblemDetail> faceJwsAlreadyAccepted(FaceJwsAlreadyAcceptedException e) {
    return conflict("STATE_CONFLICT", "this action does not match the profile's current state");
  }

  /**
   * Paused on a Civil Registry retry -- progress intact, the app shows the Stage 9 pause screen.
   */
  @ExceptionHandler(RegistryReviewPendingException.class)
  ResponseEntity<ProblemDetail> registryPending(RegistryReviewPendingException e) {
    return conflict("REGISTRY_PENDING", "a Civil Registry retry is in progress");
  }

  /**
   * The face capture aged out before it could be used. No attempt is spent (LivenessService's
   * R-012/R-021 ordering rule) -- the customer simply runs the check again.
   */
  @ExceptionHandler(ArtifactExpiredException.class)
  ResponseEntity<ProblemDetail> artifactExpired(ArtifactExpiredException e) {
    return conflict("ARTIFACT_EXPIRED", "this capture has expired");
  }

  /**
   * Same screen as {@code ARTIFACT_EXPIRED}, kept a distinct code for the same reason BL-033 kept
   * {@code ARTIFACT_EXPIRED} distinct from {@code IMAGES_UNAVAILABLE}: the match verified but its
   * audit-trail image is gone, so the attempt cannot be recorded. Also not counted against the
   * budget.
   */
  @ExceptionHandler(AuditTrailImageUnavailableException.class)
  ResponseEntity<ProblemDetail> auditTrailUnavailable(AuditTrailImageUnavailableException e) {
    return conflict("AUDIT_TRAIL_UNAVAILABLE", "this capture can no longer be used");
  }

  // ---- Stage 10's budget-spending rejections (S5-13, following BL-037) --------------------------
  //
  // The 400-side analogue, and the same rule BL-037 established for Stage 8, now extended to this
  // stage: on a Stage 10 400, a `code` on the body means AN ATTEMPT WAS SPENT; a bare 400 means the
  // request was bad. Exactly two of this stage's four 400 paths spend one of the customer's five
  // liveness attempts -- the two handled here, both of which reach this point having already been
  // counted by LivenessService.recordFailedAttempt. InvalidFaceSessionException spends nothing and
  // stays bare, as do the pre-service validation failures raised by parseProfileId/clean.
  //
  // An app that cannot tell the two groups apart misreports how many attempts remain, and the only
  // way it could tell would be a client-side counter -- exactly what LivenessAttemptBudget's
  // server-side placement and AD-002a exist to prevent.
  //
  // One code covers both causes rather than two, matching SCAN_REJECTED: customer.md Stage 10 gives
  // a failed check a single generic failure screen, so both produce the same screen, the same copy
  // and the same remedy. The two causes stay distinguishable server-side, where the distinction is
  // actually used, through their separate audit events.

  /**
   * The backend examined the face result and refused it -- one liveness attempt is gone.
   *
   * <p>{@link JwsVerificationException} is a signature or claim failure; {@link
   * ImageIntegrityException} is an audit-trail-image checksum mismatch, which {@code
   * LivenessService} counts against the budget for the same reason (uqudo-sdk.md: "a mismatch is a
   * hard failure").
   */
  @ExceptionHandler({JwsVerificationException.class, ImageIntegrityException.class})
  ResponseEntity<ProblemDetail> livenessRejected(RuntimeException e) {
    return badRequest("LIVENESS_REJECTED", "this liveness check could not be verified");
  }

  private static ResponseEntity<ProblemDetail> conflict(String code, String detail) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(problemDetail(HttpStatus.CONFLICT, code, detail));
  }

  private static ResponseEntity<ProblemDetail> badRequest(String code, String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(problemDetail(HttpStatus.BAD_REQUEST, code, detail));
  }

  private static ProblemDetail problemDetail(String code, String detail) {
    return problemDetail(HttpStatus.CONFLICT, code, detail);
  }

  private static ProblemDetail problemDetail(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }

  private static UUID parseProfileId(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is not a valid UUID");
    }
  }

  private static String clean(String value, String fieldName, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    if (trimmed.length() > maxLength) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is longer than " + maxLength + " characters");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (Character.isISOControl(trimmed.charAt(i))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, fieldName + " contains a control character");
      }
    }
    return trimmed;
  }
}
