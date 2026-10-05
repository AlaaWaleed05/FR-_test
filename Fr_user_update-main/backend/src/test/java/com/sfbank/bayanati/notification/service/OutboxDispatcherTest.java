package com.sfbank.bayanati.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.notification.domain.ClaimedOutboxEntry;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.notification.domain.OutboxDrainSummary;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class OutboxDispatcherTest {

  private final NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
  private final MessageSender messageSender = mock(MessageSender.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);

  // A transaction manager whose getTransaction/commit is a no-op recorder rather than a real
  // resource manager: this class is exercised with plain JUnit and no database (CLAUDE.md's
  // service-package rule), so what matters here is proving OutboxDispatcher opens exactly one
  // transaction around recordResult+audit, not exercising real commit/rollback semantics — that
  // is NotificationOutboxIntegrationTest's job, against a real PostgreSQL.
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

  private final OutboxDispatcher dispatcher =
      new OutboxDispatcher(repository, messageSender, auditEventWriter, transactionManager);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
  }

  @Test
  void nothingPendingReturnsEmptyAndTouchesNothingElse() {
    when(repository.claimOnePending()).thenReturn(Optional.empty());

    assertEquals(Optional.empty(), dispatcher.dispatchOnePending());

    verify(messageSender, never()).send(any());
    verify(auditEventWriter, never()).append(any());
  }

  @Test
  void aClaimedEntryIsSentRecordedAndAudited() {
    UUID outboxId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    ClaimedOutboxEntry entry =
        new ClaimedOutboxEntry(
            outboxId, profileId, MessageChannel.SMS, "+249900000000", new SmsPayload("body"));
    when(repository.claimOnePending()).thenReturn(Optional.of(entry));

    ArgumentCaptor<OutboundMessage> messageCaptor = ArgumentCaptor.forClass(OutboundMessage.class);
    MessageDispatchResult result =
        new MessageDispatchResult(
            outboxId.toString(),
            "sms",
            DispatchOutcome.ACCEPTED,
            "stub",
            "msg-1",
            "0",
            "ok",
            1,
            "2026-08-29T12:00:00Z",
            5L);
    when(messageSender.send(messageCaptor.capture())).thenReturn(result);

    Optional<MessageDispatchResult> actual = dispatcher.dispatchOnePending();

    assertEquals(Optional.of(result), actual);

    // The message built from the claimed row: messageId = outboxId, urgency always DEFERRED —
    // the outbox exists only for deferred sends (AD-002c report §5.1).
    OutboundMessage sent = messageCaptor.getValue();
    assertEquals(outboxId, sent.messageId());
    assertEquals(MessageChannel.SMS, sent.channel());
    assertEquals(Urgency.DEFERRED, sent.urgency());
    assertEquals(outboxId.toString(), sent.correlationId());

    verify(repository).recordResult(outboxId, result);

    ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(eventCaptor.capture());
    AuditEvent event = eventCaptor.getValue();
    assertEquals("profile", event.chainKind());
    assertEquals(profileId.toString(), event.chainSubject());
    assertEquals("notification_dispatched", event.eventType());
    assertEquals(profileId, event.profileId());
    assertTrue(event.payloadJson().contains("\"outcome\":\"ACCEPTED\""));

    // recordResult and the audit write happen inside one transaction, opened after send()
    // returns — proves the fix for the atomicity gap AD-005 §6 requires (see the class Javadoc).
    InOrder order = inOrder(messageSender, transactionManager, repository, auditEventWriter);
    order.verify(messageSender).send(any());
    order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    order.verify(repository).recordResult(outboxId, result);
    order.verify(auditEventWriter).append(any());
    order.verify(transactionManager).commit(transactionStatus);
  }

  @Test
  void aFailureRecordingTheResultRollsBackRatherThanLeavingTheAuditWriteUnpaired() {
    UUID outboxId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    ClaimedOutboxEntry entry =
        new ClaimedOutboxEntry(
            outboxId, profileId, MessageChannel.SMS, "+249900000000", new SmsPayload("body"));
    when(repository.claimOnePending()).thenReturn(Optional.of(entry));
    MessageDispatchResult result =
        MessageDispatchResult.permanentFailure(
            new OutboundMessage(
                outboxId,
                MessageChannel.SMS,
                "+249900000000",
                new SmsPayload("body"),
                Urgency.DEFERRED,
                "c"),
            "stub",
            "X",
            "boom");
    when(messageSender.send(any())).thenReturn(result);
    doThrow(new RuntimeException("db down")).when(repository).recordResult(outboxId, result);

    assertThrows(RuntimeException.class, dispatcher::dispatchOnePending);

    // The audit write must never happen without its paired recordResult, and the transaction
    // must be rolled back, not committed, once the callback threw.
    verify(auditEventWriter, never()).append(any());
    verify(transactionManager, never()).commit(any());
    verify(transactionManager).rollback(transactionStatus);
  }

  // --- S6-04: drainPending(), the loop a scheduler tick runs ------------------------------------

  @Test
  void drainPendingKeepsGoingUntilTheQueueIsEmpty() {
    ClaimedOutboxEntry first = anEntry();
    ClaimedOutboxEntry second = anEntry();
    ClaimedOutboxEntry third = anEntry();
    when(repository.claimOnePending())
        .thenReturn(Optional.of(first), Optional.of(second), Optional.of(third), Optional.empty());
    when(messageSender.send(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

    OutboxDrainSummary summary = dispatcher.drainPending();

    assertEquals(new OutboxDrainSummary(3, 0, false), summary);
    // All three, not just the first: the S6-04 defect was a dispatcher nothing ever called a
    // second time, so "sends more than one per pass" is the whole point of this method.
    verify(messageSender, times(3)).send(any());
    verify(repository).recordResult(eq(first.outboxId()), any());
    verify(repository).recordResult(eq(second.outboxId()), any());
    verify(repository).recordResult(eq(third.outboxId()), any());
    verify(auditEventWriter, times(3)).append(any());
    // The fourth claim is the one that found nothing and ended the pass.
    verify(repository, times(4)).claimOnePending();
  }

  @Test
  void oneFailingMessageIsCountedAndTheRestOfTheQueueStillDrains() {
    ClaimedOutboxEntry first = anEntry();
    ClaimedOutboxEntry poison = anEntry();
    ClaimedOutboxEntry third = anEntry();
    when(repository.claimOnePending())
        .thenReturn(Optional.of(first), Optional.of(poison), Optional.of(third), Optional.empty());
    when(messageSender.send(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));
    doThrow(new RuntimeException("db down for this row"))
        .when(repository)
        .recordResult(eq(poison.outboxId()), any());

    OutboxDrainSummary summary = dispatcher.drainPending();

    assertEquals(new OutboxDrainSummary(2, 1, false), summary);
    // The row after the failing one was still dispatched — a poison message must not block the
    // queue behind it. Asserting third specifically, not just a count: a pass that aborted on the
    // failure and one that continued both leave "2 attempted" if only totals are checked.
    verify(repository).recordResult(eq(first.outboxId()), any());
    verify(repository).recordResult(eq(third.outboxId()), any());
    // Nothing extra is done to the failed row here: its claim-time lease is the retry mechanism,
    // and S6-04 deliberately builds no backoff schedule and no dead-letter state.
    verify(auditEventWriter, times(2)).append(any());
  }

  @Test
  void aPassIsBoundedByThePerTickCapEvenIfRowsNeverRunOut() {
    when(repository.claimOnePending()).thenAnswer(invocation -> Optional.of(anEntry()));
    when(messageSender.send(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

    OutboxDrainSummary summary = dispatcher.drainPending();

    assertEquals(
        new OutboxDrainSummary(OutboxDispatcher.MAX_ATTEMPTS_PER_TICK, 0, true),
        summary,
        "a backlog must yield to the next tick rather than run unbounded");
    verify(repository, times(OutboxDispatcher.MAX_ATTEMPTS_PER_TICK)).claimOnePending();
  }

  @Test
  void aTotallyBrokenDependencyEndsTheTickAtTheCapRatherThanSpinningForever() {
    // The case the cap actually exists for: with the database unreachable it is the claim itself
    // that throws, so there is no "next row" to move on to and the empty-queue exit is never
    // reached. Without the cap this pass would never return.
    when(repository.claimOnePending()).thenThrow(new RuntimeException("connection refused"));

    OutboxDrainSummary summary = dispatcher.drainPending();

    assertEquals(new OutboxDrainSummary(0, OutboxDispatcher.MAX_ATTEMPTS_PER_TICK, true), summary);
    verify(messageSender, never()).send(any());
    verify(auditEventWriter, never()).append(any());
  }

  private static ClaimedOutboxEntry anEntry() {
    return new ClaimedOutboxEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        MessageChannel.SMS,
        "+249900000000",
        new SmsPayload("body"));
  }

  private static MessageDispatchResult accepted(OutboundMessage message) {
    return new MessageDispatchResult(
        message.messageId().toString(),
        "sms",
        DispatchOutcome.ACCEPTED,
        "stub",
        "msg",
        "0",
        "ok",
        1,
        "2026-09-04T12:00:00Z",
        5L);
  }
}
