package com.sfbank.bayanati.otpverification.domain;

import java.time.Duration;

/**
 * Stage 2 policy values, verbatim from docs/journeys/customer.md's "Policy values" section.
 *
 * <p>{@link #CODE_VALIDITY} deliberately duplicates {@code ContactChannelsService.OTP_VALIDITY}
 * rather than sharing a constant — extracting one would mean touching S3-06/07's already-reviewed
 * class for a one-line saving. Both must stay {@code 5 minutes}; a change to the policy value
 * changes both call sites.
 */
public final class OtpVerificationPolicy {

  /** "Code validity — 5 minutes." */
  public static final Duration CODE_VALIDITY = Duration.ofMinutes(5);

  /** "Wrong-code attempts — 5 per channel, then that channel locks for the session." */
  public static final int WRONG_ATTEMPT_LIMIT = 5;

  /** "Resend cap — 3 per channel per session." */
  public static final int RESEND_LIMIT = 3;

  /**
   * "Resend delay — 30s, then 60s, then 120s." Indexed by the channel's {@code resend_count}
   * *before* this resend (0, 1, 2) — the delay required to have elapsed since the last issuance
   * before that resend number is allowed.
   */
  public static final Duration[] RESEND_DELAYS = {
    Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120)
  };

  /**
   * "Lock after all phone channels lock — 15 minutes, escalating to 1 hour on a repeat in the same
   * session."
   */
  public static final Duration SESSION_LOCK_FIRST = Duration.ofMinutes(15);

  public static final Duration SESSION_LOCK_REPEAT = Duration.ofHours(1);

  private OtpVerificationPolicy() {}
}
