package com.sfbank.bayanati.identityscan.domain;

/**
 * {@code app.scan_result.uqudo_jti}'s UNIQUE constraint (V0008) rejected this insert — this exact
 * JWS (by Uqudo's own {@code jti}) has already been accepted once, for this profile or another one.
 * The global replay guard uqudo-sdk.md's verification checklist calls for.
 */
public class JwsAlreadyAcceptedException extends RuntimeException {

  public JwsAlreadyAcceptedException(String message) {
    super(message);
  }
}
