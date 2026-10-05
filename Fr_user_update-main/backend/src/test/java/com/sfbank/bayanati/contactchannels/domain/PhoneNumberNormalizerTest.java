package com.sfbank.bayanati.contactchannels.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PhoneNumberNormalizerTest {

  @Test
  void localSudanFormatConvertsToE164() {
    assertEquals("+249912345678", PhoneNumberNormalizer.normalizeE164("0912345678"));
  }

  @Test
  void alreadyE164PassesThroughUnchanged() {
    assertEquals("+249900004821", PhoneNumberNormalizer.normalizeE164("+249900004821"));
  }

  @Test
  void internationalPrefixIsConvertedToPlus() {
    assertEquals("+249900004821", PhoneNumberNormalizer.normalizeE164("00249900004821"));
  }

  @Test
  void separatorsAreStrippedBeforeParsing() {
    assertEquals("+249912345678", PhoneNumberNormalizer.normalizeE164("091-234 (5678)"));
  }

  @Test
  void sudanCountryCodeWithATrunkZeroKeptInIsNormalisedWithoutIt() {
    // A common mis-dial: "+249 0912345678" keeps the local trunk zero. Must normalise to the
    // exact same string the local-format branch produces for the same underlying number, or the
    // re-entry audit trail records a false delta (BL-016; found by @agent-reviewer).
    assertEquals("+249912345678", PhoneNumberNormalizer.normalizeE164("+2490912345678"));
    assertEquals("+249912345678", PhoneNumberNormalizer.normalizeE164("002490912345678"));
  }

  @Test
  void leadingZeroAfterPlusIsRejected() {
    // No ITU E.164 country code begins with 0 -- without rejecting this, "+0912345678" would be
    // stored verbatim and produce a false delta against "0912345678" (which normalises to
    // +249912345678). Found on re-review by @agent-reviewer.
    assertThrows(
        InvalidPhoneNumberException.class,
        () -> PhoneNumberNormalizer.normalizeE164("+0912345678"));
  }

  @Test
  void leadingZeroAfterInternationalPrefixIsRejected() {
    assertThrows(
        InvalidPhoneNumberException.class,
        () -> PhoneNumberNormalizer.normalizeE164("000912345678"));
  }

  @Test
  void foreignE164NumberPassesThroughUnchanged() {
    // A diaspora customer verified over WhatsApp from abroad (customer.md Stage 5) — this class
    // has no basis to reformat a number it didn't mint the country code for.
    assertEquals("+14155552671", PhoneNumberNormalizer.normalizeE164("+14155552671"));
  }

  @Test
  void arabicIndicDigitsAreRejectedNotTransliterated() {
    InvalidPhoneNumberException thrown =
        assertThrows(
            InvalidPhoneNumberException.class,
            () -> PhoneNumberNormalizer.normalizeE164("+٢٤٩912345678"));
    assertEquals(true, thrown.getMessage().contains("non-ASCII digit"));
  }

  @Test
  void extendedArabicIndicDigitsAreAlsoRejected() {
    assertThrows(
        InvalidPhoneNumberException.class,
        () -> PhoneNumberNormalizer.normalizeE164("+۲۴۹912345678"));
  }

  @Test
  void ambiguousLocalLengthIsRejected() {
    // Not 10 digits total (0 + 9), and no international prefix -- this class has no confident
    // conversion for it.
    assertThrows(
        InvalidPhoneNumberException.class, () -> PhoneNumberNormalizer.normalizeE164("091234"));
  }

  @Test
  void nonDigitCharactersAreRejected() {
    assertThrows(
        InvalidPhoneNumberException.class,
        () -> PhoneNumberNormalizer.normalizeE164("+249abc4821"));
  }

  @Test
  void tooLongAfterPlusIsRejected() {
    assertThrows(
        InvalidPhoneNumberException.class,
        () -> PhoneNumberNormalizer.normalizeE164("+1234567890123456"));
  }
}
