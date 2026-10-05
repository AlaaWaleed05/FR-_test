package com.sfbank.bayanati.messaging.domain;

import java.util.Set;

/**
 * The single outbound-messaging port. One implementation is selected per channel at startup from
 * configuration — never a runtime {@code if (stub)} branch (see {@code
 * MessageSenderConfiguration}). Callers depend on this type and on nothing else in this package.
 */
public interface MessageSender {

  /**
   * Which channels this implementation can serve. The router uses it to validate its map at
   * startup.
   */
  Set<MessageChannel> supportedChannels();

  /**
   * Attempts one delivery of one message to one destination.
   *
   * <p><strong>A provider-level rejection is a returned value, not a thrown exception.</strong>
   * "The gateway said code 12, invalid destination" is information the audit trail must record and
   * the dispatcher must act on; an exception discards the provider's own words. Only a programming
   * error — a null argument, an unsupported channel — throws. This mirrors {@code
   * CoreBankingClient} returning {@code ProcessOmniCheckAct}'s raw 1/2/-1 rather than an enum.
   *
   * <p>Implementations MUST NOT retry internally. Retry policy belongs to the outbox dispatcher for
   * DEFERRED traffic and to the customer's resend control for INTERACTIVE traffic.
   *
   * <p>Implementations MUST bound their own latency and return TRANSIENT_FAILURE on timeout,
   * because an INTERACTIVE send happens on the request thread with a customer watching.
   */
  MessageDispatchResult send(OutboundMessage message);
}
