package com.sfbank.bayanati.identityscan.domain;

import java.time.Instant;

/**
 * customer.md Stage 8: "Either budget exhausted -> temporary block ... 24 hours." Both per-type
 * budgets have been spent (equivalently, the total has reached 6) and the profile is now {@code
 * blocked_scan}.
 *
 * <p>Since S8-15 (BL-118) it may also carry {@link ScanBlockReason}, naming why this particular
 * refusal happened when the refusing arm knows. See that enum for why the reason is additive rather
 * than a new error code, and why {@code null} means "this arm does not know" rather than "an
 * ordinary block".
 */
public class ScanTemporarilyBlockedException extends RuntimeException {

  private final Instant blockedUntil;
  private final ScanBlockReason blockReason;

  public ScanTemporarilyBlockedException(Instant blockedUntil) {
    this(blockedUntil, null);
  }

  public ScanTemporarilyBlockedException(Instant blockedUntil, ScanBlockReason blockReason) {
    super("scanning is temporarily blocked until " + blockedUntil);
    this.blockedUntil = blockedUntil;
    this.blockReason = blockReason;
  }

  public Instant blockedUntil() {
    return blockedUntil;
  }

  /** Nullable — see {@link ScanBlockReason}. */
  public ScanBlockReason blockReason() {
    return blockReason;
  }
}
