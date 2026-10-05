package com.sfbank.bayanati.messaging.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelRoutingMessageSenderTest {

  private static final OutboundMessage SMS_MESSAGE =
      new OutboundMessage(
          UUID.randomUUID(),
          MessageChannel.SMS,
          "+249900000000",
          new SmsPayload("body"),
          Urgency.DEFERRED,
          "corr-1");

  @Test
  void aChannelWithNoMappedSenderReturnsPermanentFailureRatherThanThrowing() {
    // Models BOTH an unconfigured deployment and a deliberately disabled channel
    // (fru.messaging.<channel>.enabled=false) -- MessageSenderConfiguration expresses both by
    // simply leaving the channel out of this map. Either way, the caller must see a returned
    // result, never an exception (task proof: "a disabled channel producing the disabled state
    // rather than an exception").
    ChannelRoutingMessageSender router = new ChannelRoutingMessageSender(Map.of());

    MessageDispatchResult result = assertDoesNotThrow(() -> router.send(SMS_MESSAGE));

    assertEquals(DispatchOutcome.PERMANENT_FAILURE, result.outcome());
    assertEquals(ChannelRoutingMessageSender.ROUTING_PROVIDER_ID, result.providerId());
    assertEquals(ChannelRoutingMessageSender.CHANNEL_NOT_CONFIGURED, result.providerStatusCode());
  }

  @Test
  void aConfiguredChannelDelegatesToItsSender() {
    MessageDispatchResult expected =
        new MessageDispatchResult(
            SMS_MESSAGE.messageId().toString(),
            "sms",
            DispatchOutcome.ACCEPTED,
            "fake",
            "msg-1",
            "0",
            "ok",
            1,
            "2026-08-29T12:00:00Z",
            5L);
    FakeSender fake = new FakeSender(Set.of(MessageChannel.SMS), expected);
    ChannelRoutingMessageSender router =
        new ChannelRoutingMessageSender(Map.of(MessageChannel.SMS, fake));

    assertEquals(expected, router.send(SMS_MESSAGE));
  }

  @Test
  void constructionFailsIfASenderIsMappedToAChannelItDoesNotSupport() {
    FakeSender emailOnly = new FakeSender(Set.of(MessageChannel.EMAIL), null);

    assertThrows(
        IllegalStateException.class,
        () -> new ChannelRoutingMessageSender(Map.of(MessageChannel.SMS, emailOnly)));
  }

  private record FakeSender(Set<MessageChannel> supportedChannels, MessageDispatchResult toReturn)
      implements MessageSender {
    @Override
    public MessageDispatchResult send(OutboundMessage message) {
      return toReturn;
    }
  }
}
