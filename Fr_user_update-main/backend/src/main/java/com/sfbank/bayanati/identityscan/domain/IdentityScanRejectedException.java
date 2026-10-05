package com.sfbank.bayanati.identityscan.domain;

/**
 * A business-rule rejection with no more specific exception type — mirrors {@code
 * dataentry.domain.DataEntryRejectedException}.
 */
public class IdentityScanRejectedException extends RuntimeException {

  public IdentityScanRejectedException(String message) {
    super(message);
  }
}
