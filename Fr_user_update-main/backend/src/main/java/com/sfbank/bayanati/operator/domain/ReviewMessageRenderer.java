package com.sfbank.bayanati.operator.domain;

import com.sfbank.bayanati.messaging.domain.EmailPayload;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import java.util.List;

/**
 * Renders one channel's approve/reject notification. operator.md "Every status transition": "3.
 * Communicated to the customer on all verified channels" — no exception for a review decision. Pure
 * logic — no Spring, no I/O, no clock — mirrors {@code
 * submission.domain.SubmissionMessageRenderer}.
 *
 * <p>The rejection body is the caller-supplied, already-derived customer-facing message (from
 * {@code ref.reference_item.extra}, per {@code OperatorReviewService}) — this class only wraps it
 * per channel, it never invents rejection copy itself, so REJ-03/04/05/07's deliberately shared
 * neutral message (operator.md) is never duplicated or drifted here.
 */
public final class ReviewMessageRenderer {

  static final String BANK_NAME_AR = "البنك السوداني الفرنسي";

  static final String WHATSAPP_TEMPLATE_NAME_APPROVED = "update_approved";
  static final String WHATSAPP_TEMPLATE_NAME_REJECTED = "update_rejected";
  static final String WHATSAPP_LANGUAGE_CODE = "ar";

  private ReviewMessageRenderer() {}

  public static SmsPayload renderApprovedSms() {
    return new SmsPayload(approvedBody());
  }

  public static WhatsAppPayload renderApprovedWhatsApp() {
    return new WhatsAppPayload(
        WHATSAPP_TEMPLATE_NAME_APPROVED,
        WHATSAPP_LANGUAGE_CODE,
        List.of(),
        WhatsAppTemplateCategory.UTILITY);
  }

  public static EmailPayload renderApprovedEmail() {
    return new EmailPayload(BANK_NAME_AR + ": تم اعتماد طلبك", approvedBody(), null);
  }

  public static SmsPayload renderRejectedSms(String customerMessageAr) {
    return new SmsPayload(customerMessageAr);
  }

  public static WhatsAppPayload renderRejectedWhatsApp(String customerMessageAr) {
    return new WhatsAppPayload(
        WHATSAPP_TEMPLATE_NAME_REJECTED,
        WHATSAPP_LANGUAGE_CODE,
        List.of(customerMessageAr),
        WhatsAppTemplateCategory.UTILITY);
  }

  public static EmailPayload renderRejectedEmail(String customerMessageAr) {
    return new EmailPayload(BANK_NAME_AR + ": نتيجة مراجعة طلبك", customerMessageAr, null);
  }

  private static String approvedBody() {
    return BANK_NAME_AR + ": تم اعتماد تحديث بياناتك بنجاح.";
  }
}
