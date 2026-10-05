package com.sfbank.bayanati.audit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Canonical form is what makes the audit hash chain reproducible years later, so these are
 * byte-for-byte assertions, not shape assertions.
 */
class CanonicalJsonTest {

  @Test
  void membersAreSortedByKeyRegardlessOfInsertionOrder() {
    Map<String, Object> insertedBackwards = new LinkedHashMap<>();
    insertedBackwards.put("outcome", "ACTIVE");
    insertedBackwards.put("resultCode", 1);
    insertedBackwards.put("branch", "16");
    insertedBackwards.put("accountNumber", "0000000001");

    assertEquals(
        "{\"accountNumber\":\"0000000001\",\"branch\":\"16\",\"outcome\":\"ACTIVE\","
            + "\"resultCode\":1}",
        CanonicalJson.object(insertedBackwards));
  }

  @Test
  void sortingIsByUtf16CodeUnitNotByLocale() {
    // Uppercase letters sort before lowercase in UTF-16, which a locale-aware collation would not
    // guarantee. RFC 8785 section 3.2.3 requires the code-unit order.
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("a", 1);
    members.put("B", 2);
    members.put("A", 3);

    assertEquals("{\"A\":3,\"B\":2,\"a\":1}", CanonicalJson.object(members));
  }

  @Test
  void anEmptyObjectIsTwoBraces() {
    assertEquals("{}", CanonicalJson.object(Map.of()));
  }

  @Test
  void quotesAndBackslashesAreEscaped() {
    assertEquals("{\"k\":\"a\\\"b\\\\c\"}", CanonicalJson.object(Map.of("k", "a\"b\\c")));
  }

  @Test
  void theShorthandControlEscapesAreUsed() {
    assertEquals("{\"k\":\"\\b\\f\\n\\r\\t\"}", CanonicalJson.object(Map.of("k", "\b\f\n\r\t")));
  }

  @Test
  void otherControlCharactersUseTheFourDigitHexEscape() {
    String controlChars = new String(new char[] {1, 0x1f});

    assertEquals("{\"k\":\"\\u0001\\u001f\"}", CanonicalJson.object(Map.of("k", controlChars)));
  }

  @Test
  void keysAreEscapedTooNotJustValues() {
    assertEquals("{\"a\\nb\":\"v\"}", CanonicalJson.object(Map.of("a\nb", "v")));
  }

  @Test
  void arabicIsEmittedAsIsBecauseRfc8785OutputIsUtf8() {
    // Escaping it would still be valid JSON but a different byte sequence, and so a different hash.
    assertEquals("{\"branch\":\"الخرطوم\"}", CanonicalJson.object(Map.of("branch", "الخرطوم")));
  }

  @Test
  void supportedScalarTypesAreSerializedWithoutQuotes() {
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("i", 42);
    members.put("l", 9_000_000_000L);
    members.put("s", (short) 7);
    members.put("b", (byte) 3);
    members.put("t", Boolean.TRUE);
    members.put("f", Boolean.FALSE);

    assertEquals(
        "{\"b\":3,\"f\":false,\"i\":42,\"l\":9000000000,\"s\":7,\"t\":true}",
        CanonicalJson.object(members));
  }

  @Test
  void aNullValueIsTheJsonNullLiteral() {
    Map<String, Object> members = new HashMap<>();
    members.put("k", null);

    assertEquals("{\"k\":null}", CanonicalJson.object(members));
  }

  @Test
  void anUnsupportedValueTypeIsRejectedRatherThanSerializedApproximately() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", 1.5d)));

    assertTrue(thrown.getMessage().contains("k"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("Double"), thrown.getMessage());
  }

  @Test
  void nul_isRejectedBecauseThePayloadJsonbCastWouldRefuseIt() {
    String withNul = new String(new char[] {'0', 0, '1'});

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", withNul)));

    assertTrue(thrown.getMessage().contains("U+0000"), thrown.getMessage());
  }

  @Test
  void anUnpairedSurrogateIsRejectedRatherThanSilentlyBecomingAQuestionMark() {
    String loneHigh = new String(new char[] {'a', '\uD83D', 'b'});
    String loneLow = new String(new char[] {'a', '\uDE00', 'b'});

    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", loneHigh)))
            .getMessage()
            .contains("unpaired surrogate"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", loneLow)))
            .getMessage()
            .contains("unpaired surrogate"));
  }

  @Test
  void aSurrogateAtEitherEndOfTheStringIsAlsoCaughtAsUnpaired() {
    // The two edges of the pair check: a high surrogate with nothing after it, and a low surrogate
    // with nothing before it. Both are the positions a naive look-ahead/look-behind walks off.
    String trailingHigh = new String(new char[] {'a', '\uD83D'});
    String leadingLow = new String(new char[] {'\uDE00', 'a'});

    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJson.object(Map.of("k", trailingHigh)))
            .getMessage()
            .contains("unpaired surrogate"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", leadingLow)))
            .getMessage()
            .contains("unpaired surrogate"));
  }

  @Test
  void aWellFormedSurrogatePairIsEmittedAsIs() {
    assertEquals("{\"k\":\"\uD83D\uDE00\"}", CanonicalJson.object(Map.of("k", "\uD83D\uDE00")));
  }

  @Test
  void anIntegralValueOutsideTheRfc8785ExactRangeIsRejected() {
    // RFC 8785 serialises numbers as ECMAScript doubles. Emitting the exact decimal beyond 2^53
    // would diverge from every other JCS implementation -- which matters exactly when someone
    // outside this codebase recomputes the hash.
    long tooBig = (1L << 53) + 1;

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", tooBig)));

    assertTrue(thrown.getMessage().contains("2^53"), thrown.getMessage());
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", -(1L << 53) - 1)));
  }

  @Test
  void anIntegralValueAtTheBoundaryIsAccepted() {
    assertEquals("{\"k\":9007199254740992}", CanonicalJson.object(Map.of("k", 1L << 53)));
  }

  @Test
  void nestedStructuresAreRejectedNotFlattened() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalJson.object(Map.of("k", Map.of("nested", "value"))));
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalJson.object(Map.of("k", List.of("a", "b"))));
  }

  @Test
  void aNullKeyIsRejected() {
    Map<String, Object> members = new HashMap<>();
    members.put(null, "v");

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.object(members));

    assertTrue(thrown.getMessage().contains("null key"), thrown.getMessage());
  }
}
