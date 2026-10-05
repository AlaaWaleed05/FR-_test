package com.sfbank.bayanati.auth.domain;

/** Thrown by {@link OperatorUserRepository#create} when {@code username} already exists. */
public class DuplicateUsernameException extends RuntimeException {

  public DuplicateUsernameException(String message) {
    super(message);
  }
}
