package com.sfbank.bayanati.submission.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code reads eligibility for and writes the customer's own digital
 * submission (Stage 12). Mirrors the shape other features' repositories use.
 */
public interface SubmissionRepository {

  /**
   * A plain (non-locking) read, for the idempotency short-circuit before opening any transaction.
   */
  Optional<SubmissionState> checkState(UUID profileId);

  /** {@code SELECT ... FOR UPDATE} on {@code app.profile}, for the actual write path. */
  Optional<SubmissionState> lockAndCheckState(UUID profileId);

  /**
   * Draws the next reference number ({@code SFB-} + a zero-padded 9-digit {@code
   * app.reference_number_seq} value, V0045). The prefix was {@code FRU-} until S8-07, when the bank
   * asked for its own initials; the change is at the GENERATION POINT ONLY — already-issued {@code
   * FRU-} numbers are never rewritten, because a customer holds that number and the audit trail
   * records it. There were TWO mint sites until S9-01 (this one and {@code
   * operator.jdbc.JdbcManualCompletionRepository}), which had to carry the same prefix or
   * manually-completed profiles would have been distinguishable from digitally-submitted ones by
   * their reference number alone. AD-022 deleted the other one, so this is now the SOLE mint site
   * and that hazard is gone by construction rather than by keeping two constants in step. Any
   * future second mint site reintroduces it. Drawn BEFORE the {@code profile_submitted} audit event
   * is written, not as part of {@link #submit}'s own UPDATE, so that event's payload can carry the
   * actual value — a plain {@code nextval()} draw needs no lock and a skipped value on rollback is
   * harmless, matching every other sequence use in this schema.
   */
  String nextReferenceNumber();

  /**
   * Transitions {@code app.profile.status} {@code in_progress -> submitted} (legal per V0020),
   * setting {@code reference_number} and {@code submitted_at}, and writes the matching {@code
   * app.profile_status_history} row in the same call. Only ever called from {@code in_progress} —
   * every other in-flight status is a route this customer-driven submission cannot have reached.
   */
  void submit(UUID profileId, String referenceNumber, Instant now, long auditEventId);

  /**
   * The reference list versions {@code app.profile_customer_data} currently records for this
   * profile — S4-04, AD-002f §6.2. One entry per list actually populated: {@code occupation} (iff
   * {@code occupation_code} is set), {@code admin_division} (the single shared {@code
   * admin_div_version} column), {@code income_source} (V0051), and {@code country} (from {@code
   * country_of_residence_version}, falling back to {@code birth_country_version}, then {@code
   * home_country_version}, then {@code work_country_version} if the first is null).
   *
   * <p><strong>Known, documented simplification</strong>: {@code country} has four independent
   * per-field version columns, unlike the other three lists' single shared column, but {@code
   * ref.profile_reference_version}'s primary key is {@code (profile_id, list_code)} — only one
   * version can be recorded per list. By construction (each data-entry call pins one version per
   * list, and a well-behaved client pins the same version for every call within one session) these
   * four should agree; this picks one representative value rather than asserting agreement, the
   * same kind of recorded-not-silently-assumed scope boundary V0049 already uses elsewhere in this
   * codebase.
   */
  List<ListVersionUsed> usedReferenceListVersions(UUID profileId);

  /**
   * Writes one {@code ref.profile_reference_version} row per entry in {@code versions} — {@code
   * fru_app} already holds {@code SELECT, INSERT} (V0012). Called only from the one {@code
   * in_progress -> submitted} transition path, so a plain {@code INSERT} is correct: matches
   * V0012's own comment, "written once per list at stage 11 submission, never updated."
   */
  void recordReferenceVersions(UUID profileId, List<ListVersionUsed> versions);
}
