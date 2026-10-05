package com.sfbank.bayanati.dataentry.domain;

/**
 * Thrown when a data-entry stage is submitted for a profile that has already reached a terminal
 * status (customer.md Stage 13: "there is no re-entry" to a submitted/approved/rejected/
 * terminated_registry_mismatch profile). Enforced here, not only on the device, so calling this
 * endpoint directly cannot rewrite a completed profile's customer-entered data.
 */
public class ProfileNotEditableException extends RuntimeException {

  public ProfileNotEditableException(String message) {
    super(message);
  }
}
