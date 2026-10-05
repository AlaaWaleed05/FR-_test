package com.sfbank.bayanati.operator.domain;

/**
 * The profile's current status is not one {@code app.status_transition} (V0020) legalises for this
 * review action — e.g. rejecting a profile that is not {@code submitted}. An ordinary 409: the
 * request was well-formed, the profile just isn't in a state this action applies to right now.
 */
public class ProfileNotReviewableException extends RuntimeException {

  public ProfileNotReviewableException(String message) {
    super(message);
  }
}
