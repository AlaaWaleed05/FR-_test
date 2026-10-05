package com.sfbank.bayanati.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import org.junit.jupiter.api.Test;

class OutboxStateTest {

  @Test
  void acceptedIsDispatchedAndTerminal() {
    assertEquals(OutboxState.DISPATCHED, OutboxState.forOutcome(DispatchOutcome.ACCEPTED));
  }

  @Test
  void rejectedIsFailedAndNeverRetried() {
    assertEquals(OutboxState.FAILED, OutboxState.forOutcome(DispatchOutcome.REJECTED));
  }

  @Test
  void permanentFailureIsFailedAndNeverRetried() {
    assertEquals(OutboxState.FAILED, OutboxState.forOutcome(DispatchOutcome.PERMANENT_FAILURE));
  }

  @Test
  void transientFailureStaysPendingForRetry() {
    assertEquals(OutboxState.PENDING, OutboxState.forOutcome(DispatchOutcome.TRANSIENT_FAILURE));
  }

  @Test
  void wireValuesMatchTheCheckConstraintOnAppNotificationOutboxState() {
    assertEquals("pending", OutboxState.PENDING.wireValue());
    assertEquals("dispatched", OutboxState.DISPATCHED.wireValue());
    assertEquals("failed", OutboxState.FAILED.wireValue());
  }
}
