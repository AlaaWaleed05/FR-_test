package com.sfbank.bayanati.submission.domain;

/**
 * customer.md Stage 11: signature is mandatory, no skip. A submission attempted without one is
 * rejected — this is one of this task's explicit proof requirements.
 */
public class SignatureMissingException extends RuntimeException {

  public SignatureMissingException(String message) {
    super(message);
  }
}
