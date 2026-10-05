package com.sfbank.bayanati.messaging.domain;

/**
 * Acceptance and delivery are two different events. {@link MessageDispatchResult} answers "did the
 * provider take it"; this answers "did it arrive", reported asynchronously by a provider webhook or
 * a poll. No implementation of this exists yet — recorded here because the port shape needs to
 * account for it, per docs/components/messaging.md.
 *
 * @param providerMessageId correlates back to {@link MessageDispatchResult#providerMessageId()}
 * @param rawStatusCode preserved verbatim — the same principle as storing the raw Uqudo JWS and the
 *     raw Civil Registry response. The mapped enum is derived data; the provider's own words are
 *     the evidence.
 * @param rawStatusText preserved verbatim, nullable
 */
public record DeliveryReceipt(
    String providerId,
    String providerMessageId,
    DeliveryState state,
    String rawStatusCode,
    String rawStatusText,
    String reportedAtIso) {}
