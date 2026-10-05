package com.sfbank.bayanati.operator.domain;

import java.time.LocalDate;

/**
 * {@code app.registry_result} for the profile's most recent identity cycle.
 *
 * <p>{@code identityNumberReturned} (V0062) was added to this view at BL-132, because field 7
 * («الرقم الوطني») is a Civil Registry field on the printed form and nothing in the operator tier
 * exposed the registry's own value. The scan's {@code identityNumber} is provably equal on a
 * successful lookup — {@code HttpCivilRegistryClient} treats any difference as {@code not_found}
 * (AD-002b) — but printing the scan's copy under a "Civil Registry" heading would make the form's
 * provenance claim true only by coincidence, and false the day that guard is relaxed.
 */
public record RegistryResultView(
    String state,
    String nameArGiven,
    String nameArFather,
    String nameArGrandfather,
    String nameArGreatGrandfather,
    String nameArMother,
    String nameArMotherFather,
    String nameArMotherGrandfather,
    String nameArMotherGreatGrandfather,
    String firstNamesEn,
    String lastNameEn,
    String sexRegistry,
    LocalDate dateOfBirth,
    String rawAddressAr,
    String identityNumberReturned) {}
