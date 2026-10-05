package com.sfbank.bayanati.contactchannels.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.messaging.domain.EmailPayload;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Ucs2Segmenter;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import org.junit.jupiter.api.Test;

class OtpMessageRendererTest {

  /**
   * customer.md Stage 2 requires the message to name the bank, name its own channel, carry the
   * code, and state validity. This is the exact body S3-05's own {@code
   * StubMessageSenderTest}/{@code Ucs2SegmenterTest} proved as "a realistic Arabic OTP body" — 115
   * characters, 2 UCS-2 segments.
   */
  @Test
  void theSmsBodyMatchesTheProvenRealisticArabicOtpShapeAndIsTwoSegments() {
    SmsPayload payload = OtpMessageRenderer.renderSms("482913", 5);

    String expected =
        "البنك السوداني الفرنسي: رمز التحقق عبر الرسائل القصيرة الخاص بحسابك هو 482913. صالح لمدة 5 دقائق. لا تشاركه مع أحد.";
    assertEquals(expected, payload.body());
    assertEquals(115, payload.body().length(), "character count");
    assertEquals(2, Ucs2Segmenter.segments(payload.body()), "billedSegments");
  }

  @Test
  void theSmsBodyNamesTheBankAndCarriesTheCode() {
    SmsPayload payload = OtpMessageRenderer.renderSms("111222", 5);

    // BL-085: assert the FORMAL name AT THE START, not merely contained. "بنك السودان" —
    // the CENTRAL BANK, the regulator — is a substring of "البنك السوداني الفرنسي", so a
    // contains() check on either string passes against the very defect this guards. Found
    // live at S8-02, reading the first real OTP this project ever delivered off a handset.
    assertTrue(payload.body().startsWith("البنك السوداني الفرنسي: "));
    assertTrue(payload.body().contains("111222"));
    assertTrue(payload.body().contains("5"));
  }

  @Test
  void theEmailBodyNamesItsOwnChannelDifferentlyFromSms() {
    EmailPayload sms = OtpMessageRenderer.renderEmail("999888", 5);
    SmsPayload smsPayload = OtpMessageRenderer.renderSms("999888", 5);

    assertTrue(sms.bodyText().contains("999888"));
    // The two bodies must differ (each names its own channel), so a customer with both an SMS and
    // an email code in hand can tell which is which even though both carry the same code.
    assertTrue(!sms.bodyText().equals(smsPayload.body()));
  }

  @Test
  void theWhatsAppPayloadIsAnAuthenticationTemplateCarryingOnlyTheCode() {
    WhatsAppPayload payload = OtpMessageRenderer.renderWhatsApp("555444");

    assertEquals(WhatsAppTemplateCategory.AUTHENTICATION, payload.category());
    assertEquals("ar", payload.languageCode());
    assertEquals(1, payload.bodyParameters().size());
    assertEquals("555444", payload.bodyParameters().get(0));
    // Meta limits AUTHENTICATION template parameters to 15 characters.
    assertTrue(payload.bodyParameters().get(0).length() <= 15);
  }
}
