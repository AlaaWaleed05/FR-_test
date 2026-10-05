package com.sfbank.bayanati.liveness.web;

import com.sfbank.bayanati.liveness.service.FaceResultOutcome;

/** {@code blockedUntil} is non-null only when a failed attempt also exhausted the budget. */
public record FaceResultResponse(
    String profileId, boolean passed, int matchLevel, String blockedUntil) {

  public static FaceResultResponse from(String profileId, FaceResultOutcome outcome) {
    return new FaceResultResponse(
        profileId,
        outcome.passed(),
        outcome.matchLevel(),
        outcome.blockedUntil() == null ? null : outcome.blockedUntil().toString());
  }
}
