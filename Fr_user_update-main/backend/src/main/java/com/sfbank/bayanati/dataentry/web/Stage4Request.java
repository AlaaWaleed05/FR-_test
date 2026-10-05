package com.sfbank.bayanati.dataentry.web;

import java.util.List;

/**
 * Journey Stage 4 (docs/journeys/customer.md) — occupation and income.
 *
 * @param monthlyExpensesSdg digits only, SDG — parsed and range-checked at the controller boundary,
 *     never trusted as a pre-parsed number
 * @param occupationListVersion the pinned {@code occupation} list version (S4-04, AD-002f §5.3);
 *     {@code null} falls back to the server's current version
 * @param incomeSourceListVersion the pinned {@code income_source} list version; same fallback
 */
public record Stage4Request(
    String profileId,
    String occupationCode,
    List<IncomeSourceInput> incomeSources,
    String monthlyExpensesSdg,
    Integer occupationListVersion,
    Integer incomeSourceListVersion) {}
