package com.sfbank.bayanati.otpverification.domain;

/**
 * Thrown when a verify/resend request names a profile/channel combination with no {@code
 * app.profile_channel} row to act on — either the profile doesn't exist, or the channel was
 * declined (or never offered) at Stage 1b. A client-shape problem: the app only ever calls Stage
 * 2's endpoints for a channel row Stage 2 is itself showing, so this is not a business outcome the
 * UI renders inline the way {@link VerificationOutcome}/{@link ResendOutcome} are — mapped to 400
 * by the controller.
 */
public class UnknownOtpChannelException extends RuntimeException {

  public UnknownOtpChannelException(String message) {
    super(message);
  }
}
