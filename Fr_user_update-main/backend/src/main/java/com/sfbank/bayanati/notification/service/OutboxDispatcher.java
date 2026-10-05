package com.sfbank.bayanati.notification.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.notification.domain.ClaimedOutboxEntry;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.notification.domain.OutboxDrainSummary;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispatches pending {@code app.notification_outbox} rows through {@link MessageSender} and records
 * each result — both on the row (V0035's result columns) and in the audit trail (a {@code
 * notification_dispatched} event, per customer.md's "delivery result … recorded per channel in the
 * audit trail").
 *
 * <p><strong>The outbox row and the audit event commit together.</strong> AD-005 §6 is explicit:
 * "Each dispatch attempt commits its own short transaction updating the outbox row AND writing a
 * notification_dispatched audit event". {@link #dispatch(ClaimedOutboxEntry)} therefore sends first
 * — outside any transaction, since it is external I/O of unbounded latency (the same reasoning
 * AD-005 §6 gives for keeping the send out of the enqueue-time transaction) — and only then opens
 * one short transaction covering {@code recordResult} and the audit write. Without this, a failure
 * between the two (the audit insert can throw on a provider-supplied string containing U+0000 or an
 * unpaired surrogate, {@code CanonicalJson}) would leave the outbox row terminal with no audit
 * record of the send, permanently. {@link TransactionTemplate} rather than {@code @Transactional}
 * deliberately: this class calls itself internally ({@link #drainPending()} to {@link
 * #dispatchOnePending()} to {@link #dispatch(ClaimedOutboxEntry)}), so a proxy-based annotation on
 * an internal method would silently not apply (Spring AOP self-invocation).
 *
 * <p><strong>Still not a scheduler — it is what a scheduler calls.</strong> S3-05 built {@link
 * #dispatchOnePending()} alone and left "a scheduled runner is later work"; S6-04 is that work, and
 * it split in two. The drain policy — how many rows one pass takes, and what a failing row does to
 * the rest of the queue — is chosen behaviour, so it lives here in {@link #drainPending()}, plain
 * JUnit-testable with no Spring context, no database and no clock, as CLAUDE.md's {@code service}
 * rule requires. Only the timer itself ({@code notification.scheduler.OutboxDispatchScheduler}, an
 * {@code @Scheduled} method that calls {@link #drainPending()} and logs the summary) is plumbing.
 * Nothing here polls on an interval or runs on multiple threads. Depends only on interfaces ({@link
 * MessageSender}, {@link NotificationOutboxRepository}, {@link AuditEventWriter}) plus a {@link
 * PlatformTransactionManager}.
 */
@Service
public class OutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

  /** The controlled-vocabulary event type for a dispatch attempt (customer.md audit principles). */
  static final String EVENT_TYPE = "notification_dispatched";

  /** Every notification is tied to the profile whose lifecycle produced it (AD-005 §6A). */
  static final String CHAIN_KIND = "profile";

  /**
   * How many claim-send-record attempts one {@link #drainPending()} pass makes before yielding to
   * the next tick. 50 is AD-005 §6's own batch figure ({@code LIMIT 50}), reused here for the same
   * reason it was chosen there: a backlog is worked through steadily rather than in one unbounded
   * pass.
   *
   * <p>What actually makes this cap load-bearing is not a backlog, though — it is a broken
   * dependency. {@link #drainPending()} catches a failing attempt and continues, so if the database
   * is unreachable, {@code claimOnePending()} throws on every iteration and this cap is the loop's
   * only termination. Without it that tick never ends.
   */
  static final int MAX_ATTEMPTS_PER_TICK = 50;

  private final NotificationOutboxRepository outboxRepository;
  private final MessageSender messageSender;
  private final AuditEventWriter auditEventWriter;
  private final TransactionTemplate transactionTemplate;

  public OutboxDispatcher(
      NotificationOutboxRepository outboxRepository,
      MessageSender messageSender,
      AuditEventWriter auditEventWriter,
      PlatformTransactionManager transactionManager) {
    this.outboxRepository = outboxRepository;
    this.messageSender = messageSender;
    this.auditEventWriter = auditEventWriter;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Dispatches pending, due notifications one after another until the queue has none left, or until
   * {@link #MAX_ATTEMPTS_PER_TICK} attempts have been made — one scheduler tick's worth of work.
   *
   * <p><strong>One bad message must not stop the queue.</strong> Each attempt is isolated: an
   * exception is caught, counted and logged, and the pass moves on to the next pending row. The
   * failed row is not lost and needs no bookkeeping here — {@code claimOnePending()} already
   * advanced that row's {@code next_attempt_at} lease when it claimed it, so the row drops out of
   * this pass and is due again on a later tick. Deliberately no retry-backoff schedule and no
   * dead-letter state: the messaging tier is still a stub, and that complexity belongs with the
   * real provider adapter (S6-04 scope).
   *
   * <p><strong>That "drops out of this pass" was NOT true, and S9-08 fixed it (BL-169).</strong>
   * This Javadoc used to say the property held "only while a pass is shorter than the lease", note
   * that a real provider adapter would break it, and defer the fix to that adapter. Airtel shipped
   * — 5 s connect plus 15 s read — so two timed-out sends in one pass already outlived a 30 s
   * lease, and the deferral's own stated precondition had arrived without anything noticing.
   *
   * <p>The fix was not a longer lease. The exposure was narrower than the old text implied: a row
   * whose send SUCCEEDS or fails permanently leaves this pass in a terminal state and cannot be
   * re-claimed at all. Only a TRANSIENT_FAILURE stays {@code pending} — and {@code recordResult}
   * never reset {@code next_attempt_at}, so such a row kept the CLAIM's lease and fell due again
   * one lease after it was claimed, which can be inside this same pass. A transient failure is
   * precisely the case where the provider may have delivered the message anyway, so the second send
   * is a real duplicate to a real customer.
   *
   * <p>So the two intervals are now separate and separately justified, in application.properties:
   * {@code claim-lease} covers ONE send (claim to result), and {@code transient-retry-backoff}
   * exceeds the longest possible pass. Sizing one number to do both jobs is what produced the
   * defect. The batch claim AD-005 §6 sketches remains unbuilt and is still the right eventual
   * shape.
   *
   * <p>A completed attempt whose provider outcome was REJECTED or TRANSIENT_FAILURE counts as
   * {@link OutboxDrainSummary#attempted()}, not {@link OutboxDrainSummary#failed()} — its result
   * was recorded and audited exactly as designed. Only a throw is a failure of this pass.
   *
   * @return counts for the pass, for the caller to log — never message content
   */
  public OutboxDrainSummary drainPending() {
    int attempted = 0;
    int failed = 0;

    for (int i = 0; i < MAX_ATTEMPTS_PER_TICK; i++) {
      try {
        if (dispatchOnePending().isEmpty()) {
          return new OutboxDrainSummary(attempted, failed, false);
        }
        attempted++;
      } catch (RuntimeException e) {
        failed++;
        // No outbox id in the message: dispatchOnePending() does not surface which row it claimed,
        // and the throw may equally have come from the claim itself. The stack identifies the
        // failing stage, and the row's own claim lease is what schedules its retry.
        //
        // THE ONE RESIDUAL BL-169 DOES NOT CLOSE, stated rather than left implied. A row that is
        // claimed and then THROWS before recordResult runs keeps only the claim lease, so in a pass
        // longer than that lease it can be re-claimed and sent again -- the very thing BL-169 fixed
        // for the transient-failure path. It is narrow: AirtelSmsSender catches RestClientException
        // (connect and read timeouts included) and RETURNS TRANSIENT_FAILURE rather than throwing,
        // so the failure mode that actually occurs at scale goes through recordResult and gets the
        // backoff. What reaches here is a programming error or the database being unavailable, and
        // in the latter case the backoff write would have failed too. Closing it properly means
        // surfacing the claimed id so this catch can apply a backoff itself.
        log.warn("outbox dispatch attempt failed; leaving the row for a later tick", e);
      }
    }
    return new OutboxDrainSummary(attempted, failed, true);
  }

  /**
   * Claims and sends one pending, due notification, if one exists.
   *
   * @return the dispatch result, or empty if nothing was pending
   */
  public Optional<MessageDispatchResult> dispatchOnePending() {
    Optional<ClaimedOutboxEntry> claimed = outboxRepository.claimOnePending();
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(dispatch(claimed.get()));
  }

  private MessageDispatchResult dispatch(ClaimedOutboxEntry entry) {
    OutboundMessage message =
        new OutboundMessage(
            entry.outboxId(),
            entry.channel(),
            entry.destination(),
            entry.payload(),
            Urgency.DEFERRED, // the outbox exists only for deferred, dispatcher-driven sends —
            // an INTERACTIVE OTP is sent inline by the OTP service, never enqueued here
            // (AD-002c report §5.1).
            entry.outboxId().toString());

    MessageDispatchResult result = messageSender.send(message); // outside any transaction

    transactionTemplate.executeWithoutResult(
        status -> {
          outboxRepository.recordResult(entry.outboxId(), result);
          audit(entry.profileId(), result);
        });
    return result;
  }

  private void audit(UUID profileId, MessageDispatchResult result) {
    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            profileId.toString(),
            EVENT_TYPE,
            "system",
            null,
            profileId,
            null,
            null,
            CanonicalJson.object(result.canonicalPayload())));
  }
}
