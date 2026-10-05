package com.sfbank.bayanati.liveness.domain;

/**
 * No {@code app.profile} row exists for the given id — an ordinary 404, not a "should never
 * happen". Mirrors {@code identityscan.domain.UnknownProfileException}'s shape; kept as its own
 * type per feature package, same as that package keeps its own copy rather than sharing one across
 * features.
 */
public class UnknownProfileException extends RuntimeException {

  public UnknownProfileException(String message) {
    super(message);
  }
}
