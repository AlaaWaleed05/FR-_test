package com.sfbank.bayanati.salarycertificate.web;

/**
 * @param contentBase64 base64-encoded certificate bytes — same wire shape as {@code
 *     signature.web.SignatureRequest}, consistent with how this backend carries every
 *     client-originated binary artifact.
 */
public record SalaryCertificateRequest(
    String profileId, String contentType, String contentBase64) {}
