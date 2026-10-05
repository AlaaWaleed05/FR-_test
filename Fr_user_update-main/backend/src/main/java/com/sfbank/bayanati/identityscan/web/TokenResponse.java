package com.sfbank.bayanati.identityscan.web;

/**
 * @param usableUntil ISO-8601, always present (S8-15, BL-114(a)) — the moment after which this
 *     issuance must not be re-launched. The app compares it to the clock before reusing an issuance
 *     a camera-permission denial left unused; see {@code TokenIssuance} for why the backend answers
 *     this rather than the app assuming a duration.
 */
public record TokenResponse(
    String profileId,
    String accessToken,
    String sessionId,
    String nonce,
    String documentType,
    String usableUntil) {}
