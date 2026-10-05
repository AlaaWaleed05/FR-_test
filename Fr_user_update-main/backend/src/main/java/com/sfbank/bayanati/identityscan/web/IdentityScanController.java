package com.sfbank.bayanati.identityscan.web;

import com.sfbank.bayanati.identityscan.domain.IdentityScanRejectedException;
import com.sfbank.bayanati.identityscan.domain.ImagesUnavailableForAcceptanceException;
import com.sfbank.bayanati.identityscan.domain.InvalidScanSessionException;
import com.sfbank.bayanati.identityscan.domain.JwsAlreadyAcceptedException;
import com.sfbank.bayanati.identityscan.domain.NoActiveRegistryReviewException;
import com.sfbank.bayanati.identityscan.domain.ProfileNotEditableException;
import com.sfbank.bayanati.identityscan.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.identityscan.domain.ScanTemporarilyBlockedException;
import com.sfbank.bayanati.identityscan.domain.ScanTypeExhaustedException;
import com.sfbank.bayanati.identityscan.domain.UnknownProfileException;
import com.sfbank.bayanati.identityscan.service.IdentityScanService;
import com.sfbank.bayanati.identityscan.service.ScanDisplayPayload;
import com.sfbank.bayanati.identityscan.service.TokenIssuance;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stages 8 and 9's endpoints (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/identity-scan")
public class IdentityScanController {

  static final int MAX_CODE_LENGTH = 32;

  /**
   * A compact JWS is three base64url segments. R-024 (whether the real JWS embeds {@code faceImage}
   * as base64 bytes or only a {@code faceImageId} reference) is unresolved until S1-02 — sized
   * generously against the base64-embedded-image reading of that open question, not just a
   * metadata-only payload.
   */
  static final int MAX_JWS_LENGTH = 1_048_576;

  private final IdentityScanService identityScanService;

  public IdentityScanController(IdentityScanService identityScanService) {
    this.identityScanService = identityScanService;
  }

  @PostMapping("/token")
  public TokenResponse token(@RequestBody TokenRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String documentType = clean(request.documentType(), "documentType", MAX_CODE_LENGTH);

    TokenIssuance issuance = run(() -> identityScanService.issueToken(profileId, documentType));
    return new TokenResponse(
        profileId.toString(),
        issuance.accessToken(),
        issuance.sessionId(),
        issuance.nonce(),
        issuance.documentType(),
        issuance.usableUntil().toString());
  }

  @PostMapping("/scan-result")
  public ScanDisplayResponse scanResult(@RequestBody ScanResultRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String sessionId = clean(request.sessionId(), "sessionId", 64);
    String nonce = clean(request.nonce(), "nonce", 64);
    String documentType = clean(request.documentType(), "documentType", MAX_CODE_LENGTH);
    String jws = clean(request.jws(), "jws", MAX_JWS_LENGTH);

    ScanDisplayPayload payload =
        run(() -> identityScanService.submitScan(profileId, sessionId, nonce, documentType, jws));
    return ScanDisplayResponse.from(payload);
  }

  @PostMapping("/cancel")
  public AckResponse cancel(@RequestBody CancelScanRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String documentType = clean(request.documentType(), "documentType", MAX_CODE_LENGTH);

    run(
        () -> {
          identityScanService.cancelScan(profileId, documentType);
          return null;
        });
    return new AckResponse(profileId.toString());
  }

  @PostMapping("/registry-review/accept")
  public AckResponse acceptReview(@RequestBody ProfileIdRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    run(
        () -> {
          identityScanService.acceptRegistryReview(profileId);
          return null;
        });
    return new AckResponse(profileId.toString());
  }

  @PostMapping("/registry-review/wrong-number")
  public WrongNumberResponse wrongNumber(@RequestBody ProfileIdRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    var blockedUntil = run(() -> identityScanService.reportWrongNumber(profileId));
    return new WrongNumberResponse(
        profileId.toString(), blockedUntil.map(Object::toString).orElse(null));
  }

  @PostMapping("/registry-review/wrong-details")
  public AckResponse wrongDetails(@RequestBody ProfileIdRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    run(
        () -> {
          identityScanService.reportWrongDetails(profileId);
          return null;
        });
    return new AckResponse(profileId.toString());
  }

  /**
   * Stage 9's display payload, read back from what is already stored — S5-11, the read half of
   * customer.md Stage 13's resume. Returns exactly what {@code /scan-result} and {@code
   * /registry-review/retry} return, without their live Civil Registry call and without writing
   * anything.
   *
   * <p>{@code POST} for a read, deliberately. Every other endpoint here is a POST, and a {@code
   * GET} would put the profile id in the request line — and so in every access log and proxy along
   * the way — on a surface that is unauthenticated by design (R-051). The {@code GET /image/{kind}}
   * endpoint above carries that cost because an image URL has no other shape; a JSON read does.
   *
   * <p>A registry result that is not ready yet is a {@code 200} with {@code registryReady=false},
   * not a conflict: see {@code IdentityScanService#currentReviewPayload}.
   */
  @PostMapping("/registry-review/current")
  public ScanDisplayResponse currentReview(@RequestBody ProfileIdRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    ScanDisplayPayload payload = run(() -> identityScanService.currentReviewPayload(profileId));
    return ScanDisplayResponse.from(payload);
  }

  @PostMapping("/registry-review/retry")
  public ScanDisplayResponse retry(@RequestBody ProfileIdRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    ScanDisplayPayload payload = run(() -> identityScanService.retryRegistryLookup(profileId));
    return ScanDisplayResponse.from(payload);
  }

  /**
   * One Stage 9 review image — the document front or back, the portrait Uqudo extracted from it, or
   * the Civil Registry's own photograph. customer.md Stage 9 shows these beside the registry data.
   *
   * <p>Bytes, not a URL — and that choice is now settled rather than provisional. R-046 (signed URL
   * versus blob fetch for the <em>back office</em>) CLOSED on 2026-09-13 by refuting its own
   * premise: the back office is same-origin with the API and authenticates with a session cookie,
   * so its images are an ordinary cookie-authenticated GET too ({@code
   * operator.web.ProfileImageController}, BL-075). This endpoint is unaffected either way — its
   * client is Flutter, which sets its own headers and renders from bytes — and {@code
   * artifact_ref.storage_key} stayed untouched and is now dead.
   *
   * <p>Everything absent is one {@code 404} — an unknown profile, an unknown or non-viewable kind,
   * a passport's missing back, a purged body. Distinguishing them would leak whether a profile id
   * exists to anyone who can guess one.
   *
   * <p>{@code no-store}: these are identity documents, and neither a proxy nor a disk cache should
   * keep a copy the journey cannot later purge.
   */
  @GetMapping("/image/{kind}")
  public ResponseEntity<byte[]> reviewImage(
      @PathVariable String kind, @RequestParam("profileId") String profileIdValue) {
    UUID profileId = parseProfileId(profileIdValue);
    String requestedKind = clean(kind, "kind", MAX_CODE_LENGTH);

    return identityScanService
        .reviewImage(profileId, requestedKind)
        .map(
            artifact ->
                ResponseEntity.ok()
                    .contentType(mediaType(artifact.contentType()))
                    .cacheControl(CacheControl.noStore())
                    .body(artifact.bytes()))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "image not available"));
  }

  /**
   * The content type recorded when the artifact was stored. Falls back to {@code
   * application/octet-stream} rather than guessing: a stored type that no longer parses is a data
   * problem, and inventing {@code image/jpeg} over it would be a guess presented as a fact.
   */
  private static MediaType mediaType(String storedContentType) {
    if (storedContentType == null || storedContentType.isBlank()) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    try {
      return MediaType.parseMediaType(storedContentType);
    } catch (InvalidMediaTypeException notParseable) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  // ---- Stage 8/9 conflicts (BL-033) ------------------------------------------------------------
  //
  // All eight stay 409: the status is what the app's existing mapper switches on, and moving any of
  // them would break it. What changes is that the body now carries a machine-readable `code`, so a
  // client can tell "your journey is over" from "wait 24 hours" from "the registry is not ready".
  //
  // Why ProblemDetail and not ResponseStatusException's reason: the reason string is discarded
  // before it reaches the wire. Proven live against this Spring Boot version (4.1.0) -- a real 409
  // from this controller returns exactly {timestamp,status,error,path} and nothing else. A
  // ProblemDetail extension property does render, as a top-level member, with the content type
  // switching to application/problem+json. Both captures are in
  // docs/sessions/2026-09-04-bl033-error-contract.md.
  //
  // `detail` is a fixed generic string per code, never the exception's own message: those messages
  // embed the profile id, and AD-002a's rule is that the wire carries a code, never a message.

  /**
   * The journey is over (approved/rejected/submitted/mismatch) -- the app re-runs the launch check.
   */
  @ExceptionHandler(ProfileNotEditableException.class)
  ResponseEntity<ProblemDetail> profileTerminal(ProfileNotEditableException e) {
    return conflict("PROFILE_TERMINAL", "this profile can no longer be edited");
  }

  /** Both scan budgets spent -- 24-hour block, with the moment it lifts. */
  @ExceptionHandler(ScanTemporarilyBlockedException.class)
  ResponseEntity<ProblemDetail> scanBlocked(ScanTemporarilyBlockedException e) {
    ProblemDetail problem =
        problemDetail("SCAN_BLOCKED", "document scanning is temporarily unavailable");
    // Null-guarded deliberately. The field is nullable at construction and the service itself
    // branches on a null scanBlockedUntil elsewhere; an unguarded toString() here would turn this
    // 409 into a 500. A client that gets no blockedUntil falls back to its generic block copy.
    Instant blockedUntil = e.blockedUntil();
    if (blockedUntil != null) {
      problem.setProperty("blockedUntil", blockedUntil.toString());
    }
    // BL-118. Additive alongside blockedUntil, on the UNCHANGED SCAN_BLOCKED code -- see
    // ScanBlockReason for why this is not a new code, and why its absence means "this arm does not
    // know" rather than "an ordinary block". Omitted entirely when null, so an app that does not
    // read it sees byte-identical bodies to the ones it sees today.
    if (e.blockReason() != null) {
      problem.setProperty("blockReason", e.blockReason().name());
    }
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  /** This document type's budget is spent; the other type may still be offered (Stage 7). */
  @ExceptionHandler(ScanTypeExhaustedException.class)
  ResponseEntity<ProblemDetail> scanTypeExhausted(ScanTypeExhaustedException e) {
    return conflict("SCAN_TYPE_EXHAUSTED", "no scan attempts remain for this document type");
  }

  /** The stored capture has no usable images -- the customer scans again. */
  @ExceptionHandler(ImagesUnavailableForAcceptanceException.class)
  ResponseEntity<ProblemDetail> imagesUnavailable(ImagesUnavailableForAcceptanceException e) {
    return conflict("IMAGES_UNAVAILABLE", "this capture can no longer be used");
  }

  /** Same screen as IMAGES_UNAVAILABLE: the capture aged out before it was accepted. */
  @ExceptionHandler(ArtifactExpiredException.class)
  ResponseEntity<ProblemDetail> artifactExpired(ArtifactExpiredException e) {
    return conflict("ARTIFACT_EXPIRED", "this capture has expired");
  }

  /** Paused on a Civil Registry retry -- progress intact, the app shows the pause screen. */
  @ExceptionHandler(RegistryReviewPendingException.class)
  ResponseEntity<ProblemDetail> registryPending(RegistryReviewPendingException e) {
    return conflict("REGISTRY_PENDING", "a Civil Registry retry is in progress");
  }

  /**
   * Two conditions, two codes -- the BL-033 split.
   *
   * <p>{@code REGISTRY_NOT_READY}: the cycle exists but its registry result is not {@code ok} yet.
   * The customer is legitimately paused and Stage 9 gives that its own screen; the client has
   * usually raced its own retry. {@code STATE_CONFLICT}: no active cycle at all, which a correct
   * client cannot reach -- the app re-syncs through the Stage 13 resume instead of showing a pause.
   */
  @ExceptionHandler(NoActiveRegistryReviewException.class)
  ResponseEntity<ProblemDetail> noActiveRegistryReview(NoActiveRegistryReviewException e) {
    return e.registryNotReady()
        ? conflict("REGISTRY_NOT_READY", "the Civil Registry result is not ready yet")
        : conflict("STATE_CONFLICT", "this action does not match the profile's current state");
  }

  /** A genuine replay, or a concurrent retry that lost the race -- re-sync via Stage 13. */
  @ExceptionHandler(JwsAlreadyAcceptedException.class)
  ResponseEntity<ProblemDetail> jwsAlreadyAccepted(JwsAlreadyAcceptedException e) {
    return conflict("STATE_CONFLICT", "this action does not match the profile's current state");
  }

  // ---- Stage 8's budget-spending rejections (BL-037) -------------------------------------------
  //
  // The 400-side analogue of BL-033. Stage 8 answers 400 on ten distinct paths, and until this they
  // were byte-identical on the wire. Exactly TWO of them SPEND one of the customer's limited
  // per-type attempts -- the two handled here. The other eight spend nothing: the five pre-service
  // validation failures raised by parseProfileId/clean, an unparseable request body, and
  // IdentityScanRejectedException and InvalidScanSessionException in run() below. An app that
  // cannot tell the two groups apart either shows the wrong screen
  // or misreports how many attempts remain, and the only way it could tell would be a client-side
  // counter, which is exactly what ScanAttemptBudget's server-side placement and AD-002a exist to
  // prevent.
  //
  // The rule this establishes, and the whole of what a client needs to know: on a Stage 8 400, a
  // `code` on the body means AN ATTEMPT WAS SPENT; a bare 400 means the request was bad.
  //
  // Status stays 400 (see run()'s note). One code covers both causes rather than two: customer.md
  // l.666-667 gives a rejected scan a single generic failure screen, so both produce the same
  // screen, the same copy and the same remedy -- rescan. The two causes stay distinguishable
  // server-side, where that distinction is actually used, through their separate audit events
  // (scan_jws_rejected, scan_image_integrity_failed).

  /**
   * The backend examined the scan and refused it -- one attempt is gone (customer.md Stage 8).
   *
   * <p>{@link JwsVerificationException} is a signature or claim failure; {@link
   * ImageIntegrityException} is a document-image checksum mismatch, which {@code
   * IdentityScanService} counts against the budget for the same reason (uqudo-sdk.md: "a mismatch
   * is a hard failure"). Both reach here having already been counted by {@code
   * recordFailedAttempt}.
   */
  @ExceptionHandler({JwsVerificationException.class, ImageIntegrityException.class})
  ResponseEntity<ProblemDetail> scanRejected(RuntimeException e) {
    return badRequest("SCAN_REJECTED", "this scan could not be verified");
  }

  private static ResponseEntity<ProblemDetail> conflict(String code, String detail) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail(code, detail));
  }

  /** BL-037's sibling of {@link #conflict}. Added beside it, deliberately not in place of it. */
  private static ResponseEntity<ProblemDetail> badRequest(String code, String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(problemDetail(HttpStatus.BAD_REQUEST, code, detail));
  }

  /**
   * The BL-033 shape, unchanged: every one of the eight 409s still builds through this overload and
   * still gets {@code HttpStatus.CONFLICT}. BL-037 generalised the status ADDITIVELY rather than
   * parameterising this method's own signature, so no existing conflict could be moved by accident.
   */
  private static ProblemDetail problemDetail(String code, String detail) {
    return problemDetail(HttpStatus.CONFLICT, code, detail);
  }

  private static ProblemDetail problemDetail(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }

  /**
   * {@code 404} unknown profile, bare {@code 400} for a request the client got wrong.
   *
   * <p>The eight {@code 409} conflicts are deliberately NOT caught here. They travel out of the
   * controller method as their own types so the {@code @ExceptionHandler} methods above can give
   * each one a distinguishable {@code code} — BL-033. Catching them here would collapse them again
   * and leave those handlers unreachable.
   *
   * <p>BL-037 removed {@link JwsVerificationException} and {@link ImageIntegrityException} from the
   * clause below for the same reason, and added {@link #scanRejected} in the same edit — removing
   * either without its handler would have made it an unmapped {@code 500}, not a {@code 400}.
   *
   * <p>What is left here is a document type the service refuses, and a sessionId/nonce that do not
   * match what {@code issueToken} issued. Neither spends an attempt, so neither carries a code —
   * and the status stays {@code 400} for both kinds.
   *
   * <p>The second of those was described here as something "a correct client cannot provoke against
   * a backend it is in step with". BL-039 made that false: the pending session is now single-use,
   * consumed the moment an attempt is spent, so a correct client whose acknowledgement was lost
   * after a REJECTED scan re-posts a triple the backend has already retired and gets this bare
   * {@code 400}. The refusal is right — the attempt was spent and must not be spent twice — but a
   * bare 400 routes that client back to the upload-retry screen rather than to a rescan. Filed as
   * BL-064; deliberately not fixed by giving this exception a code, because BL-037's rule that a
   * {@code code} on a Stage 8 400 means AN ATTEMPT WAS SPENT is what makes the coded ones useful.
   * Splitting the spending rejections off by STATUS rather than by code would be a redesign of the
   * Stage 8 contract, and no client exists yet that a status change would serve.
   */
  private static <T> T run(Supplier<T> call) {
    try {
      return call.get();
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    } catch (IdentityScanRejectedException | InvalidScanSessionException rejected) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.getMessage());
    }
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
