package com.sfbank.bayanati.liveness.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code app.profile}'s status plus the stage-10 retry-budget columns (V0043/V0044), read together
 * under one row lock — mirrors {@code identityscan.domain.ScanState}'s shape.
 *
 * @param acceptedCycleId the profile's current {@code active} identity cycle with {@code
 *     accepted_at IS NOT NULL}, or {@code null} if stage 9 has not yet been accepted
 * @param facePassed {@code app.face_result.passed} for {@code acceptedCycleId}, or {@code null} if
 *     no face-session attempt has been recorded for it yet
 * @param faceTokensMinted the profile's lifetime face-session-token mint count (V0065, BL-039 Slice
 *     B) — read under the same lock as everything else here because the cap it feeds is checked
 *     before the block-expiry lift and incremented inside the issuance transaction
 */
public record LivenessState(
    String status,
    boolean terminal,
    int livenessAttempts,
    Instant livenessBlockedUntil,
    String pendingFaceSessionId,
    UUID acceptedCycleId,
    Boolean facePassed,
    int faceTokensMinted) {}
