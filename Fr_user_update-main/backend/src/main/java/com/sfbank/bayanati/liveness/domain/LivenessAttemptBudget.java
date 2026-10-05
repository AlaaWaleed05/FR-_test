package com.sfbank.bayanati.liveness.domain;

/**
 * customer.md Stage 10's policy values: "Liveness attempts -- 5" and "Block after liveness
 * exhaustion -- 24 hours". Unlike {@code identityscan.domain.ScanAttemptBudget}, there is a single
 * counter, not one per document type — every retry applies to the one already-accepted identity
 * cycle, and face-match failure shares the same budget explicitly ("Same budget as liveness, no
 * separate block").
 *
 * <p>Pure logic — no Spring, no database — exercised by plain JUnit (CLAUDE.md's business-logic
 * testability rule).
 */
public final class LivenessAttemptBudget {

  public static final int LIMIT = 5;

  /**
   * The lifetime cap on how many face-session tokens one profile may EVER mint (V0065, BL-039 Slice
   * B). The Stage 10 counterpart of {@code identityscan.domain.ScanAttemptBudget}'s constant of the
   * same name, capped independently and blocking independently — this side applies {@code
   * blocked_liveness}, that side {@code blocked_scan}.
   *
   * <p>Unlike {@link #LIMIT}, which {@code resumeFromLivenessBlock} zeroes on the "try later"
   * promise, this never resets. It is the bound that survives the block, and it is why an
   * unauthenticated caller cannot mint indefinitely by simply waiting each block out. Held at the
   * same 20 as the scan side; the two numbers are kept separately on purpose (see that class), so
   * changing one is a prompt to consider the other, not an automatic change to both.
   */
  public static final int LIFETIME_TOKEN_CAP = 20;

  private LivenessAttemptBudget() {}

  /** Whether a new attempt (token issuance) may start, given how many have already been used. */
  public static boolean canAttempt(int attemptsSoFar) {
    return attemptsSoFar < LIMIT;
  }

  /** Whether recording one more attempt (bringing the running total to {@code newTotal}) blocks. */
  public static boolean blockTriggered(int newTotal) {
    return newTotal >= LIMIT;
  }

  /**
   * Whether this profile has already minted its lifetime allowance of face-session tokens. Crossing
   * it applies the existing 24-hour {@code blocked_liveness} block — no new error code, no new
   * screen, no new copy.
   */
  public static boolean tokenCapReached(int tokensMintedSoFar) {
    return tokensMintedSoFar >= LIFETIME_TOKEN_CAP;
  }
}
