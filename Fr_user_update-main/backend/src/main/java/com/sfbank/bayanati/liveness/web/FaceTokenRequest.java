package com.sfbank.bayanati.liveness.web;

/**
 * Stage 10: "the app requests a liveness token from the backend at the moment of tapping to begin".
 */
public record FaceTokenRequest(String profileId) {}
