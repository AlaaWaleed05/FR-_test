package com.sfbank.bayanati.messaging.airtel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserted against the REAL captured contract (Postman, 2026-09-07 — the success path, the failure
 * path, and the two-segment Arabic body the same day), not an invented one. Only the phone number
 * and the message id are synthetic: CLAUDE.md forbids a real number in the repository.
 *
 * <p>Every assertion here is <strong>direct</strong> — it names the value the parser must produce,
 * so it cannot pass against a broken parser and needs no revert-restore proof.
 */
class AirtelSmsResponseParserTest {

  private static final String NUMBER_SENT = "0912345678";

  /** The captured success shape, with the unit count the Arabic capture actually returned. */
  private static final String CAPTURED_SUCCESS =
      """
      Status: completed
      Total Units: 2
      0912345678 -> apiMsgId: 9900001 (units=2)
      """;

  /** The captured failure shape, with the one observed reason. */
  private static final String CAPTURED_FAILURE =
      """
      Status: failed
      Total Units: 0
      0912345678 -> FAILED: Invalid Sudanese number
      """;

  @Test
  void theCapturedSuccessResponseYieldsTheApiMsgIdAndTheUnitCount() {
    AirtelSmsResponse parsed = AirtelSmsResponseParser.parse(CAPTURED_SUCCESS, NUMBER_SENT);

    assertEquals(AirtelSmsResponse.Kind.SUCCESS, parsed.kind());
    assertEquals("9900001", parsed.apiMsgId());
    assertEquals(2, parsed.totalUnits());
    assertEquals("completed", parsed.statusWord());
    assertEquals(NUMBER_SENT, parsed.echoedNumber());
  }

  @Test
  void theCapturedFailureResponseCarriesAirtelsOwnWordsVerbatim() {
    AirtelSmsResponse parsed = AirtelSmsResponseParser.parse(CAPTURED_FAILURE, NUMBER_SENT);

    assertEquals(AirtelSmsResponse.Kind.RECIPIENT_FAILED, parsed.kind());
    assertEquals("Invalid Sudanese number", parsed.failureReason());
    assertEquals(0, parsed.totalUnits());
    assertEquals("failed", parsed.statusWord());
    assertNull(parsed.apiMsgId());
  }

  /**
   * BL-080: "any per-recipient FAILED is a failure regardless of the top line, never the reverse."
   */
  @Test
  void aPerRecipientFailureBeatsACompletedTopLine() {
    String body =
        """
        Status: completed
        Total Units: 1
        0912345678 -> FAILED: Invalid Sudanese number
        """;

    AirtelSmsResponse parsed = AirtelSmsResponseParser.parse(body, NUMBER_SENT);

    assertEquals(AirtelSmsResponse.Kind.RECIPIENT_FAILED, parsed.kind());
    assertEquals("Invalid Sudanese number", parsed.failureReason());
  }

  /** An apiMsgId under a top line nobody has observed is not accepted as a send. */
  @Test
  void anApiMsgIdWithoutACompletedTopLineIsNotASend() {
    String body =
        """
        Status: queued
        Total Units: 1
        0912345678 -> apiMsgId: 9900001 (units=1)
        """;

    assertUnrecognised(AirtelSmsResponseParser.parse(body, NUMBER_SENT));
  }

  /**
   * The format is multi-recipient even though we send one. Two result lines means the response is
   * not about our single message alone, so it is not read as one.
   */
  @Test
  void twoRecipientLinesAreRefused() {
    String body =
        """
        Status: completed
        Total Units: 2
        0912345678 -> apiMsgId: 9900001 (units=1)
        0912345679 -> apiMsgId: 9900002 (units=1)
        """;

    assertUnrecognised(AirtelSmsResponseParser.parse(body, NUMBER_SENT));
  }

  @Test
  void aStatusAndUnitsWithNoRecipientLineIsRefused() {
    String body =
        """
        Status: completed
        Total Units: 1
        """;

    assertUnrecognised(AirtelSmsResponseParser.parse(body, NUMBER_SENT));
  }

  /**
   * BL-080's trap, from the other side. The response hands the number back; if it is not the number
   * we actually sent, the apiMsgId cannot be trusted to correlate with this message, so it is not
   * reported as accepted.
   */
  @Test
  void anEchoedNumberThatIsNotTheNumberSentIsRefused() {
    String body =
        """
        Status: completed
        Total Units: 1
        0912999999 -> apiMsgId: 9900001 (units=1)
        """;

    assertUnrecognised(AirtelSmsResponseParser.parse(body, NUMBER_SENT));
  }

  /**
   * The reason a bare {@code contains("FAILED")} check is not enough, and the reason HTTP 200 on
   * both paths is dangerous: none of these contains the word FAILED, and every one of them would be
   * read as a successful send by an adapter that did not positively confirm success.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "   ",
        "<html><head><title>503 Service Unavailable</title></head></html>",
        "Status: completed",
        "Status: completed\nTotal Units: 1\n0912345678 -> apiMsgId:",
        "Rate limit exceeded, try again later",
        "Status: completed\nTotal Units: many\n0912345678 -> apiMsgId: 9900001 (units=1)",
        "Total Units: 1\nStatus: completed\n0912345678 -> apiMsgId: 9900001 (units=1)"
      })
  void anythingThatIsNotAnObservedShapeIsUnrecognised(String body) {
    assertUnrecognised(AirtelSmsResponseParser.parse(body, NUMBER_SENT));
  }

  @Test
  void trailingWhitespaceAndCarriageReturnsDoNotBreakTheMatch() {
    String body =
        "Status: completed\r\nTotal Units: 2\r\n0912345678 -> apiMsgId: 9900001 (units=2)\r\n";

    AirtelSmsResponse parsed = AirtelSmsResponseParser.parse(body, NUMBER_SENT);

    assertEquals(AirtelSmsResponse.Kind.SUCCESS, parsed.kind());
    assertEquals("9900001", parsed.apiMsgId());
  }

  /** The reason is persisted and audited, so it gets an explicit ceiling. */
  @Test
  void aVeryLongFailureReasonIsCapped() {
    String body = "Status: failed\nTotal Units: 0\n0912345678 -> FAILED: " + "x".repeat(500);

    AirtelSmsResponse parsed = AirtelSmsResponseParser.parse(body, NUMBER_SENT);

    assertEquals(AirtelSmsResponse.Kind.RECIPIENT_FAILED, parsed.kind());
    assertEquals(AirtelSmsResponseParser.MAX_REASON_LENGTH, parsed.failureReason().length());
  }

  /**
   * An unrecognised body must never be quoted back: the response may be an error page echoing the
   * request URI, which carries the account password. Only our own description travels onward.
   */
  @Test
  void anUnrecognisedResponseNeverCarriesAnyPartOfTheBody() {
    String body = "<html>GET https://sms.invalid/send?password=hunter2 failed</html>";

    AirtelSmsResponse parsed = AirtelSmsResponseParser.parse(body, NUMBER_SENT);

    assertEquals(AirtelSmsResponse.Kind.UNRECOGNISED, parsed.kind());
    assertTrue(
        !parsed.unrecognisedReason().contains("hunter2"),
        "the reason must not quote the body: " + parsed.unrecognisedReason());
    assertTrue(
        !parsed.unrecognisedReason().contains("password"),
        "the reason must not quote the body: " + parsed.unrecognisedReason());
  }

  private static void assertUnrecognised(AirtelSmsResponse parsed) {
    assertEquals(AirtelSmsResponse.Kind.UNRECOGNISED, parsed.kind());
    assertNull(parsed.apiMsgId(), "an unrecognised response has no message id");
    assertEquals(AirtelSmsResponse.UNITS_UNKNOWN, parsed.totalUnits());
  }
}
