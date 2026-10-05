package com.sfbank.bayanati.contactchannels.domain;

/**
 * Masks a destination for display, matching customer.md Stage 2's row shape exactly: {@code "••••
 * 4821"} for a phone number, {@code "a•••@gmail.com"} for an email address. Used only for what the
 * response returns to the app — never for what gets audited or stored, which keep the real value.
 *
 * <p>Pure logic — no Spring, no I/O.
 */
public final class DestinationMasker {

  private static final String MASK = "••••";

  private DestinationMasker() {}

  /**
   * @param phoneNumber never blank — validated before this is called
   */
  public static String maskPhone(String phoneNumber) {
    int len = phoneNumber.length();
    String last4 = len >= 4 ? phoneNumber.substring(len - 4) : phoneNumber;
    return MASK + " " + last4;
  }

  /**
   * @param emailAddress never blank — validated before this is called
   */
  public static String maskEmail(String emailAddress) {
    int at = emailAddress.indexOf('@');
    if (at <= 0) {
      // Not a well-formed address (validation elsewhere should have caught this) — mask
      // everything rather than risk leaking more than intended.
      return MASK;
    }
    return emailAddress.charAt(0) + "•••" + emailAddress.substring(at);
  }
}
