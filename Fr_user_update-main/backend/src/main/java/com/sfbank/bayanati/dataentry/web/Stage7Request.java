package com.sfbank.bayanati.dataentry.web;

/**
 * Journey Stage 7 (docs/journeys/customer.md) — identity document type.
 *
 * @param identityType {@code "passport"} or {@code "national_id"}
 */
public record Stage7Request(String profileId, String identityType) {}
