package com.sfbank.bayanati.auth.domain;

/**
 * Thrown when a new password exceeds bcrypt's 72-byte input limit. Bytes, not characters — Arabic
 * is 2 bytes/character in UTF-8, so a 40-character Arabic passphrase already exceeds this.
 */
public class PasswordTooLongException extends RuntimeException {

  public PasswordTooLongException(String message) {
    super(message);
  }
}
