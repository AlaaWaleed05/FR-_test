package com.sfbank.bayanati.liveness.domain;

import java.time.Instant;

/**
 * customer.md Stage 10: "Budget exhausted -- 5 -> temporary block on this stage only ... 24 hours."
 * Mirrors {@code identityscan.domain.ScanTemporarilyBlockedException}'s shape. Fires for both a
 * failed {@code submitFaceResult} and a {@code reportLivenessTerminated} call that exhausts the
 * shared liveness/face-match budget (customer.md: "Same budget as liveness, no separate block").
 *
 * <p>Since S8-15 (BL-118) it may also carry {@link LivenessBlockReason}, naming why this particular
 * refusal happened when the refusing arm knows. See that enum for why the reason is additive rather
 * than a new error code, and why {@code null} means "this arm does not know".
 */
public class LivenessTemporarilyBlockedException extends RuntimeException {

  private final Instant blockedUntil;
  private final LivenessBlockReason blockReason;

  public LivenessTemporarilyBlockedException(Instant blockedUntil) {
    this(blockedUntil, null);
  }

  public LivenessTemporarilyBlockedException(
      Instant blockedUntil, LivenessBlockReason blockReason) {
    super("liveness checking is temporarily blocked until " + blockedUntil);
    this.blockedUntil = blockedUntil;
    this.blockReason = blockReason;
  }

  public Instant blockedUntil() {
    return blockedUntil;
  }

  /** Nullable — see {@link LivenessBlockReason}. */
  public LivenessBlockReason blockReason() {
    return blockReason;
  }
}
