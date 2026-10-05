package com.sfbank.bayanati.uqudo.domain;

import java.time.Instant;

/**
 * One freshly minted tenant-scoped bearer token for the SDK, with the moment it stops working.
 *
 * <p><strong>Why the expiry is returned rather than assumed (S8-15, BL-114(a)).</strong> {@code
 * HttpUqudoClient} has always parsed Uqudo's own {@code expires_in} into an expiry instant and then
 * thrown it away, returning the token string alone. The handset therefore had no way to know how
 * long what it was holding would last, and any client-side reuse rule would have had to invent a
 * constant. The only figure available to invent from is the {@code ~1859 s} observed ONCE at S1-02
 * against a documented 1800 — a sample of one, already flagged in {@code uqudo-sdk.md}. Returning
 * the server's own answer replaces that guess with a fact, and costs nothing: the value was already
 * computed.
 *
 * @param value the bearer token itself. Never logged, never persisted, never stored in the {@code
 *     app} schema — it crosses to the device and lives in memory on both sides.
 * @param expiresAt when Uqudo stops accepting it, derived from the token response's {@code
 *     expires_in}. <strong>One caveat a consumer must know:</strong> when {@code expires_in} is
 *     absent or non-positive, {@code HttpUqudoClient} substitutes its 60-second internal-renewal
 *     headroom rather than failing, so this can be a fabricated near-term deadline rather than a
 *     reported one. That fallback predates this record and is harmless while nothing reads the
 *     value; anything that starts making decisions on it should decide what an unreported expiry
 *     ought to mean first.
 */
public record IssuedAccessToken(String value, Instant expiresAt) {}
