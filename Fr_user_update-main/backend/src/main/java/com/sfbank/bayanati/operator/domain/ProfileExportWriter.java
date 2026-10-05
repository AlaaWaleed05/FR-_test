package com.sfbank.bayanati.operator.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Renders {@link ExportRow}s to XLSX (Apache POI SXSSF, per AD-006) and CSV bytes. Pure logic — no
 * Spring, no database, no network, no clock — mirrors {@code
 * submission.domain.SubmissionMessageRenderer}'s tier: a plain JUnit test builds a small {@code
 * List<ExportRow>} and asserts the bytes this class produces directly.
 *
 * <p><strong>Every cell/field is written as text</strong>, never a numeric type: account numbers,
 * phone numbers and reference numbers can carry leading digits a spreadsheet's own numeric
 * auto-detection would otherwise strip or reformat (scientific notation past 15 digits).
 *
 * <p><strong>CSV-injection mitigation</strong>: in the CSV output only, a value beginning with
 * {@code =}, {@code -} or {@code @} is prefixed with a leading {@code '} before writing — the
 * standard OWASP CSV-injection mitigation, since export fields include customer- and
 * Civil-Registry-supplied free text (names, and branch/status labels this codebase controls but
 * customer-entered names it does not) that reaches a spreadsheet application. {@code +} is
 * deliberately NOT a trigger character here despite appearing in OWASP's usual list: {@code
 * phoneNumber} is E.164 (CLAUDE.md: "everywhere — wire, storage, comparison and display masking")
 * and therefore always starts with {@code +} legitimately — treating every phone number in every
 * export as a formula-injection candidate would corrupt the one field operator.md explicitly names
 * CSV as being for ("anything downstream") on every single row, for a character this codebase's own
 * validation (BL-016) never lets arrive from anywhere but a genuine country code. XLSX gets no
 * sanitisation at all: {@link org.apache.poi.ss.usermodel.Cell#setCellValue(String)} writes a typed
 * string cell, which Excel never evaluates as a formula on open (the CSV-injection heuristic is
 * specific to untyped text formats, where Excel's own import guesses a leading {@code =}/{@code -}/
 * {@code @} means a formula) — sanitising it here would only corrupt data for no protective effect.
 */
public final class ProfileExportWriter {

  private static final String SHEET_NAME = "Profiles";
  private static final int PHONE_NUMBER_COLUMN_INDEX = columnIndex("phoneNumber");

  private ProfileExportWriter() {}

  public static byte[] toXlsx(List<ExportRow> rows) {
    SXSSFWorkbook workbook = new SXSSFWorkbook(100);
    try {
      Sheet sheet = workbook.createSheet(SHEET_NAME);
      writeRow(sheet.createRow(0), ExportRow.COLUMNS);
      int rowNum = 1;
      for (ExportRow row : rows) {
        writeRow(sheet.createRow(rowNum++), toValues(row));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write export workbook", e);
    } finally {
      // Removes the temp files SXSSF's streaming row window backs itself with -- required
      // regardless of whether write() above succeeded.
      workbook.dispose();
    }
  }

  public static byte[] toCsv(List<ExportRow> rows) {
    StringBuilder out = new StringBuilder();
    // UTF-8 BOM (U+FEFF, encodes to EF BB BF): without it, Excel opens Arabic CSV content as
    // mojibake, guessing a legacy code page instead of UTF-8. XLSX has no equivalent concern --
    // OOXML strings are UTF-8-encoded XML internally. Written as an escape, not a literal
    // character, so the byte-order mark itself never appears raw in this source file.
    out.append('\uFEFF');
    appendCsvRow(out, ExportRow.COLUMNS);
    for (ExportRow row : rows) {
      appendCsvRow(out, toValues(row));
    }
    return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** No sanitisation -- see this class's Javadoc for why XLSX string cells need none. */
  private static void writeRow(Row row, String[] values) {
    for (int i = 0; i < values.length; i++) {
      row.createCell(i).setCellValue(values[i]);
    }
  }

  private static void appendCsvRow(StringBuilder out, String[] values) {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(csvField(sanitizeForCsv(i, values[i])));
    }
    out.append("\r\n");
  }

  private static String csvField(String value) {
    boolean needsQuoting =
        value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (!needsQuoting) {
      return value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  /**
   * OWASP CSV-injection mitigation — see this class's Javadoc for why {@code +} is excluded and why
   * {@code phoneNumber} (the one field always starting with it) is skipped entirely.
   */
  private static String sanitizeForCsv(int columnIndex, String value) {
    if (columnIndex == PHONE_NUMBER_COLUMN_INDEX || value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    if (first == '=' || first == '-' || first == '@') {
      return "'" + value;
    }
    return value;
  }

  private static int columnIndex(String column) {
    for (int i = 0; i < ExportRow.COLUMNS.length; i++) {
      if (ExportRow.COLUMNS[i].equals(column)) {
        return i;
      }
    }
    throw new IllegalStateException("no such column: " + column);
  }

  /** In {@link ExportRow#COLUMNS} order — the two must never drift apart. */
  private static String[] toValues(ExportRow row) {
    return new String[] {
      nullToEmpty(row.referenceNumber()),
      nullToEmpty(row.accountNumber()),
      
      nullToEmpty(row.status()),
      nullToEmpty(row.statusLabelEn()),
      nullToEmpty(row.provenance()),
      nullToEmpty(row.phoneNumber()),
      nullToEmpty(row.emailAddress()),
      nullToEmpty(row.nameAr()),
      nullToEmpty(row.nameEn()),
      nullToEmpty(row.nationalNumber()),
      formatInstant(row.submittedAt()),
      formatInstant(row.createdAt()),
      nullToEmpty(row.rejectionReasonCode()),
      nullToEmpty(row.rejectionReasonLabelEn())
    };
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String formatInstant(Instant instant) {
    return instant == null ? "" : DateTimeFormatter.ISO_INSTANT.format(instant);
  }
}
