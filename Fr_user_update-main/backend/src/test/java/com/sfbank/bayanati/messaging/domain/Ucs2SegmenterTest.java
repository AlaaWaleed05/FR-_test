package com.sfbank.bayanati.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Ucs2SegmenterTest {

  @Test
  void blankBodyIsZeroSegments() {
    assertEquals(0, Ucs2Segmenter.segments(""));
  }

  @Test
  void exactlyTheSingleSegmentLimitIsOneSegment() {
    assertEquals(1, Ucs2Segmenter.segments("a".repeat(70)));
  }

  @Test
  void oneCharacterOverTheSingleSegmentLimitConcatenates() {
    // 71 characters no longer fits in one segment, and concatenation uses the smaller 67-char
    // limit per segment (UDH overhead) -- so 71 needs 2 segments, not "1 segment + 1 char".
    assertEquals(2, Ucs2Segmenter.segments("a".repeat(71)));
  }

  @Test
  void exactlyTwoConcatenatedSegmentsAtTheBoundary() {
    assertEquals(2, Ucs2Segmenter.segments("a".repeat(134))); // 2 * 67
    assertEquals(3, Ucs2Segmenter.segments("a".repeat(135))); // 2*67 + 1
  }

  @Test
  void aRealisticArabicOtpBodyIsTwoSegments() {
    // The shape customer.md requires: names the bank, names its own channel, carries the code,
    // states validity. Matches the research's own anchor and estimate (AD-002c report §3.2,
    // §4.1): a realistic Arabic OTP body is 2 segments.
    String body =
        "البنك السوداني الفرنسي: رمز التحقق عبر الرسائل القصيرة الخاص بحسابك هو 482913. صالح لمدة 5 دقائق. لا تشاركه مع أحد.";
    assertEquals(2, Ucs2Segmenter.segments(body), "body length was " + body.length());
  }

  @Test
  void nullBodyThrows() {
    assertThrows(NullPointerException.class, () -> Ucs2Segmenter.segments(null));
  }
}
