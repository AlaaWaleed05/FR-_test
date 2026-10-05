package com.sfbank.bayanati.accountcheck.domain;

import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;

/**
 * The outcomes journey Stage 1a can produce, and the mapping from the core banking middleware's raw
 * {@code Response_Code} onto them.
 *
 * <p>The raw code is the middleware's contract — {@code 1} found / {@code 0} not found / {@code -1}
 * system error, closed by decision (OQ-025) — and stops here: nothing above this class handles
 * integers, and nothing below it handles journey meaning.
 *
 * <p>There is deliberately no outcome for {@code -1}. "System Error" is not something the customer
 * can act on by correcting their input or visiting a branch; it is an outage, and {@code
 * AccountCheckService} surfaces it as {@code CoreBankingUnavailableException} (HTTP 503) before
 * this mapping runs. The former {@code INACTIVE} outcome (Oracle code {@code 2}) is gone with
 * AD-007: the middleware cannot report it.
 */
public enum AccountCheckOutcome {
  /** {@code 1} — the account exists and is active. The journey continues to Stage 1b. */
  ACTIVE(CoreBankingCheckResult.FOUND, AccountCheckContinuation.PROCEED),

  /** {@code 0} — no such account. The customer corrects the number and retries. */
  INVALID(CoreBankingCheckResult.NOT_FOUND, AccountCheckContinuation.RETRY);

  private final int resultCode;
  private final AccountCheckContinuation continuation;

  AccountCheckOutcome(int resultCode, AccountCheckContinuation continuation) {
    this.resultCode = resultCode;
    this.continuation = continuation;
  }

  /** The middleware {@code Response_Code} this outcome corresponds to. */
  public int resultCode() {
    return resultCode;
  }

  /**
   * What the journey does next: retryable, terminal, or proceed. This is journey semantics, not a
   * screen — the backend reports it and the app decides what to render.
   */
  public AccountCheckContinuation continuation() {
    return continuation;
  }

  /**
   * Maps a middleware {@code Response_Code} onto an outcome.
   *
   * <p>Fails closed: an unrecognised code is never quietly folded into one of the two. The contract
   * is {@code 1} / {@code 0} (with {@code -1} handled before this method as an outage) and anything
   * else means our understanding of the middleware is wrong, which is a defect to surface, not to
   * absorb.
   *
   * @throws IllegalArgumentException if {@code resultCode} is not 1 or 0
   */
  public static AccountCheckOutcome fromResultCode(int resultCode) {
    for (AccountCheckOutcome outcome : values()) {
      if (outcome.resultCode == resultCode) {
        return outcome;
      }
    }
    throw new IllegalArgumentException(
        "unrecognised core banking Response_Code: "
            + resultCode
            + " (expected 1 found or 0 not found; -1 system error is an outage, handled before"
            + " mapping)");
  }
}
