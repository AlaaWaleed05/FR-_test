package com.sfbank.bayanati.identityscan.domain;

import java.util.UUID;

/**
 * Stage 9's actions (accept, wrong-number, wrong-details, retry) all require an active identity
 * cycle whose registry lookup has come back {@code ok}. Two distinct situations fail that
 * requirement, and BL-033 splits them because the customer sees different screens:
 *
 * <ul>
 *   <li>{@link #registryNotReady} — the cycle exists but its {@code registry_result.state} is not
 *       yet {@code ok}. The customer is legitimately paused (customer.md Stage 9 gives this its own
 *       screen) and the client has usually raced its own retry. Surfaces as {@code
 *       REGISTRY_NOT_READY}.
 *   <li>{@link #noActiveCycle} — there is no active identity cycle at all. Nothing the customer did
 *       leads here from a correct client; it is a state-sync defect, resolved by the Stage 13
 *       resume. Surfaces as {@code STATE_CONFLICT}.
 * </ul>
 */
public class NoActiveRegistryReviewException extends RuntimeException {

  private final boolean registryNotReady;

  private NoActiveRegistryReviewException(String message, boolean registryNotReady) {
    super(message);
    this.registryNotReady = registryNotReady;
  }

  /** The cycle exists; its registry result is not {@code ok} yet. The customer is paused. */
  public static NoActiveRegistryReviewException registryNotReady(UUID profileId) {
    return new NoActiveRegistryReviewException(
        "profile " + profileId + " has no ready registry result to review", true);
  }

  /** No active identity cycle exists for this profile at all. */
  public static NoActiveRegistryReviewException noActiveCycle(UUID profileId) {
    return new NoActiveRegistryReviewException(
        "profile " + profileId + " has no active identity cycle to review", false);
  }

  /**
   * {@code true} for the pause case, {@code false} for the missing-cycle case. Read only by {@code
   * IdentityScanController}'s exception handler to choose the wire code.
   */
  public boolean registryNotReady() {
    return registryNotReady;
  }
}
