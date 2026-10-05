package com.sfbank.bayanati.messaging.domain;

import java.util.List;
import java.util.Objects;

/**
 * @param templateName the name registered with Meta. Never free text: authentication and utility
 *     traffic outside a customer-service window is template-only, and every send this product makes
 *     is outside one (AD-002c report §3.3).
 * @param languageCode Meta's code, e.g. {@code "ar"}.
 * @param bodyParameters positional parameters. Meta limits AUTHENTICATION template parameters to 15
 *     characters and forbids URLs, media and emojis.
 * @param category declared, not inferred — it decides the price and the rules.
 */
public record WhatsAppPayload(
    String templateName,
    String languageCode,
    List<String> bodyParameters,
    WhatsAppTemplateCategory category)
    implements MessagePayload {

  public WhatsAppPayload {
    if (templateName == null || templateName.isBlank()) {
      throw new IllegalArgumentException("templateName must not be blank");
    }
    if (languageCode == null || languageCode.isBlank()) {
      throw new IllegalArgumentException("languageCode must not be blank");
    }
    Objects.requireNonNull(category, "category");
    bodyParameters = bodyParameters == null ? List.of() : List.copyOf(bodyParameters);
  }

  @Override
  public MessageChannel channel() {
    return MessageChannel.WHATSAPP;
  }
}
