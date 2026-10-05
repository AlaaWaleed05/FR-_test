package com.sfbank.bayanati.dataentry.domain;

/**
 * Stage 5's full-replace write to {@code app.profile_customer_data} (customer.md Stage 5: home
 * address). {@code homeStateCode}/{@code homeLocalityCode} are populated when {@code
 * homeCountryCode} is {@code "SD"}; otherwise {@code homeStateText}/{@code homeLocalityText} are
 * (customer.md "Addresses outside Sudan": both fall back to free text).
 *
 * @param adminDivVersion the {@code admin_division} version the state/locality codes were validated
 *     against, or {@code null} when the country isn't Sudan (see Stage3Fields)
 */
public record Stage5Fields(
    String homeCountryCode,
    int homeCountryVersion,
    String homeStateCode,
    String homeStateText,
    String homeLocalityCode,
    String homeLocalityText,
    String homeCity,
    String homeArea,
    String homeStreet,
    String homeBlock,
    String homeHouseNo,
    Integer adminDivVersion) {}
