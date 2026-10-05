package com.sfbank.bayanati.liveness.service;

import java.time.Instant;

/**
 * What {@link LivenessService#submitFaceResult} returns for a SUCCESSFULLY VERIFIED JWS — customer.
 * md: a failed match is a normal business outcome the customer sees as "try again", not an error,
 * exactly like a rejected identity-scan document is still a rendered display payload at stage 9.
 * Only a JWS the backend itself rejects (bad signature, wrong session) throws.
 *
 * @param passed {@code match && matchLevel >= the applied threshold}
 * @param matchLevel 1-5, as returned by Uqudo
 * @param blockedUntil non-null only when this failed attempt was the one that exhausted the
 *     liveness/face-match budget (customer.md: "Same budget as liveness, no separate block")
 */
public record FaceResultOutcome(boolean passed, int matchLevel, Instant blockedUntil) {}
