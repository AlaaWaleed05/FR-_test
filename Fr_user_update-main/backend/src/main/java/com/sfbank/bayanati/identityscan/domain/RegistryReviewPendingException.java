package com.sfbank.bayanati.identityscan.domain;

/**
 * The profile is paused awaiting a Civil Registry retry (status {@code awaiting_registry}) — a new
 * Stage 8 scan attempt cannot start until that resolves (accept/wrong-number/wrong-details) or the
 * retry succeeds. Prevents {@code applyScanBlock}/{@code transitionToTerminatedMismatch} from ever
 * being asked to fire an illegal {@code awaiting_registry -> *} transition V0020 does not define —
 * found by {@code @agent-reviewer}.
 */
public class RegistryReviewPendingException extends RuntimeException {

  public RegistryReviewPendingException(String message) {
    super(message);
  }
}
