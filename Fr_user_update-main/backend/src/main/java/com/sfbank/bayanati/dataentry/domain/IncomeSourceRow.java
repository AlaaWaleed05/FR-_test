package com.sfbank.bayanati.dataentry.domain;

/**
 * One row of {@code app.profile_income_source} — either as submitted by the customer at Stage 4, or
 * as currently on record, read back before a resubmission replaces the set.
 *
 * @param otherText non-{@code null} only when {@code sourceCode} is {@code OTHER}
 */
public record IncomeSourceRow(String sourceCode, boolean primary, String otherText) {}
