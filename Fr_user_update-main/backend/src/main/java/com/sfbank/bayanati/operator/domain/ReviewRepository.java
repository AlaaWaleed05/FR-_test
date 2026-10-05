package com.sfbank.bayanati.operator.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code writes an approve/reject decision. Both {@link #approve} and {@link
 * #reject} pair a single guarded {@code UPDATE app.profile} with the matching {@code
 * app.profile_status_history} INSERT in one call, mirroring how {@code
 * profile.jdbc.JdbcProfileRepository#insertProfile} and {@code
 * submission.jdbc.JdbcSubmissionRepository#submit} each do the same two writes together — the
 * deferred {@code profile_status_requires_history} constraint trigger (V0020) checks both exist by
 * commit, whatever order they run in, but a single method keeps a caller from ever writing one
 * without the other.
 */
public interface ReviewRepository {

  /**
   * {@code app.profile.status}, or empty if no such profile exists. A plain, unlocked read — for a
   * friendly 404 and the idempotent-already-approved/-rejected fast path only.
   */
  Optional<String> currentStatus(UUID profileId);

  /**
   * {@code SELECT status FROM app.profile WHERE profile_id = ?::uuid FOR UPDATE} — must be the
   * transaction's FIRST statement before calling {@link #approve}, so the status it returns is
   * still current (and nothing else can change the row) when {@link #approve}'s own {@code UPDATE}
   * runs immediately after. See {@code operator.jdbc.JdbcReviewRepository}'s class Javadoc for the
   * race this closes. {@code empty} if no such profile exists.
   *
   * <p><strong>Lock ordering invariant, binding on every writer that takes both locks this method
   * and an audit write together imply</strong>: the {@code app.profile} row lock (taken here) MUST
   * always be acquired BEFORE the audit-chain lock ({@link
   * com.sfbank.bayanati.audit.domain.AuditEventWriter#append}'s own {@code SELECT ... FOR UPDATE}
   * on {@code audit.audit_chain}, inside {@code audit.chain_append()}). {@code approve()} and
   * {@code reject()} both call this method first, then write their audit event, then run their own
   * guarded {@code UPDATE} — found the hard way at S4-01: an earlier {@code approve()} took this
   * lock first while {@code reject()} still wrote its audit event (the chain lock) first, so two
   * concurrent reviews of the same profile — one approving, one rejecting — could each hold one
   * lock and block on the other, deadlocking (PostgreSQL {@code 40P01}). Fixed by reordering {@code
   * reject()} to match. {@code operator.service.ManualCompletionService} (S4-02) used to be the
   * third OPERATOR-SIDE writer to take both locks, and followed the same order for the same reason;
   * it was deleted by AD-022 at S9-01, which removes a writer from this invariant but changes
   * nothing about the invariant itself. The invariant is not scoped to the operator side, though:
   * reviewing S4-02's diff for it found two pre-existing CUSTOMER-side writers violating it too
   * ({@code identityscan.service.IdentityScanService#reportWrongDetails}/{@code
   * #acceptRegistryReview}, both fixed the same session — see docs/components/persistence.md "Lock
   * ordering across every writer" for the full account). Any future writer anywhere in this
   * codebase that takes both locks must follow this order.
   */
  Optional<String> lockAndReadStatus(UUID profileId);

  /**
   * A guarded {@code UPDATE ... WHERE status IN ('submitted','rejected')}, which also legalises
   * {@code rejected -> approved} (operator.md "Re-approving a rejected profile") — there is no
   * separate code path for it. Safe to call on its own (no preceding lock required) — the {@code
   * UPDATE}'s {@code WHERE} clause is the entire enforcement.
   *
   * <p>V0009's comment shows this statement with a second {@code AND NOT EXISTS (...)} conjunct
   * enforcing the four-eyes rule. <strong>AD-013 (2026-09-13) removed that rule entirely.</strong>
   *
   * <p><strong>Corrected 2026-09-16 (S9-01).</strong> This used to add that {@code
   * app.profile_status_history.is_manual_completion} "is still written, so the predicate can be
   * restored with no migration". AD-022 deleted manual completion, so nothing writes that column
   * any more and restoring four-eyes would need a WRITER as well as the predicate — the reversal is
   * no longer free. The column is kept (V0067) so profiles completed before the ruling stay
   * distinguishable in their own history, which is a different and still-valid reason. See R-054.
   *
   * @param fromStatus the row's status as read by a preceding {@link #lockAndReadStatus} call in
   *     the same transaction, recorded verbatim on the paired history row — see this interface's
   *     Javadoc for why the caller must supply it rather than this method trying to read it back
   *     itself
   * @return {@code true} if the update (and the paired history insert) happened; {@code false} if
   *     zero rows matched — a lost race or an ineligible status, which the caller distinguishes via
   *     {@code fromStatus}
   */
  boolean approve(
      UUID profileId, String operatorId, String fromStatus, Instant now, long auditEventId);

  /**
   * A guarded {@code UPDATE ... WHERE status = 'submitted'}.
   *
   * @return {@code true} if the update (and the paired history insert) happened; {@code false} if
   *     the profile was not {@code submitted}
   */
  boolean reject(
      UUID profileId,
      String reasonCode,
      int reasonVersion,
      String internalNote,
      String operatorId,
      Instant now,
      long auditEventId);
}
