package com.sfbank.bayanati.operator.domain;

import java.util.List;

/**
 * {@code app.profile_customer_data} plus its income sources — every field the customer entered
 * across stages 3-6 (field-provenance.md), as-is. This view does not resolve reference codes
 * (occupation, admin_division, country) to labels — the operator UI already has the reference-data
 * delivery contract (AD-002f) for that, and duplicating it here would be a second, driftable copy.
 */
public record CustomerDataView(
    String phoneNumber,
    String emailAddress,
    String sexDeclared,
    String maritalStatus,
    String spouseName,
    Boolean hasChildren,
    Integer childrenCount,
    Integer educationLevel,
    String occupationCode,
    Integer occupationVersion,
    Long monthlyExpensesSdg,
    String identityType,
    String ethnicity,
    String countryOfResidenceCode,
    String birthCountryCode,
    String birthStateCode,
    String birthStateText,
    String birthCityText,
    String homeCountryCode,
    String homeStateCode,
    String homeLocalityCode,
    String homeStateText,
    String homeLocalityText,
    String homeCity,
    String homeArea,
    String homeStreet,
    String homeBlock,
    String homeHouseNo,
    String employerName,
    String workCountryCode,
    String workStateCode,
    String workLocalityCode,
    String workStateText,
    String workLocalityText,
    String workCity,
    String workArea,
    String workStreet,
    String workBlock,
    List<IncomeSourceView> incomeSources) {}
