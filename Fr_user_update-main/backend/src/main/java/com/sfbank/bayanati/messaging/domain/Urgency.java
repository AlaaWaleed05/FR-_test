package com.sfbank.bayanati.messaging.domain;

/**
 * Whether a send is on the customer's critical path.
 *
 * <p>INTERACTIVE — a stage 2 OTP. The customer is staring at a countdown; the journey is blocked
 * until the code arrives. An implementation MUST use its lowest-latency route, apply a short
 * deadline, and MUST NOT retry internally — a retried OTP is a duplicate code on the customer's
 * handset, and the resend control (30s/60s/120s, capped at 3) is the journey's own retry mechanism
 * and the only one permitted.
 *
 * <p>DEFERRED — a submission or status-transition notification, sent by the {@code
 * app.notification_outbox} dispatcher. Fire-and-forget relative to the profile (customer.md: "a
 * failed SMS does not change a status"). An implementation MAY use a bulk/economy route and a
 * longer deadline; retry is the outbox dispatcher's job, driven by {@code next_attempt_at}, never
 * the sender's.
 */
public enum Urgency {
  INTERACTIVE,
  DEFERRED
}
