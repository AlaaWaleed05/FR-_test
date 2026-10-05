package com.sfbank.bayanati.otpverification.domain;

/** One resend request's outcome (docs/journeys/customer.md Stage 2 "Each channel row"). */
public enum ResendOutcome {
  /** A fresh challenge was issued and sent. */
  ISSUED,
  /** "Resends exhausted on a channel — 3 per channel per session." */
  CAP_EXHAUSTED,
  /** The escalating delay since the last issuance (30s/60s/120s) has not yet elapsed. */
  TOO_SOON,
  /** The channel already reached 5 wrong attempts and cannot be retried this session. */
  CHANNEL_LOCKED,
  /** The channel is already verified; resending it is meaningless. */
  ALREADY_VERIFIED
}
