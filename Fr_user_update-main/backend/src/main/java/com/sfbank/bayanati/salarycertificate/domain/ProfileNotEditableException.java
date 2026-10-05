package com.sfbank.bayanati.salarycertificate.domain;

/**
 * The profile has already reached a terminal status (customer.md Stage 12: "no re-entry, including
 * after a rejection") — no further certificate upload or replacement is accepted, the same rule and
 * reasoning already applied to the signature.
 */
public class ProfileNotEditableException extends RuntimeException {

  public ProfileNotEditableException(String message) {
    super(message);
  }
}
