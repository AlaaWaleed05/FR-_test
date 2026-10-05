package com.sfbank.bayanati.liveness.domain;

import java.util.UUID;

/**
 * Stage 10 cannot proceed against the profile's current state, for one of two reasons. S5-13 splits
 * them because the customer sees different screens and has a different remedy — the same split, and
 * the same shape, as {@code identityscan.domain.NoActiveRegistryReviewException}:
 *
 * <ul>
 *   <li>{@link #noAcceptedCycle} — the profile has no {@code app.identity_cycle} row that is both
 *       {@code active} and {@code accepted_at IS NOT NULL}, so stage 10 was reached before stage
 *       9's registry review was accepted. Nothing the customer did leads here from a correct
 *       client; it is a state-sync defect, resolved by the Stage 13 resume. Surfaces as {@code
 *       STATE_CONFLICT}.
 *   <li>{@link #referenceImageUnavailable} — such a cycle exists, but its {@code portrait_uqudo}
 *       reference image has no usable body (S5-06: purged by {@code
 *       app.purge_abandoned_artifacts()} after 90 days abandoned, then the profile was
 *       reactivated), so a fresh scan is required before liveness can run. The customer goes back
 *       to stage 7/8 and rescans. Surfaces as {@code RESCAN_REQUIRED}.
 * </ul>
 */
public class NoAcceptedIdentityCycleException extends RuntimeException {

  private final boolean rescanRequired;

  private NoAcceptedIdentityCycleException(String message, boolean rescanRequired) {
    super(message);
    this.rescanRequired = rescanRequired;
  }

  /** No active, accepted identity cycle exists for this profile at all. */
  public static NoAcceptedIdentityCycleException noAcceptedCycle(UUID profileId) {
    return new NoAcceptedIdentityCycleException(
        "profile " + profileId + " has no accepted identity cycle to run liveness against", false);
  }

  /** The cycle exists; its reference portrait has been purged, so the customer must rescan. */
  public static NoAcceptedIdentityCycleException referenceImageUnavailable(UUID profileId) {
    return new NoAcceptedIdentityCycleException(
        "profile "
            + profileId
            + " has no usable reference image for its accepted identity cycle -- a fresh scan"
            + " is required",
        true);
  }

  /**
   * {@code true} for the purged-portrait case, {@code false} for the missing-cycle case. Read only
   * by {@code LivenessController}'s exception handler to choose the wire code.
   */
  public boolean rescanRequired() {
    return rescanRequired;
  }
}
