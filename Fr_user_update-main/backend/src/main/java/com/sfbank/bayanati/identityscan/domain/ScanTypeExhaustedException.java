package com.sfbank.bayanati.identityscan.domain;

/**
 * customer.md Stage 8: "once exhausted, the customer may still switch to the other document type
 * with a fresh per-type budget." This document type's 3 attempts are spent, but the total has not
 * yet reached 6 — a narrower rejection than {@link ScanTemporarilyBlockedException}: the stage is
 * not blocked, only this one document type is.
 */
public class ScanTypeExhaustedException extends RuntimeException {

  public ScanTypeExhaustedException(String appDocumentType) {
    super(
        appDocumentType
            + " has used its scan attempts; the other document type may still be tried");
  }
}
