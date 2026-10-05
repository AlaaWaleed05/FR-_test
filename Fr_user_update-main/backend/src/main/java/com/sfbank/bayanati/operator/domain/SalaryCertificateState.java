package com.sfbank.bayanati.operator.domain;

/**
 * What an operator can be told about a profile's optional salary certificate (BL-122).
 *
 * <p>Before this existed, a missing certificate was a single undifferentiated absence: no {@code
 * app.artifact_ref} row of {@code kind='salary_certificate'}, whether the customer declined to
 * attach one or attached one whose upload never arrived. Those are very different facts about the
 * same person and they were byte-identical at rest.
 *
 * <p><strong>Purely informative.</strong> Product-owner ruling, 2026-09-13: an operator does
 * nothing differently about any of these values. The certificate is optional, gates nothing, and no
 * status, completion or review action depends on it — customer.md Stage 6's "must never block
 * completion" is unchanged, and {@link #ATTACH_FAILED} is emphatically not a rejection reason. This
 * type exists so the operator is informed, not so anything acts on it.
 *
 * <p><strong>Why only the claim is asserted and the decline is derived.</strong> The customer has
 * no way to say "no" — the Stage 6 field offers camera, picker and retry, with no remove and no
 * skip — so declining is done by doing nothing, and there is no affordance to hang an assertion on.
 * Deriving it also fails in the safe direction: a claim lost in transit reads as {@link #DECLINED},
 * which is exactly the behaviour that shipped before BL-122, whereas a lost decline assertion would
 * read as {@link #ATTACH_FAILED} and send an operator chasing a customer who simply said no.
 */
public enum SalaryCertificateState {

  /** The bank holds a committed certificate artifact for this profile. */
  PRESENT,

  /**
   * The customer said at Stage 6 that they attached a certificate, and the bank does not hold one.
   * The upload failed, is still queued, or was refused. Not an error on the customer's part and not
   * an instruction to the operator.
   *
   * <p><strong>One known way this answer is imprecise, deliberately left as it is.</strong> A
   * certificate the bank DID receive and then purged under retention ({@code
   * app.purge_abandoned_artifacts}, V0055 — body nulled, {@code state='purged'}) leaves no
   * committed row while the claim still stands, so it resolves here. Reaching it takes a profile
   * abandoned for over 90 days that is then re-entered. Every alternative is also wrong rather than
   * less wrong ({@link #DECLINED} would assert a choice the customer did not make), and telling a
   * purged certificate apart would mean a fifth operator-facing state — a product decision, not one
   * to take in passing. What is true in every case is the part an operator acts on: the bank does
   * not hold the file. Filed as BL-142.
   */
  ATTACH_FAILED,

  /** The customer completed Stage 6 and attached nothing. The ordinary, expected outcome. */
  DECLINED,

  /**
   * Stage 6 never arrived, so the customer was never asked. Reachable in more ways than it looks:
   * the backend enforces no stage ordering and gates submission on liveness and signature alone
   * ({@code SubmissionService#currentJourneyPointer}), and Stage 6's own POST is offline-queued on
   * the handset — so a profile can reach submission with Stage 6 still unsent.
   */
  NOT_REACHED;

  /**
   * Resolves the four states in priority order, from facts a single profile row already carries.
   *
   * <p>A committed artifact wins outright, which is what lets a certificate that "lands whenever it
   * can" (customer.md Stage 6) resolve to {@link #PRESENT} on its own, with no second write needed
   * to retire the claim.
   *
   * <p><strong>{@code app.profile.provenance} is deliberately NOT consulted, though an earlier
   * draft of this method did.</strong> The reasoning was that a {@code manual} profile's {@code
   * employer_name} is an operator's typing rather than a customer's answer, so it should not be
   * read as a decline. That is what AD-015's per-field manual entry will mean — but it is not what
   * this column means today.
   *
   * <p><strong>Corrected 2026-09-16 (S9-01, AD-022).</strong> Until this session {@code 'manual'}
   * had exactly one writer, {@code JdbcManualCompletionRepository}'s manual-completion UPDATE, and
   * it marked a CUSTOMER-STARTED profile that an operator finished over the counter. AD-022 deleted
   * manual completion outright, so that writer is gone.
   *
   * <p><strong>Corrected again 2026-09-16 (S9-02, BL-135).</strong> The sentence above used to end
   * "until BL-135 ships per-field editing", predicting that BL-135 would restore a writer. It
   * shipped and deliberately did NOT. {@code app.profile.provenance} is write-never for good:
   * provenance is DERIVED by {@code app.derived_provenance()} (V0073) as "the stored column already
   * said manual, OR an operator has keyed at least one field". The column itself is read only as
   * that function's first disjunct, which is what keeps the pre-AD-022 manually completed profiles
   * distinguishable. Not consulting it here therefore remains correct, and for a second reason now
   * — a profile-level flag cannot carry the per-field meaning this method would need, and the
   * per-field record that can is {@code app.profile_field_edit}.
   *
   * <p>The original defect this note records still stands as the reason not to reintroduce the
   * branch: branching on the column flipped a genuine {@link #DECLINED} to {@link #NOT_REACHED} the
   * moment a profile was manually completed, showing "has not reached this stage yet" on a
   * submitted profile — a claim about a future that never arrives, and on exactly the profiles an
   * operator reviews most. Found by {@code @agent-reviewer}.
   *
   * @param hasCommittedArtifact whether a committed {@code salary_certificate} artifact exists
   * @param claimed whether the customer asserted an attachment at Stage 6
   * @param stage6Arrived whether Stage 6's write ever reached the bank, via {@code employer_name}
   */
  public static SalaryCertificateState resolve(
      boolean hasCommittedArtifact, boolean claimed, boolean stage6Arrived) {
    if (hasCommittedArtifact) {
      return PRESENT;
    }
    if (claimed) {
      return ATTACH_FAILED;
    }
    return stage6Arrived ? DECLINED : NOT_REACHED;
  }
}
