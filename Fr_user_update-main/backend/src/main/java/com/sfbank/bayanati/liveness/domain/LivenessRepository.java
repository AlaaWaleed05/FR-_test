package com.sfbank.bayanati.liveness.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code writes {@code app.face_result}, the {@code face_audit_trail} {@code
 * app.artifact_ref} rows, and the stage-10 retry-budget columns on {@code app.profile} (V0043,
 * V0044). Mirrors {@code identityscan.domain.IdentityScanRepository}'s shape: this feature locks
 * {@code app.profile} itself for the same reason that package's own Javadoc gives — the lock read
 * must also return the retry-budget counters in the same statement.
 */
public interface LivenessRepository {

  /**
   * {@code SELECT ... FOR UPDATE} on {@code app.profile}, joined to the profile's current {@code
   * active}+accepted identity cycle and its {@code face_result.passed} if one exists. Empty if the
   * profile does not exist.
   */
  Optional<LivenessState> lockAndGetLivenessState(UUID profileId);

  /**
   * The accepted identity cycle's portrait, read via {@code app.artifact_read()} (checksum-verified
   * — raises on a mismatch, never returns silently-wrong bytes) from {@code app.artifact_ref} (kind
   * {@code portrait_uqudo}), read at token-issuance time. AD-004 closed at S5-06: this used to read
   * a narrow transient bridge column (V0041/V0042, now dropped — see RISKS.md R-047) that
   * duplicated bytes already durably stored here since S3-12.
   *
   * <p>Empty if no such row exists at all for the profile's active accepted cycle — a hard failure
   * the caller treats as an invariant violation, since accepting a scan and inserting its {@code
   * portrait_uqudo} artifact_ref row are the same transaction, so a row should always exist. But a
   * present {@link ReferenceImage} whose {@code imageBytes()} is {@code null} is a real, expected
   * outcome, not a bug: {@code app.artifact_read()} returns {@code NULL} rather than raising when
   * the row's {@code body} is absent, which happens once {@code app.purge_abandoned_artifacts()}
   * has purged a profile abandoned over 90 days — and that profile can then be reactivated ({@code
   * abandoned -> in_progress}, {@code ContactChannelsService}) straight back to this same accepted
   * cycle. The caller distinguishes the two.
   */
  Optional<ReferenceImage> currentAcceptedCycleReferenceImage(UUID profileId);

  /**
   * The {@code uqudo_jti} already recorded in {@code app.face_result} for this cycle, if any — lets
   * {@code submitFaceResult} recognise a retried upload of the SAME JWS (customer.md Stage 10: "a
   * returned JWS is retained and the upload retried rather than repeating the check") as idempotent
   * rather than a fresh attempt, so a network retry does not spend a second draw from the liveness
   * budget for one real-world attempt.
   */
  Optional<String> currentFaceResultJti(UUID cycleId);

  /**
   * Persists the Uqudo-returned Face Session id this attempt just minted (V0044), so {@code
   * submitFaceResult} validates the returned JWS's {@code jti} against what was actually issued.
   */
  void recordPendingFaceSession(UUID profileId, String faceSessionId);

  /**
   * Transitions {@code app.profile.status} {@code in_progress -> blocked_liveness} (legal per
   * V0020) and writes the matching {@code app.profile_status_history} row.
   */
  void applyLivenessBlock(UUID profileId, Instant blockedUntil, Instant now, long auditEventId);

  /**
   * Transitions {@code blocked_liveness -> in_progress} and resets {@code liveness_attempts} to
   * zero — customer.md's "try later" promise, mirroring {@code
   * IdentityScanRepository#resumeFromScanBlock}.
   */
  void resumeFromLivenessBlock(UUID profileId, Instant now, long auditEventId);

  /** Increments {@code liveness_attempts} by one. Called for every countable attempt. */
  void applyLivenessAttempt(UUID profileId);

  /**
   * Increments {@code face_tokens_minted} — the profile's lifetime face-session-token mint count
   * (V0065, BL-039 Slice B). Called inside {@code issueFaceSessionToken}'s second transaction,
   * beside {@link #recordPendingFaceSession}, so a request refused before the mint counts nothing.
   *
   * <p>Never reset — not by {@link #resumeFromLivenessBlock}, and deliberately not by AD-008's
   * device-less re-entry, which already leaves the liveness budget alone. It is the bound that
   * survives the 24-hour block.
   */
  void incrementFaceTokensMinted(UUID profileId);

  /**
   * {@code INSERT ... ON CONFLICT (cycle_id) DO UPDATE} — one row per cycle reflects the *latest*
   * face-session attempt, exactly like {@code IdentityScanRepository#updateRegistryResultOnRetry}'s
   * own retry pattern. Full attempt history (including every failed match) lives in the audit
   * trail, not in this table.
   */
  void upsertFaceResult(
      UUID cycleId,
      String jti,
      String faceSessionId,
      boolean match,
      int matchLevel,
      int thresholdApplied,
      Instant receivedAt);

  /**
   * {@code INSERT ... ON CONFLICT (cycle_id, kind) DO UPDATE} for the {@code face_audit_trail} kind
   * — same one-row-per-cycle reasoning as {@link #upsertFaceResult}. {@code storageKey} is an
   * opaque identifier, currently unused by any reader (AD-004 is closed — bytes live in {@code
   * body}).
   */
  void upsertFaceAuditTrailArtifact(
      UUID cycleId,
      String uqudoImageId,
      String uqudoChecksum,
      String storageKey,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] body,
      Instant now);
}
