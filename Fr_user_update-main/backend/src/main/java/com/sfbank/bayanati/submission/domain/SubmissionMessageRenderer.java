package com.sfbank.bayanati.submission.domain;

import com.sfbank.bayanati.messaging.domain.EmailPayload;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import java.util.List;

/**
 * Renders one channel's submission-notification payload. customer.md Stage 12: "On submission --
 * received, with the reference number." Pure logic — no Spring, no I/O, no clock — mirrors {@code
 * contactchannels.domain.OtpMessageRenderer}'s role and division of labour (rendering happens here,
 * never in {@code MessageSender}).
 *
 * <p>{@code WhatsAppTemplateCategory.UTILITY} — named in that enum's own Javadoc as exactly this
 * use ("submission/status notifications"), distinct from stage 2's OTP AUTHENTICATION template. The
 * template name itself is a placeholder pending a real WABA (R-042/AD-002c, same status as {@code
 * OtpMessageRenderer.WHATSAPP_TEMPLATE_NAME}).
 */
public final class SubmissionMessageRenderer {

  static final String BANK_NAME_AR = "البنك السوداني الفرنسي";

  static final String WHATSAPP_TEMPLATE_NAME = "submission_confirmation";
  static final String WHATSAPP_LANGUAGE_CODE = "ar";

  private SubmissionMessageRenderer() {}

  public static SmsPayload renderSms(String referenceNumber) {
    return new SmsPayload(body(referenceNumber));
  }

  public static WhatsAppPayload renderWhatsApp(String referenceNumber) {
    return new WhatsAppPayload(
        WHATSAPP_TEMPLATE_NAME,
        WHATSAPP_LANGUAGE_CODE,
        List.of(referenceNumber),
        WhatsAppTemplateCategory.UTILITY);
  }

  public static EmailPayload renderEmail(String referenceNumber) {
    return new EmailPayload(BANK_NAME_AR + ": تم استلام طلبك", body(referenceNumber), null);
  }

  private static String body(String referenceNumber) {
    return BANK_NAME_AR
        + ": تم استلام تحديث بياناتك وهو قيد المراجعة. الرقم المرجعي: "
        + referenceNumber
        + ". سيتم إبلاغك بالنتيجة.";
  }
}
