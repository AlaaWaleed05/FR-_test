package com.sfbank.bayanati.liveness.web;

/**
 * @param usableUntil ISO-8601, always present (S8-15, BL-114(a)). On this stage it is the FACE
 *     SESSION's deadline, not the access token's — see {@code FaceSessionIssuance}.
 */
public record FaceTokenResponse(
    String profileId, String accessToken, String faceSessionId, String usableUntil) {}
