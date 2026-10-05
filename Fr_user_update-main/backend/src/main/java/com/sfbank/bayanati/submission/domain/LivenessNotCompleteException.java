package com.sfbank.bayanati.submission.domain;

/** Stage 10 (liveness/face-match) has not yet produced a passing result for this profile. */
public class LivenessNotCompleteException extends RuntimeException {

  public LivenessNotCompleteException(String message) {
    super(message);
  }
}
