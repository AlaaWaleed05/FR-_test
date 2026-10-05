package com.sfbank.bayanati.messaging.domain;

/**
 * The closed set the profile and the outbox may store for a delivery receipt. It is the UNION
 * across channels, deliberately: READ is WhatsApp-only, EXPIRED is SMS-and-WhatsApp, UNDELIVERED
 * covers an SMS DLR failure and an email bounce alike. An implementation that meets a provider
 * status it cannot map MUST return UNKNOWN and preserve the raw code on {@link
 * DeliveryReceipt#rawStatusCode()} — it must never invent a new constant, and it must never guess
 * DELIVERED.
 */
public enum DeliveryState {
  ACCEPTED,
  DELIVERED,
  READ,
  UNDELIVERED,
  EXPIRED,
  UNKNOWN
}
