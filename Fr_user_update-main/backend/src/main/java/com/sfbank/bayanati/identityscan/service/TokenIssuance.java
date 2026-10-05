package com.sfbank.bayanati.identityscan.service;

import java.time.Instant;

/**
 * What {@code POST /api/v1/identity-scan/token} hands back to the app: the tenant-scoped bearer
 * token for the SDK, and the backend-minted {@code sessionId}/{@code nonce} the app must pass into
 * the SDK builder and echo back unchanged on scan submission (uqudo-sdk.md: "Backend-minted
 * setSessionId(uuidv4) and setNonce() on every launch").
 *
 * @param usableUntil the moment after which this whole issuance stops working and must not be
 *     re-launched (S8-15, BL-114(a)). For the scan stage this is the access token's own expiry,
 *     since the {@code sessionId}/{@code nonce} the backend minted have no deadline of their own.
 *     <p><strong>Why the backend answers this rather than the app assuming it.</strong> The app
 *     needs it to know whether an issuance left unused by a camera-permission denial can be reused
 *     on the retry instead of minting a fresh one — and a wrong answer is worse than the bug it
 *     fixes, because a re-launch past expiry surfaces as {@code SESSION_EXPIRED} or {@code
 *     UNEXPECTED_ERROR}, both of which Stage 8 treats as a LAUNCHED session and charges an attempt
 *     for. The only figure the app could otherwise reason from is a single S1-02 observation.
 *     Compare it against the clock; do not derive a duration from it.
 */
public record TokenIssuance(
    String accessToken, String sessionId, String nonce, String documentType, Instant usableUntil) {}
