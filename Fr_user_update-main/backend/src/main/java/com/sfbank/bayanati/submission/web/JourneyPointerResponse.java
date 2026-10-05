package com.sfbank.bayanati.submission.web;

import com.sfbank.bayanati.submission.service.JourneyPointer;
import java.util.List;

/**
 * What the Stage 10-12 resume read reports to the app.
 *
 * <p>{@code verifiedChannels} carries channel wire values only ({@code sms}/{@code whatsapp}/{@code
 * email}) — never a phone number or an email address, exactly as {@code SubmissionResponse} already
 * does. The read is on the unauthenticated customer surface, and the channel a decision travels on
 * is not itself PII.
 */
public record JourneyPointerResponse(
    String profileId,
    String stage,
    String blockedUntil,
    String referenceNumber,
    List<String> verifiedChannels) {

  public static JourneyPointerResponse from(JourneyPointer pointer) {
    return new JourneyPointerResponse(
        pointer.profileId(),
        pointer.stage().name(),
        pointer.blockedUntil() == null ? null : pointer.blockedUntil().toString(),
        pointer.referenceNumber(),
        pointer.verifiedChannels().stream().map(c -> c.wireValue()).sorted().toList());
  }
}
