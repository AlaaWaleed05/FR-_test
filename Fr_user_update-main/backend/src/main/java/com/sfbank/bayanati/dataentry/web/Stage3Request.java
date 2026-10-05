package com.sfbank.bayanati.dataentry.web;

/**
 * Journey Stage 3 (docs/journeys/customer.md) — personal, social and birth data.
 *
 * @param spouseName present only when {@code maritalStatus} is {@code married}
 * @param hasChildren {@code null} when {@code maritalStatus} is {@code single} — the journey never
 *     asks a single customer this question
 * @param childrenCount present only when {@code hasChildren} is {@code true}
 * @param birthStateCode present only when {@code birthCountryCode} is {@code "SD"}
 * @param birthStateText present only when {@code birthCountryCode} is not {@code "SD"}
 * @param countryListVersion the pinned {@code country} list version (S4-04, AD-002f §5.3); {@code
 *     null} falls back to the server's current version — see {@code DataEntryService}'s Javadoc
 * @param adminDivisionListVersion the pinned {@code admin_division} list version, used only when
 *     {@code birthCountryCode} is {@code "SD"}; same fallback as {@code countryListVersion}
 */
public record Stage3Request(
    String profileId,
    String sexDeclared,
    String ethnicity,
    String countryOfResidenceCode,
    String maritalStatus,
    String spouseName,
    Boolean hasChildren,
    Integer childrenCount,
    Integer educationLevel,
    String birthCountryCode,
    String birthStateCode,
    String birthStateText,
    String birthCityText,
    Integer countryListVersion,
    Integer adminDivisionListVersion) {}
