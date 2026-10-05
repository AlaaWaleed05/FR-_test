package com.sfbank.bayanati.dataentry.domain;

/**
 * The result of {@link DataEntryRepository#lockAndGetStatus}: the profile's current status, taken
 * under a row lock, and whether that status is terminal per {@code app.status_code.is_terminal}.
 */
public record ProfileLock(String status, boolean terminal) {}
