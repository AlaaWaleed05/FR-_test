package com.sfbank.bayanati.operator.domain;

/**
 * The whitelisted set of columns the profile list can sort on — never a client-supplied SQL
 * fragment (AD-006/BL-015: "Ant Design does no filtering and no sorting", the server owns the
 * comparator entirely). {@link #SUBMITTED_AT} descending is operator.md's stated default.
 */
public enum ProfileListSortField {
  SUBMITTED_AT,
  ACCOUNT_NUMBER,
  STATUS,
  BRANCH
}
