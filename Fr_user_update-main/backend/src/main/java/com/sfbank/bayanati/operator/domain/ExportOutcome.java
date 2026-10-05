package com.sfbank.bayanati.operator.domain;

/**
 * The bytes {@code ProfileExportService} produced, plus what the caller needs to build the HTTP
 * response — never re-parsed out of the bytes themselves.
 */
public record ExportOutcome(byte[] content, ExportFormat format, int rowCount, boolean truncated) {}
