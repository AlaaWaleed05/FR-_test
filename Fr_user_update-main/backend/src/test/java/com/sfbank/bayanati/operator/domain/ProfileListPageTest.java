package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProfileListPageTest {

  @Test
  void offsetIsZeroOnTheFirstPage() {
    assertEquals(0L, new ProfileListPage(1, 20).offset());
  }

  @Test
  void offsetIsComputedInLongArithmeticToAvoidIntOverflow() {
    // (page - 1) * pageSize in plain int arithmetic overflows to a negative value for a large
    // enough page -- found under review: a client-supplied page near Integer.MAX_VALUE produced a
    // negative offset that PostgreSQL then rejected with an unmapped 500 ("OFFSET must not be
    // negative") instead of the empty page a page-past-the-end request should return.
    long offset = new ProfileListPage(Integer.MAX_VALUE, 200).offset();
    assertEquals(429_496_729_200L, offset);
  }

  @Test
  void pageMustBeAtLeastOne() {
    assertThrows(IllegalArgumentException.class, () -> new ProfileListPage(0, 20));
  }

  @Test
  void pageSizeMustBeWithinBounds() {
    assertThrows(IllegalArgumentException.class, () -> new ProfileListPage(1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProfileListPage(1, ProfileListPage.MAX_PAGE_SIZE + 1));
  }
}
