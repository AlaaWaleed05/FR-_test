package com.sfbank.bayanati.operator.domain;

import java.time.Instant;

/**
 * {@code app.face_result} for the profile's most recent identity cycle — liveness and face-match
 * "recorded separately with their confidence figures" (operator.md). {@code null} when the profile
 * has not reached (or not yet passed) stage 10 — a liveness failure produces no JWS at all
 * (AD-002a) and so leaves no row here; that history lives in audit, not surfaced by this view (a
 * deliberate scope decision, see the S4-01 session report).
 */
public record FaceResultView(
    boolean match, int matchLevel, int thresholdApplied, boolean passed, Instant receivedAt) {}
