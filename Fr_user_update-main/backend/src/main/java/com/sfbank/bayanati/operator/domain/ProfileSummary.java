package com.sfbank.bayanati.operator.domain;

import java.time.Instant;

/**
 * operator.md's default profile-list columns: "account number · name · branch · status · provenance
 * · submitted date". {@code displayNameAr} is the best available name at list time — the Civil
 * Registry's composed name if the active identity cycle has one, else the on-document name, else
 * {@code null} for a profile that has not reached stage 8 yet ("incomplete profiles are visible").
 */
public record ProfileSummary(
    String profileId,
    String accountNumber,
    
    String displayNameAr,
    String displayNameEn,
    String status,
    String provenance,
    Instant submittedAt,
    Instant createdAt) {}
