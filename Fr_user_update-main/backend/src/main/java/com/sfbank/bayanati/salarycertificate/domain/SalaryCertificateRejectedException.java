package com.sfbank.bayanati.salarycertificate.domain;

/** The supplied content fails the customer.md Stage 6 Policy value: 10 MB max, JPEG/PNG/PDF. */
public class SalaryCertificateRejectedException extends RuntimeException {

  public SalaryCertificateRejectedException(String message) {
    super(message);
  }
}
