package com.sfbank.bayanati.messaging.airtel;

/**
 * One Airtel response, parsed. Produced only by {@link AirtelSmsResponseParser}.
 *
 * <p>Deliberately does NOT carry the raw response body. An unrecognised body may be an HTML error
 * or proxy page, and such pages commonly echo the requested URL — which for this gateway carries
 * the account password in its query string. {@code providerStatusText} is persisted to {@code
 * app.notification_outbox} and written into the audit trail, so a raw body reaching this record
 * would be a credential leak into durable storage. {@link #unrecognisedReason} is our own bounded,
 * credential-free description instead.
 *
 * @param kind what the parser concluded
 * @param statusWord the top line's status verbatim ({@code completed} / {@code failed}), null when
 *     the top line was not recognised
 * @param totalUnits the {@code Total Units} figure, or {@code -1} when it could not be read
 * @param echoedNumber the recipient the response named back, null when there was no usable
 *     recipient line
 * @param apiMsgId the provider's own message id, present only on {@link Kind#SUCCESS}
 * @param failureReason Airtel's own words from a {@code -> FAILED: <reason>} line, present only on
 *     {@link Kind#RECIPIENT_FAILED}. Structurally bounded by the parser's strict match, so it is
 *     safe to persist verbatim as AD-002c requires
 * @param unrecognisedReason OUR description of why the body was not recognised, present only on
 *     {@link Kind#UNRECOGNISED}. Never contains any part of the body
 */
public record AirtelSmsResponse(
    Kind kind,
    String statusWord,
    int totalUnits,
    String echoedNumber,
    String apiMsgId,
    String failureReason,
    String unrecognisedReason) {

  /** {@link #totalUnits} when the response did not state a readable unit count. */
  public static final int UNITS_UNKNOWN = -1;

  public enum Kind {
    /** {@code Status: completed} plus exactly one recipient line carrying an {@code apiMsgId}. */
    SUCCESS,

    /** Exactly one recipient line carrying {@code FAILED:}, whatever the top line said. */
    RECIPIENT_FAILED,

    /** Anything else at all. Fail-closed: never treated as a send that happened. */
    UNRECOGNISED
  }

  static AirtelSmsResponse success(
      String statusWord, int totalUnits, String echoedNumber, String apiMsgId) {
    return new AirtelSmsResponse(
        Kind.SUCCESS, statusWord, totalUnits, echoedNumber, apiMsgId, null, null);
  }

  static AirtelSmsResponse recipientFailed(
      String statusWord, int totalUnits, String echoedNumber, String failureReason) {
    return new AirtelSmsResponse(
        Kind.RECIPIENT_FAILED, statusWord, totalUnits, echoedNumber, null, failureReason, null);
  }

  static AirtelSmsResponse unrecognised(String unrecognisedReason) {
    return new AirtelSmsResponse(
        Kind.UNRECOGNISED, null, UNITS_UNKNOWN, null, null, null, unrecognisedReason);
  }
}
