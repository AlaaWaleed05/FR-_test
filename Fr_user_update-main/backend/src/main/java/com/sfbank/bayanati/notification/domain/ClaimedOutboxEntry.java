package com.sfbank.bayanati.notification.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import java.util.UUID;

/**
 * One {@code app.notification_outbox} row, claimed and ready to send. Carries everything {@link
 * com.sfbank.bayanati.notification.service.OutboxDispatcher} needs to build an {@code
 * OutboundMessage} without a second query.
 */
public record ClaimedOutboxEntry(
    UUID outboxId,
    UUID profileId,
    MessageChannel channel,
    String destination,
    MessagePayload payload) {}
