package com.sfbank.bayanati.accountcheck.domain;

/**
 * What journey Stage 1a's outcome permits next, per docs/journeys/customer.md.
 *
 * <p>Deliberately not a screen name. The backend states whether the journey may continue, must
 * stop, or may be retried; the mobile app owns the mapping from that to a screen.
 */
public enum AccountCheckContinuation {
  /** The customer may correct the input and try again. Stage 1a repeats. */
  RETRY,

  /** No path forward in the app; the customer is directed to a branch. */
  TERMINAL,

  /** The journey continues to Stage 1b, where the session is created. */
  PROCEED,

  /**
   * An incomplete profile for this account exists and its phone channels are currently under Stage
   * 2's escalating OTP lock (customer.md Stage 0: "Blocked until [time]"). Not retryable until
   * {@code AccountCheckResponse.blockedUntil} (S4-06, BL-021).
   */
  BLOCKED
}
