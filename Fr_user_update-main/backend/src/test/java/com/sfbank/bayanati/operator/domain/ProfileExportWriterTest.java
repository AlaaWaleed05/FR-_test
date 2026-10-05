package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit — no Spring, no database, no network, no clock, per CLAUDE.md's business-logic tier.
 * The Arabic round trip in both formats, and the CSV-injection mitigation.
 */
class ProfileExportWriterTest {

  private static final ExportRow ARABIC_ROW =
      new ExportRow(
          "FRU-000000042",
          "0000000900",
          "16",
          "Khartoum North",
          "الخرطوم بحري",
          "submitted",
          "Submitted",
          "manual",
          "+249911009001",
          "customer@example.sd",
          "محمد أحمد الطيب",
          "Mohammed Ahmed Eltayeb",
          "1234567890",
          Instant.parse("2026-08-31T10:15:00Z"),
          Instant.parse("2026-08-30T09:00:00Z"),
          null,
          null);

  /**
   * BL-117, S8-15 — product-owner decision. The profile UUID is gone from the export, and this is
   * the guard that keeps it gone.
   *
   * <p>Why it mattered: the identity-image endpoint authorises on nothing but the profile UUID, so
   * anyone holding a copy of an export could fetch every passport and ID scan it listed — and an
   * export is a file that travels, out of the trusted group and beyond any audit. R-051's deferral
   * is untouched by this; what closes here is the specific route by which the id left the building.
   *
   * <p>Asserted over the whole COLUMNS array rather than by index, because both writers and the
   * export audit event's {@code fields} value all derive from it — so this one assertion covers the
   * header row, every data row, and the audit record together.
   */
  @Test
  void theProfileUuidIsNotAnExportColumn() {
    assertFalse(
        List.of(ExportRow.COLUMNS).contains("profileId"),
        "the profile UUID must never travel in an export file");
    assertEquals("referenceNumber", ExportRow.COLUMNS[0], "reference number is the operator's id");
  }

  @Test
  void xlsxRoundTripsArabicTextExactly() throws IOException {
    byte[] bytes = ProfileExportWriter.toXlsx(List.of(ARABIC_ROW));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      Row header = workbook.getSheetAt(0).getRow(0);
      for (int i = 0; i < ExportRow.COLUMNS.length; i++) {
        assertEquals(ExportRow.COLUMNS[i], header.getCell(i).getStringCellValue());
      }
      Row dataRow = workbook.getSheetAt(0).getRow(1);
      assertEquals("الخرطوم بحري", dataRow.getCell(branchLabelArIndex()).getStringCellValue());
      assertEquals("محمد أحمد الطيب", dataRow.getCell(nameArIndex()).getStringCellValue());
    }
  }

  @Test
  void csvRoundTripsArabicTextExactlyWithUtf8Bom() {
    byte[] bytes = ProfileExportWriter.toCsv(List.of(ARABIC_ROW));

    // The first three bytes are the UTF-8 BOM (EF BB BF), the encoded form of U+FEFF.
    assertEquals((byte) 0xEF, bytes[0]);
    assertEquals((byte) 0xBB, bytes[1]);
    assertEquals((byte) 0xBF, bytes[2]);

    String text = new String(bytes, StandardCharsets.UTF_8);
    assertEquals('\uFEFF', text.charAt(0));
    String[] lines = text.substring(1).split("\r\n");
    assertEquals(2, lines.length);
    String[] header = lines[0].split(",", -1);
    assertEquals(List.of(ExportRow.COLUMNS), List.of(header));
    String[] dataRow = lines[1].split(",", -1);
    assertEquals("الخرطوم بحري", dataRow[branchLabelArIndex()]);
    assertEquals("محمد أحمد الطيب", dataRow[nameArIndex()]);
  }

  @Test
  void nullFieldsBecomeEmptyStringsNotTheWordNull() {
    byte[] bytes = ProfileExportWriter.toCsv(List.of(ARABIC_ROW));
    String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
    String[] dataRow = text.split("\r\n")[1].split(",", -1);
    assertEquals("", dataRow[rejectionReasonCodeIndex()]);
  }

  @Test
  void csvInjectionCandidateValuesArePrefixedWithAQuote() {
    ExportRow formulaRow =
        new ExportRow(
            "FRU-000000043",
            "0000000901",
            "16",
            "=cmd|' /C calc'!A1",
            null,
            "in_progress",
            "In progress",
            "digital",
            "+249911009002",
            null,
            null,
            "-2+3",
            null,
            null,
            null,
            null,
            null);
    byte[] bytes = ProfileExportWriter.toCsv(List.of(formulaRow));
    String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
    String[] dataRow = text.split("\r\n")[1].split(",", -1);
    assertTrue(dataRow[branchLabelEnIndex()].startsWith("'="));
    assertTrue(dataRow[nameEnIndex()].startsWith("'-"));
  }

  @Test
  void csvPhoneNumberIsNeverPrefixedDespiteStartingWithAPlus() {
    // E.164 phone numbers always start with '+' -- the one OWASP trigger character this class
    // deliberately excludes (see ProfileExportWriter's Javadoc), since flagging every phone
    // number in every export would corrupt the field on every single row.
    byte[] bytes = ProfileExportWriter.toCsv(List.of(ARABIC_ROW));
    String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
    String[] dataRow = text.split("\r\n")[1].split(",", -1);
    assertEquals("+249911009001", dataRow[phoneNumberIndex()]);
  }

  @Test
  void xlsxCellsAreNeverSanitisedSinceStringCellsAreNotEvaluatedAsFormulas() throws IOException {
    ExportRow formulaRow =
        new ExportRow(
            "FRU-000000044",
            "0000000902",
            "16",
            "=cmd|' /C calc'!A1",
            null,
            "in_progress",
            "In progress",
            "digital",
            "+249911009003",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    byte[] bytes = ProfileExportWriter.toXlsx(List.of(formulaRow));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      Row dataRow = workbook.getSheetAt(0).getRow(1);
      assertEquals(
          "=cmd|' /C calc'!A1", dataRow.getCell(branchLabelEnIndex()).getStringCellValue());
    }
  }

  private static int branchLabelArIndex() {
    return columnIndex("branchLabelAr");
  }

  private static int branchLabelEnIndex() {
    return columnIndex("branchLabelEn");
  }

  private static int nameArIndex() {
    return columnIndex("nameAr");
  }

  private static int nameEnIndex() {
    return columnIndex("nameEn");
  }

  private static int phoneNumberIndex() {
    return columnIndex("phoneNumber");
  }

  private static int rejectionReasonCodeIndex() {
    return columnIndex("rejectionReasonCode");
  }

  private static int columnIndex(String column) {
    for (int i = 0; i < ExportRow.COLUMNS.length; i++) {
      if (ExportRow.COLUMNS[i].equals(column)) {
        return i;
      }
    }
    throw new IllegalStateException("no such column: " + column);
  }
}
