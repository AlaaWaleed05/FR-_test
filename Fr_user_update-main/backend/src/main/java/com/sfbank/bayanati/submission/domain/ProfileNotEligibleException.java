package com.sfbank.bayanati.submission.domain;

import java.util.UUID;

/**
 * The profile cannot be submitted from the status it is in. Distinct from the idempotent
 * short-circuit for {@code submitted}/{@code approved}/{@code rejected} (customer.md: "Reopening
 * the app ... returns the current status" — those three are not errors, they are the normal
 * reconciliation case).
 *
 * <p>S5-13 splits the two causes, because only one of them is actually terminal and telling a
 * customer "your journey is over" when it is not would be a lie:
 *
 * <ul>
 *   <li>{@link #terminalStatus} — the pre-check found a genuinely terminal status, currently only
 *       {@code terminated_registry_mismatch} (Stage 9 "wrong details"), which never has a passing
 *       liveness result or a signature, so it could not reach {@link LivenessNotCompleteException}
 *       or {@link SignatureMissingException} first. Surfaces as {@code PROFILE_TERMINAL}.
 *   <li>{@link #becameIneligible} — the profile left {@code in_progress} between the pre-check and
 *       the row lock. The status it landed in need NOT be terminal: a concurrent {@code
 *       reportLivenessTerminated} can move it to {@code blocked_liveness}, which is temporary. The
 *       app re-syncs through the Stage 13 resume. Surfaces as {@code STATE_CONFLICT}.
 * </ul>
 */
public class ProfileNotEligibleException extends RuntimeException {

  private final boolean terminal;

  private ProfileNotEligibleException(String message, boolean terminal) {
    super(message);
    this.terminal = terminal;
  }

  /** The pre-check found a terminal status this journey branch cannot submit from. */
  public static ProfileNotEligibleException terminalStatus(UUID profileId, String status) {
    return new ProfileNotEligibleException(
        "profile "
            + profileId
            + " is in status "
            + status
            + ", which this journey branch cannot submit from",
        true);
  }

  /**
   * The profile left {@code in_progress} between the pre-check and the lock — possibly to a
   * non-terminal status.
   */
  public static ProfileNotEligibleException becameIneligible(UUID profileId, String status) {
    return new ProfileNotEligibleException(
        "profile "
            + profileId
            + " is in status "
            + status
            + ", which this journey branch cannot submit from",
        false);
  }

  /**
   * {@code true} only for the terminal pre-check case. Read only by {@code SubmissionController}'s
   * exception handler to choose the wire code.
   */
  public boolean terminal() {
    return terminal;
  }
}
