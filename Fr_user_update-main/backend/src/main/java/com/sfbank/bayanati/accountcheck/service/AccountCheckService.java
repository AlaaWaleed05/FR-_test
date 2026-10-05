package com.sfbank.bayanati.accountcheck.service;

import com.sfbank.bayanati.accountcheck.domain.AccountCheckContinuation;
import com.sfbank.bayanati.accountcheck.domain.AccountCheckOutcome;
import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import com.sfbank.bayanati.corebanking.domain.CoreBankingClient;
import com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException;
import com.sfbank.bayanati.corebanking.domain.RawExchange;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Journey Stage 1a — account identification (docs/journeys/customer.md).
 *
 * <p>Calls the core banking middleware with the account number (the branch is NOT sent — OQ-024 —
 * it stays profile data; since V0061/BL-032 the profile-existence check below is keyed on the
 * account number alone too, so the branch appears only in the audit payload), maps the result, and
 * records the attempt. For an ACTIVE account, also checks whether a profile already exists (S3-07,
 * BL-006): no profile or an incomplete one leaves the default {@code PROCEED} continuation from
 * {@code AccountCheckOutcome.ACTIVE} untouched; a <strong>complete</strong> profile overrides it to
 * {@code TERMINAL} — "the profile update for this account has already been completed successfully
 * ... there is no re-entry, no supersede path" (customer.md). <strong>Stage 1a itself still creates
 * and mutates nothing in the {@code app} schema</strong> — the existence check is read-only; the
 * journey is explicit that the session begins at Stage 1b, "so no session exists for an account
 * that failed the core-bank check". The audit events below are not an exception to that — the audit
 * trail is a separate store with a separate lifetime, and the journey requires the attempt recorded
 * precisely because no profile is created.
 *
 * <p>Deliberately out of scope here, per BL-006's remainder: the device-less <em>review flow</em>
 * an incomplete profile also requires (restoring customer-entered data, offering
 * discard-and-start-over). That depends on data-entry stages (Stage 3 onward) that do not exist
 * yet. This class only carries the account as far as re-entry to Stage 1b.
 */
@Service
public class AccountCheckService {

  /** The controlled-vocabulary event type for this attempt (docs/journeys/customer.md, audit). */
  static final String EVENT_TYPE = "account_check_attempted";

  /**
   * The event that carries the raw {@code omni_check_request} artifact (S3-02). {@code
   * audit.audit_event.artifact_id} is a single FK, so the request and response bytes need one event
   * each; this one is written immediately before {@link #EVENT_TYPE}, on the same chain and under
   * the same {@code requestId}, and only when a real HTTP exchange happened — the stub has no
   * request bytes, and an event with nothing to carry would be noise.
   */
  static final String REQUEST_EVENT_TYPE = "account_check_requested";

  static final String REQUEST_ARTIFACT_KIND = "omni_check_request";
  static final String RESPONSE_ARTIFACT_KIND = "omni_check_response";

  /**
   * The audit chain account checks append to. It is a {@code system} chain rather than a {@code
   * profile} one because at Stage 1a there is no profile to hang the event on, and because {@code
   * fru_app} holds SELECT only on {@code audit.audit_chain} and cannot create one — the chain is
   * seeded by migration V0034. See R-038 for the serialisation this implies.
   */
  static final String CHAIN_KIND = "system";

  static final String CHAIN_SUBJECT = "account_check";

  /**
   * Recorded as the outcome when the middleware answered with a parseable code outside its stated
   * 1/0/-1 contract. Deliberately not one of the outcomes: the attempt happened and must be on the
   * record, but we do not know what it means and must not guess.
   */
  static final String OUTCOME_UNMAPPED = "UNMAPPED";

  /**
   * Recorded as the outcome when the call produced no usable answer — no response, an unreadable
   * body, or a thrown call. Whether it reached the middleware at all is unknowable from here, which
   * is itself worth having on the record.
   */
  static final String OUTCOME_CALL_FAILED = "CALL_FAILED";

  /**
   * Recorded as the outcome when the middleware itself answered {@code -1} "System Error" (S3-02).
   * Distinct from {@link #OUTCOME_CALL_FAILED}: the middleware was reached and replied, and its
   * reply is stored as the response artifact — an investigation can tell an outage on our side of
   * the wire from one on the bank's.
   */
  static final String OUTCOME_SYSTEM_ERROR = "SYSTEM_ERROR";

  /**
   * Recorded as the outcome when the core banking call succeeded (ACTIVE) but the follow-up
   * profile-existence check (S3-07) then threw. The core banking result is still on the record —
   * only the database read that decides the continuation failed.
   */
  static final String OUTCOME_PROFILE_CHECK_FAILED = "PROFILE_CHECK_FAILED";

  private final CoreBankingClient coreBankingClient;
  private final AuditEventWriter auditEventWriter;
  private final ProfileRepository profileRepository;
  private final Clock clock;

  public AccountCheckService(
      CoreBankingClient coreBankingClient,
      AuditEventWriter auditEventWriter,
      ProfileRepository profileRepository,
      Clock clock) {
    this.coreBankingClient = coreBankingClient;
    this.auditEventWriter = auditEventWriter;
    this.profileRepository = profileRepository;
    this.clock = clock;
  }

  /**
   * Checks one account against the core banking middleware and records the attempt.
   *
   * <p><strong>Every attempt is audited, including the ones that fail.</strong> An unrecognised
   * result code, a middleware system error and a thrown call are exactly the anomalies a misuse or
   * incident investigation reads, so each is written to the trail before the failure propagates.
   * When a real HTTP exchange happened, the raw request and response bytes go with it as {@code
   * omni_check_request} / {@code omni_check_response} artifacts (PROJECT_PLAN.md Architecture).
   *
   * <p>Deliberately <strong>not</strong> {@code @Transactional}. Each audit write is a single
   * INSERT (plus its artifact row, which the writer orders itself), so autocommit already gives the
   * only atomicity there is to give — it commits or it throws, and a caller never receives an
   * unaudited answer either way. A surrounding transaction would buy nothing and would actively
   * break the failure paths above by rolling back the very event they exist to record. It also
   * keeps this class free of any claim about a database, which is what lets it live in a `service`
   * package (see CLAUDE.md).
   *
   * <p>The core banking call is a read against a different system, with no shared transaction
   * manager, so it cannot be rolled back. That is safe here: Stage 1a changes nothing anywhere, and
   * the core is read-only to this solution, so a failed attempt is simply retried by the customer.
   *
   * <p>On every failure path, the original failure is what propagates even if the audit write
   * itself then throws — an audit failure is attached with {@link Throwable#addSuppressed} rather
   * than replacing it, so a database outage during a core-banking outage does not erase the record
   * of which one happened first.
   *
   * @throws CoreBankingUnavailableException if the middleware produced no usable answer, or
   *     answered {@code -1} "System Error" — after the attempt has been recorded. The web layer
   *     maps this to 503.
   * @throws IllegalArgumentException if the middleware returns a parseable code outside its stated
   *     contract — after the attempt has been recorded
   */
  public AccountCheckResult check(String accountNumber) {
    UUID requestId = UUID.randomUUID();

    CoreBankingCheckResult result;
    try {
      result = coreBankingClient.check(accountNumber);
    } catch (CoreBankingUnavailableException unavailable) {
      auditBeforeRethrow(
          unavailable,
          new Attempt(accountNumber, requestId, null, unavailable.exchange()),
          OUTCOME_CALL_FAILED,
          null,
          null,
          null);
      throw unavailable;
    } catch (RuntimeException callFailed) {
      auditBeforeRethrow(
          callFailed,
          new Attempt(accountNumber, requestId, null, null),
          OUTCOME_CALL_FAILED,
          null,
          null,
          null);
      throw callFailed;
    }

    Attempt attempt = new Attempt(accountNumber, requestId, result, result.exchange());

    if (result.code() == CoreBankingCheckResult.SYSTEM_ERROR) {
      // The middleware was reached and said "System Error". Not a journey outcome the customer
      // can act on -- an outage, surfaced as 503 -- but its reply is evidence, stored as the
      // response artifact under a distinct outcome so it is never mistaken for "not found".
      CoreBankingUnavailableException systemError =
          new CoreBankingUnavailableException(
              "the core banking middleware reported a system error"
                  + (result.message() == null ? "" : ": " + result.message()),
              result.exchange());
      auditBeforeRethrow(systemError, attempt, OUTCOME_SYSTEM_ERROR, null, null, null);
      throw systemError;
    }

    AccountCheckOutcome outcome;
    try {
      outcome = AccountCheckOutcome.fromResultCode(result.code());
    } catch (IllegalArgumentException unmapped) {
      auditBeforeRethrow(unmapped, attempt, OUTCOME_UNMAPPED, null, null, null);
      throw unmapped;
    }

    AccountCheckContinuation continuation = outcome.continuation();
    String profileStatus = null;
    Boolean profileTerminal = null;
    Instant blockedUntil = null;
    if (outcome == AccountCheckOutcome.ACTIVE) {
      Optional<ExistingProfile> existing;
      try {
        existing = profileRepository.findExisting(accountNumber);
      } catch (RuntimeException profileCheckFailed) {
        auditBeforeRethrow(
            profileCheckFailed, attempt, OUTCOME_PROFILE_CHECK_FAILED, null, null, null);
        throw profileCheckFailed;
      }
      if (existing.isPresent()) {
        profileStatus = existing.get().status();
        profileTerminal = existing.get().terminal();
        if (profileTerminal) {
          // customer.md Stage 1a: "A complete profile exists ... terminal. ... There is no
          // re-entry, no supersede path". No profile is written here either way — this only
          // overrides which continuation is reported.
          continuation = AccountCheckContinuation.TERMINAL;
        } else {
          // BL-021: a relaunch during Stage 2's escalating phone lock used to land back in
          // verification with no signal, only discovering the lock on the next verify/resend
          // attempt. currentPhoneLockUntil is the same column OtpVerificationService/
          // ContactChannelsService already read for this exact lock (V0037, R-044) -- see
          // AccountCheckResult's Javadoc for what this exposes to this endpoint's
          // unauthenticated caller and why it's safe.
          Instant now = clock.instant();
          try {
            blockedUntil =
                profileRepository
                    .currentPhoneLockUntil(existing.get().profileId())
                    .filter(until -> until.isAfter(now))
                    .orElse(null);
          } catch (RuntimeException phoneLockCheckFailed) {
            // Same failure class and same audit treatment as the findExisting() catch above --
            // this read is also part of "the profile-existence check", just its second half.
            // Found by @agent-reviewer, S4-06: previously this branch had no audit coverage at
            // all for a DB failure here, unlike its sibling one catch block up.
            auditBeforeRethrow(
                phoneLockCheckFailed,
                attempt,
                OUTCOME_PROFILE_CHECK_FAILED,
                profileStatus,
                profileTerminal,
                null);
            throw phoneLockCheckFailed;
          }
          if (blockedUntil != null) {
            continuation = AccountCheckContinuation.BLOCKED;
          }
        }
      }
    }

    audit(attempt, outcome.name(), profileStatus, profileTerminal, blockedUntil);
    return new AccountCheckResult(outcome, continuation, requestId, blockedUntil);
  }

  /** One attempt's identity and raw material, threaded through the audit helpers. */
  private record Attempt(
      
      String accountNumber,
      UUID requestId,
      CoreBankingCheckResult result,
      RawExchange exchange) {}

  private void auditBeforeRethrow(
      Throwable original,
      Attempt attempt,
      String outcome,
      String profileStatus,
      Boolean profileTerminal,
      Instant blockedUntil) {
    try {
      audit(attempt, outcome, profileStatus, profileTerminal, blockedUntil);
    } catch (RuntimeException auditFailure) {
      original.addSuppressed(auditFailure);
    }
  }

  /**
   * Writes the request-artifact event (only when there are request bytes) and then the attempt
   * event, response artifact attached when there are response bytes. Both under the same {@code
   * requestId}, in that order, so the chain reads request-then-outcome.
   */
  private void audit(
      Attempt attempt,
      String outcome,
      String profileStatus,
      Boolean profileTerminal,
      Instant blockedUntil) {
    RawExchange exchange = attempt.exchange();
    if (exchange != null && exchange.requestBody() != null) {
      Map<String, Object> requestPayload = new LinkedHashMap<>();
      
      requestPayload.put("accountNumber", attempt.accountNumber());
      auditEventWriter.appendWithArtifact(
          event(attempt, REQUEST_EVENT_TYPE, CanonicalJson.object(requestPayload)),
          new AuditArtifact(REQUEST_ARTIFACT_KIND, "application/json", exchange.requestBody()));
    }

    // Branch, account number, result code and outcome — exactly the four the journey's audit
    // section names, because invalid attempts are what a misuse investigation reads.
    // resultCode is null only on the CALL_FAILED path, where there is no code to record.
    // profileStatus/profileTerminal (S3-07) are populated only on the ACTIVE path where the
    // profile-existence check actually ran — extra evidence for a misuse investigation ("this
    // account tried to resubmit after already completing").
    CoreBankingCheckResult result = attempt.result();
    Map<String, Object> payload = new LinkedHashMap<>();
    
    payload.put("accountNumber", attempt.accountNumber());
    payload.put("resultCode", result == null ? null : result.code());
    payload.put("responseMessage", result == null ? null : result.message());
    payload.put(
        "httpStatus",
        exchange == null || exchange.responseBody() == null ? null : exchange.httpStatus());
    payload.put("outcome", outcome);
    payload.put("profileStatus", profileStatus);
    payload.put("profileTerminal", profileTerminal);
    payload.put("blockedUntilIso", blockedUntil == null ? null : blockedUntil.toString());

    AuditEvent event = event(attempt, EVENT_TYPE, CanonicalJson.object(payload));
    if (exchange != null && exchange.responseBody() != null && exchange.responseBody().length > 0) {
      auditEventWriter.appendWithArtifact(
          event,
          new AuditArtifact(
              RESPONSE_ARTIFACT_KIND,
              exchange.responseMediaType() == null
                  ? "application/octet-stream"
                  : exchange.responseMediaType(),
              exchange.responseBody()));
    } else {
      auditEventWriter.append(event);
    }
  }

  private static AuditEvent event(Attempt attempt, String eventType, String payloadJson) {
    return new AuditEvent(
        CHAIN_KIND,
        CHAIN_SUBJECT,
        eventType,
        "customer",
        null, // no actor id: the customer is unidentified until Stage 1b verifies a channel
        null, // no profile: the journey requires this recorded even when none is created
        null, // no session: Stage 1b creates it
        attempt.requestId(),
        payloadJson);
  }
}
