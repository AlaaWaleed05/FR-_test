package com.sfbank.bayanati.uqudo.domain;

/**
 * The JWS's own {@code exp} claim has passed — a stale-artifact case (R-012/R-021), not a scan
 * defect: uqudo-sdk.md's design rule is explicit that {@code ARTIFACT_EXPIRED} does not count
 * against the stage 8 retry budget, exactly like {@link ImageUnavailableException}. Distinct from
 * {@link JwsVerificationException} so a caller can tell the two apart — collapsing them was found
 * by {@code @agent-reviewer} to silently count a stale clock against the customer's budget.
 */
public class ArtifactExpiredException extends RuntimeException {

  public ArtifactExpiredException(String message) {
    super(message);
  }
}
