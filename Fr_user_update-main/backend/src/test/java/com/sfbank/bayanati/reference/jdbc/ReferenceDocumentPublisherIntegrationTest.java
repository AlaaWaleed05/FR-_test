package com.sfbank.bayanati.reference.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.reference.domain.ReferenceDocumentGenerator;
import com.sfbank.bayanati.reference.domain.ReferenceDocumentItem;
import com.sfbank.bayanati.reference.domain.ReferenceListDocumentInput;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AD-002f's publication step, plus the two schema guards V0049 adds (R-045). {@code fru_app} — the
 * role every other integration test connects as via {@link AbstractPostgresIntegrationTest}'s
 * shared datasource — holds no INSERT/UPDATE grant on {@code ref.reference_list_document} or {@code
 * ref.reference_list_version} (publication is deliberately admin-only, V0012/V0048's own comments).
 * So this class opens its own {@code fru_migrator} connection, the same role {@code
 * ReferenceDocumentPublicationRunner} connects as in production (via the {@code
 * publish-reference-documents} Spring profile) — {@code @SpringBootTest} is kept only so the shared
 * container's Flyway migrations have definitely run before these tests execute.
 *
 * <p><strong>Namespace claim, per {@code AbstractPostgresIntegrationTest}'s rule:</strong> the
 * guard tests below permanently leave two throwaway rows in the shared container, never {@code
 * is_current} so neither can be picked up by {@link
 * ReferenceDocumentPublisher#publishAllCurrentVersions()} or by any code that reads "the current
 * version" of a real list, regardless of test execution order: {@code s403_root_trigger_test} in
 * both {@code ref.reference_list} and {@code ref.reference_list_version} (its {@code
 * reference_item} row never lands — that INSERT is the one under test, and it is rejected), and
 * {@code s403_root_fk_test} in {@code ref.reference_list} only — its {@code reference_list_version}
 * INSERT is the one under test, rejected by {@code root_country_fk} before ever landing.
 */
@Tag("integration")
@SpringBootTest
class ReferenceDocumentPublisherIntegrationTest extends AbstractPostgresIntegrationTest {

  private JdbcTemplate migrator;
  private DataSourceTransactionManager migratorTransactionManager;
  private ReferenceDocumentPublisher publisher;

  @BeforeEach
  void setUpMigratorConnection() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD);
    migrator = new JdbcTemplate(dataSource);
    // Same DataSource instance backs both -- JdbcTemplate participates in
    // DataSourceTransactionManager's transaction via DataSourceUtils, which is what makes
    // ReferenceDocumentPublisher#publish's TransactionTemplate boundary real for this test
    // rather than a no-op (there is no Spring AOP proxy involved either way).
    migratorTransactionManager = new DataSourceTransactionManager(dataSource);
    publisher = new ReferenceDocumentPublisher(migrator, migratorTransactionManager);
  }

  @Test
  void publishingAllCurrentVersionsProducesDocumentsThatRecomputeToTheSameBytes() {
    List<String> published = publisher.publishAllCurrentVersions();

    // Scoped to the seven known lists, not a bare count (AbstractPostgresIntegrationTest's own
    // rule against unscoped whole-table assertions in a container shared across the whole
    // Surefire run) -- occupation, branch, admin_division, income_source, education_level,
    // rejection_reason, country, seeded through S2-07 (PROJECT_PLAN.md AD-002f card). The
    // throwaway rows the two guard tests below leave behind are never `is_current`, so they
    // cannot appear here regardless of test execution order.
    assertEquals(
        java.util.Set.of(
            "occupation/1",
            "branch/1",
            "admin_division/1",
            "income_source/1",
            "education_level/1",
            "rejection_reason/1",
            "country/1"),
        java.util.Set.copyOf(published));

    for (String entry : published) {
      String listCode = entry.substring(0, entry.indexOf('/'));
      int version = Integer.parseInt(entry.substring(entry.indexOf('/') + 1));

      byte[] storedDocumentSha256 =
          migrator.queryForObject(
              "SELECT sha256 FROM ref.reference_list_document WHERE list_code = ? AND version = ?",
              byte[].class,
              listCode,
              version);
      byte[] storedContentHash =
          migrator.queryForObject(
              "SELECT content_hash FROM ref.reference_list_version WHERE list_code = ? AND version = ?",
              byte[].class,
              listCode,
              version);

      byte[] recomputed = recomputeSha256(listCode, version);

      assertArrayEquals(recomputed, storedDocumentSha256, "document sha256 mismatch for " + entry);
      assertArrayEquals(recomputed, storedContentHash, "content_hash mismatch for " + entry);
    }
  }

  @Test
  void publishingTwiceIsIdempotent() {
    publisher.publish("occupation", 1);
    byte[] firstSha256 = readSha256("occupation", 1);
    String firstDocument = readDocumentJson("occupation", 1);
    byte[] firstContentHash = readContentHash("occupation", 1);

    publisher.publish("occupation", 1);
    byte[] secondSha256 = readSha256("occupation", 1);
    String secondDocument = readDocumentJson("occupation", 1);
    byte[] secondContentHash = readContentHash("occupation", 1);

    assertArrayEquals(firstSha256, secondSha256);
    assertEquals(firstDocument, secondDocument);
    assertArrayEquals(firstContentHash, secondContentHash);
  }

  @Test
  void itemCountMismatchIsRejectedBeforeAnyWrite() {
    // Regression for a first-pass reviewer finding: ReferenceListDocumentInput.itemCount's own
    // Javadoc claims it "can be compared" against items.size(), but nothing did until this check
    // was added to ReferenceDocumentPublisher#publish.
    migrator.update(
        "INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)"
            + " VALUES (?, 'x', 'x', false)",
        "s403_item_count_mismatch");
    migrator.update(
        "INSERT INTO ref.reference_list_version"
            + " (list_code, version, content_hash, item_count, is_current)"
            + " VALUES (?, 1, decode('00', 'hex'), 2, false)", // declares 2, only 1 row below
        "s403_item_count_mismatch");
    migrator.update(
        "INSERT INTO ref.reference_item (list_code, version, item_code, label_ar, sort_ordinal)"
            + " VALUES (?, 1, 'A', 'x', 1)",
        "s403_item_count_mismatch");

    assertThrows(
        IllegalStateException.class, () -> publisher.publish("s403_item_count_mismatch", 1));

    Integer documentRows =
        migrator.queryForObject(
            "SELECT count(*) FROM ref.reference_list_document WHERE list_code = ? AND version = 1",
            Integer.class,
            "s403_item_count_mismatch");
    assertEquals(0, documentRows, "no document row must be written when the count check fails");
  }

  @Test
  void aFailureInsideAnOuterTransactionRollsBackBothWritesTogether() {
    // Regression for a first-pass reviewer finding: replacing @Transactional (which never applied
    // -- self-invocation bypasses the Spring proxy) with an explicit TransactionTemplate needed
    // proof the two writes actually commit or roll back as one unit, not just that the code
    // compiles. publish()'s TransactionTemplate uses REQUIRED propagation by default, so it joins
    // this outer transaction rather than starting its own -- an outer rollback must undo both the
    // reference_list_document upsert and the content_hash update together.
    migrator.update(
        "INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)"
            + " VALUES (?, 'x', 'x', false)",
        "s403_tx_rollback_test");
    migrator.update(
        "INSERT INTO ref.reference_list_version"
            + " (list_code, version, content_hash, item_count, is_current)"
            + " VALUES (?, 1, decode('00', 'hex'), 1, false)",
        "s403_tx_rollback_test");
    migrator.update(
        "INSERT INTO ref.reference_item (list_code, version, item_code, label_ar, sort_ordinal)"
            + " VALUES (?, 1, 'A', 'x', 1)",
        "s403_tx_rollback_test");
    byte[] contentHashBeforePublish = readContentHash("s403_tx_rollback_test", 1);

    class DeliberateFailure extends RuntimeException {}
    TransactionTemplate outer = new TransactionTemplate(migratorTransactionManager);
    assertThrows(
        DeliberateFailure.class,
        () ->
            outer.executeWithoutResult(
                status -> {
                  publisher.publish("s403_tx_rollback_test", 1);
                  throw new DeliberateFailure();
                }));

    Integer documentRows =
        migrator.queryForObject(
            "SELECT count(*) FROM ref.reference_list_document WHERE list_code = ? AND version = 1",
            Integer.class,
            "s403_tx_rollback_test");
    assertEquals(
        0, documentRows, "the document upsert must have rolled back with the outer failure");
    assertArrayEquals(
        contentHashBeforePublish,
        readContentHash("s403_tx_rollback_test", 1),
        "content_hash must be unchanged -- it must have rolled back together with the document write");
  }

  @Test
  void constraintTriggerRejectsAHierarchicalListWhoseParentlessRowDisagreesWithTheDeclaredRoot() {
    // R-045's whole purpose (research report §3.3/§9) -- a throwaway, never-current list version
    // so it cannot interfere with anything else reading "the current version" of a real list.
    migrator.update(
        "INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)"
            + " VALUES (?, 'x', 'x', true)",
        "s403_root_trigger_test");
    migrator.update(
        "INSERT INTO ref.reference_list_version"
            + " (list_code, version, content_hash, item_count, is_current, root_item_code,"
            + "  root_country_version)"
            + " VALUES (?, 1, decode('00', 'hex'), 1, false, 'SD', 1)",
        "s403_root_trigger_test");

    // The list's own parentless row carries 'ZZ', not the declared root 'SD' -- must be rejected
    // at commit (the constraint trigger is DEFERRABLE INITIALLY DEFERRED; a plain JdbcTemplate
    // connection auto-commits each statement, so this single INSERT's own implicit commit is
    // where the deferred check runs).
    DataAccessException ex =
        assertThrows(
            DataAccessException.class,
            () ->
                migrator.update(
                    "INSERT INTO ref.reference_item"
                        + " (list_code, version, item_code, parent_code, label_ar, sort_ordinal)"
                        + " VALUES (?, 1, 'ZZ', NULL, 'x', 1)",
                    "s403_root_trigger_test"));

    assertTrue(
        ex.getMostSpecificCause().getMessage().contains("does not match declared root_item_code"),
        "unexpected message: " + ex.getMostSpecificCause().getMessage());
  }

  @Test
  void compositeFkRejectsARootItemCodeAbsentFromTheCountryList() {
    migrator.update(
        "INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)"
            + " VALUES (?, 'x', 'x', true)",
        "s403_root_fk_test");

    DataAccessException ex =
        assertThrows(
            DataAccessException.class,
            () ->
                migrator.update(
                    "INSERT INTO ref.reference_list_version"
                        + " (list_code, version, content_hash, item_count, is_current,"
                        + "  root_item_code, root_country_version)"
                        + " VALUES (?, 1, decode('00', 'hex'), 0, false, 'ZZ', 1)",
                    "s403_root_fk_test"));

    assertTrue(
        ex.getMostSpecificCause().getMessage().toLowerCase().contains("root_country_fk"),
        "unexpected message: " + ex.getMostSpecificCause().getMessage());
  }

  private byte[] readSha256(String listCode, int version) {
    return migrator.queryForObject(
        "SELECT sha256 FROM ref.reference_list_document WHERE list_code = ? AND version = ?",
        byte[].class,
        listCode,
        version);
  }

  private String readDocumentJson(String listCode, int version) {
    return migrator.queryForObject(
        "SELECT document_json FROM ref.reference_list_document WHERE list_code = ? AND version = ?",
        String.class,
        listCode,
        version);
  }

  private byte[] readContentHash(String listCode, int version) {
    return migrator.queryForObject(
        "SELECT content_hash FROM ref.reference_list_version WHERE list_code = ? AND version = ?",
        byte[].class,
        listCode,
        version);
  }

  /**
   * Independently re-reads {@code ref.reference_item}/{@code ref.reference_list_version} and
   * regenerates -- the guard against the document and the rows drifting apart (research report
   * §6.3). Deliberately its own query text, not a call into {@code ReferenceDocumentPublisher}'s
   * internals: this proves the generator reproduces the stored bytes from current rows, not merely
   * that the same method returns the same thing twice (the separate {@code
   * publishingTwiceIsIdempotent} test above already covers that).
   */
  private byte[] recomputeSha256(String listCode, int version) {
    record Metadata(
        String nameAr, String nameEn, boolean isHierarchical, int itemCount, String rootItemCode) {}

    Metadata metadata =
        migrator.queryForObject(
            "SELECT l.name_ar, l.name_en, l.is_hierarchical, v.item_count, v.root_item_code"
                + "  FROM ref.reference_list_version v JOIN ref.reference_list l"
                + "    ON l.list_code = v.list_code"
                + " WHERE v.list_code = ? AND v.version = ?",
            (rs, rowNum) ->
                new Metadata(
                    rs.getString("name_ar"),
                    rs.getString("name_en"),
                    rs.getBoolean("is_hierarchical"),
                    rs.getInt("item_count"),
                    rs.getString("root_item_code")),
            listCode,
            version);

    List<ReferenceDocumentItem> items =
        migrator.query(
            "SELECT item_code, parent_code, label_ar, label_en, search_ar, search_en,"
                + "        sort_ordinal, is_active, extra::text AS extra_text"
                + "   FROM ref.reference_item WHERE list_code = ? AND version = ?"
                + "  ORDER BY item_code COLLATE \"C\"",
            (rs, rowNum) ->
                new ReferenceDocumentItem(
                    rs.getString("item_code"),
                    rs.getString("parent_code"),
                    rs.getString("label_ar"),
                    rs.getString("label_en"),
                    rs.getString("search_ar"),
                    rs.getString("search_en"),
                    rs.getInt("sort_ordinal"),
                    rs.getBoolean("is_active"),
                    rs.getString("extra_text")),
            listCode,
            version);

    ReferenceListDocumentInput input =
        new ReferenceListDocumentInput(
            listCode,
            version,
            metadata.itemCount(),
            metadata.nameAr(),
            metadata.nameEn(),
            metadata.isHierarchical(),
            metadata.rootItemCode(),
            items);

    byte[] documentBytes = ReferenceDocumentGenerator.generate(input);
    try {
      return MessageDigest.getInstance("SHA-256").digest(documentBytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
