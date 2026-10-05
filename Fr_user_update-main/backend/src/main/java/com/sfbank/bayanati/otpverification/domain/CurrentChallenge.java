package com.sfbank.bayanati.otpverification.domain;

import java.time.Instant;

/**
 * The most recently issued {@code app.otp_challenge} row for one profile's channel, regardless of
 * whether it has since expired — expiry is decided by comparing {@link #expiresAt()} against the
 * caller's own clock, not baked into the query.
 *
 * @param codeHash {@code sha256(salt || code)} — never logged, never returned, never audited
 * @param salt the salt {@code codeHash} was computed with
 */
public record CurrentChallenge(
    java.util.UUID challengeId, byte[] codeHash, byte[] salt, Instant issuedAt, Instant expiresAt) {

  public boolean expired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
