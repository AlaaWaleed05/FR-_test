package com.sfbank.bayanati.operator.domain;

/**
 * The one way application code reads the operator profile list. All filtering, sorting, searching
 * and pagination happen here, in SQL — never in the caller, and never in the browser (AD-006,
 * BL-015): "the backend does all filtering, sorting, searching and pagination."
 */
public interface ProfileListRepository {

  ProfileListResult search(
      ProfileListFilter filter,
      ProfileListSortField sortField,
      SortOrder sortOrder,
      ProfileListPage page);

  /**
   * operator.md "Export": the same filter this port's {@link #search} applies, honoured by the same
   * WHERE-clause-building code (AD-006, BL-015: "reuses that query rather than reimplementing it —
   * two filter implementations will disagree") — never a separately maintained filter.
   *
   * <p>Ordered {@code p.submitted_at DESC NULLS LAST, p.profile_id ASC}, the same default sort and
   * tiebreaker {@link #search} uses, for the same reason: determinism across a filter matching many
   * rows that share {@code submitted_at IS NULL}.
   *
   * @param rowLimit the maximum rows to return — the implementation queries one more than this and
   *     reports {@link ExportResult#truncated()} if that extra row exists, rather than a separate
   *     {@code COUNT(*)} round trip
   */
  ExportResult forExport(ProfileListFilter filter, int rowLimit);

  /**
   * Ensures the {@code operator} chain this operator's search-audit events append to exists, via
   * {@code audit.ensure_operator_chain} (V0047) — mirrors {@code
   * profile.domain.ProfileRepository#ensureAuditChain}'s idempotent-call-before-every-write shape.
   */
  void ensureOperatorChain(String operatorId);
}
