package com.sfbank.bayanati.accountcheck.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mapping journey Stage 1a rests on. Both mapped middleware codes are covered, plus the
 * fail-closed case, which is the one that matters most: any other code from the middleware must not
 * be quietly treated as one of the two. {@code -1} is deliberately in the rejected set — it is an
 * outage, handled by {@code AccountCheckService} before this mapping ever runs.
 */
class AccountCheckOutcomeTest {

  @Test
  void resultCode1IsAnActiveAccountAndTheJourneyProceeds() {
    AccountCheckOutcome outcome = AccountCheckOutcome.fromResultCode(1);

    assertEquals(AccountCheckOutcome.ACTIVE, outcome);
    assertEquals(AccountCheckContinuation.PROCEED, outcome.continuation());
    assertEquals(1, outcome.resultCode());
  }

  @Test
  void resultCode0IsAnInvalidAccountAndIsRetryable() {
    AccountCheckOutcome outcome = AccountCheckOutcome.fromResultCode(0);

    assertEquals(AccountCheckOutcome.INVALID, outcome);
    assertEquals(AccountCheckContinuation.RETRY, outcome.continuation());
    assertEquals(0, outcome.resultCode());
  }

  @Test
  void thereIsNoInactiveOutcomeAnyMore() {
    // AD-007: the middleware cannot report the Oracle contract's 2. Nothing may map it.
    assertEquals(2, AccountCheckOutcome.values().length);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2, 3, -2, 99, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void anyOtherResultCodeIsRejectedRatherThanGuessedAt(int unknownCode) {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> AccountCheckOutcome.fromResultCode(unknownCode));

    assertTrue(
        thrown.getMessage().contains(String.valueOf(unknownCode)),
        "the rejected code should be named in the message, got: " + thrown.getMessage());
  }

  @Test
  void everyOutcomeRoundTripsThroughItsOwnResultCode() {
    // Guards against a future outcome being added with a code that collides with an existing one,
    // which fromResultCode's linear scan would resolve silently in declaration order.
    for (AccountCheckOutcome outcome : AccountCheckOutcome.values()) {
      assertEquals(outcome, AccountCheckOutcome.fromResultCode(outcome.resultCode()));
    }
  }
}
