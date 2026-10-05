package com.sfbank.bayanati.auth.domain;

/**
 * Thrown by {@code auth.service.PasswordChangeService} when the supplied current password does not
 * match the stored hash.
 */
public class IncorrectCurrentPasswordException extends RuntimeException {

  public IncorrectCurrentPasswordException(String message) {
    super(message);
  }
}
