package com.sfbank.bayanati.contactchannels.domain;

/**
 * Thrown by {@link PhoneNumberNormalizer} when a phone number cannot be confidently normalised to
 * E.164 — either it contains a non-ASCII digit (BL-016: never silently transliterated) or its
 * format is not one of the small set this normaliser understands.
 */
public class InvalidPhoneNumberException extends RuntimeException {

  public InvalidPhoneNumberException(String message) {
    super(message);
  }
}
