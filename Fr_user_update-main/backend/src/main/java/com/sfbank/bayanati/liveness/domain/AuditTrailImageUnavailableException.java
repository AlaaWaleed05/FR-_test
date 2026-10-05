package com.sfbank.bayanati.liveness.domain;

/**
 * The face-session JWS verified, but its {@code auditTrailImageId} is already gone. Mirrors {@code
 * identityscan.domain.ImagesUnavailableForAcceptanceException}'s R-012/R-021 ordering rule: the
 * face-match result is NOT accepted. Does not count against the liveness retry budget — a
 * system-timing fact, not a liveness/face-match quality problem.
 */
public class AuditTrailImageUnavailableException extends RuntimeException {

  public AuditTrailImageUnavailableException(String message) {
    super(message);
  }
}
