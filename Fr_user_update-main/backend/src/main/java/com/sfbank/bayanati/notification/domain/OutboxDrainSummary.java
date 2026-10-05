package com.sfbank.bayanati.notification.domain;

/**
 * What one drain pass over {@code app.notification_outbox} did — the return value of {@code
 * OutboxDispatcher.drainPending()} (S6-04).
 *
 * <p>Counts, never content: a summary is logged on every scheduled tick, and {@code destination}
 * and {@code payload} are customer PII that must never reach a log (CLAUDE.md).
 *
 * @param attempted rows claimed, sent and recorded — whatever the provider's outcome was. A
 *     REJECTED or TRANSIENT_FAILURE result is a completed attempt, not a failure of this pass; the
 *     row's own {@code outcome} column and its {@code notification_dispatched} audit event carry
 *     that detail.
 * @param failed attempts that threw. The row (if one was claimed at all) keeps its claim-time lease
 *     and is retried on a later tick — no state is lost, and nothing else in the queue was blocked.
 * @param reachedCap true if the pass stopped because it hit {@code MAX_ATTEMPTS_PER_TICK} rather
 *     than because the queue was empty. A tick that ends this way means either a genuine backlog
 *     (the next tick continues it) or a persistently failing dependency.
 */
public record OutboxDrainSummary(int attempted, int failed, boolean reachedCap) {}
