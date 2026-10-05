package com.sfbank.bayanati.dataentry.web;

/**
 * Journey Stage 5 (docs/journeys/customer.md) — home address.
 *
 * @param stateCode/localityCode present when {@code countryCode} is {@code "SD"}
 * @param stateText/localityText present otherwise (customer.md "Addresses outside Sudan")
 * @param countryListVersion the pinned {@code country} list version (S4-04, AD-002f §5.3); {@code
 *     null} falls back to the server's current version
 * @param adminDivisionListVersion the pinned {@code admin_division} list version, used only when
 *     {@code countryCode} is {@code "SD"}; same fallback
 */
public record Stage5Request(
    String profileId,
    String countryCode,
    String stateCode,
    String stateText,
    String localityCode,
    String localityText,
    String city,
    String area,
    String street,
    String block,
    String houseNumber,
    Integer countryListVersion,
    Integer adminDivisionListVersion) {}
