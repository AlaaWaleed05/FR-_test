package com.sfbank.bayanati.identityscan.domain;

/**
 * The submitted {@code sessionId}/{@code nonce} does not match what {@code issueToken} actually
 * issued for this profile — either no token was ever issued, or the caller is attempting to bind a
 * JWS to a session the backend never minted for it (see {@code
 * IdentityScanRepository#recordPendingSession}'s Javadoc).
 */
public class InvalidScanSessionException extends RuntimeException {

  public InvalidScanSessionException(String message) {
    super(message);
  }
}
