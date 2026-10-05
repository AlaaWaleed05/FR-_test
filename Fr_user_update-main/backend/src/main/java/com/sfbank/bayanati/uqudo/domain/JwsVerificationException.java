package com.sfbank.bayanati.uqudo.domain;

/**
 * The signature did not verify, or a claim did not match what the backend expected ({@code jti},
 * {@code nonce}, {@code documentType}). customer.md Stage 8: "Backend rejects the JWS ... counted
 * as a failed attempt." Deliberately NOT thrown for an already-passed {@code exp} — see {@link
 * ArtifactExpiredException}, a sibling type for that one case, which is NOT counted (R-012/R-021).
 */
public class JwsVerificationException extends RuntimeException {

  public JwsVerificationException(String message) {
    super(message);
  }
}
