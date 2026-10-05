package com.sfbank.bayanati.liveness.web;

/** {@code jws} is forwarded from the SDK to the app untouched, per CLAUDE.md's hard rule. */
public record FaceResultRequest(String profileId, String faceSessionId, String jws) {}
