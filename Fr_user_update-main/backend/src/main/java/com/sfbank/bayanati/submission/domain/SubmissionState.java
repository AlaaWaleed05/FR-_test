package com.sfbank.bayanati.submission.domain;

import java.time.Instant;

/**
 * {@code app.profile}'s current status plus the two Stage 12 preconditions, read together — mirrors
 * the shape other features' own state records use ({@code ScanState}, {@code LivenessState}).
 *
 * <p><strong>S5-13 widened this record</strong> so the Stage 10-12 resume pointer can be answered
 * from one non-locking {@code checkState} read. The two additions are on tables this query already
 * touches: {@code liveness_blocked_until} sits on {@code app.profile} beside {@code status}
 * (V0043), and {@code hasAcceptedCycle} is the presence of the {@code app.identity_cycle} row the
 * query already LEFT JOINs to reach {@code facePassed}. Reading them here is what lets the pointer
 * avoid {@code LivenessRepository.lockAndGetLivenessState}, which is a {@code SELECT ... FOR
 * UPDATE} and has no business on a read.
 *
 * @param referenceNumber {@code null} until submitted
 * @param facePassed {@code app.face_result.passed} for the active, accepted identity cycle
 * @param hasSignature whether at least one {@code kind='signature'} {@code app.artifact_ref} row
 *     exists for this profile
 * @param livenessBlockedUntil when the Stage 10 block lifts (V0043), or {@code null} if the profile
 *     is not liveness-blocked. Nullable even while {@code status = 'blocked_liveness'} — the column
 *     is set independently of the status transition, so every reader null-guards it.
 * @param hasAcceptedCycle whether the profile has an {@code active} identity cycle with {@code
 *     accepted_at IS NOT NULL} — i.e. whether Stage 9 has been accepted at all, which is the
 *     precondition for being anywhere in Stages 10-12
 */
public record SubmissionState(
    String status,
    boolean terminal,
    String referenceNumber,
    boolean facePassed,
    boolean hasSignature,
    Instant livenessBlockedUntil,
    boolean hasAcceptedCycle) {}
