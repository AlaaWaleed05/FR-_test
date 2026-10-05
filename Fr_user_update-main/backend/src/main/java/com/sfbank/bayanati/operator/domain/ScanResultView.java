package com.sfbank.bayanati.operator.domain;

import java.time.Instant;
import java.time.LocalDate;

/** {@code app.scan_result} for the profile's most recent identity cycle. */
public record ScanResultView(
    String documentType,
    String cardVariant,
    String identityNumber,
    String documentNumber,
    Boolean mrzVerified,
    String nationality,
    String sexOnDocument,
    LocalDate dateOfBirth,
    LocalDate dateOfIssue,
    LocalDate dateOfExpiry,
    String placeOfIssue,
    String issuingCountry,
    String nameArOnDocument,
    String nameEnOnDocument,
    String bloodType,
    String birthCity,
    Instant receivedAt) {}
