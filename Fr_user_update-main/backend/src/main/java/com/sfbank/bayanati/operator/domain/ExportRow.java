package com.sfbank.bayanati.operator.domain;

import java.time.Instant;

/**
 * One profile's field data for export (operator.md "Export": "Field data only. No document
 * images."). Every column here is either a raw {@code app}/{@code ref} scalar or a server-resolved
 * label — never a storage key, checksum, or anything from {@code app.artifact_ref} (the export
 * query, {@code JdbcProfileListRepository#forExport}, never joins that table at all).
 *
 * <p>{@code provenance} is its own column, never folded into another field — operator.md: "must
 * never be pooled with digital ones in counts, exports or dashboard figures without the distinction
 * being visible".
 *
 * <p><strong>The profile UUID is deliberately NOT a column (BL-117, product-owner decision
 * 2026-09-11, removed at S8-15).</strong> The identity-image endpoint checks nothing but the
 * profile UUID, so anyone holding a copy of an export could fetch every passport and ID scan it
 * listed — and an export is a file that travels. This closes that specific route out of the trusted
 * group; it does not reopen R-051's deferral, which stands. {@code referenceNumber} is the
 * identifier operators and customers actually use, and it stays. Nothing downstream consumed the
 * UUID column: there is no export UI in the back office at all (BL-026 cut it from Phase 1), and
 * the two consumers that existed — {@code ProfileExportWriter} and the export audit event's {@code
 * fields} value — both derive from {@link #COLUMNS} and followed automatically.
 */
public record ExportRow(
    String referenceNumber,
    String accountNumber,
    
    
    String status,
    String statusLabelEn,
    String provenance,
    String phoneNumber,
    String emailAddress,
    String nameAr,
    String nameEn,
    String nationalNumber,
    Instant submittedAt,
    Instant createdAt,
    String rejectionReasonCode,
    String rejectionReasonLabelEn) {

  /**
   * Column keys in export order — both the header row {@code ProfileExportWriter} writes and the
   * {@code fields} value {@code ProfileExportService} records on the export's audit event, so the
   * two can never drift apart.
   */
  public static final String[] COLUMNS = {
    "referenceNumber",
    "accountNumber",
  
    "status",
    "statusLabelEn",
    "provenance",
    "phoneNumber",
    "emailAddress",
    "nameAr",
    "nameEn",
    "nationalNumber",
    "submittedAt",
    "createdAt",
    "rejectionReasonCode",
    "rejectionReasonLabelEn"
  };
}
