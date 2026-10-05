package com.sfbank.bayanati.messaging.airtel;

import java.util.Optional;

/**
 * Converts a stored E.164 destination into the national form the Airtel gateway accepts.
 *
 * <p>Pure logic — no Spring, no I/O — so a plain JUnit test reaches it.
 *
 * <p><strong>This class is BL-080's trap, made explicit.</strong> We store {@code +249…} by
 * CLAUDE.md's hard rule; Airtel takes {@code 0…}. Send the stored form and the gateway answers
 * {@code Invalid Sudanese number} — which the system would then record as <em>the customer's</em>
 * number being bad. Support would chase a number that is perfectly correct while the real cause
 * points away from us. That is S7-12's Civil Registry defect in a new place, and the conversion
 * here is the whole defence.
 *
 * <p><strong>A destination outside Sudan is refused, not attempted.</strong> {@code
 * PhoneNumberNormalizer} deliberately passes a foreign number through unchanged — the journey
 * allows a customer verified from abroad — so a {@code +9715…} destination genuinely can reach this
 * adapter. It has no {@code 0…} national form, Airtel is a domestic route, and sending it anyway
 * would produce exactly the {@code Invalid Sudanese number} reply this class exists to prevent
 * being misattributed. {@link AirtelSmsSender} turns the empty result into a {@code REJECTED}
 * outcome, which is terminal and correctly blames the destination rather than the gateway.
 */
public final class AirtelDestination {

  /** Sudan's E.164 country code, with the plus. */
  static final String SUDAN_E164_PREFIX = "+249";

  /** Digits after the country code — and, identically, after the national trunk {@code 0}. */
  static final int NATIONAL_SIGNIFICANT_DIGITS = 9;

  private AirtelDestination() {}

  /**
   * @param e164 a destination as stored, e.g. {@code +249900000000}
   * @return the national form Airtel expects ({@code 0} followed by nine digits), or empty when
   *     {@code e164} is not a Sudanese number this adapter can address
   */
  public static Optional<String> toNationalForm(String e164) {
    if (e164 == null) {
      return Optional.empty();
    }
    String candidate = e164.strip();
    if (!candidate.startsWith(SUDAN_E164_PREFIX)) {
      return Optional.empty();
    }
    String national = candidate.substring(SUDAN_E164_PREFIX.length());
    if (national.length() != NATIONAL_SIGNIFICANT_DIGITS || !isAsciiDigits(national)) {
      return Optional.empty();
    }
    return Optional.of("0" + national);
  }

  private static boolean isAsciiDigits(String value) {
    return value.chars().allMatch(c -> c >= '0' && c <= '9');
  }
}
