package com.sfbank.bayanati.messaging.airtel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Airtel gateway's plain-text response. Pure logic — no Spring, no I/O, no clock — so a
 * plain JUnit test exercises every shape.
 *
 * <p><strong>The contract, as captured by Postman on 2026-09-07 (both paths, and the Arabic body
 * the same day). Three lines, plain text, NOT JSON:</strong>
 *
 * <pre>
 *   Status: completed          Status: failed
 *   Total Units: 2             Total Units: 0
 *   0… -&gt; apiMsgId: … (units=2) 0… -&gt; FAILED: Invalid Sudanese number
 * </pre>
 *
 * <p><strong>HTTP status is 200 on BOTH paths — confirmed, not assumed.</strong> The status code
 * says only that the request reached Airtel. Three consequences drive every rule below, each of
 * which would otherwise ship silently (BL-080):
 *
 * <ul>
 *   <li>An adapter that skips parsing reports <em>every failed OTP as delivered</em>. So success is
 *       <strong>positively confirmed</strong> — {@code Status: completed} AND an {@code apiMsgId}
 *       on the recipient line — never inferred from the absence of an error.
 *   <li>Testing for the word {@code FAILED} is equally wrong: a rate-limit page, a maintenance
 *       page, an HTML error or a truncated body contains no {@code FAILED} and would read as
 *       success. Anything that does not match a known shape exactly is {@link
 *       AirtelSmsResponse.Kind#UNRECOGNISED} — the fail-closed discipline {@code
 *       HttpCivilRegistryClient} already applies to a non-JSON body.
 *   <li>The format is multi-recipient even though we send one, so <strong>exactly one</strong>
 *       recipient line is required, and <strong>any per-recipient {@code FAILED} is a failure
 *       regardless of the top line</strong> — never the reverse.
 * </ul>
 *
 * <p><strong>The echoed number is compared against what was SENT</strong>, not against the stored
 * E.164 form. Comparing against the stored form is precisely the mistake that made S7-12's Civil
 * Registry fix a two-sided change: the request is canonicalised at the boundary, so the response
 * must be judged against the canonical value that actually went out.
 *
 * <p><strong>Two asymmetries here are deliberate, and both are narrower than they could
 * be.</strong> The echo check is applied on the success path only — a {@code FAILED} line is taken
 * at its word without comparing its recipient, because the outcome is a rejection either way and we
 * send one recipient per request. And the per-recipient {@code (units=N)} figure is captured but
 * not read: {@code Total Units} is the billed total and is what {@code billedSegments} records, so
 * a disagreement between the two is ignored rather than treated as an unobserved shape. Neither has
 * been observed to matter; both are places to tighten if a future capture shows they can.
 *
 * <p><strong>No reason string is ever inspected to classify an outcome.</strong> The wrong-password
 * response has never been captured (road map 0.2), so there is no auth-failure branch here and none
 * is guessed — see {@link AirtelSmsSender} for how an unrecognised body is treated and why.
 */
public final class AirtelSmsResponseParser {

  static final String STATUS_PREFIX = "Status:";
  static final String TOTAL_UNITS_PREFIX = "Total Units:";

  /** The status word the gateway returns when it accepted the message. */
  static final String COMPLETED = "completed";

  /**
   * Cap on {@link AirtelSmsResponse#failureReason()}. The reason is structurally bounded by the
   * match below, but it is persisted and audited, so it gets an explicit ceiling rather than an
   * implied one.
   */
  static final int MAX_REASON_LENGTH = 200;

  private static final Pattern SUCCESS_LINE =
      Pattern.compile("^(\\S+)\\s*->\\s*apiMsgId:\\s*(\\S+)\\s*\\(units=(\\d+)\\)$");

  private static final Pattern FAILED_LINE = Pattern.compile("^(\\S+)\\s*->\\s*FAILED:\\s*(.+)$");

  private AirtelSmsResponseParser() {}

  /**
   * @param body the response body, decoded as UTF-8
   * @param numberSent the national-form number this request actually carried, for the echo check
   * @return never null; {@link AirtelSmsResponse.Kind#UNRECOGNISED} rather than an exception, so
   *     the caller always has an outcome to record
   */
  public static AirtelSmsResponse parse(String body, String numberSent) {
    if (body == null || body.isBlank()) {
      return AirtelSmsResponse.unrecognised("empty body");
    }

    List<String> lines = significantLines(body);
    if (lines.size() != 3) {
      return AirtelSmsResponse.unrecognised(
          "expected 3 non-blank lines, found "
              + lines.size()
              + " (one recipient line is required)");
    }

    String statusLine = lines.get(0);
    if (!statusLine.startsWith(STATUS_PREFIX)) {
      return AirtelSmsResponse.unrecognised("first line is not a '" + STATUS_PREFIX + "' line");
    }
    String statusWord = statusLine.substring(STATUS_PREFIX.length()).strip();
    if (statusWord.isEmpty()) {
      return AirtelSmsResponse.unrecognised("'" + STATUS_PREFIX + "' line carries no status word");
    }

    String unitsLine = lines.get(1);
    if (!unitsLine.startsWith(TOTAL_UNITS_PREFIX)) {
      return AirtelSmsResponse.unrecognised(
          "second line is not a '" + TOTAL_UNITS_PREFIX + "' line");
    }
    int totalUnits;
    try {
      totalUnits = Integer.parseInt(unitsLine.substring(TOTAL_UNITS_PREFIX.length()).strip());
    } catch (NumberFormatException notANumber) {
      return AirtelSmsResponse.unrecognised("'" + TOTAL_UNITS_PREFIX + "' is not a whole number");
    }

    String recipientLine = lines.get(2);

    // A per-recipient FAILED wins over whatever the top line said -- never the reverse.
    Matcher failed = FAILED_LINE.matcher(recipientLine);
    if (failed.matches()) {
      return AirtelSmsResponse.recipientFailed(
          statusWord, totalUnits, failed.group(1), cap(failed.group(2).strip()));
    }

    Matcher success = SUCCESS_LINE.matcher(recipientLine);
    if (!success.matches()) {
      return AirtelSmsResponse.unrecognised(
          "recipient line matches neither the apiMsgId nor the FAILED shape");
    }
    if (!COMPLETED.equalsIgnoreCase(statusWord)) {
      // An apiMsgId under a top line that is not 'completed' is a shape nobody has observed.
      // Refuse to call it a send.
      return AirtelSmsResponse.unrecognised(
          "recipient line carries an apiMsgId but the status word is not '" + COMPLETED + "'");
    }
    String echoedNumber = success.group(1);
    if (!echoedNumber.equals(numberSent)) {
      // BL-080's trap: the response must be judged against what actually went out. A mismatch
      // means the correlation between this apiMsgId and this message is not trustworthy, so it
      // is not reported as an accepted send. Lengths only in any log -- a number is PII.
      return AirtelSmsResponse.unrecognised(
          "the echoed recipient differs from the number sent (echoed length "
              + echoedNumber.length()
              + ", sent length "
              + numberSent.length()
              + ")");
    }
    return AirtelSmsResponse.success(statusWord, totalUnits, echoedNumber, success.group(2));
  }

  /** Splits on any line terminator, strips each line, and drops blank ones. */
  private static List<String> significantLines(String body) {
    List<String> lines = new ArrayList<>();
    for (String line : body.split("\\R")) {
      String stripped = line.strip();
      if (!stripped.isEmpty()) {
        lines.add(stripped);
      }
    }
    return lines;
  }

  private static String cap(String reason) {
    return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
  }
}
