package com.sfbank.bayanati.identityscan.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * customer.md Stage 8 policy values: 5 per document type, 10 total (BL-039, 2026-09-06; was 3 and
 * 6). No Spring context — plain logic (CLAUDE.md's business-logic testability rule).
 */
class ScanAttemptBudgetTest {

  @Test
  void canAttemptBelowPerTypeLimit() {
    assertTrue(ScanAttemptBudget.canAttempt(0));
    assertTrue(ScanAttemptBudget.canAttempt(4));
  }

  @Test
  void canAttemptFailsAtPerTypeLimit() {
    assertFalse(ScanAttemptBudget.canAttempt(5));
    assertFalse(ScanAttemptBudget.canAttempt(6));
  }

  @Test
  void blockNotTriggeredBelowTotalLimit() {
    assertFalse(ScanAttemptBudget.blockTriggered(9));
  }

  @Test
  void blockTriggeredAtTotalLimit() {
    assertTrue(ScanAttemptBudget.blockTriggered(10));
    assertTrue(ScanAttemptBudget.blockTriggered(11));
  }

  @Test
  void exhaustingOneTypeAloneDoesNotTriggerTheBlock() {
    // customer.md: "once exhausted, the customer may still switch to the other document type" --
    // reconciled with "10 bounds the whole stage" because TOTAL_LIMIT == 2 * PER_TYPE_LIMIT: the
    // first type's exhaustion (total 5) never alone reaches the block threshold.
    assertFalse(ScanAttemptBudget.canAttempt(ScanAttemptBudget.PER_TYPE_LIMIT));
    assertFalse(ScanAttemptBudget.blockTriggered(ScanAttemptBudget.PER_TYPE_LIMIT));
  }

  // ---- BL-039 Slice B: the lifetime mint cap ----

  @Test
  void tokenCapNotReachedBelowTheCap() {
    assertFalse(ScanAttemptBudget.tokenCapReached(0));
    assertFalse(ScanAttemptBudget.tokenCapReached(19));
  }

  @Test
  void tokenCapReachedAtAndAboveTheCap() {
    assertTrue(ScanAttemptBudget.tokenCapReached(20));
    assertTrue(ScanAttemptBudget.tokenCapReached(21));
  }

  /**
   * The cap is a different KIND of bound from the per-type/total pair, and this states the
   * relationship the DECISION recorded as its accepted trade-off: 20 mints is about two full budget
   * cycles of {@link ScanAttemptBudget#TOTAL_LIMIT} launches each. Asserted as a property rather
   * than as "20 == 2 * 10" so that retuning either number leaves this test saying something true or
   * failing loudly, instead of quietly meaning nothing.
   */
  @Test
  void theLifetimeCapIsAboutTwoFullBudgetCycles() {
    assertEquals(2, ScanAttemptBudget.LIFETIME_TOKEN_CAP / ScanAttemptBudget.TOTAL_LIMIT);
    // And it is NOT derived from them -- a profile that exhausts one full cycle is nowhere near it.
    assertFalse(ScanAttemptBudget.tokenCapReached(ScanAttemptBudget.TOTAL_LIMIT));
  }

  /**
   * The relationship itself, not the two numbers. BL-039 raised the per-type limit and the total
   * had to move with it: a total left at 6 against a per-type of 5 would give the customer ONE try
   * on their second document, turning the promised switch into a token gesture. TOTAL_LIMIT is
   * derived in code so the pair cannot drift; this asserts the property that derivation exists to
   * hold, so a future edit that re-writes it as a literal fails here.
   */
  @Test
  void totalLimitIsExactlyTwiceThePerTypeLimit() {
    assertEquals(2 * ScanAttemptBudget.PER_TYPE_LIMIT, ScanAttemptBudget.TOTAL_LIMIT);

    // Stated as behaviour, not just arithmetic: exhausting BOTH types is what reaches the block,
    // and the last attempt of the second type is the one that trips it.
    assertFalse(ScanAttemptBudget.blockTriggered(2 * ScanAttemptBudget.PER_TYPE_LIMIT - 1));
    assertTrue(ScanAttemptBudget.blockTriggered(2 * ScanAttemptBudget.PER_TYPE_LIMIT));
  }
}
