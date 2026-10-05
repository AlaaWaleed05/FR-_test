package com.sfbank.bayanati.messaging.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts a {@link MessagePayload} to and from the JSON text stored in {@code
 * app.notification_outbox.payload} (V0035, a {@code jsonb} column).
 *
 * <p>Deliberately NOT {@code audit.domain.CanonicalJson}: that class exists for the audit hash
 * chain and rejects nested objects and arrays outright, which a {@link WhatsAppPayload}'s {@code
 * bodyParameters} list needs. This column carries no hash-chain obligation — it is ordinary {@code
 * app}-schema state, mutated in place across dispatch attempts — so an ordinary JSON mapper is the
 * right tool, not a constraint violation waiting to happen.
 *
 * <p>Pure logic — one library call in, one out — so it lives in {@code domain} per CLAUDE.md's
 * package rule and is exercised by plain JUnit with no Spring context.
 */
public final class MessagePayloadJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private MessagePayloadJson() {}

  public static String toJson(MessagePayload payload) {
    Map<String, Object> fields =
        switch (payload) {
          case SmsPayload sms -> Map.of("body", sms.body());
          case WhatsAppPayload wa -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("templateName", wa.templateName());
            map.put("languageCode", wa.languageCode());
            map.put("bodyParameters", wa.bodyParameters());
            map.put("category", wa.category().name());
            yield map;
          }
          case EmailPayload email -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("subject", email.subject());
            map.put("bodyText", email.bodyText());
            map.put("bodyHtml", email.bodyHtml());
            yield map;
          }
        };
    return MAPPER.writeValueAsString(fields);
  }

  /**
   * @param channel which {@link MessagePayload} subtype to reconstruct — the caller already knows
   *     this from {@code app.notification_outbox.channel}, so it is not re-derived from the JSON
   * @throws IllegalArgumentException if {@code json} does not match the shape {@link #toJson}
   *     produces for {@code channel}
   */
  public static MessagePayload fromJson(MessageChannel channel, String json) {
    JsonNode node = MAPPER.readTree(json);
    return switch (channel) {
      case SMS -> new SmsPayload(requiredText(node, "body"));
      case WHATSAPP ->
          new WhatsAppPayload(
              requiredText(node, "templateName"),
              requiredText(node, "languageCode"),
              stringList(node, "bodyParameters"),
              WhatsAppTemplateCategory.valueOf(requiredText(node, "category")));
      case EMAIL ->
          new EmailPayload(
              requiredText(node, "subject"),
              requiredText(node, "bodyText"),
              node.hasNonNull("bodyHtml") ? node.get("bodyHtml").asText() : null);
    };
  }

  private static String requiredText(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      throw new IllegalArgumentException("payload JSON is missing required field '" + field + "'");
    }
    return node.get(field).asText();
  }

  private static List<String> stringList(JsonNode node, String field) {
    if (!node.has(field)) {
      return List.of();
    }
    List<String> values = new java.util.ArrayList<>();
    node.get(field).forEach(element -> values.add(element.asText()));
    return values;
  }
}
