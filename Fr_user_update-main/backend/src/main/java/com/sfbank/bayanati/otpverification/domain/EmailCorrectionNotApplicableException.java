package com.sfbank.bayanati.otpverification.domain;

/**
 * Thrown when a resend request supplies {@code correctedEmailAddress} for any channel but {@code
 * EMAIL} (S4-06, BL-012). Changing the phone number is deliberately out of scope for resend — the
 * journey routes that back to Stage 1b instead, because it changes what the session authenticates
 * against — so this endpoint refuses to accept a phone correction disguised as this field, even
 * when called directly rather than through the app. A client-shape problem, mapped to 400 by the
 * controller, the same way {@link UnknownOtpChannelException} is.
 */
public class EmailCorrectionNotApplicableException extends RuntimeException {

  public EmailCorrectionNotApplicableException(String message) {
    super(message);
  }
}
