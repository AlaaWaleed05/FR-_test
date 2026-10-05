package com.sfbank.bayanati.operator.domain;

/**
 * "Free text alone is not accepted as a rejection reason" (operator.md) — thrown when {@code
 * reasonCode} is blank, not one of the {@code rejection_reason} list's current items, or is {@code
 * REJ-07} with no {@code internalNote} (V0009's own {@code rej07_needs_detail} CHECK, validated
 * here first for a clean 400 instead of a raw constraint-violation 500). An ordinary 400 — checked
 * before any write, per operator.md's "requires... before anything is read further" pattern already
 * used by every other reject-shaped precondition in this codebase.
 */
public class InvalidRejectionReasonException extends RuntimeException {

  public InvalidRejectionReasonException(String message) {
    super(message);
  }
}
