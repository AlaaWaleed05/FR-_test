package com.sfbank.bayanati.contactchannels.web;

import com.sfbank.bayanati.contactchannels.domain.InvalidPhoneNumberException;
import com.sfbank.bayanati.contactchannels.domain.NoPhoneChannelSelectedException;
import com.sfbank.bayanati.contactchannels.domain.PhoneNumberNormalizer;
import com.sfbank.bayanati.contactchannels.domain.ProfileAlreadyCompleteException;
import com.sfbank.bayanati.contactchannels.domain.SessionTemporarilyBlockedException;
import com.sfbank.bayanati.contactchannels.service.ContactChannelsResult;
import com.sfbank.bayanati.contactchannels.service.ContactChannelsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Journey Stage 1b's single endpoint (docs/journeys/customer.md). */
@RestController
@RequestMapping("/api/v1/contact-channels")
public class ContactChannelsController {

  /** Same generous, abuse-bound (not format-validating) cap as {@code AccountCheckController}. */
  static final int MAX_FIELD_LENGTH = 64;

  /** RFC 5321 §4.5.3.1.3 caps a mailbox at 254 characters. */
  static final int MAX_EMAIL_LENGTH = 254;

  private final ContactChannelsService contactChannelsService;

  public ContactChannelsController(ContactChannelsService contactChannelsService) {
    this.contactChannelsService = contactChannelsService;
  }

  /**
   * Creates the session and profile, records each channel's initial state, and issues one OTP per
   * challenged channel — or, for an account whose profile already exists, updates it (re-entry) or
   * rejects the call (an already-complete profile) instead. See {@link ContactChannelsService}'s
   * class Javadoc for the three-way branch (S3-07).
   *
   * <p>{@code 400} covers both malformed input (see {@link #clean}) and the journey's own
   * at-least-one-phone-channel rule ({@link NoPhoneChannelSelectedException}) — enforced here, not
   * only in the app, so calling the API directly cannot bypass it. {@code 409} covers a profile
   * that has already reached a terminal status ({@link ProfileAlreadyCompleteException}) — enforced
   * here too, so this endpoint refuses a terminal account even when called directly, bypassing
   * Stage 1a. {@code 429} (S3-08, R-044) covers a re-entry attempted while Stage 2's escalating
   * phone-lock block is still active ({@link SessionTemporarilyBlockedException}). Nothing is
   * written or sent before any of these checks runs.
   */
  @PostMapping
  public ContactChannelsResponse submit(@RequestBody ContactChannelsRequest request) {
    
    String accountNumber = clean(request.accountNumber(), "accountNumber", MAX_FIELD_LENGTH);
    String phoneNumber =
        normalizePhone(clean(request.phoneNumber(), "phoneNumber", MAX_FIELD_LENGTH));
    String emailAddress = cleanOptional(request.emailAddress(), "emailAddress", MAX_EMAIL_LENGTH);

    ContactChannelsResult result;
    try {
      result =
          contactChannelsService.submit(
             
              accountNumber,
              phoneNumber,
              request.smsSelected(),
              request.whatsappSelected(),
              emailAddress);
    } catch (NoPhoneChannelSelectedException noPhoneChannel) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, noPhoneChannel.getMessage());
    } catch (ProfileAlreadyCompleteException alreadyComplete) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, alreadyComplete.getMessage());
    } catch (SessionTemporarilyBlockedException temporarilyBlocked) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, temporarilyBlocked.getMessage());
    }

    List<ChannelInfo> channels =
        result.channels().stream()
            .map(
                c ->
                    new ChannelInfo(
                        c.channel().wireValue(), c.state().wireValue(), c.maskedDestination()))
            .toList();
    return new ContactChannelsResponse(result.profileId().toString(), channels);
  }

  /**
   * BL-016: normalises to E.164 at the boundary, after {@link #clean} has already rejected blank,
   * oversized or control-character input. {@link InvalidPhoneNumberException} covers both a
   * non-ASCII (e.g. Arabic-Indic) digit and any format {@link PhoneNumberNormalizer} does not
   * recognise.
   */
  private static String normalizePhone(String cleaned) {
    try {
      return PhoneNumberNormalizer.normalizeE164(cleaned);
    } catch (InvalidPhoneNumberException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
  }

  /** A required field: blank or oversized or containing a control character all fail 400. */
  private static String clean(String value, String fieldName, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return validated(trimmed, fieldName, maxLength);
  }

  /**
   * An optional field: blank or absent means "not supplied"; present still obeys {@link #clean}'s
   * bounds.
   */
  private static String cleanOptional(String value, String fieldName, int maxLength) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return validated(trimmed, fieldName, maxLength);
  }

  /**
   * The same abuse bound {@code AccountCheckController} applies: a generous length cap and a
   * rejection of control characters, because these values are written verbatim into an append-only
   * audit trail from an unauthenticated request.
   */
  private static String validated(String trimmed, String fieldName, int maxLength) {
    if (trimmed.length() > maxLength) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is longer than " + maxLength + " characters");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (Character.isISOControl(trimmed.charAt(i))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, fieldName + " contains a control character");
      }
    }
    return trimmed;
  }
}
