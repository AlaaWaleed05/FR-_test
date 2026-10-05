package com.sfbank.bayanati.operator.domain;

import java.time.Instant;

/**
 * operator.md's profile-list filters, plus the free-text search field. Every member is optional —
 * {@code null} means "no constraint on this field", not "match nothing".
 *
 * @param searchText matched against the scoped field set {@code JdbcProfileListRepository}
 *     documents (account number, branch, reference number, phone, email, national number, names,
 *     status label, rejection-reason code/label) — a deliberate scope, not literally every column,
 *     see that class's Javadoc.
 */
public record ProfileListFilter(
    String status,
    String provenance,
 
    String rejectionReasonCode,
    Instant submittedFrom,
    Instant submittedTo,
    String searchText) {

  public static ProfileListFilter none() {
    return new ProfileListFilter(null, null, null, null, null, null, null);
  }
}
