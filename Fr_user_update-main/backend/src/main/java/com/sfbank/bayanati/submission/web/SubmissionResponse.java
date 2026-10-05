package com.sfbank.bayanati.submission.web;

import com.sfbank.bayanati.submission.service.SubmissionOutcome;
import java.util.List;

public record SubmissionResponse(
    String profileId, String referenceNumber, String status, List<String> verifiedChannels) {

  public static SubmissionResponse from(SubmissionOutcome outcome) {
    return new SubmissionResponse(
        outcome.profileId(),
        outcome.referenceNumber(),
        outcome.status(),
        outcome.verifiedChannels().stream().map(c -> c.wireValue()).toList());
  }
}
