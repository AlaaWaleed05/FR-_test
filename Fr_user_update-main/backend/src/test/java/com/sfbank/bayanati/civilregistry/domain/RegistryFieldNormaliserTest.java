package com.sfbank.bayanati.civilregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The product owner's Q11 matrix: parse what is parseable, null everything else, never throw. */
class RegistryFieldNormaliserTest {

  @Test
  void theObservedBirthDateShapeParses() {
    assertEquals(LocalDate.of(1990, 1, 1), RegistryFieldNormaliser.parseBirthDate("01/01/1990"));
    assertEquals(LocalDate.of(2000, 2, 29), RegistryFieldNormaliser.parseBirthDate("29/02/2000"));
    assertEquals(
        LocalDate.of(1985, 12, 31),
        RegistryFieldNormaliser.parseBirthDate(" 31/12/1985 "),
        "surrounding whitespace is stripped first");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "00/00/0000",
        "29/02/1991",
        "31/04/1990",
        "12/1990",
        "1990",
        "1990-01-01",
        "1/1/1990",
        "01/01/90",
        "01-01-1990",
        "٠١/٠١/١٩٩٠",
        "not a date"
      })
  void anythingElseIsNullNotAnException(String raw) {
    assertNull(RegistryFieldNormaliser.parseBirthDate(raw));
  }

  @Test
  void genderAcceptsExactlyLowercaseMAndFAfterStripAndLowercase() {
    assertEquals("m", RegistryFieldNormaliser.normaliseGender("m"));
    assertEquals("f", RegistryFieldNormaliser.normaliseGender("f"));
    assertEquals("m", RegistryFieldNormaliser.normaliseGender("M"));
    assertEquals("f", RegistryFieldNormaliser.normaliseGender("  F\t"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "male", "Male", "M.", "x", "1", "ذكر", "mf"})
  void anyOtherGenderValueIsNull(String raw) {
    // V0023's CHECK admits only 'm'/'f'; a pass-through would fail the insert.
    assertNull(RegistryFieldNormaliser.normaliseGender(raw));
  }

  @Test
  void stripCollapsesBlankToNullAndRemovesUnicodeSpaceSeparators() {
    assertNull(RegistryFieldNormaliser.strip(null));
    assertNull(RegistryFieldNormaliser.strip(""));
    assertNull(RegistryFieldNormaliser.strip(" \t\n"));
    assertEquals("x", RegistryFieldNormaliser.strip("　x "), "strip(), not trim()");
    assertEquals(
        " x",
        RegistryFieldNormaliser.strip(" x"),
        "the honest limit: a no-break space is not whitespace to Character.isWhitespace");
  }

  @Test
  void digitsOnlyStripsThePassportsGroupingSeparators() {
    // The shape a Sudanese passport prints, fabricated digits (CLAUDE.md hard rule).
    assertEquals("00000000001", RegistryFieldNormaliser.digitsOnly("000-0000-0001"));
  }

  @Test
  void digitsOnlyLeavesAnAlreadyBareNumberUntouchedIncludingLeadingZeros() {
    // The registry binds NID as a string, so a lost leading zero is a different lookup.
    assertEquals("00000000001", RegistryFieldNormaliser.digitsOnly("00000000001"));
  }

  @ParameterizedTest
  @ValueSource(strings = {" 000-0000-0001 ", "000 0000 0001", "000/0000/0001"})
  void digitsOnlyIsIndifferentToWhichSeparatorTheDocumentUsed(String raw) {
    assertEquals("00000000001", RegistryFieldNormaliser.digitsOnly(raw));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "---", "abc"})
  void digitsOnlyIsNullWhenThereIsNoDigitAtAll(String raw) {
    // The adapter turns this into the empty NID, which the service answers with the same 400 it
    // always has -- behaviour unchanged for a number that carries nothing to look up.
    assertNull(RegistryFieldNormaliser.digitsOnly(raw));
  }

  @Test
  void digitsOnlyKeepsOnlyAsciiDigitsNotArabicIndicOnes() {
    // Phone numbers reject non-ASCII digits at the boundary (CLAUDE.md); the same reasoning holds
    // here -- an Arabic-Indic digit is not something the registry has ever been shown to accept,
    // and silently transliterating one would be an assumption Q10 declined to make.
    assertNull(RegistryFieldNormaliser.digitsOnly("٠١٢"));
  }
}
