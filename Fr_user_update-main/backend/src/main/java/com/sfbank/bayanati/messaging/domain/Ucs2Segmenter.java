package com.sfbank.bayanati.messaging.domain;

/**
 * Counts UCS-2 SMS segments for a message body.
 *
 * <p>This product is Arabic-first, and a single Arabic character forces an SMS's <em>entire</em>
 * body to UCS-2 encoding — there is no partial saving from mixing scripts (AD-002c report §3.2,
 * [DOC] twilio.com/docs/glossary/what-sms-character-limit). Every body this product sends therefore
 * uses UCS-2's limits: <strong>70 characters single-segment, 67 characters per segment once
 * concatenation is needed</strong>. This class does not attempt GSM-7 detection — an English-only
 * body would fit more per segment under GSM-7, but treating it as UCS-2 anyway is the honest,
 * conservative answer for a product whose bodies are never guaranteed English-only.
 *
 * <p>Pure logic, no Spring, no I/O — lives in {@code domain} per CLAUDE.md's package rule, and is
 * used by {@code StubMessageSender} so {@code billedSegments} is a real computation rather than a
 * hardcoded constant.
 */
public final class Ucs2Segmenter {

  /** Maximum characters in a single, unconcatenated UCS-2 SMS segment. */
  public static final int SINGLE_SEGMENT_LIMIT = 70;

  /** Maximum characters per segment once a body must be split across several (UDH overhead). */
  public static final int CONCATENATED_SEGMENT_LIMIT = 67;

  private Ucs2Segmenter() {}

  /**
   * @param body the rendered SMS body. UTF-16 code units are counted, which is exact for every
   *     character this journey's copy uses — Arabic and Latin script are both in the Basic
   *     Multilingual Plane, so one {@code char} is one UCS-2 code unit.
   * @return the number of billable segments; {@code 0} for a blank body.
   * @throws NullPointerException if {@code body} is {@code null}
   */
  public static int segments(String body) {
    int length = body.length();
    if (length == 0) {
      return 0;
    }
    if (length <= SINGLE_SEGMENT_LIMIT) {
      return 1;
    }
    return (length + CONCATENATED_SEGMENT_LIMIT - 1) / CONCATENATED_SEGMENT_LIMIT;
  }
}
