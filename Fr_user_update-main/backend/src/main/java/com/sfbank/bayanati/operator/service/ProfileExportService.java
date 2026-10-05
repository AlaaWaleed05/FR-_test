package com.sfbank.bayanati.operator.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.ExportFormat;
import com.sfbank.bayanati.operator.domain.ExportOutcome;
import com.sfbank.bayanati.operator.domain.ExportResult;
import com.sfbank.bayanati.operator.domain.ExportRow;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorAuditPayload;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileExportWriter;
import com.sfbank.bayanati.operator.domain.ProfileListFilter;
import com.sfbank.bayanati.operator.domain.ProfileListRepository;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * operator.md "Export" — field data only (never document images), honouring the same filter the
 * profile list applies, capped at {@link #EXPORT_ROW_LIMIT} rows with no second approval.
 *
 * <p>"Every export is an audit event" (operator.md): recorded on the calling operator's own {@code
 * operator} chain, mirroring {@code OperatorProfileListService}'s {@code profile_list_searched}
 * shape exactly — same flattened filter fields, plus {@code format}, {@code rowCount}, {@code
 * truncated} and {@code fields} (the exported column list, so the audit record states precisely
 * what left the system, not just how much).
 */
@Service
public class ProfileExportService {

  static final String CHAIN_KIND = "operator";
  static final String EVENT_PROFILE_EXPORTED = "profile_exported";

  /** operator.md: "Limit: 10,000 rows per export. No second approval." */
  static final int EXPORT_ROW_LIMIT = 10_000;

  private final ProfileListRepository profileListRepository;
  private final AuditEventWriter auditEventWriter;

  public ProfileExportService(
      ProfileListRepository profileListRepository, AuditEventWriter auditEventWriter) {
    this.profileListRepository = profileListRepository;
    this.auditEventWriter = auditEventWriter;
  }

  public ExportOutcome export(
      OperatorIdentity identity, ProfileListFilter filter, ExportFormat format) {
    requireOperatorLevel(identity);
    profileListRepository.ensureOperatorChain(identity.operatorId());

    ExportResult result = profileListRepository.forExport(filter, EXPORT_ROW_LIMIT);
    byte[] content =
        switch (format) {
          case XLSX -> ProfileExportWriter.toXlsx(result.rows());
          case CSV -> ProfileExportWriter.toCsv(result.rows());
        };

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", filter.status());
    payload.put("provenance", filter.provenance());
    payload.put("branchCode", filter.branchCode());
    payload.put("rejectionReasonCode", filter.rejectionReasonCode());
    payload.put(
        "submittedFrom",
        filter.submittedFrom() == null
            ? null
            : DateTimeFormatter.ISO_INSTANT.format(filter.submittedFrom()));
    payload.put(
        "submittedTo",
        filter.submittedTo() == null
            ? null
            : DateTimeFormatter.ISO_INSTANT.format(filter.submittedTo()));
    payload.put("searchText", filter.searchText());
    payload.put("format", format.name());
    payload.put("rowCount", result.rows().size());
    payload.put("truncated", result.truncated());
    payload.put("fields", String.join(",", ExportRow.COLUMNS));

    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            identity.operatorId(),
            EVENT_PROFILE_EXPORTED,
            "operator",
            identity.operatorId(),
            null,
            null,
            UUID.randomUUID(),
            CanonicalJson.object(OperatorAuditPayload.withActorRole(identity, payload))));

    return new ExportOutcome(content, format, result.rows().size(), result.truncated());
  }

  private void requireOperatorLevel(OperatorIdentity identity) {
    if (identity.accessLevel() != OperatorAccessLevel.OPERATOR) {
      throw new AccessLevelRequiredException(
          "operator "
              + identity.operatorId()
              + " has access level "
              + identity.accessLevel()
              + ", which may not export");
    }
  }
}
