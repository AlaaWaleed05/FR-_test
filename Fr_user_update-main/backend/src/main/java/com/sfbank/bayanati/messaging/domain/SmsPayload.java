package com.sfbank.bayanati.messaging.domain;

/**
 * @param body the rendered message text. No {@code senderId} field: the sender id is registered
 *     with the operator and belongs to the implementation's configuration, not to a per-message
 *     call site — an unregistered sender does not deliver on MTN Sudan or Sudani One (AD-002c
 *     report §3.2).
 */
public record SmsPayload(String body) implements MessagePayload {
  public SmsPayload {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("SMS body must not be blank");
    }
  }

  @Override
  public MessageChannel channel() {
    return MessageChannel.SMS;
  }
}
