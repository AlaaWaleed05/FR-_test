package com.sfbank.bayanati.otpverification.domain;

/**
 * One verification attempt's outcome (docs/journeys/customer.md Stage 2 "Each channel row" /
 * "Failure paths"). All four are legitimate, expected states the UI renders inline — none of them
 * is an HTTP error.
 */
public enum VerificationOutcome {
  /** The code matched; the channel is now {@code verified}. */
  VERIFIED,
  /** The code did not match; counted against the channel's wrong-attempt budget. */
  WRONG_CODE,
  /** No unexpired challenge exists for this channel. Not counted, does not lock the channel. */
  EXPIRED,
  /** The channel already reached 5 wrong attempts and cannot be retried this session. */
  CHANNEL_LOCKED
}
