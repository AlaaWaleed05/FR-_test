package com.sfbank.bayanati.liveness.domain;

/**
 * A business-rule rejection with no more specific exception type — mirrors {@code
 * identityscan.domain.IdentityScanRejectedException}.
 */
public class LivenessRejectedException extends RuntimeException {

  public LivenessRejectedException(String message) {
    super(message);
  }
}
