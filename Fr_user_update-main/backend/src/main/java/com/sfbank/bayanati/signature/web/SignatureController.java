package com.sfbank.bayanati.signature.web;

import com.sfbank.bayanati.signature.domain.LivenessNotCompleteException;
import com.sfbank.bayanati.signature.domain.ProfileNotEditableException;
import com.sfbank.bayanati.signature.domain.SignatureRejectedException;
import com.sfbank.bayanati.signature.domain.UnknownProfileException;
import com.sfbank.bayanati.signature.service.SignatureService;
import java.util.Base64;
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

/** Journey Stage 11's endpoint (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/signature")
public class SignatureController {

  /**
   * Base64 inflates by ~4/3 — sized generously above {@code SignatureService.MAX_BYTES} (5 MB) so
   * the service's own size check, not this one, is what actually rejects an oversized signature.
   */
  public static final int MAX_BASE64_LENGTH = 8_000_000;

  static final int MAX_CODE_LENGTH = 64;

  private final SignatureService signatureService;

  public SignatureController(SignatureService signatureService) {
    this.signatureService = signatureService;
  }

  @PostMapping
  public AckResponse submit(@RequestBody SignatureRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    String captureMethod = clean(request.captureMethod(), "captureMethod", MAX_CODE_LENGTH);
    String contentType = clean(request.contentType(), "contentType", MAX_CODE_LENGTH);
    String contentBase64 = clean(request.contentBase64(), "contentBase64", MAX_BASE64_LENGTH);

    byte[] content;
    try {
      content = Base64.getDecoder().decode(contentBase64);
    } catch (IllegalArgumentException malformed) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "contentBase64 is not valid base64");
    }

    // S5-13: only the 404 is collapsed here. The two 409s and the 400 travel out as their own
    // types so the @ExceptionHandler methods below can give each a distinguishable `code`.
    try {
      signatureService.submitSignature(profileId, captureMethod, contentType, content);
    } catch (UnknownProfileException unknownProfile) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknownProfile.getMessage());
    }
    return new AckResponse(profileId.toString());
  }

  // ---- Stage 11's error contract (S5-13, following BL-033/BL-037) ------------------------------
  //
  // Statuses are held exactly as they were; what changes is that the body now carries a
  // machine-readable `code`. `detail` is a fixed generic string per code, never the exception's own
  // message -- those embed the profile id, and AD-002a's rule is that the wire carries a code.
  //
  // On the 400: BL-037's rule that "a `code` on a 400 means an attempt was spent" is scoped to the
  // stages that HAVE an attempt budget (8 and 10). Stage 11 has none -- there is no counter for a
  // client to misread -- so a code here carries no such implication. It earns its place because one
  // of SignatureRejectedException's four causes, content over SignatureService.MAX_BYTES, is a
  // screen a customer can legitimately reach by drawing a large signature, and "too large" and
  // "wrong content type" want different copy.

  /** The journey is over -- the app re-runs the launch check. */
  @ExceptionHandler(ProfileNotEditableException.class)
  ResponseEntity<ProblemDetail> profileTerminal(ProfileNotEditableException e) {
    return conflict("PROFILE_TERMINAL", "this profile can no longer be edited");
  }

  /** Stage 10 has not passed yet -- the app sends the customer back to the liveness check. */
  @ExceptionHandler(LivenessNotCompleteException.class)
  ResponseEntity<ProblemDetail> livenessRequired(LivenessNotCompleteException e) {
    return conflict("LIVENESS_REQUIRED", "the liveness check must pass before a signature");
  }

  /**
   * The signature itself was refused -- wrong capture method, content type, empty, or too large.
   */
  @ExceptionHandler(SignatureRejectedException.class)
  ResponseEntity<ProblemDetail> signatureRejected(SignatureRejectedException e) {
    return badRequest("SIGNATURE_REJECTED", "this signature could not be accepted");
  }

  private static ResponseEntity<ProblemDetail> conflict(String code, String detail) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(problemDetail(HttpStatus.CONFLICT, code, detail));
  }

  private static ResponseEntity<ProblemDetail> badRequest(String code, String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(problemDetail(HttpStatus.BAD_REQUEST, code, detail));
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
