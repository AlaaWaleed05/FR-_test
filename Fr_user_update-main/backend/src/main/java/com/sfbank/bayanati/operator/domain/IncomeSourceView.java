package com.sfbank.bayanati.operator.domain;

/** One {@code app.profile_income_source} row. */
public record IncomeSourceView(String sourceCode, boolean isPrimary, String otherText) {}
