package com.sfbank.bayanati.messaging.domain;

/**
 * Meta's template category. Declared on every {@link WhatsAppPayload}, not inferred — it decides
 * the price and the delivery rules (AD-002c report §6). This product only ever sends AUTHENTICATION
 * (stage 2 OTP) and UTILITY (submission/status notifications); MARKETING is never used and is named
 * here only because the category is Meta's, not ours to narrow.
 */
public enum WhatsAppTemplateCategory {
  AUTHENTICATION,
  UTILITY,
  MARKETING
}
