package com.sfbank.bayanati.liveness.domain;

/**
 * Why a particular {@link LivenessTemporarilyBlockedException} was raised, when the refusing arm
 * actually knows (S8-15, BL-118). Rides alongside the existing {@code blockedUntil} on the
 * unchanged {@code LIVENESS_BLOCKED} response.
 *
 * <p>Mirrors {@code identityscan.domain.ScanBlockReason} rather than sharing one enum with it, for
 * the reason {@code LivenessAttemptBudget} already gives for carrying its own copy of the lifetime
 * cap: the two features are separate quarantine boundaries (CLAUDE.md, Architecture), they block
 * independently, and either vocabulary could grow a value the other must not have. Change one,
 * consider the other.
 *
 * <p><strong>{@code null} means "this arm does not know", never "not capped".</strong> Only {@code
 * issueFaceSessionToken} evaluates the cap. {@code submitFaceResult} and {@code
 * reportLivenessTerminated} re-report a {@code blocked_liveness} row applied earlier by an arm they
 * cannot see, and {@code SubmissionService.journeyPointer} reports {@code LIVENESS_BLOCKED} on
 * resume with no discriminator at all. Reading an absent reason as "an ordinary block" would
 * re-create BL-114(b) at a new door.
 *
 * <p>Unlike the scan side, this feature needs {@link #BUDGET_EXHAUSTED} as a distinct value: Stage
 * 10 has no second document to switch to, so a spent budget reuses the same 24-hour block response
 * as the cap instead of getting its own code the way {@code SCAN_TYPE_EXHAUSTED} does.
 */
public enum LivenessBlockReason {

  /**
   * The profile has spent its lifetime allowance of face-token mints (BL-039 Slice B). Permanent by
   * ruling; see {@code ScanBlockReason.LIFETIME_CAP}.
   */
  LIFETIME_CAP,

  /**
   * The five-attempt Stage 10 budget is spent. Temporary and self-clearing — {@code
   * resumeFromLivenessBlock} zeroes {@code liveness_attempts} when the customer returns after the
   * deadline, which is exactly what makes this block a way out rather than a dead end.
   */
  BUDGET_EXHAUSTED
}
