package com.sfbank.bayanati.dataentry.domain;

/**
 * Every stage 3-7 column of {@code app.profile_customer_data} as it stands before a submission
 * overwrites it — read once per submission so each stage's audit event can carry {@code
 * previous<Field>} values (customer.md: "a re-submission of an already-submitted stage is a
 * distinct event ... the permanent record must show what it was before"). All fields are {@code
 * null} on a profile's first stage 3-7 submission — there is nothing to compare against yet.
 */
public record CustomerDataSnapshot(
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
    String occupationCode,
    Long monthlyExpensesSdg,
    String homeCountryCode,
    String homeStateCode,
    String homeStateText,
    String homeLocalityCode,
    String homeLocalityText,
    String homeCity,
    String homeArea,
    String homeStreet,
    String homeBlock,
    String homeHouseNo,
    String employerName,
    String workCountryCode,
    String workStateCode,
    String workStateText,
    String workLocalityCode,
    String workLocalityText,
    String workCity,
    String workArea,
    String workStreet,
    String workBlock,
    String identityType) {}
