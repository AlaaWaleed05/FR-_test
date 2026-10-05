package com.sfbank.bayanati.submission.domain;

import java.util.UUID;

/**
 * The Stage 10-12 resume read was asked about a profile that is not in Stages 10-12 at all — it has
 * no accepted identity cycle yet, or it sits in a Stage 8/9 status ({@code blocked_scan}, {@code
 * awaiting_registry}) or in {@code abandoned}. Surfaces as {@code STATE_CONFLICT}: the app re-syncs
 * through the earlier stages rather than showing a Stage 10-12 screen.
 *
 * <p><strong>This is where BL-051's boundary sits.</strong> A {@code blocked_scan} profile gets
 * {@code STATE_CONFLICT} here rather than a scan-block answer, because this read is scoped to
 * Stages 10-12 and inventing a Stage 8 answer from it would put the same fact in two places. BL-051
 * — no read tells a returning customer they are scan-blocked — therefore stays open, and S5-13
 * closes only the liveness half of that gap.
 */
public class NotInFinalStagesException extends RuntimeException {

  public NotInFinalStagesException(UUID profileId, String status) {
    super(
        "profile " + profileId + " is in status " + status + ", which is not within stages 10-12");
  }
}
