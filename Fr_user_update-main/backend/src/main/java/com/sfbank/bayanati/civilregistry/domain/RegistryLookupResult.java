package com.sfbank.bayanati.civilregistry.domain;

import java.time.LocalDate;

/**
 * The observed Civil Registry response shape (docs/components/civil-registry.md, one real Postman
 * sample 2026-08-23, confirmed live 2026-09-04), matching {@code app.registry_result}'s columns
 * (V0023, V0062) column-for-column.
 *
 * <p>Every field except {@code identityNumber} is optional by product-owner decision (Q12): the
 * adapter stores what comes and never rejects a record for a missing or malformed field. {@code
 * FIRST_NAMES} was empty in the one record whose fields were identified.
 *
 * @param identityNumber the registry's {@code IDENTITY_NUMBER}, read from the found record — never
 *     copied from the request (AD-002b, Q2). Equal to the number sent, by construction: a record
 *     with a different value is classified {@code not_found} and never becomes this type
 * @param sexRegistry {@code m} or {@code f}, normalised from {@code GENDER} by {@link
 *     RegistryFieldNormaliser#normaliseGender}; null for anything else (V0023's CHECK admits only
 *     those two values)
 * @param dateOfBirth parsed explicitly from the registry's {@code DD/MM/YYYY} by {@link
 *     RegistryFieldNormaliser#parseBirthDate} — never a locale default (field-provenance.md field
 *     21); null on any parse failure, never rejected (Q11)
 * @param rawAddressAr the registry's one comma-separated free-text Arabic address string — stored
 *     for comparison only, never decomposed into the structured address fields
 * @param photograph the second portrait (civil-registry.md {@code PHOTOGRAPH}), decoded from the
 *     inline base64 once, by the adapter — stored byte-identical (AD-004, {@code
 *     app.artifact_ref.body}), never re-encoded; null when absent or undecodable (the raw base64
 *     stays in the {@code civil_registry_response} artifact)
 */
public record RegistryLookupResult(
    String identityNumber,
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
    byte[] photograph) {}
