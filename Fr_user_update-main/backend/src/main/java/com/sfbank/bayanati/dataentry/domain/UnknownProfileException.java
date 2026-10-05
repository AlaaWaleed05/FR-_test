package com.sfbank.bayanati.dataentry.domain;

/** Thrown when a data-entry stage is submitted for a {@code profileId} that does not exist. */
public class UnknownProfileException extends RuntimeException {

  public UnknownProfileException(String message) {
    super(message);
  }
}
