package com.sfbank.bayanati.accountcheck.web;

import com.sfbank.bayanati.accountcheck.service.AccountCheckResult;
import com.sfbank.bayanati.accountcheck.service.AccountCheckService;
import com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stage 1a's single endpoint. */
@RestController
@RequestMapping("/api/v1/account-check")
public class AccountCheckController {

  /**
   * A generous upper bound, not a format rule — see {@link #clean}. Account numbers and branch
   * codes in the seeded reference data are far shorter; this exists so an unauthenticated request
   * cannot write an arbitrarily large value into a table that is never deleted from.
   */
  static final int MAX_FIELD_LENGTH = 64;

  private final AccountCheckService accountCheckService;

  public AccountCheckController(AccountCheckService accountCheckService) {
    this.accountCheckService = accountCheckService;
  }

  /**
   * Checks one account against the core banking middleware.
   *
   * <p>Always {@code 200} when the check produced an answer. Not-found is a business outcome of the
   * journey, not a transport error: a {@code 404} for an unknown account would make the app infer
   * from a status code what the body already states plainly, and would collide with a genuinely
   * missing endpoint.
   *
   * <p>{@code 400} is reserved for a request that never reached the core banking system. Such an
   * attempt is not audited — the journey requires <em>account-check attempts</em> recorded, and
   * nothing was attempted against the core.
   *
   * <p>{@code 503} (see {@link #coreBankingUnavailable}) means the middleware gave no usable
   * answer, including its own {@code -1} "System Error". The attempt <em>was</em> audited.
   */
  @PostMapping
  public AccountCheckResponse check(@RequestBody AccountCheckRequest request) {
    String branch = clean(request.branch(), "branch");
    String accountNumber = clean(request.accountNumber(), "accountNumber");

    AccountCheckResult result = accountCheckService.check(branch, accountNumber);

    return new AccountCheckResponse(
        result.outcome(),
        result.continuation(),
        result.requestId().toString(),
        result.blockedUntil() == null ? null : result.blockedUntil().toString());
  }

  /**
   * The core banking middleware produced no usable answer (no response, unreadable body, or its own
   * {@code -1} "System Error") — S3-02, closing the documented half of BL-008.
   *
   * <p>{@code 503 Service Unavailable}, not a {@code 200} with a new outcome value: an outage is
   * not something the customer can act on by correcting their input, and a new wire value would
   * have forced the app's exhaustive outcome switches to change. The app maps any 5xx to its "could
   * not reach the server, try again" state on the entry screen and to "backend unreachable, resume
   * offline" at launch — both correct here. No {@code Retry-After}: nothing is known about how long
   * the middleware stays down (OQ-025).
   */
  @ExceptionHandler(CoreBankingUnavailableException.class)
  ResponseEntity<ProblemDetail> coreBankingUnavailable(CoreBankingUnavailableException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "the core banking system is unavailable; try again later");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
  }

  /**
   * The only bound on what reaches the core banking system and the audit trail from an
   * unauthenticated request.
   *
   * <p><strong>Trims first, then checks.</strong> The obvious order is wrong: {@code isBlank()}
   * tests {@code Character.isWhitespace}, which is false for U+0000–U+0008 and U+000E–U+001B, while
   * {@code trim()} strips everything at or below U+0020. Checking before trimming therefore lets
   * {@code "\u0001"} through as a non-blank value that trims to the empty string, and an account
   * check would be attempted — and audited — with an empty account number.
   *
   * <p><strong>Rejects control characters outright</strong>, not only leading and trailing ones. An
   * interior U+0000 survives trimming, and {@code audit.audit_event.payload} is a generated {@code
   * payload_json::jsonb} column: PostgreSQL rejects {@code \u0000} in a text-to-jsonb cast, so such
   * a request would 500 with nothing recorded. Rejecting it here turns an obscure 500 into a plain
   * 400.
   *
   * <p><strong>Caps the length</strong>, because these values are written verbatim into an
   * append-only table that is never deleted from, on a single serialised chain (R-038). Neither
   * field has a documented maximum, so the cap is deliberately generous — it is a bound on abuse,
   * not a validation of format. The product owner declined to assume an account-number format
   * (nothing observed supports one), and checking {@code branch} against the {@code ref.branch}
   * list is a separate question (see BL-007).
   */
  private static String clean(String value, String fieldName) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    if (trimmed.length() > MAX_FIELD_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          fieldName + " is longer than " + MAX_FIELD_LENGTH + " characters");
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
