package com.sfbank.bayanati.notification.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.notification.domain.OutboxDrainSummary;
import com.sfbank.bayanati.notification.service.OutboxDispatcher;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer's own behaviour, with no Spring context: it delegates one tick to {@link
 * OutboxDispatcher#drainPending()} and does nothing else. That the {@code @Scheduled} annotation is
 * actually honoured by a running container is a different claim, proven against a real context by
 * {@code NotificationOutboxIntegrationTest.theScheduledTaskIsActuallyRegistered}.
 */
class OutboxDispatchSchedulerTest {

  private final OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
  private final OutboxDispatchScheduler scheduler = new OutboxDispatchScheduler(dispatcher);

  @Test
  void aTickDrainsTheOutbox() {
    when(dispatcher.drainPending()).thenReturn(new OutboxDrainSummary(3, 0, false));

    scheduler.pollOutbox();

    verify(dispatcher).drainPending();
  }

  @Test
  void aTickThatFindsNothingIsHarmless() {
    when(dispatcher.drainPending()).thenReturn(new OutboxDrainSummary(0, 0, false));

    scheduler.pollOutbox();

    verify(dispatcher).drainPending();
  }

  @Test
  void aSummaryReportingFailuresOrTheCapIsStillJustLogged() {
    when(dispatcher.drainPending()).thenReturn(new OutboxDrainSummary(49, 1, true));

    scheduler.pollOutbox();

    verify(dispatcher).drainPending();
  }

  @Test
  void nothingIsSwallowedHere() {
    // drainPending() already isolates each message's failure, so anything escaping it is not a bad
    // message. A catch-all here would hide a genuinely broken dispatcher behind an endlessly quiet
    // scheduler.
    when(dispatcher.drainPending()).thenThrow(new IllegalStateException("dispatcher is broken"));

    assertThrows(IllegalStateException.class, scheduler::pollOutbox);
  }

  @Test
  void theScheduleIsAFixedDelayReadFromConfigurationWithADefault() throws NoSuchMethodException {
    // Guards the two things a typo here would break silently: fixedRate instead of fixedDelay
    // (which lets slow passes queue up behind each other), and a hardcoded interval that no
    // deployment could retune. The values themselves are documented on the class.
    Method poll = OutboxDispatchScheduler.class.getMethod("pollOutbox");
    Scheduled scheduled = poll.getAnnotation(Scheduled.class);

    assertEquals("${fru.notification.outbox.poll-interval:30s}", scheduled.fixedDelayString());
    assertEquals(
        "${fru.notification.outbox.poll-initial-delay:30s}", scheduled.initialDelayString());
    assertEquals("", scheduled.fixedRateString(), "fixedRate would allow ticks to pile up");
  }
}
