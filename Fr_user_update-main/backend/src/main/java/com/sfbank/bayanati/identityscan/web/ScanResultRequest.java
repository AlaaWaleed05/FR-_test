package com.sfbank.bayanati.identityscan.web;

/**
 * Stage 8: "The app posts that string to the backend untouched." {@code jws} is opaque to this
 * class and to every class between here and {@code UqudoClient.verifyAndParse} — never decoded,
 * never inspected.
 */
public record ScanResultRequest(
    String profileId, String sessionId, String nonce, String documentType, String jws) {}
