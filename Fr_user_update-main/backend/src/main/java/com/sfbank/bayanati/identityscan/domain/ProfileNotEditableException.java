package com.sfbank.bayanati.identityscan.domain;

/**
 * The profile has already reached a terminal status — mirrors {@code dataentry}'s exception of the
 * same shape.
 */
public class ProfileNotEditableException extends RuntimeException {

  public ProfileNotEditableException(String message) {
    super(message);
  }
}
