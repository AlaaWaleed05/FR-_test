package com.sfbank.bayanati.identityscan.domain;

import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code writes {@code app.identity_cycle}, {@code app.scan_result}, {@code
 * app.registry_result}, {@code app.artifact_ref}, and the stage-8 retry-budget columns on {@code
 * app.profile} (V0039). Mirrors {@code dataentry.domain.DataEntryRepository}'s shape: this feature
 * locks {@code app.profile} itself (rather than depending on {@code
 * profile.domain.ProfileRepository} for it) because the lock read must also return the scan-budget
 * counters in the same statement — the same reasoning {@code DataEntryRepository.lockAndGetStatus}
 * already established for stages 3-6's own lock.
 */
public interface IdentityScanRepository {

  /**
   * {@code SELECT ... FOR UPDATE} on {@code app.profile}, or empty if the profile does not exist.
   */
  Optional<ScanState> lockAndGetScanState(UUID profileId);

  /**
   * The same read <strong>without</strong> the row lock, or empty if the profile does not exist —
   * S5-11. Stage 9's read-only resume endpoint needs the profile's existence and terminality, and a
   * path whose whole contract is that it changes nothing must not take {@code FOR UPDATE} on {@code
   * app.profile}: that would block every concurrent writer of the same row for the length of a
   * request that writes nothing.
   *
   * <p>Never use this to decide a write. Its result is not held against a concurrent update, which
   * is exactly what {@link #lockAndGetScanState} exists to provide.
   */
  Optional<ScanState> readScanState(UUID profileId);

  /**
   * Increments {@code scan_attempts_<appDocumentType>} and {@code scan_attempts_total} by one.
   * Called for every countable attempt (cancel, JWS rejection, an image checksum-integrity failure,
   * Stage 9 "wrong number") — never for a connectivity failure, an images-unavailable outcome, or
   * an expired-artifact outcome (R-012/R-021).
   */
  void applyScanAttempt(UUID profileId, String appDocumentType);

  /**
   * Persists the {@code sessionId}/{@code nonce} this attempt just minted, so {@code submitScan}
   * can validate a returned JWS's claims against what the backend actually issued rather than what
   * the request merely claims (uqudo-sdk.md: "jti == the sessionId WE passed to setSessionId ...
   * data.nonce == the nonce WE issued"). Found missing by {@code @agent-reviewer} — without this,
   * an attacker holding any valid JWS could bind it to an arbitrary profile.
   */
  void recordPendingSession(UUID profileId, String sessionId, String nonce);

  /**
   * Clears {@code pending_scan_session_id}/{@code pending_scan_nonce}, making the pending scan
   * session <strong>single-use</strong> — BL-039's fix, and the exact opposite of what V0040's
   * original comment promised (superseded by V0064).
   *
   * <p>Called at the moment a try is <em>spent</em> and nowhere else: inside {@code
   * IdentityScanService.recordFailedAttempt}'s transaction and {@code reportWrongNumber}'s, beside
   * {@link #applyScanAttempt}. Before this, the session was an equality check that never expired,
   * so one issued token could back an unbounded number of failed posts while {@code canAttempt} was
   * consulted only at issuance.
   *
   * <p><strong>Never on a successful scan</strong>, and never on the two exempt failures. BL-034's
   * upload retry and {@code submitScan}'s accepted-snapshot short-circuit both re-post the same
   * {@code (sessionId, nonce, jws)} triple through the session-equality check that sits
   * <em>above</em> them, so clearing on acceptance would turn a lost acknowledgement into {@code
   * INVALID_SCAN_SESSION} — the very failure BL-034 was filed to remove.
   */
  void consumePendingScanSession(UUID profileId);

  /**
   * Increments {@code scan_tokens_minted} — the profile's lifetime scan-token mint count (V0065,
   * BL-039 Slice B). Called inside {@code issueToken}'s transaction, beside {@link
   * #recordPendingSession}, so an issuance refused before the mint counts nothing.
   *
   * <p>Never decremented, and never reset: not by {@link #resumeFromScanBlock}, and deliberately
   * not by AD-008's device-less re-entry. It is the bound that survives the 24-hour block, which is
   * the only reason it closes anything — {@code POST /api/v1/contact-channels} is unauthenticated
   * by design (R-051), so a bound that re-entry could clear would not be a bound.
   */
  void incrementScanTokensMinted(UUID profileId);

  /**
   * Transitions {@code app.profile.status} {@code in_progress -> blocked_scan} (legal per V0020),
   * sets {@code scan_blocked_until}, and writes the matching {@code app.profile_status_history} row
   * the deferred constraint trigger requires — actor {@code system}, since this is a budget
   * consequence, not a customer action.
   */
  void applyScanBlock(UUID profileId, Instant blockedUntil, Instant now, long auditEventId);

  /**
   * Transitions {@code blocked_scan -> in_progress}, resets every counter to zero and clears {@code
   * scan_blocked_until} — customer.md's "try later" promise: a block that left the budget
   * permanently spent would make that promise empty. Called the next time the customer requests a
   * scan token after {@code scan_blocked_until} has passed, never on a timer.
   */
  void resumeFromScanBlock(UUID profileId, Instant now, long auditEventId);

  /** Transitions {@code in_progress -> awaiting_registry} (legal per V0020) — the Stage 9 pause. */
  void transitionToAwaitingRegistry(UUID profileId, Instant now, long auditEventId);

  /** Transitions {@code awaiting_registry -> in_progress} — a successful registry retry. */
  void transitionBackToInProgressFromRegistry(UUID profileId, Instant now, long auditEventId);

  /**
   * Transitions {@code in_progress -> terminated_registry_mismatch} (legal per V0020) — Stage 9's
   * "details are wrong" outcome.
   */
  void transitionToTerminatedMismatch(UUID profileId, Instant now, long auditEventId);

  /**
   * Marks the profile's current {@code active} identity cycle {@code superseded}, if one exists. A
   * no-op otherwise. Called before inserting a fresh {@code active} cycle (defensive — {@code
   * identity_one_active}'s partial unique index would reject two active rows anyway) and by Stage
   * 9's "wrong number" outcome (the cycle succeeded at scanning but is being redone).
   */
  void supersedeActiveCycleIfAny(UUID profileId, Instant now);

  /**
   * Inserts an {@code identity_cycle} row in state {@code abandoned} — an attempt that never
   * produced an accepted scan (SDK cancel, JWS rejection, image checksum-integrity failure). {@code
   * seq} is computed from the profile's own cycle history, mirroring {@code
   * JdbcProfileRepository}'s reactivation-history {@code seq} subquery.
   */
  UUID insertAbandonedCycle(UUID profileId, Instant now);

  /**
   * Supersedes any existing active cycle, then inserts a new {@code identity_cycle} row in state
   * {@code active} — an accepted scan (images already downloaded and checksum-verified).
   */
  UUID insertAcceptedCycle(UUID profileId, Instant now);

  /** Full write of {@code app.scan_result} for one accepted cycle. */
  void insertScanResult(UUID cycleId, ParsedEnrolmentResult parsed, Instant receivedAt);

  /**
   * Inserts one {@code app.artifact_ref} row, including the artifact's bytes (AD-004, S5-06: bytes
   * live in this row's own {@code body} column). {@code storageKey} is an opaque identifier that is
   * now DEAD rather than reserved: R-046 closed at BL-075 (2026-09-13) and its addressing scheme
   * needs no stored key — the operator image endpoint addresses an artifact by its own {@code
   * artifact_ref_id}. Still supplied because the column is NOT NULL, and never a storage-technology
   * reference (AD-004 is closed).
   */
  void insertArtifactRef(
      UUID cycleId,
      String kind,
      String uqudoImageId,
      String uqudoChecksum,
      String storageKey,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] body,
      Instant now);

  /**
   * First write of {@code app.registry_result} for a cycle — one row per cycle (PK is {@code
   * cycle_id}).
   *
   * @param fields the parsed record when {@code state} is {@code ok}; null otherwise
   * @param identityNumberReturned the {@code IDENTITY_NUMBER} the registry returned (V0062, BL-030)
   *     — present on {@code ok} and on a {@code not_found} caused by a differing value, so the
   *     guard's evidence is kept even though no record fields are; null when nothing parseable came
   *     back. Passed separately from {@code fields} precisely because it exists when {@code fields}
   *     does not
   */
  void insertRegistryResult(
      UUID cycleId,
      String state,
      Instant queriedAt,
      int attempts,
      RegistryLookupResult fields,
      String identityNumberReturned);

  /**
   * Updates {@code app.registry_result} on a retry (customer.md: "no rescan, no new token"). Same
   * parameters as {@link #insertRegistryResult}.
   */
  void updateRegistryResultOnRetry(
      UUID cycleId,
      String state,
      Instant queriedAt,
      int attempts,
      RegistryLookupResult fields,
      String identityNumberReturned);

  /**
   * The profile's active cycle and its registry result, for every Stage 9 action. Empty if none.
   */
  Optional<ActiveRegistryContext> currentActiveRegistryContext(UUID profileId);

  /**
   * The profile's active cycle as an already-accepted scan — its stored {@code uqudo_jti} plus
   * everything the Stage 9 display payload needs — so a customer's own re-upload of the same
   * enrolment JWS can be answered from what is already stored (BL-034). Empty when there is no
   * active cycle or it carries no {@code scan_result} yet, which is the ordinary first-submission
   * case.
   *
   * <p>Scoped by {@code profile_id} and {@code state = 'active'}, deliberately
   * <strong>never</strong> keyed on the jti: a jti-keyed read would match another profile's row and
   * turn {@code scan_result.uqudo_jti}'s global replay guard (V0008) into a success path.
   */
  Optional<AcceptedScanSnapshot> currentAcceptedScanSnapshot(UUID profileId);

  /** Sets {@code identity_cycle.accepted_at} — Stage 9's "Accept" outcome. */
  void markCycleAccepted(UUID cycleId, Instant acceptedAt);

  /**
   * One artifact of the profile's <strong>active</strong> cycle, for Stage 9's review screen. Bytes
   * come back through {@code app.artifact_read()} (V0054), which verifies them against the stored
   * checksum and raises rather than returning something that no longer matches.
   *
   * <p>Keyed on {@code state = 'active'} and deliberately NOT on {@code accepted_at IS NOT NULL}:
   * Stage 9 is the screen where the customer decides whether to accept, so the cycle is active with
   * a null {@code accepted_at} for exactly as long as these images are needed. (Stage 10's {@code
   * LivenessRepository#currentAcceptedCycleReferenceImage} requires acceptance because it runs
   * after that decision — same table, different moment, different predicate.)
   *
   * <p>Empty when there is no active cycle, no artifact of that kind, or the body has legitimately
   * gone ({@code app.purge_abandoned_artifacts()} nulls it past the retention window and {@code
   * app.artifact_read()} returns NULL for that) — three cases the caller cannot usefully tell apart
   * and should not leak the difference between.
   */
  Optional<ScanArtifact> activeCycleArtifact(UUID profileId, String kind);

  /**
   * Which of the artifact kinds the active cycle actually holds bytes for — so Stage 9's screen
   * requests only what exists rather than probing. A passport has no {@code doc_back}, and {@code
   * portrait_registry} exists only once the Civil Registry lookup has succeeded.
   */
  List<String> activeCycleArtifactKinds(UUID profileId);
}
