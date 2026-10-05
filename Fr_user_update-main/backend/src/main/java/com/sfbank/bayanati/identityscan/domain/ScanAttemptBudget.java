package com.sfbank.bayanati.identityscan.domain;

/**
 * customer.md Stage 8's policy values: 5 scan attempts per document type, 10 total. Pure logic, no
 * Spring, no database — exercised by plain JUnit (CLAUDE.md's business-logic testability rule).
 *
 * <p><strong>What one attempt is</strong> (BL-039, settled 2026-09-06): a launched SDK session —
 * one token per launch, and that token is single-use. The budget counts a <em>spent</em> attempt,
 * not a token mint and not a submission: a successful scan, a camera-permission denial, an expired
 * artifact and an images-unavailable outcome all stay free to the customer. {@code
 * IdentityScanService.recordFailedAttempt} and {@code reportWrongNumber} are the only two paths
 * that spend one, and both now consult {@link #canAttempt} under the same row lock that does the
 * spending — before BL-039 the check sat at token issuance alone, so one never-cleared pending
 * session could absorb unlimited failed posts.
 *
 * <p>Reconciling the two policy statements that read as contradictory in isolation: "once
 * exhausted, the customer may still switch to the other document type with a fresh per-type budget"
 * and "10 bounds the whole stage regardless of switching". Because {@link #TOTAL_LIMIT} is
 * <em>derived</em> as exactly {@code 2 * PER_TYPE_LIMIT}, the total can only reach 10 once
 * <em>both</em> per-type budgets are separately exhausted — so exhausting the first type alone only
 * forces a switch (via {@link #canAttempt}); the real 24h block is what {@link #blockTriggered}
 * answers, and it can only fire at the point the second type also runs out. Derived rather than
 * written out so the two cannot drift: a total left at 6 against a per-type of 5 would leave the
 * customer one try on their second document, making the promised switch a token gesture.
 */
public final class ScanAttemptBudget {

  public static final int PER_TYPE_LIMIT = 5;

  /**
   * Deliberately derived, never a literal — see the class Javadoc. The document-switch fallback is
   * only a real offer while this is exactly twice {@link #PER_TYPE_LIMIT}.
   */
  public static final int TOTAL_LIMIT = 2 * PER_TYPE_LIMIT;

  /**
   * The lifetime cap on how many scan tokens one profile may EVER mint (V0065, BL-039 Slice B) — a
   * different bound from {@link #PER_TYPE_LIMIT}/{@link #TOTAL_LIMIT} and deliberately not derived
   * from them.
   *
   * <p>Those two count <em>spent attempts</em> and are zeroed by {@code resumeFromScanBlock} on
   * customer.md's "try later" promise. This one counts <em>mints</em> and never resets, which is
   * what closes the hole they cannot: token issuance was gated by a counter it never incremented,
   * so requesting a token and never using it moved nothing at all, on a surface that is
   * unauthenticated by design (R-051).
   *
   * <p>20 is about two full budget cycles of 10. {@code LivenessAttemptBudget} carries its own copy
   * of this number rather than sharing one: the two features are separate quarantine boundaries
   * (CLAUDE.md, Architecture), they block independently, and either cap could be retuned without
   * the other. Change one, consider the other.
   */
  public static final int LIFETIME_TOKEN_CAP = 20;

  private ScanAttemptBudget() {}

  /** Whether a new attempt may start, given how many this document type has already used. */
  public static boolean canAttempt(int attemptsForRequestedType) {
    return attemptsForRequestedType < PER_TYPE_LIMIT;
  }

  /**
   * Whether recording one more attempt (bringing the running total to {@code newTotal}) triggers
   * customer.md's 24h block.
   */
  public static boolean blockTriggered(int newTotal) {
    return newTotal >= TOTAL_LIMIT;
  }

  /**
   * Whether this profile has already minted its lifetime allowance of scan tokens, given how many
   * it has minted so far. Crossing it applies the existing 24-hour block — no new error code, no
   * new screen, no new copy.
   */
  public static boolean tokenCapReached(int tokensMintedSoFar) {
    return tokensMintedSoFar >= LIFETIME_TOKEN_CAP;
  }
}
