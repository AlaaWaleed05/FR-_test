package com.sfbank.bayanati.submission.domain;

/**
 * One reference list version a profile's stage 3-6 answers were actually validated against, read
 * back from {@code app.profile_customer_data} at submission time for writing into {@code
 * ref.profile_reference_version} (S4-04, AD-002f §6.2).
 */
public record ListVersionUsed(String listCode, int version) {}
