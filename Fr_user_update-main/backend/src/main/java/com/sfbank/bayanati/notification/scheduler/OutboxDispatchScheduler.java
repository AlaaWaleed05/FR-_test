package com.sfbank.bayanati.notification.scheduler;

import com.sfbank.bayanati.notification.domain.OutboxDrainSummary;
import com.sfbank.bayanati.notification.service.OutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S6-04: the timer that drains {@code app.notification_outbox}. Before this class existed nothing
 * in the codebase called {@link OutboxDispatcher#dispatchOnePending()} at all, so every {@code
 * Urgency.DEFERRED} message the journey enqueues — the submission notification, and every
 * approve/reject/manual-completion status transition — was written to the table and never sent.
 *
 * <p>Plumbing, not business logic, and therefore deliberately outside {@code domain}/{@code
 * service} (CLAUDE.md's package rule): it holds a schedule and a log line. The drain policy it
 * triggers — the loop, the per-attempt failure isolation, the per-tick cap — is {@link
 * OutboxDispatcher#drainPending()}'s, where a plain JUnit test can reach it with no Spring context.
 *
 * <h2>Why 30 seconds</h2>
 *
 * <ol>
 *   <li><strong>Nothing in this queue is time-critical.</strong> The outbox carries {@code
 *       DEFERRED} messages only — "we received your submission", "your profile was approved". The
 *       one message a customer actively waits on, the OTP, never enters the outbox: {@code
 *       ContactChannelsService} sends it inline from the request thread with {@code
 *       Urgency.INTERACTIVE} (docs/components/messaging.md §S3-06). Tens of seconds of latency on a
 *       status notice costs nothing.
 *   <li><strong>30 s is already the claim lease.</strong> {@code
 *       JdbcNotificationOutboxRepository.CLAIM_ONE_PENDING} advances a claimed row's {@code
 *       next_attempt_at} by {@code interval '30 seconds'}. A row left {@code pending} by a
 *       TRANSIENT_FAILURE therefore cannot become due sooner than that, so polling faster than the
 *       lease cannot deliver anything sooner — it only runs queries that find nothing. The lease is
 *       the natural floor for the poll period, so the two are tied together rather than picked
 *       independently.
 *   <li><strong>Idle cost is negligible.</strong> One indexed query every 30 s against a table
 *       whose pending set is normally empty.
 * </ol>
 *
 * <p><strong>{@code fixedDelay}, not {@code fixedRate}.</strong> The delay is measured from the end
 * of the previous run, so a slow pass — plausible once a real provider adapter replaces the stub —
 * never accumulates a burst of catch-up ticks behind it. Spring's default single-thread scheduler
 * already serialises runs; {@code fixedDelay} states that intent rather than depending on the pool
 * size staying at one.
 *
 * <p>Both values are properties with defaults rather than constants, so a deployment can retune the
 * cadence without a rebuild. Every integration test registers them as {@code 1h} — see {@code
 * AbstractPostgresIntegrationTest} for why that matters on a shared container.
 */
@Component
public class OutboxDispatchScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

  private final OutboxDispatcher dispatcher;

  public OutboxDispatchScheduler(OutboxDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * One tick: drain whatever is pending, then log what the pass did. A tick that finds an empty
   * queue — the normal case — logs nothing at all, so a quiet system stays quiet.
   *
   * <p>Nothing is caught here. {@link OutboxDispatcher#drainPending()} already isolates each
   * message's failure, so anything escaping it is not a bad message but a broken JVM ({@code
   * Error}), which Spring's scheduler logs and which must not be swallowed.
   */
  @Scheduled(
      fixedDelayString = "${fru.notification.outbox.poll-interval:30s}",
      initialDelayString = "${fru.notification.outbox.poll-initial-delay:30s}")
  public void pollOutbox() {
    OutboxDrainSummary summary = dispatcher.drainPending();

    if (summary.reachedCap()) {
      log.warn(
          "outbox drain hit its per-tick cap: {} attempted, {} failed; continuing on the next tick",
          summary.attempted(),
          summary.failed());
    } else if (summary.failed() > 0) {
      log.warn(
          "outbox drained with failures: {} attempted, {} failed", // detail is in the WARN above it
          summary.attempted(),
          summary.failed());
    } else if (summary.attempted() > 0) {
      log.info("outbox drained: {} dispatched", summary.attempted());
    }
  }
}
