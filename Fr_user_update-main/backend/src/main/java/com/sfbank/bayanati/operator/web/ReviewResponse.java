package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.ReviewOutcome;
import java.util.List;

public record ReviewResponse(
    String profileId, String status, boolean alreadyDone, List<String> notifiedChannels) {

  public static ReviewResponse from(ReviewOutcome outcome) {
    return new ReviewResponse(
        outcome.profileId(),
        outcome.status(),
        outcome.alreadyDone(),
        outcome.notifiedChannels().stream().map(c -> c.wireValue()).toList());
  }
}
