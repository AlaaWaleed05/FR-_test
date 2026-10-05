package com.sfbank.bayanati.reference.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import com.sfbank.bayanati.reference.domain.ReferenceItemDetail;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Against the real, seeded {@code ref} schema — S3-11's first code to read it from Java. Tagged
 * "integration", run with {@code ./mvnw test -Pdb-integration-test}.
 */
@Tag("integration")
@SpringBootTest
class JdbcReferenceCatalogTest extends AbstractPostgresIntegrationTest {

  @Autowired private ReferenceCatalog referenceCatalog;

  @Test
  void currentVersionIsPublishedForEverySeededList() {
    assertEquals(1, referenceCatalog.currentVersion("occupation"));
    assertEquals(1, referenceCatalog.currentVersion("country"));
    assertEquals(1, referenceCatalog.currentVersion("admin_division"));
    assertEquals(1, referenceCatalog.currentVersion("income_source"));
  }

  @Test
  void anActiveOccupationCodeExists() {
    // مبرمج / Programmer -- the smaller of two duplicate codes, the one that wins (PROJECT_PLAN.md
    // decisions log).
    assertTrue(referenceCatalog.exists("occupation", 1, "86"));
  }

  @Test
  void aSupersededDuplicateOccupationCodeDoesNotExist() {
    // The larger, is_active=false duplicate for the same occupation as code 86.
    assertFalse(referenceCatalog.exists("occupation", 1, "133"));
  }

  /**
   * BL-154, and the whole mechanism of the withdrawal in one assertion pair.
   *
   * <p>AD-022 ruling 3 removed the on-screen evidence for a face-match failure, so REJ-03 was a
   * reason an operator could cite but no longer see. The product owner withdrew it (2026-09-16,
   * V0074) by {@code is_active = false} rather than by deleting the row or publishing a new list
   * version — and this is why that works: {@code exists} filters the flag, so {@code
   * OperatorReviewService.validateReasonCode} now refuses the code; {@code find} does NOT, so every
   * profile already rejected under it keeps its label.
   *
   * <p>Deleting the row is impossible in any case — {@code app.profile_status_history} carries a
   * composite FK onto {@code ref.reference_item} (V0013).
   */
  @Test
  void theWithdrawnRejectionReasonCannotBeChosenButIsStillResolvable() {
    int version = referenceCatalog.currentVersion("rejection_reason");

    assertFalse(
        referenceCatalog.exists("rejection_reason", version, "REJ-03"),
        "REJ-03 is withdrawn and must not validate as a choosable reason (BL-154)");

    Optional<ReferenceItemDetail> stillResolvable =
        referenceCatalog.find("rejection_reason", version, "REJ-03");
    assertTrue(
        stillResolvable.isPresent(),
        "a profile already rejected under REJ-03 must keep its label, not show a bare code");
    assertEquals("فشل أو عدم وضوح مطابقة الوجه", stillResolvable.get().labelAr());
  }

  /** The withdrawal is scoped to REJ-03 alone — the other six stay choosable. */
  @Test
  void everyOtherRejectionReasonIsStillChoosable() {
    int version = referenceCatalog.currentVersion("rejection_reason");

    for (String code : new String[] {"REJ-01", "REJ-02", "REJ-04", "REJ-05", "REJ-06", "REJ-07"}) {
      assertTrue(referenceCatalog.exists("rejection_reason", version, code), code);
    }
  }

  @Test
  void anUnknownCodeDoesNotExist() {
    assertFalse(referenceCatalog.exists("occupation", 1, "not-a-real-code"));
  }

  @Test
  void adminDivisionParentChainResolvesCorrectly() {
    // '11' (Northern state) is a direct child of the list's root, 'SD'.
    assertEquals(
        java.util.Optional.of("SD"), referenceCatalog.parentCode("admin_division", 1, "11"));
    // '1101' (Halfa) is a child of state '11'.
    assertEquals(
        java.util.Optional.of("11"), referenceCatalog.parentCode("admin_division", 1, "1101"));
  }

  @Test
  void rootItemHasNoParent() {
    assertEquals(
        java.util.Optional.empty(), referenceCatalog.parentCode("admin_division", 1, "SD"));
  }

  @Test
  void hierarchicalListDeclaresItsCascadeRoot() {
    // V0049 (AD-002f, R-045): admin_division's current version declares 'SD' as its root,
    // matching the parentless row S2-03 seeded (V0016) and guarded by a composite FK onto the
    // country list's own 'SD' row (V0022).
    assertEquals(
        java.util.Optional.of("SD"), referenceCatalog.currentRootItemCode("admin_division"));
  }

  @Test
  void flatListHasNoDeclaredRoot() {
    assertEquals(java.util.Optional.empty(), referenceCatalog.currentRootItemCode("occupation"));
    assertEquals(java.util.Optional.empty(), referenceCatalog.currentRootItemCode("country"));
  }

  @Test
  void aPublishedVersionExists() {
    // S4-04, AD-002f §5.3: versionExists checks any published version, not only the current one --
    // the seeds only ever published version 1, so this is the same row currentVersion() itself
    // reads.
    assertTrue(referenceCatalog.versionExists("occupation", 1));
  }

  @Test
  void aNeverPublishedVersionDoesNotExist() {
    assertFalse(referenceCatalog.versionExists("occupation", 99));
  }
}
