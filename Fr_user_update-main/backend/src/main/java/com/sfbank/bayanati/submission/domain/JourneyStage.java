package com.sfbank.bayanati.submission.domain;

/**
 * Where a customer stands in journey Stages 10-12, as the Stage 13 resume read reports it
 * (customer.md l.1112-1114: "The backend alone decides whether the session is still open. Every
 * resume begins by asking. The answer can be <em>proceed</em>, <em>already complete</em>, or
 * <em>blocked until X</em>").
 *
 * <p>Deliberately not a screen name — the same discipline {@code AccountCheckContinuation} states
 * for Stage 1a. The backend says where the customer is; the app owns the mapping to a screen.
 *
 * <p><strong>Why the three already-complete values are answers and not refusals.</strong> The
 * sibling Stage 9 read ({@code IdentityScanService#currentReviewPayload}) rejects a terminal
 * profile outright, because a Stage 9 display payload has nothing to say about a finished journey.
 * This read is the opposite case: {@link #SUBMITTED} is its single most important answer. A
 * customer whose app was killed between submitting and rendering the confirmation screen has no
 * other way to recover their reference number, and terminal-refusal would dead-end exactly the
 * person the read exists to rescue. Terminal-refusal stays where it belongs — on the action
 * endpoints, where it is what stops a submitted customer signing again.
 */
public enum JourneyStage {

  /** Stage 10 is available and has not passed yet. */
  LIVENESS,

  /** The Stage 10 budget is spent; the pointer carries when the block lifts. */
  LIVENESS_BLOCKED,

  /** Stage 10 passed, no signature stored yet — the customer is at Stage 11. */
  SIGNATURE,

  /** Liveness passed and a signature is stored — the customer is at Stage 12, not yet submitted. */
  SUBMIT,

  /** Submitted and awaiting operator review. Carries the reference number and channels. */
  SUBMITTED,

  /** Reviewed and accepted. Carries the reference number and channels. */
  APPROVED,

  /** Reviewed and refused. Carries the reference number and channels. */
  REJECTED
}
