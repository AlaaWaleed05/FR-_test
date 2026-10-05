package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.ExportFormat;
import com.sfbank.bayanati.operator.domain.ExportOutcome;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileListFilter;
import com.sfbank.bayanati.operator.service.ProfileExportService;
import java.time.Instant;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** operator.md "Export" — field data only, XLSX and CSV, capped at 10,000 rows. */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class ExportController {

  private static final MediaType XLSX_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  private static final MediaType CSV_MEDIA_TYPE =
      MediaType.parseMediaType("text/csv;charset=UTF-8");

  private final ProfileExportService exportService;

  public ExportController(ProfileExportService exportService) {
    this.exportService = exportService;
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
      OperatorIdentity identity,
      @RequestParam String format,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String provenance,
     
      @RequestParam(required = false) String rejectionReasonCode,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo,
      @RequestParam(required = false) String q) {

    ExportFormat exportFormat = parseFormat(format);
    ProfileListFilter filter =
        new ProfileListFilter(
            status,
            provenance,
            
            rejectionReasonCode,
            parseInstant(submittedFrom, "submittedFrom"),
            parseInstant(submittedTo, "submittedTo"),
            q);

    ExportOutcome outcome;
    try {
      outcome = exportService.export(identity, filter, exportFormat);
    } catch (AccessLevelRequiredException forbidden) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbidden.getMessage());
    }

    MediaType mediaType = exportFormat == ExportFormat.XLSX ? XLSX_MEDIA_TYPE : CSV_MEDIA_TYPE;
    String extension = exportFormat == ExportFormat.XLSX ? "xlsx" : "csv";

    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("profiles-export." + extension)
                .build()
                .toString())
        .header("X-Export-Row-Count", Integer.toString(outcome.rowCount()))
        .header("X-Export-Truncated", Boolean.toString(outcome.truncated()))
        .body(outcome.content());
  }

  private static ExportFormat parseFormat(String value) {
    try {
      return ExportFormat.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException notAFormat) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown format: " + value);
    }
  }

  private static Instant parseInstant(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException notIso) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is not a valid ISO-8601 instant: " + value);
    }
  }
}
