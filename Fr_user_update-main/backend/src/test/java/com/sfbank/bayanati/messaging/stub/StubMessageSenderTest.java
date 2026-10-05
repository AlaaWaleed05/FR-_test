package com.sfbank.bayanati.messaging.stub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Ucs2Segmenter;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubMessageSenderTest {

  private static final String SEEDED_REJECTED = "+249900000001";
  private static final String SEEDED_TRANSIENT = "+249900000002";
  private static final String UNSEEDED = "+249900000099";

  private final StubMessageSender sender =
      new StubMessageSender(
          new StubMessagingProperties(
              Map.of(
                  SEEDED_REJECTED, DispatchOutcome.REJECTED,
                  SEEDED_TRANSIENT, DispatchOutcome.TRANSIENT_FAILURE),
              0,
              false));

  @Test
  void anUnseededDestinationIsAccepted() {
    MessageDispatchResult result = sender.send(smsTo(UNSEEDED, "short body"));

    assertEquals(DispatchOutcome.ACCEPTED, result.outcome());
    assertEquals(StubMessageSender.PROVIDER_ID, result.providerId());
    assertNotNull(result.providerMessageId());
  }

  @Test
  void aSeededDestinationReturnsItsConfiguredOutcome() {
    assertEquals(DispatchOutcome.REJECTED, sender.send(smsTo(SEEDED_REJECTED, "body")).outcome());
    assertEquals(
        DispatchOutcome.TRANSIENT_FAILURE, sender.send(smsTo(SEEDED_TRANSIENT, "body")).outcome());
  }

  @Test
  void aRejectedAttemptHasNoProviderMessageId() {
    MessageDispatchResult result = sender.send(smsTo(SEEDED_REJECTED, "body"));

    assertNull(result.providerMessageId());
  }

  @Test
  void billedSegmentsIsComputedHonestlyForARealisticArabicOtpBody() {
    // The task's own proof: "naming the bank, naming its own channel, carrying the code, stating
    // validity". Same body Ucs2SegmenterTest uses (115 chars -> 2 segments), matching the
    // research's estimate and FIB's own 104-char anchor (AD-002c report §3.2, §4.1).
    String body =
        "البنك السوداني الفرنسي: رمز التحقق عبر الرسائل القصيرة الخاص بحسابك هو 482913. صالح لمدة 5 دقائق. لا تشاركه مع أحد.";

    MessageDispatchResult result = sender.send(smsTo(UNSEEDED, body));

    assertEquals(Ucs2Segmenter.segments(body), result.billedSegments());
    assertEquals(2, result.billedSegments(), "matches the research's 2-segment estimate");
  }

  @Test
  void billedSegmentsIsNotApplicableForWhatsApp() {
    OutboundMessage message =
        new OutboundMessage(
            UUID.randomUUID(),
            MessageChannel.WHATSAPP,
            UNSEEDED,
            new WhatsAppPayload(
                "otp", "ar", List.of("123456"), WhatsAppTemplateCategory.AUTHENTICATION),
            Urgency.DEFERRED,
            "corr-1");

    MessageDispatchResult result = sender.send(message);

    assertEquals(MessageDispatchResult.SEGMENTS_NOT_APPLICABLE, result.billedSegments());
  }

  @Test
  void supportsAllThreeChannels() {
    assertTrue(sender.supportedChannels().containsAll(List.of(MessageChannel.values())));
  }

  @Test
  void recordingIsOffByDefaultSoNoAttemptIsKept() {
    sender.send(smsTo(UNSEEDED, "one"));

    assertTrue(sender.recordedSends().isEmpty(), "recordSends defaults to false");
  }

  @Test
  void recordsEveryAttemptWhereATestCanReadItWhenExplicitlyEnabled() {
    // Deliberately excludes the payload -- see StubMessageSender.SentMessage's Javadoc: the
    // recorder must never retain a rendered OTP code.
    StubMessageSender recordingSender =
        new StubMessageSender(new StubMessagingProperties(Map.of(), 0, true));
    OutboundMessage first = smsTo(UNSEEDED, "one");
    OutboundMessage second = smsTo(SEEDED_REJECTED, "two");

    recordingSender.send(first);
    recordingSender.send(second);

    assertEquals(
        List.of(
            new StubMessageSender.SentMessage(
                first.messageId(), first.channel(), first.destination()),
            new StubMessageSender.SentMessage(
                second.messageId(), second.channel(), second.destination())),
        recordingSender.recordedSends());
  }

  private static OutboundMessage smsTo(String destination, String body) {
    return new OutboundMessage(
        UUID.randomUUID(),
        MessageChannel.SMS,
        destination,
        new SmsPayload(body),
        Urgency.DEFERRED,
        "corr-1");
  }
}
