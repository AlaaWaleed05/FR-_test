package com.sfbank.bayanati.reference.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ReferenceDocumentGenerator} in isolation — no Spring, no database, no clock. Exact-bytes
 * assertions, since this class's whole purpose is that identical input always produces identical
 * output (AD-002f's {@code content_hash} is defined over these bytes).
 */
class ReferenceDocumentGeneratorTest {

  private static String generateToString(ReferenceListDocumentInput input) {
    return new String(ReferenceDocumentGenerator.generate(input), StandardCharsets.UTF_8);
  }

  @Test
  void flatListWithOneItemAndNoExtra() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "occupation",
            1,
            1,
            "المهنة",
            "Occupation",
            false,
            null,
            List.of(
                new ReferenceDocumentItem(
                    "1", null, "طبيب", "Doctor", "طبيب", "doctor", 97, true, null)));

    String actual = generateToString(input);

    assertEquals(
        "{\"listCode\":\"occupation\",\"version\":1,\"itemCount\":1,\"nameAr\":\"المهنة\","
            + "\"nameEn\":\"Occupation\",\"isHierarchical\":false,\"rootItemCode\":null,"
            + "\"items\":[{\"itemCode\":\"1\",\"parentCode\":null,\"labelAr\":\"طبيب\","
            + "\"labelEn\":\"Doctor\",\"searchAr\":\"طبيب\",\"searchEn\":\"doctor\","
            + "\"sortOrdinal\":97,\"isActive\":true,\"extra\":null}]}",
        actual);
  }

  @Test
  void hierarchicalListCarriesRootItemCodeAndItemParentCode() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "admin_division",
            1,
            2,
            "التقسيم الإداري",
            "Administrative division",
            true,
            "SD",
            List.of(
                new ReferenceDocumentItem(
                    "SD", null, "السودان", "Sudan", "السودان", "sudan", 1, true, null),
                new ReferenceDocumentItem(
                    "11", "SD", "الشمالية", "Northern", "الشمالية", "northern", 2, true, null)));

    String actual = generateToString(input);

    assertEquals(
        "{\"listCode\":\"admin_division\",\"version\":1,\"itemCount\":2,"
            + "\"nameAr\":\"التقسيم الإداري\",\"nameEn\":\"Administrative division\","
            + "\"isHierarchical\":true,\"rootItemCode\":\"SD\",\"items\":["
            + "{\"itemCode\":\"SD\",\"parentCode\":null,\"labelAr\":\"السودان\","
            + "\"labelEn\":\"Sudan\",\"searchAr\":\"السودان\",\"searchEn\":\"sudan\","
            + "\"sortOrdinal\":1,\"isActive\":true,\"extra\":null},"
            + "{\"itemCode\":\"11\",\"parentCode\":\"SD\",\"labelAr\":\"الشمالية\","
            + "\"labelEn\":\"Northern\",\"searchAr\":\"الشمالية\",\"searchEn\":\"northern\","
            + "\"sortOrdinal\":2,\"isActive\":true,\"extra\":null}]}",
        actual);
  }

  @Test
  void extraIsEmbeddedVerbatimNotReparsed() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "country",
            1,
            1,
            "الدولة",
            "Country",
            false,
            null,
            List.of(
                new ReferenceDocumentItem(
                    "SD",
                    null,
                    "السودان",
                    "Sudan",
                    "السودان",
                    "sudan",
                    1,
                    true,
                    "{\"alpha3\": \"SDN\"}")));

    String actual = generateToString(input);

    // The raw fragment is embedded exactly as supplied, including its own internal whitespace --
    // proof that this class does not re-parse or re-serialise `extra`.
    org.junit.jupiter.api.Assertions.assertTrue(
        actual.endsWith("\"extra\":{\"alpha3\": \"SDN\"}}]}"));
  }

  @Test
  void emptyItemsProducesAnEmptyArray() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput("branch", 1, 0, "الفرع", "Branch", false, null, List.of());

    String actual = generateToString(input);

    assertEquals(
        "{\"listCode\":\"branch\",\"version\":1,\"itemCount\":0,\"nameAr\":\"الفرع\","
            + "\"nameEn\":\"Branch\",\"isHierarchical\":false,\"rootItemCode\":null,"
            + "\"items\":[]}",
        actual);
  }

  @Test
  void nullLabelEnAndSearchEnAreSerialisedAsJsonNull() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "occupation",
            1,
            1,
            "المهنة",
            "Occupation",
            false,
            null,
            List.of(
                new ReferenceDocumentItem("1", null, "طبيب", null, "طبيب", null, 1, true, null)));

    String actual = generateToString(input);

    org.junit.jupiter.api.Assertions.assertTrue(actual.contains("\"labelEn\":null"));
    org.junit.jupiter.api.Assertions.assertTrue(actual.contains("\"searchEn\":null"));
  }

  @Test
  void quotesAndBackslashesInLabelsAreEscaped() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "occupation",
            1,
            1,
            "المهنة",
            "Say \"hi\" \\ ok",
            false,
            null,
            List.of(new ReferenceDocumentItem("1", null, "طبيب", null, null, null, 1, true, null)));

    String actual = generateToString(input);

    org.junit.jupiter.api.Assertions.assertTrue(
        actual.contains("\"nameEn\":\"Say \\\"hi\\\" \\\\ ok\""));
  }

  @Test
  void namedControlCharacterEscapesAndGenericLowControlEscapeAreBothCorrect() {
    // Built from character codes only, never a source-level backslash escape, for the same
    // reason nulCharacterIsRejected() avoids one: this keeps the exact bytes this test needs
    // unambiguous regardless of how the surrounding tooling might otherwise interpret a literal
    // "\n"/"" appearing in this file's own text.
    char backspace = (char) 0x08;
    char formFeed = (char) 0x0C;
    char lineFeed = (char) 0x0A;
    char carriageReturn = (char) 0x0D;
    char tab = (char) 0x09;
    char soh = (char) 0x01; // a low control character with no named JSON escape
    String rawControlChars = "" + backspace + formFeed + lineFeed + carriageReturn + tab + soh;

    char backslash = (char) 0x5C;
    String expectedEscapes =
        "" + backslash + 'b' + backslash + 'f' + backslash + 'n' + backslash + 'r' + backslash + 't'
            + backslash + "u0001";

    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "occupation",
            1,
            1,
            rawControlChars,
            "Occupation",
            false,
            null,
            List.of(new ReferenceDocumentItem("1", null, "x", null, null, null, 1, true, null)));

    String actual = generateToString(input);

    org.junit.jupiter.api.Assertions.assertTrue(
        actual.contains("\"nameAr\":\"" + expectedEscapes + "\""),
        "expected escapes not found in: " + actual);
  }

  @Test
  void sameInputProducesByteIdenticalOutputAcrossRuns() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "income_source",
            1,
            1,
            "مصدر الدخل",
            "Income source",
            false,
            null,
            List.of(
                new ReferenceDocumentItem(
                    "RATIB", null, "راتب", "Salary", "راتب", "salary", 1, true, null)));

    byte[] first = ReferenceDocumentGenerator.generate(input);
    byte[] second = ReferenceDocumentGenerator.generate(input);

    assertEquals(
        new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
  }

  @Test
  void unpairedSurrogateIsRejected() {
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "occupation",
            1,
            1,
            "\uD800", // lone high surrogate, no low surrogate follows
            "Occupation",
            false,
            null,
            List.of(new ReferenceDocumentItem("1", null, "x", null, null, null, 1, true, null)));

    assertThrows(IllegalArgumentException.class, () -> ReferenceDocumentGenerator.generate(input));
  }

  @Test
  void nulCharacterIsRejected() {
    // Built via char concatenation, not a source-level escape, so the compiled .java file
    // itself never carries a raw NUL byte -- only the resulting String value does, which is
    // exactly what this test needs to exercise.
    String withEmbeddedNul = "a" + (char) 0 + "b";
    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            "occupation",
            1,
            1,
            withEmbeddedNul,
            "Occupation",
            false,
            null,
            List.of(new ReferenceDocumentItem("1", null, "x", null, null, null, 1, true, null)));

    assertThrows(IllegalArgumentException.class, () -> ReferenceDocumentGenerator.generate(input));
  }
}
