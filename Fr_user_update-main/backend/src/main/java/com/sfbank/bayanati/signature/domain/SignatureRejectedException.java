package com.sfbank.bayanati.signature.domain;

/**
 * A business-rule or format/size rejection with no more specific exception type — mirrors {@code
 * identityscan.domain.IdentityScanRejectedException}. Covers customer.md Stage 11's own {@code
 * [POLICY: signature file size and format limits]} marker, which stays unset in the journey
 * document — the limits this exception enforces are an operational placeholder, not a resolution of
 * that marker (see the S3-13 session report).
 */
public class SignatureRejectedException extends RuntimeException {

  public SignatureRejectedException(String message) {
    super(message);
  }
}
