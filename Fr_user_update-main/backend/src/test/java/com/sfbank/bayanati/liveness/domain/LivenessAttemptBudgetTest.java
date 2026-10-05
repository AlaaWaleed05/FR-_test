package com.sfbank.bayanati.liveness.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * customer.md Stage 10 policy value: 5, single counter, no per-type split. No Spring context —
 * plain logic (CLAUDE.md's business-logic testability rule).
 */
class LivenessAttemptBudgetTest {

  @Test
  void canAttemptBelowLimit() {
    assertTrue(LivenessAttemptBudget.canAttempt(0));
    assertTrue(LivenessAttemptBudget.canAttempt(4));
  }

  @Test
  void canAttemptFailsAtLimit() {
    assertFalse(LivenessAttemptBudget.canAttempt(5));
    assertFalse(LivenessAttemptBudget.canAttempt(6));
  }

  @Test
  void blockNotTriggeredBelowLimit() {
    assertFalse(LivenessAttemptBudget.blockTriggered(4));
  }

  @Test
  void blockTriggeredAtLimit() {
    assertTrue(LivenessAttemptBudget.blockTriggered(5));
    assertTrue(LivenessAttemptBudget.blockTriggered(6));
  }

  // ---- BL-039 Slice B: the lifetime mint cap ----

  @Test
  void tokenCapNotReachedBelowTheCap() {
    assertFalse(LivenessAttemptBudget.tokenCapReached(0));
    assertFalse(LivenessAttemptBudget.tokenCapReached(19));
  }

  @Test
  void tokenCapReachedAtAndAboveTheCap() {
    assertTrue(LivenessAttemptBudget.tokenCapReached(20));
    assertTrue(LivenessAttemptBudget.tokenCapReached(21));
  }

  /**
   * The two bounds are independent, and this says so: exhausting the per-attempt budget several
   * times over is nowhere near the lifetime cap, which is exactly why the cap closes a hole {@link
   * LivenessAttemptBudget#LIMIT} cannot — {@code LIMIT} is zeroed by every block resume, the cap
   * never is.
   */
  @Test
  void theLifetimeCapIsIndependentOfThePerAttemptLimit() {
    assertFalse(LivenessAttemptBudget.tokenCapReached(LivenessAttemptBudget.LIMIT));
    assertTrue(LivenessAttemptBudget.LIFETIME_TOKEN_CAP > LivenessAttemptBudget.LIMIT);
  }
}
