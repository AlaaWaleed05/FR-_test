package com.sfbank.bayanati.operator.domain;

/** One-indexed page number, matching how {@code ProfileListController} exposes it to a client. */
public record ProfileListPage(int page, int pageSize) {

  public static final int MAX_PAGE_SIZE = 200;

  public ProfileListPage {
    if (page < 1) {
      throw new IllegalArgumentException("page must be >= 1");
    }
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }
  }

  /**
   * {@code long}, not {@code int}: {@code page} has no upper bound (only {@code pageSize} is
   * capped), so {@code (page - 1) * pageSize} in 32-bit arithmetic overflows to a negative value
   * for a large enough {@code page} — found live, a client-supplied {@code page} near {@code
   * Integer.MAX_VALUE} produced a negative offset PostgreSQL then rejected with an unmapped 500
   * ({@code OFFSET must not be negative}) instead of the empty page a page-past-the-end request
   * should return.
   */
  public long offset() {
    return (long) (page - 1) * pageSize;
  }
}
