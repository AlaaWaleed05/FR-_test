package com.sfbank.bayanati.contactchannels.domain;

/**
 * Normalises a customer-entered phone number to E.164 at the boundary (BL-016; CLAUDE.md: "Phone
 * numbers are E.164 everywhere — wire, storage, comparison and display masking").
 *
 * <p>Pure logic — no Spring, no I/O — same style as {@link DestinationMasker}. Deliberately a
 * small, bespoke validator rather than a third-party library: CLAUDE.md's
 * {@code @agent-researcher}-before-integration rule is scoped to Uqudo, the Oracle procedure and
 * the Civil Registry, not to self-contained parsing like this.
 *
 * <p><strong>Scoped to Sudan local-format conversion only.</strong> A number already carrying a
 * country code (a leading {@code +} or international {@code 00} prefix) is trusted and passed
 * through unchanged — the journey deliberately allows a customer verified over WhatsApp from abroad
 * (customer.md, Stage 5 "Addresses outside Sudan"), and this class has no basis for reformatting a
 * foreign number it cannot validate. Only the bank's own paper-form format — a bare {@code 0}
 * followed by nine digits — is unambiguous enough to convert with confidence.
 */
public final class PhoneNumberNormalizer {

  private static final int MIN_E164_DIGITS = 8;
  private static final int MAX_E164_DIGITS = 15;
  private static final int SUDAN_LOCAL_DIGITS_AFTER_ZERO = 9;
  private static final String SUDAN_COUNTRY_CODE = "249";

  private PhoneNumberNormalizer() {}

  /**
   * @param raw a non-blank, already control-character-free value (the caller's {@code clean()} runs
   *     first)
   * @throws InvalidPhoneNumberException if {@code raw} contains a non-ASCII digit, or its format is
   *     not one of the forms this class can confidently convert
   */
  public static String normalizeE164(String raw) {
    rejectNonAsciiDigits(raw);

    StringBuilder stripped = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '.') {
        continue;
      }
      stripped.append(c);
    }
    String candidate = stripped.toString();

    if (candidate.startsWith("+")) {
      String digits = candidate.substring(1);
      requireAsciiDigits(digits);
      rejectLeadingZeroCountryCode(digits);
      digits = stripSudanTrunkZero(digits);
      requireLengthInRange(digits);
      return "+" + digits;
    }
    if (candidate.startsWith("00")) {
      String digits = candidate.substring(2);
      requireAsciiDigits(digits);
      rejectLeadingZeroCountryCode(digits);
      digits = stripSudanTrunkZero(digits);
      requireLengthInRange(digits);
      return "+" + digits;
    }
    if (candidate.startsWith("0")) {
      String digits = candidate.substring(1);
      requireAsciiDigits(digits);
      if (digits.length() == SUDAN_LOCAL_DIGITS_AFTER_ZERO) {
        return "+" + SUDAN_COUNTRY_CODE + digits;
      }
    }
    throw new InvalidPhoneNumberException(
        "phoneNumber is not a recognised format: expected E.164 (+…), international-prefix"
            + " (00…), or Sudan local format (0 followed by "
            + SUDAN_LOCAL_DIGITS_AFTER_ZERO
            + " digits)");
  }

  /**
   * BL-016: a client that fails to normalise Arabic-Indic digits must be rejected outright here,
   * never silently transliterated — a silent fix would let {@code +٢٤٩...} reach the profile from a
   * different, non-validating path later.
   */
  private static void rejectNonAsciiDigits(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      boolean arabicIndic = c >= '٠' && c <= '٩';
      boolean extendedArabicIndic = c >= '۰' && c <= '۹';
      if (arabicIndic || extendedArabicIndic) {
        throw new InvalidPhoneNumberException(
            "phoneNumber contains a non-ASCII digit — send ASCII 0-9 only, never Arabic-Indic"
                + " digits");
      }
    }
  }

  /**
   * A number already carrying Sudan's own country code can still be mistyped with the local trunk
   * {@code 0} kept in (e.g. "+249 0912345678", a common mis-dial pattern) — undialable, and a false
   * delta against the same number entered in local format ({@code 0912345678} normalises to {@code
   * +249912345678} with no trunk zero). Strip it, but only when the total length after stripping
   * matches the real Sudan national significant number length exactly — this must not touch a
   * number that legitimately starts {@code 2490...} for an unrelated reason. Found by
   * {@code @agent-reviewer}.
   */
  private static String stripSudanTrunkZero(String digits) {
    String prefix = SUDAN_COUNTRY_CODE + "0";
    int expectedLength = SUDAN_COUNTRY_CODE.length() + 1 + SUDAN_LOCAL_DIGITS_AFTER_ZERO;
    if (digits.startsWith(prefix) && digits.length() == expectedLength) {
      return SUDAN_COUNTRY_CODE + digits.substring(prefix.length());
    }
    return digits;
  }

  /**
   * No ITU E.164 country code begins with {@code 0}. Without this, {@code "+0912345678"} and {@code
   * "000912345678"} would pass {@link #requireLengthInRange} untouched and be stored verbatim,
   * undialable and producing a false delta against the same customer's number entered as {@code
   * "0912345678"} (which normalises to {@code +249912345678}) — the same class of bug {@link
   * #stripSudanTrunkZero} closes for the "249" case, found on re-review by {@code @agent-reviewer}.
   */
  private static void rejectLeadingZeroCountryCode(String digits) {
    if (digits.startsWith("0")) {
      throw new InvalidPhoneNumberException(
          "phoneNumber cannot have a country code starting with 0");
    }
  }

  private static void requireAsciiDigits(String digits) {
    if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw new InvalidPhoneNumberException(
          "phoneNumber must contain only ASCII digits after its prefix");
    }
  }

  private static void requireLengthInRange(String digits) {
    if (digits.length() < MIN_E164_DIGITS || digits.length() > MAX_E164_DIGITS) {
      throw new InvalidPhoneNumberException(
          "phoneNumber must have between "
              + MIN_E164_DIGITS
              + " and "
              + MAX_E164_DIGITS
              + " digits after its country code");
    }
  }
}
