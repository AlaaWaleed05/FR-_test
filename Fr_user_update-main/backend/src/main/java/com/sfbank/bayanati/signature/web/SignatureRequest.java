package com.sfbank.bayanati.signature.web;

/**
 * @param captureMethod {@code "drawn"} or {@code "uploaded"} (customer.md Stage 11's two routes)
 * @param contentBase64 base64-encoded image bytes — consistent with how this backend already
 *     carries the Uqudo JWS as a JSON string rather than multipart, which has no precedent anywhere
 *     in this codebase
 */
public record SignatureRequest(
    String profileId, String captureMethod, String contentType, String contentBase64) {}
