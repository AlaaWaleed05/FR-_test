package com.sfbank.bayanati.messaging.airtel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * All numbers here are synthetic. Direct assertions on the converted value — they cannot pass
 * against a missing or wrong conversion, so no revert-restore proof is needed.
 */
class AirtelDestinationTest {

  @Test
  void aStoredSudaneseNumberBecomesTheNationalFormAirtelExpects() {
    assertEquals(Optional.of("0912345678"), AirtelDestination.toNationalForm("+249912345678"));
  }

  @Test
  void theLeadingZeroReplacesTheCountryCodeRatherThanBeingPrepended() {
    // The whole conversion in one assertion: 9 significant digits in, 10 characters out.
    String converted = AirtelDestination.toNationalForm("+249900000000").orElseThrow();

    assertEquals("0900000000", converted);
    assertEquals(10, converted.length());
    assertTrue(converted.startsWith("0"));
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertEquals(Optional.of("0912345678"), AirtelDestination.toNationalForm("  +249912345678  "));
  }

  /**
   * {@code PhoneNumberNormalizer} deliberately passes a number with a foreign country code through
   * unchanged — the journey allows a customer verified from abroad — so these genuinely reach this
   * adapter. Airtel is a domestic route: there is no national form to convert them to, and sending
   * them anyway would return {@code Invalid Sudanese number}, which the system would then record as
   * the customer's number being bad.
   */
  @ParameterizedTest
  @ValueSource(strings = {"+971501234567", "+201234567890", "+441234567890"})
  void aNumberOutsideSudanHasNoNationalFormAndIsRefused(String foreign) {
    assertTrue(
        AirtelDestination.toNationalForm(foreign).isEmpty(),
        foreign + " must not be converted for a domestic gateway");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "   ",
        "0912345678", // already national: not the stored form, so not this method's input
        "249912345678", // no plus
        "+24991234567", // one digit short
        "+2499123456789", // one digit long
        "+24991234567a", // not all digits
        "+٢٤٩٩١٢٣٤٥٦٧٨" // Arabic-Indic digits, rejected at the boundary by CLAUDE.md's rule
      })
  void anythingThatIsNotAStoredSudaneseE164NumberIsRefused(String raw) {
    assertTrue(AirtelDestination.toNationalForm(raw).isEmpty(), "must refuse: " + raw);
  }
}
