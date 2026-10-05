package com.sfbank.bayanati.submission.web;

import com.sfbank.bayanati.submission.domain.LivenessNotCompleteException;
import com.sfbank.bayanati.submission.domain.NotInFinalStagesException;
import com.sfbank.bayanati.submission.domain.ProfileNotEligibleException;
import com.sfbank.bayanati.submission.domain.SignatureMissingException;
import com.sfbank.bayanati.submission.domain.UnknownProfileException;
import com.sfbank.bayanati.submission.service.JourneyPointer;
import com.sfbank.bayanati.submission.service.SubmissionOutcome;
import com.sfbank.bayanati.submission.service.SubmissionService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stage 12's endpoint (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/submission")
public class SubmissionController {

  private final SubmissionService submissionService;

  public SubmissionController(SubmissionService submissionService) {
    this.submissionService = submissionService;
  }

  @PostMapping
  public SubmissionResponse submit(@RequestBody SubmissionRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    // S5-13: only the 404 is collapsed here. The three 409s travel out as their own types so the
    // @ExceptionHandler methods below can give each a distinguishable `code`.
    SubmissionOutcome outcome;
    try {
      outcome = submissionService.submit(profileId);
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    }
    return SubmissionResponse.from(outcome);
  }

  /**
   * Journey Stage 13's resume read for Stages 10-12 (S5-13) — the app asks "where am I" and gets an
   * answer, including "already complete", which is customer.md l.1112-1114's own vocabulary.
   *
   * <p>{@code POST} for a read, deliberately, for the reason S5-11 gives at {@code
   * /registry-review/current}: a {@code GET} would put the profile id in the request line, and so
   * into every access log and proxy along the way, on a surface unauthenticated by design (R-051).
   *
   * <p>Read-only — see {@code SubmissionService#currentJourneyPointer}. No external call, no write,
   * no status transition, no budget draw, no audit event.
   *
   * <p><strong>Binding note for S5-08.</strong> The confirmation screen must source {@code
   * verifiedChannels} from THIS endpoint on every path, including immediately after a successful
   * submit — not from {@code SubmissionResponse.verifiedChannels}, which correctly reports only
   * what this call enqueued and is therefore empty on an idempotent re-submit. One extra call on
   * the happy path is the accepted cost of the screen having one source of truth on all three entry
   * paths. Branching on "did I get a non-empty list from the submit response" reintroduces exactly
   * the two-sources bug this endpoint exists to remove.
   */
  @PostMapping("/current")
  public JourneyPointerResponse current(@RequestBody SubmissionRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    JourneyPointer pointer;
    try {
      pointer = submissionService.currentJourneyPointer(profileId);
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    }
    return JourneyPointerResponse.from(pointer);
  }

  // ---- Stage 12's error contract (S5-13, following BL-033) -------------------------------------
  //
  // Statuses are held exactly as they were; the body now carries a machine-readable `code`.
  // `detail` is a fixed generic string per code, never the exception's own message -- those embed
  // the profile id, and AD-002a's rule is that the wire carries a code, never a message.

  /**
   * Two conditions, two codes.
   *
   * <p>{@code PROFILE_TERMINAL}: the pre-check found a genuinely terminal status and the journey is
   * over. {@code STATE_CONFLICT}: the profile left {@code in_progress} between the pre-check and
   * the lock, and the status it landed in need not be terminal at all — a concurrent {@code
   * reportLivenessTerminated} can move it to {@code blocked_liveness}, which is temporary. Telling
   * that customer their journey was over would be a lie, so it gets the re-sync code instead.
   */
  @ExceptionHandler(ProfileNotEligibleException.class)
  ResponseEntity<ProblemDetail> notEligible(ProfileNotEligibleException e) {
    return e.terminal()
        ? conflict("PROFILE_TERMINAL", "this profile can no longer be edited")
        : conflict("STATE_CONFLICT", "this action does not match the profile's current state");
  }

  /** The customer is not in Stages 10-12 at all — the app re-syncs through the earlier stages. */
  @ExceptionHandler(NotInFinalStagesException.class)
  ResponseEntity<ProblemDetail> notInFinalStages(NotInFinalStagesException e) {
    return conflict("STATE_CONFLICT", "this action does not match the profile's current state");
  }

  /** Stage 10 has not passed yet. */
  @ExceptionHandler(LivenessNotCompleteException.class)
  ResponseEntity<ProblemDetail> livenessRequired(LivenessNotCompleteException e) {
    return conflict("LIVENESS_REQUIRED", "the liveness check must pass before submission");
  }

  /** Stage 11 has not been captured yet. */
  @ExceptionHandler(SignatureMissingException.class)
  ResponseEntity<ProblemDetail> signatureRequired(SignatureMissingException e) {
    return conflict("SIGNATURE_REQUIRED", "a signature must be captured before submission");
  }

  private static ResponseEntity<ProblemDetail> conflict(String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
    problem.setProperty("code", code);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
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
}
