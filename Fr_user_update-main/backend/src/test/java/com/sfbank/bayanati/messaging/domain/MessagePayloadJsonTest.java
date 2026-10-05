package com.sfbank.bayanati.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MessagePayloadJsonTest {

  @Test
  void smsPayloadRoundTrips() {
    SmsPayload original = new SmsPayload("رمز التحقق: 123456");

    MessagePayload restored =
        MessagePayloadJson.fromJson(MessageChannel.SMS, MessagePayloadJson.toJson(original));

    assertEquals(original, restored);
  }

  @Test
  void whatsAppPayloadRoundTripsIncludingTheParameterList() {
    WhatsAppPayload original =
        new WhatsAppPayload(
            "otp_verification", "ar", List.of("123456"), WhatsAppTemplateCategory.AUTHENTICATION);

    MessagePayload restored =
        MessagePayloadJson.fromJson(MessageChannel.WHATSAPP, MessagePayloadJson.toJson(original));

    assertEquals(original, restored);
  }

  @Test
  void emailPayloadRoundTripsWithNullHtmlBody() {
    EmailPayload original = new EmailPayload("رمز التحقق", "رمزك هو 123456", null);

    MessagePayload restored =
        MessagePayloadJson.fromJson(MessageChannel.EMAIL, MessagePayloadJson.toJson(original));

    assertEquals(original, restored);
  }

  @Test
  void emailPayloadRoundTripsWithHtmlBody() {
    EmailPayload original =
        new EmailPayload("رمز التحقق", "رمزك هو 123456", "<p>رمزك هو 123456</p>");

    MessagePayload restored =
        MessagePayloadJson.fromJson(MessageChannel.EMAIL, MessagePayloadJson.toJson(original));

    assertEquals(original, restored);
  }
}
