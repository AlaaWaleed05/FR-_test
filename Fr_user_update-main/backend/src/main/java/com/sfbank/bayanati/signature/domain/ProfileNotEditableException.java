package com.sfbank.bayanati.signature.domain;

/** The profile has already reached a terminal status ({@code app.status_code.is_terminal}). */
public class ProfileNotEditableException extends RuntimeException {

  public ProfileNotEditableException(String message) {
    super(message);
  }
}
