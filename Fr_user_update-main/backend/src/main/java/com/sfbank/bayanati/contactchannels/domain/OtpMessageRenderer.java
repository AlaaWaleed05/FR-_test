package com.sfbank.bayanati.contactchannels.domain;

import com.sfbank.bayanati.messaging.domain.EmailPayload;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import java.util.List;

/**
 * Renders one channel's OTP payload. customer.md, Stage 2: "the delivered message should name its
 * own channel so a customer can tell which code belongs where" — the SMS and email bodies each name
 * the bank, name their own channel, carry the code, and state the 5-minute validity (customer.md
 * Policy values). WhatsApp needs no channel name in its body: Meta's AUTHENTICATION templates carry
 * fixed, non-customisable preset text (docs/sessions/2026-08-29-research-ad-002c- messaging.md
 * §3.3), and the customer already knows which app just notified them.
 *
 * <p>Pure logic — no Spring, no I/O, no clock. Rendering happens here rather than in {@code
 * MessageSender} per AD-002c's own division of labour: "by the time a payload reaches the port
 * there is nothing left to decide."
 */
public final class OtpMessageRenderer {

  static final String BANK_NAME_AR = "البنك السوداني الفرنسي";

  /**
   * Meta template name for the OTP authentication template. Real value pending a WABA
   * (R-042/AD-002c).
   */
  static final String WHATSAPP_TEMPLATE_NAME = "otp_verification";

  static final String WHATSAPP_LANGUAGE_CODE = "ar";

  private OtpMessageRenderer() {}

  public static SmsPayload renderSms(String code, int validityMinutes) {
    return new SmsPayload(body("الرسائل القصيرة", code, validityMinutes));
  }

  public static WhatsAppPayload renderWhatsApp(String code) {
    return new WhatsAppPayload(
        WHATSAPP_TEMPLATE_NAME,
        WHATSAPP_LANGUAGE_CODE,
        List.of(code),
        WhatsAppTemplateCategory.AUTHENTICATION);
  }

  public static EmailPayload renderEmail(String code, int validityMinutes) {
    return new EmailPayload(
        BANK_NAME_AR + ": رمز التحقق", body("البريد الإلكتروني", code, validityMinutes), null);
  }

  private static String body(String channelNameAr, String code, int validityMinutes) {
    return BANK_NAME_AR
        + ": رمز التحقق عبر "
        + channelNameAr
        + " الخاص بحسابك هو "
        + code
        + ". صالح لمدة "
        + validityMinutes
        + " دقائق. لا تشاركه مع أحد.";
  }
}
