package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.ProfileSummary;
import java.time.Instant;

public record ProfileSummaryResponse(
    String profileId,
    String accountNumber,
    String branchCode,
    String displayNameAr,
    String displayNameEn,
    String status,
    String provenance,
    Instant submittedAt,
    Instant createdAt) {

  public static ProfileSummaryResponse from(ProfileSummary summary) {
    return new ProfileSummaryResponse(
        summary.profileId(),
        summary.accountNumber(),
        summary.branchCode(),
        summary.displayNameAr(),
        summary.displayNameEn(),
        summary.status(),
        summary.provenance(),
        summary.submittedAt(),
        summary.createdAt());
  }
}
