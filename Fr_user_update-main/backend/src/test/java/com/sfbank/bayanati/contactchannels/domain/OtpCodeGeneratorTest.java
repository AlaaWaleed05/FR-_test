package com.sfbank.bayanati.contactchannels.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.contactchannels.domain.OtpCodeGenerator.GeneratedOtp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OtpCodeGeneratorTest {

  @Test
  void theCodeIsSixDigits() {
    GeneratedOtp otp = OtpCodeGenerator.generate();

    assertEquals(6, otp.code().length());
    // ASCII digits, asserted as a RANGE rather than with Character.isDigit. isDigit admits every
    // Unicode decimal digit, so it passed happily on the Arabic-Indic codes this class produced
    // under an Arabic-default JVM until S9-07 -- see theCodeIsAsciiEvenOnAnArabicDefaultJvm.
    assertTrue(otp.code().chars().allMatch(c -> c >= '0' && c <= '9'), otp.code());
  }

  /**
   * THE OTP BYPASS THIS CLASS COULD NOT SEE, because a test suite runs under the developer's locale
   * and the defect only exists under someone else's.
   *
   * <p>{@code String.format} without a {@code Locale} takes the JVM default. Under an Arabic-Indic
   * numbering locale the code came out as U+0660..U+0669, {@link OtpCodeGenerator}'s {@code hash}
   * encodes with {@code US_ASCII} so each became {@code '?'}, and every challenge in the system
   * therefore stored {@code sha256(salt || "??????")}. {@code OtpVerificationController} admitted
   * any six characters {@code Character.isDigit} accepts, which includes those same digits, so ANY
   * six of them verified against ANY challenge.
   *
   * <p>This test forces the locale rather than describing it, and it is the assertion that fails
   * against the unfixed generator. {@code Locale.setDefault} is restored in a finally: leaving an
   * Arabic default behind would silently change every test that runs after this one in the same
   * JVM, which is the same class of cross-test leakage the defect itself belongs to.
   */
  @Test
  void theCodeIsAsciiEvenOnAnArabicDefaultJvm() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-SD"));
      GeneratedOtp otp = OtpCodeGenerator.generate();

      assertTrue(
          otp.code().chars().allMatch(c -> c >= '0' && c <= '9'),
          "the code must be ASCII digits whatever the JVM default locale: " + otp.code());

      // The consequence, asserted directly rather than left to inference: a non-ASCII digit does
      // not survive US_ASCII encoding, so a code that is not ASCII destroys its own hash.
      //
      // Note the BYTE LENGTH is deliberately not asserted. Each U+0660..U+0669 encodes to exactly
      // one '?' byte, so the buggy generator produced six bytes too -- a length assertion here
      // would read like a guard and could not fail against the defect. The '?' check below is the
      // one that does the work.
      assertFalse(
          new String(otp.code().getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII)
              .contains("?"),
          "a '?' here means the stored hash carries none of the code's entropy");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void aLeadingZeroCodeIsStillSixDigitsNotFive() {
    // Generate enough attempts that a leading-zero code (~10% chance each time) almost certainly
    // appears, proving the zero-padding rather than relying on one lucky draw.
    boolean sawLeadingZero = false;
    for (int i = 0; i < 200 && !sawLeadingZero; i++) {
      GeneratedOtp otp = OtpCodeGenerator.generate();
      assertEquals(6, otp.code().length());
      if (otp.code().charAt(0) == '0') {
        sawLeadingZero = true;
      }
    }
    assertTrue(sawLeadingZero, "expected at least one leading-zero code in 200 draws");
  }

  @Test
  void theSaltIsSixteenBytes() {
    assertEquals(16, OtpCodeGenerator.generate().salt().length);
  }

  @Test
  void theHashIsSha256OfSaltThenCode() throws NoSuchAlgorithmException {
    GeneratedOtp otp = OtpCodeGenerator.generate();

    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(otp.salt());
    digest.update(otp.code().getBytes(StandardCharsets.US_ASCII));
    byte[] expected = digest.digest();

    assertArrayEquals(expected, otp.codeHash());
  }

  @Test
  void successiveCallsProduceDistinctCodesAndSalts() {
    GeneratedOtp first = OtpCodeGenerator.generate();
    GeneratedOtp second = OtpCodeGenerator.generate();

    assertNotEquals(first.code(), second.code());
    assertFalse(java.util.Arrays.equals(first.salt(), second.salt()));
    assertFalse(java.util.Arrays.equals(first.codeHash(), second.codeHash()));
  }

  @Test
  void matchesIsTrueForTheCorrectCodeAndSalt() {
    GeneratedOtp otp = OtpCodeGenerator.generate();

    assertTrue(OtpCodeGenerator.matches(otp.code(), otp.salt(), otp.codeHash()));
  }

  @Test
  void matchesIsFalseForAWrongCode() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    String wrongCode = otp.code().equals("000000") ? "111111" : "000000";

    assertFalse(OtpCodeGenerator.matches(wrongCode, otp.salt(), otp.codeHash()));
  }

  @Test
  void matchesIsFalseWithTheRightCodeButTheWrongSalt() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    byte[] otherSalt = OtpCodeGenerator.generate().salt();

    assertFalse(OtpCodeGenerator.matches(otp.code(), otherSalt, otp.codeHash()));
  }

  @Test
  void manyCallsProduceIndependentlyRandomCodes() {
    // Not a proof of uniform distribution, just a sanity check against a broken generator that
    // always returns the same value or a narrow range.
    Set<String> codes = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      codes.add(OtpCodeGenerator.generate().code());
    }
    assertTrue(
        codes.size() > 40, "expected mostly-distinct codes across 50 draws, got " + codes.size());
  }
}
