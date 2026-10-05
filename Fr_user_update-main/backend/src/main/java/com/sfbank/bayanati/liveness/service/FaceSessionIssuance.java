package com.sfbank.bayanati.liveness.service;

import java.time.Instant;

/**
 * What {@link LivenessService#issueFaceSessionToken} returns to the app.
 *
 * @param usableUntil the moment after which this whole issuance stops working and must not be
 *     re-launched (S8-15, BL-114(a)).
 *     <p><strong>Here this is NOT the access token's expiry, and the difference is the entire
 *     point.</strong> Uqudo deletes a face session and its reference image 600 s after creation
 *     (Face API OpenAPI, corroborated by S1-02's measured {@code exp - iat} of 600 s), while the
 *     access token lives about 1800 s. So the face session dies roughly three times sooner than the
 *     token, and a client-side freshness rule reasoned from the token would be wrong by that
 *     factor. This field is therefore the EARLIER of the two deadlines, computed server-side where
 *     both are known, so the app compares one instant to the clock and needs no per-stage rule and
 *     no constant of its own.
 */
public record FaceSessionIssuance(String accessToken, String faceSessionId, Instant usableUntil) {}
