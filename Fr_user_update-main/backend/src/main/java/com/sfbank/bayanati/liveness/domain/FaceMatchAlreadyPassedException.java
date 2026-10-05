package com.sfbank.bayanati.liveness.domain;

/**
 * The profile's active identity cycle already has a passing {@code app.face_result} — stage 10 is
 * complete and there is nothing to return to (customer.md Stage 11: "Back -> disabled. Liveness is
 * complete and its artifact issued"). Reissuing a Face Session token or re-submitting a result at
 * this point would serve no purpose and risks overwriting a passed result with an unrelated one.
 */
public class FaceMatchAlreadyPassedException extends RuntimeException {

  public FaceMatchAlreadyPassedException(String message) {
    super(message);
  }
}
