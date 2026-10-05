package com.sfbank.bayanati.signature.domain;

/**
 * customer.md Stage 11 follows Stage 10 directly ("Back -> disabled. Liveness is complete and its
 * artifact issued") — a profile with no passing {@code app.face_result} for its active identity
 * cycle cannot reach the signature stage.
 */
public class LivenessNotCompleteException extends RuntimeException {

  public LivenessNotCompleteException(String message) {
    super(message);
  }
}
