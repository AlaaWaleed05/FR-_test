package com.sfbank.bayanati.signature.domain;

/**
 * Whether a profile may submit a signature — mirrors the precondition checks other stages make
 * before their own writes, without needing a full row lock: a signature submission has no counter
 * to increment and no status to transition, so unlike stage 10's retry budget there is nothing here
 * for concurrent submissions to corrupt.
 *
 * @param facePassed {@code app.face_result.passed} for the profile's active, accepted identity
 *     cycle — {@code false} if no such cycle or result exists yet
 */
public record SignatureEligibility(boolean terminal, boolean facePassed) {}
