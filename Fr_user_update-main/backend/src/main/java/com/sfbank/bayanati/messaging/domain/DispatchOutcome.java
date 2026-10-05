package com.sfbank.bayanati.messaging.domain;

/**
 * What a dispatcher acts on. Implementations of {@link MessageSender} map their provider's response
 * onto this closed set; they never extend it.
 */
public enum DispatchOutcome {
  /** The provider accepted it for delivery. NOT proof of delivery — see {@link DeliveryState}. */
  ACCEPTED,

  /**
   * The provider refused this message and will refuse it again. Do not retry. Bad number, blocked
   * destination, unapproved template.
   */
  REJECTED,

  /** Timeout, 5xx, rate limit, connection failure. Retry per the outbox schedule. */
  TRANSIENT_FAILURE,

  /**
   * Auth failure, quota exhausted, account suspended. Retry is pointless and the operator must be
   * told. Distinguished from {@link #REJECTED} because the fault is ours/the account's, not the
   * destination's — this is also what a disabled or unconfigured channel reports at the router (see
   * {@code ChannelRoutingMessageSender}).
   */
  PERMANENT_FAILURE
}
