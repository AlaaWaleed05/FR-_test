package com.sfbank.bayanati.operator.domain;

import java.util.List;

/**
 * @param truncated {@code true} when more rows matched the filter than the caller's row limit —
 *     {@code rows} holds exactly {@code rowLimit} rows in that case, never more
 */
public record ExportResult(List<ExportRow> rows, boolean truncated) {}
