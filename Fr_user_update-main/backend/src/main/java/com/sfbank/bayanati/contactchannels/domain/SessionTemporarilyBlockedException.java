package com.sfbank.bayanati.contactchannels.domain;

import java.time.Instant;

/**
 * Thrown when a Stage 1b re-entry is attempted while {@code app.profile.phone_lock_until} (S3-08,
 * R-044) is still in the future — the escalating 15-minute/1-hour block customer.md's Stage 2 "Both
 * phone channels locked ... terminal" policy value describes, enforced here because a locked
 * channel "cannot be retried" and the only way back into verification is a fresh Stage 1b
 * submission. Nothing is sent or written before this is thrown.
 */
public class SessionTemporarilyBlockedException extends RuntimeException {

  public SessionTemporarilyBlockedException(Instant blockedUntil) {
    super("phone verification is temporarily blocked until " + blockedUntil);
  }
}
