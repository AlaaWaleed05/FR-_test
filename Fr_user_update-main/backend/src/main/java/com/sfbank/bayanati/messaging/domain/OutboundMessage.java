package com.sfbank.bayanati.messaging.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * @param messageId OUR identifier and OUR idempotency key — the {@code app.notification_outbox} row
 *     id for a DEFERRED send, the {@code app.otp_challenge} id for an INTERACTIVE one. Passed to
 *     the provider as its client-reference/idempotency field wherever one exists, so a dispatcher
 *     retry after an unacknowledged response cannot produce a second message.
 * @param destination E.164 for SMS and WhatsApp; an address for EMAIL.
 * @param correlationId opaque, non-PII, for provider-side tracing. NEVER the account number, the
 *     national number, or the customer's name.
 */
public record OutboundMessage(
    UUID messageId,
    MessageChannel channel,
    String destination,
    MessagePayload payload,
    Urgency urgency,
    String correlationId) {

  public OutboundMessage {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(urgency, "urgency");
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("destination must not be blank");
    }
    if (payload.channel() != channel) {
      throw new IllegalArgumentException(
          "payload is for channel " + payload.channel() + " but message declares " + channel);
    }
  }
}
