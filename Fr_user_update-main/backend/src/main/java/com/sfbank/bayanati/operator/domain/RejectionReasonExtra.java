package com.sfbank.bayanati.operator.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the customer-facing message out of a {@code rejection_reason} item's {@code extra} column
 * (V0019: {@code extra carries the customer-facing message in both languages}). Pure logic — no
 * Spring, no I/O — mirrors {@code messaging.domain.MessagePayloadJson}'s one-library-call shape.
 */
public final class RejectionReasonExtra {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private RejectionReasonExtra() {}

  /**
   * @return the Arabic customer-facing message, or {@code null} if {@code extraJson} carries none
   */
  public static String customerMessageAr(String extraJson) {
    return field(extraJson, "customerMessageAr");
  }

  private static String field(String extraJson, String field) {
    if (extraJson == null) {
      return null;
    }
    JsonNode node = MAPPER.readTree(extraJson);
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }
}
