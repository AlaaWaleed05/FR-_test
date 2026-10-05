package com.sfbank.bayanati.liveness.domain;

/**
 * The profile is {@code awaiting_registry} — stage 9's Civil Registry retry is still pending, so
 * stage 10 cannot be reached yet. Mirrors {@code
 * identityscan.domain.RegistryReviewPendingException}. Also guards {@code recordFailedAttempt}
 * against mutating the retry-budget columns or firing an illegal {@code awaiting_registry ->
 * blocked_liveness} transition V0020 does not define — the exact defect class
 * {@code @agent-reviewer} found in this method's stage-8 counterpart at S3-12.
 */
public class RegistryReviewPendingException extends RuntimeException {

  public RegistryReviewPendingException(String message) {
    super(message);
  }
}
