package com.sfbank.bayanati.contactchannels.domain;

/**
 * Thrown when Stage 1b is submitted for an account whose profile already has a terminal status
 * ({@code submitted}/{@code approved}/{@code rejected}/{@code terminated_registry_mismatch}) —
 * whether reached honestly via Stage 1a or by calling this endpoint directly (S3-07, BL-009).
 * customer.md, Stage 1a: "there is no re-entry, no supersede path ... the campaign requires one
 * update per account". Thrown before anything is generated, sent, or written — the check runs
 * before the OTP send loop and before the transaction opens.
 */
public class ProfileAlreadyCompleteException extends RuntimeException {

  public ProfileAlreadyCompleteException(String message) {
    super(message);
  }
}
