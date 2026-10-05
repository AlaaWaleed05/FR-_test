package com.sfbank.bayanati.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboundMessageTest {

  @Test
  void aPayloadForAnotherChannelIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OutboundMessage(
                UUID.randomUUID(),
                MessageChannel.SMS,
                "+249900000000",
                new EmailPayload("subject", "body", null),
                Urgency.DEFERRED,
                "corr-1"));
  }

  @Test
  void aBlankDestinationIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OutboundMessage(
                UUID.randomUUID(),
                MessageChannel.SMS,
                "  ",
                new SmsPayload("body"),
                Urgency.INTERACTIVE,
                "corr-1"));
  }
}
