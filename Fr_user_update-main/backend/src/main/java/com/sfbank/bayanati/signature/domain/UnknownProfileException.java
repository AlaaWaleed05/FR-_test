package com.sfbank.bayanati.signature.domain;

/** No {@code app.profile} row exists for the given id — an ordinary 404. */
public class UnknownProfileException extends RuntimeException {

  public UnknownProfileException(String message) {
    super(message);
  }
}
