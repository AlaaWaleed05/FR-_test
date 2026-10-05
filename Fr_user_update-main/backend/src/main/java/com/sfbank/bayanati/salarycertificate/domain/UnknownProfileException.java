package com.sfbank.bayanati.salarycertificate.domain;

/** No {@code app.profile} row exists for the supplied {@code profileId}. */
public class UnknownProfileException extends RuntimeException {

  public UnknownProfileException(String message) {
    super(message);
  }
}
