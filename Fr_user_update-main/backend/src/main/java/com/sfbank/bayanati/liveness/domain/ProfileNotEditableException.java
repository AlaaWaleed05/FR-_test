package com.sfbank.bayanati.liveness.domain;

/**
 * The profile has already reached a terminal status ({@code app.status_code.is_terminal}) — mirrors
 * {@code identityscan.domain.ProfileNotEditableException}'s role for stage 10.
 */
public class ProfileNotEditableException extends RuntimeException {

  public ProfileNotEditableException(String message) {
    super(message);
  }
}
