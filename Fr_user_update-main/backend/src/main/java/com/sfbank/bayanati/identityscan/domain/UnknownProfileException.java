package com.sfbank.bayanati.identityscan.domain;

/**
 * No {@code app.profile} row exists for the given id — an ordinary 404, not a "should never
 * happen".
 */
public class UnknownProfileException extends RuntimeException {

  public UnknownProfileException(String message) {
    super(message);
  }
}
