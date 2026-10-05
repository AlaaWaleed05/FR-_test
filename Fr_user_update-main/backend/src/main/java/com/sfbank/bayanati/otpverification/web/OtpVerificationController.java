package com.sfbank.bayanati.otpverification.web;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.otpverification.domain.EmailCorrectionNotApplicableException;
import com.sfbank.bayanati.otpverification.domain.UnknownOtpChannelException;
import com.sfbank.bayanati.otpverification.service.OtpVerificationService;
import com.sfbank.bayanati.otpverification.service.ResendAttemptResult;
import com.sfbank.bayanati.otpverification.service.VerificationAttemptResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stage 2's two endpoints (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/otp")
public class OtpVerificationController {

  private static final int CODE_DIGITS = 6;

  /**
   * RFC 5321 §4.5.3.1.3 caps a mailbox at 254 characters — same cap as {@code
   * ContactChannelsController.MAX_EMAIL_LENGTH}.
   */
  private static final int MAX_EMAIL_LENGTH = 254;

  private final OtpVerificationService otpVerificationService;

  public OtpVerificationController(OtpVerificationService otpVerificationService) {
    this.otpVerificationService = otpVerificationService;
  }

  /**
   * {@code 200} always, carrying the outcome as a response field — {@code WRONG_CODE}/{@code
   * EXPIRED}/{@code CHANNEL_LOCKED} are expected states Stage 2's UI renders inline, not HTTP
   * errors (the same design already used for {@code declined} channel states in {@code
   * ContactChannelsResponse}). {@code 400} covers a malformed request or a channel this profile
   * never challenged ({@link UnknownOtpChannelException}).
   */
  @PostMapping("/verify")
  public OtpVerifyResponse verify(@RequestBody OtpVerifyRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    MessageChannel channel = parseChannel(request.channel());
    String code = parseCode(request.code());

    VerificationAttemptResult result;
    try {
      result = otpVerificationService.verify(profileId, channel, code);
    } catch (UnknownOtpChannelException unknownChannel) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unknownChannel.getMessage());
    }

    return new OtpVerifyResponse(
        result.channel().wireValue(),
        result.outcome().name(),
        result.channelState().wireValue(),
        result.sessionBlockedUntil() == null ? null : result.sessionBlockedUntil().toString());
  }

  /**
   * Same {@code 200}-carries-outcome / {@code 400}-for-malformed shape as {@link #verify}.
   *
   * <p>{@code correctedEmailAddress} (S4-06, BL-012) lets the customer fix a mistyped email address
   * in place before resending (customer.md Stage 2 "Corrections") — {@code 400} covers both a
   * malformed address (same length-cap/control-character bound as {@code
   * ContactChannelsController}) and one supplied for any channel but {@code email} ({@link
   * EmailCorrectionNotApplicableException}).
   */
  @PostMapping("/resend")
  public OtpResendResponse resend(@RequestBody OtpResendRequest request) {
    UUID profileId = parseProfileId(request.profileId());
    MessageChannel channel = parseChannel(request.channel());
    String correctedEmailAddress =
        cleanOptionalEmail(request.correctedEmailAddress(), "correctedEmailAddress");

    ResendAttemptResult result;
    try {
      result = otpVerificationService.resend(profileId, channel, correctedEmailAddress);
    } catch (UnknownOtpChannelException unknownChannel) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unknownChannel.getMessage());
    } catch (EmailCorrectionNotApplicableException notApplicable) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, notApplicable.getMessage());
    }

    return new OtpResendResponse(
        result.channel().wireValue(),
        result.outcome().name(),
        result.maskedDestination(),
        result.secondsUntilAllowed());
  }

  private static UUID parseProfileId(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is not a valid UUID");
    }
  }

  /**
   * {@code null}/blank means "no correction supplied" — same shape as {@code
   * ContactChannelsController.cleanOptional}. No email *format* validation exists anywhere in this
   * codebase, including at Stage 1b's own email intake, so none is invented here either — only the
   * same length-cap and control-character abuse bound every unauthenticated field in this backend
   * gets.
   */
  private static String cleanOptionalEmail(String value, String fieldName) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_EMAIL_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          fieldName + " is longer than " + MAX_EMAIL_LENGTH + " characters");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (Character.isISOControl(trimmed.charAt(i))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, fieldName + " contains a control character");
      }
    }
    return trimmed;
  }

  private static MessageChannel parseChannel(String value) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel is required");
    }
    try {
      return MessageChannel.fromWireValue(value.trim());
    } catch (IllegalArgumentException unrecognised) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel is not recognised");
    }
  }

  /**
   * customer.md: codes are always 6 ASCII digits (see {@code OtpCodeGenerator}).
   *
   * <p><strong>ASCII digits, not {@link Character#isDigit}.</strong> That method returns true for
   * every Unicode decimal digit, including Arabic-Indic U+0660..U+0669, and this is the boundary
   * CLAUDE.md's "reject non-ASCII digits at the boundary" rule is about. It mattered more than a
   * rule: {@code OtpCodeGenerator.hash} encodes with {@code US_ASCII}, so a non-ASCII digit becomes
   * {@code '?'} before hashing, and six of them all hash alike. Paired with a generator that could
   * itself emit Arabic-Indic digits under an Arabic-default JVM, that made any six such characters
   * verify against any challenge. The generator is fixed in the same commit; this gate is the
   * second of two independent closures, kept because a boundary that accepts what the hash cannot
   * represent is wrong whatever the generator does.
   */
  private static String parseCode(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.length() != CODE_DIGITS || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "code must be exactly " + CODE_DIGITS + " digits");
    }
    return trimmed;
  }
}
