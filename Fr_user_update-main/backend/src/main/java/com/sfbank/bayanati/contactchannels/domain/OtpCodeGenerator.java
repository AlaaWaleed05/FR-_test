package com.sfbank.bayanati.contactchannels.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates one OTP code plus the salted hash {@code app.otp_challenge} stores in its place.
 *
 * <p>{@code app.otp_challenge.code_hash}'s own comment (V0007) is the contract this class satisfies
 * exactly: {@code sha256(salt || code)}, computed here in Java rather than by a database function —
 * {@code pgcrypto} is not installed and does not need to be, since the code must never touch the
 * database in plaintext to be hashed there.
 *
 * <p><strong>The generated {@link GeneratedOtp#code()} must never be logged, returned, or stored
 * anywhere but the caller's own stack frame</strong> long enough to render it into a message body
 * and then discard it — see docs/components/messaging.md, "DO NOT COPY" (the FIB defect this
 * product's whole design exists to avoid).
 *
 * <p>Pure logic — no Spring, no I/O, no clock — exercised by plain JUnit.
 */
public final class OtpCodeGenerator {

  private static final int CODE_DIGITS = 6;
  private static final int CODE_MODULUS = 1_000_000;
  private static final int SALT_LENGTH_BYTES = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  private OtpCodeGenerator() {}

  /**
   * @param codeHash {@code sha256(salt || code)}.
   */
  public record GeneratedOtp(String code, byte[] salt, byte[] codeHash) {}

  /**
   * Generates a fresh, independently random 6-digit code, salt and hash.
   *
   * <p><strong>{@code Locale.ROOT} is a security control here, not formatting hygiene.</strong>
   * Without it this line takes the JVM's default locale, and on a JVM defaulting to an Arabic-Indic
   * numbering locale — entirely plausible on an Arabic-first system serving Sudan — it emits
   * U+0660..U+0669 instead of ASCII digits. {@link #hash} then encodes the code as {@code
   * US_ASCII}, where every one of those becomes {@code '?'}, so EVERY challenge in the system
   * stores {@code sha256(salt || "??????")} and the code carries no entropy into the hash at all.
   * Since {@code OtpVerificationController} accepts any six characters {@code Character.isDigit}
   * admits, and that includes Arabic-Indic digits, any six of them submitted by anyone would hash
   * to the same value and verify. That is an OTP bypass for every channel and every customer,
   * reachable by a deployment setting rather than by a code change, and no test could see it
   * because the suite runs under the developer's locale.
   *
   * <p>Found at S9-07 while sweeping for default-locale formatting in the tests, proven live under
   * {@code -Duser.language=ar}, and fixed here rather than filed. The controller's digit gate is
   * tightened to ASCII in the same commit — either fix alone closes the hole, and both are kept
   * because the second is also what CLAUDE.md's "reject non-ASCII digits at the boundary" requires.
   */
  public static GeneratedOtp generate() {
    String code =
        String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", RANDOM.nextInt(CODE_MODULUS));
    byte[] salt = new byte[SALT_LENGTH_BYTES];
    RANDOM.nextBytes(salt);
    return new GeneratedOtp(code, salt, hash(salt, code));
  }

  /**
   * Stage 2's own verification check: recomputes {@code sha256(salt || code)} for a submitted code
   * and compares it against the stored hash with {@link MessageDigest#isEqual}, which is specified
   * to run in time independent of where the two arrays first differ — an ordinary {@code
   * Arrays.equals} short-circuits and would leak, over many requests, how many leading bytes of the
   * hash matched.
   */
  public static boolean matches(String code, byte[] salt, byte[] expectedHash) {
    return MessageDigest.isEqual(hash(salt, code), expectedHash);
  }

  private static byte[] hash(byte[] salt, String code) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(salt);
      digest.update(code.getBytes(StandardCharsets.US_ASCII));
      return digest.digest();
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is a mandatory JDK algorithm (JLS/JCA standard names) — this never actually throws.
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
