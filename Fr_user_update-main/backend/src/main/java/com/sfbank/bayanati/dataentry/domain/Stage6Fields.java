package com.sfbank.bayanati.dataentry.domain;

/**
 * Stage 6's full-replace write to {@code app.profile_customer_data} (customer.md Stage 6: work
 * address and employer). Same cascade rule as {@link Stage5Fields}, minus a house number — the form
 * carries the employer in its place.
 *
 * @param salaryCertificateClaimed whether the customer says they attached a salary certificate
 *     (BL-122). The one field here that is NOT part of the full replace: it is written
 *     monotonically, never cleared, because a Stage 6 replay queued before the customer picked
 *     their file would otherwise erase a claim made after it. {@code null} is "this client did not
 *     say" and, like {@code false}, leaves any existing claim standing.
 */
public record Stage6Fields(
    String employerName,
    String workCountryCode,
    int workCountryVersion,
    String workStateCode,
    String workStateText,
    String workLocalityCode,
    String workLocalityText,
    String workCity,
    String workArea,
    String workStreet,
    String workBlock,
    Integer adminDivVersion,
    Boolean salaryCertificateClaimed) {}
