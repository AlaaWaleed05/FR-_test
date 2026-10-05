package com.sfbank.bayanati.dataentry.web;

/**
 * One entry of Stage 4's income-source multi-select.
 *
 * @param otherText only meaningful when {@code code} is {@code OTHER}
 */
public record IncomeSourceInput(String code, boolean primary, String otherText) {}
