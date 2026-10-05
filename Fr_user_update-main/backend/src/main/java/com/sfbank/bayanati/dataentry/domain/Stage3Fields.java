package com.sfbank.bayanati.dataentry.domain;

/**
 * Stage 3's full-replace write to {@code app.profile_customer_data} (customer.md Stage 3: personal,
 * social and birth data). Every field is set on every submission — the device sends its complete
 * current answer, never a patch (Stage 13's device-authoritative-on-reconcile rule).
 *
 * @param spouseName non-{@code null} only when {@code maritalStatus} is {@code married}
 * @param hasChildren {@code null} only when {@code maritalStatus} is {@code single} — the journey
 *     never asks a single customer this question
 * @param childrenCount non-{@code null} only when {@code hasChildren} is {@code true}
 * @param birthStateCode non-{@code null} only when {@code birthCountryCode} is {@code "SD"}
 * @param birthStateText non-{@code null} only when {@code birthCountryCode} is not {@code "SD"}
 * @param adminDivVersion the {@code admin_division} version {@code birthStateCode} was validated
 *     against, or {@code null} when birth country isn't Sudan (leaves the shared, single-column
 *     {@code admin_div_version} untouched — see R-045/BL-017)
 */
public record Stage3Fields(
    String sexDeclared,
    String ethnicity,
    String countryOfResidenceCode,
    int countryOfResidenceVersion,
    String maritalStatus,
    String spouseName,
    Boolean hasChildren,
    Integer childrenCount,
    int educationLevel,
    String birthCountryCode,
    int birthCountryVersion,
    String birthStateCode,
    String birthStateText,
    String birthCityText,
    Integer adminDivVersion) {}
