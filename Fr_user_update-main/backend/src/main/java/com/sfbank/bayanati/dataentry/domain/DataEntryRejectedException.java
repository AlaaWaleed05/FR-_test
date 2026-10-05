package com.sfbank.bayanati.dataentry.domain;

/**
 * Thrown for any business-rule rejection of a data-entry submission that isn't about the profile
 * itself: an unknown reference code (occupation, country, admin_division, income source), a bad
 * income-source selection (wrong primary count, a duplicate code, {@code OTHER} with no free text),
 * or a monthly-expenses value that isn't a plain non-negative integer. One type covers all of these
 * — each maps to the same {@code 400}, and the message says which rule fired.
 */
public class DataEntryRejectedException extends RuntimeException {

  public DataEntryRejectedException(String message) {
    super(message);
  }
}
