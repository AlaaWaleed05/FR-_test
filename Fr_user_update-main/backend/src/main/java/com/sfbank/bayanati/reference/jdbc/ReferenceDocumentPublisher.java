package com.sfbank.bayanati.reference.jdbc;

import com.sfbank.bayanati.reference.domain.ReferenceDocumentGenerator;
import com.sfbank.bayanati.reference.domain.ReferenceDocumentItem;
import com.sfbank.bayanati.reference.domain.ReferenceListDocumentInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AD-002f's publication step (research report §6.7/§6.3) — populates {@code
 * ref.reference_list_document} and redefines {@code ref.reference_list_version.content_hash} as the
 * SHA-256 of the generated document's bytes. Run only by {@code ReferenceDocumentPublicationRunner}
 * (a Spring profile-gated {@code CommandLineRunner}, not an HTTP endpoint — see that class's
 * Javadoc and {@code db/post-migrate/02-publish-reference- documents.md}), which is the only caller
 * expected to connect as {@code fru_migrator} — {@code fru_app} holds no INSERT/UPDATE grant on
 * either table this class writes.
 *
 * <p><strong>Idempotent.</strong> {@code ref.reference_item} rows for a published version are never
 * rewritten (AD-005's never-delete-a-version rule), so re-reading the same {@code (listCode,
 * version)} and regenerating always produces byte-identical output; publishing twice overwrites
 * with identical values both times.
 */
@Repository
public class ReferenceDocumentPublisher {

  private static final String CURRENT_VERSIONS =
      "SELECT list_code, version FROM ref.reference_list_version WHERE is_current = true"
          + " ORDER BY list_code";

  private static final String ASSERT_ROOT = "SELECT ref.assert_list_root(?, ?)";

  private static final String LIST_METADATA =
      """
      SELECT l.name_ar, l.name_en, l.is_hierarchical, v.item_count, v.root_item_code
        FROM ref.reference_list_version v
        JOIN ref.reference_list l ON l.list_code = v.list_code
       WHERE v.list_code = ? AND v.version = ?
      """;

  private static final String ITEMS =
      """
      SELECT item_code, parent_code, label_ar, label_en, search_ar, search_en, sort_ordinal,
             is_active, extra::text AS extra_text
        FROM ref.reference_item
       WHERE list_code = ? AND version = ?
       ORDER BY item_code COLLATE "C"
      """;

  private static final String UPSERT_DOCUMENT =
      """
      INSERT INTO ref.reference_list_document (list_code, version, document_json, sha256)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (list_code, version) DO UPDATE
        SET document_json = EXCLUDED.document_json,
            sha256 = EXCLUDED.sha256,
            published_at = clock_timestamp()
      """;

  private static final String UPDATE_CONTENT_HASH =
      "UPDATE ref.reference_list_version SET content_hash = ? WHERE list_code = ? AND version = ?";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public ReferenceDocumentPublisher(
      JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** Publishes every list version currently marked {@code is_current}, one at a time. */
  public List<String> publishAllCurrentVersions() {
    List<ListVersion> current =
        jdbcTemplate.query(
            CURRENT_VERSIONS,
            (rs, rowNum) -> new ListVersion(rs.getString("list_code"), rs.getInt("version")));
    List<String> published = new ArrayList<>();
    for (ListVersion lv : current) {
      publish(lv.listCode(), lv.version());
      published.add(lv.listCode() + "/" + lv.version());
    }
    return published;
  }

  /**
   * Publishes one {@code (listCode, version)}: validates the cascade root first (loud failure
   * before writing anything, doubling the guarantee the constraint trigger already gives future
   * {@code ref.reference_item} writes), then generates, hashes, and stores the document.
   *
   * <p>Both writes run in one transaction via {@link TransactionTemplate} — not
   * {@code @Transactional}, which would need a Spring AOP proxy to take effect and this class is
   * never called through one (self-invocation from {@link #publishAllCurrentVersions()}, and
   * constructed directly with {@code new} in {@code ReferenceDocumentPublisherIntegrationTest}).
   * Without an explicit transaction, a failure between the two {@code jdbcTemplate.update} calls
   * below would leave {@code ref.reference_list_document} holding new bytes while {@code
   * ref.reference_list_version.content_hash} still held the old value — exactly the document/hash
   * mismatch this whole design exists to prevent.
   */
  public void publish(String listCode, int version) {
    transactionTemplate.executeWithoutResult(
        status -> {
          assertRoot(listCode, version);
          ReferenceListDocumentInput input = load(listCode, version);
          if (input.itemCount() != input.items().size()) {
            throw new IllegalStateException(
                "ref.reference_list_version.item_count ("
                    + input.itemCount()
                    + ") disagrees with the actual row count ("
                    + input.items().size()
                    + ") for "
                    + listCode
                    + "/"
                    + version);
          }
          byte[] documentBytes = ReferenceDocumentGenerator.generate(input);
          byte[] sha256 = sha256(documentBytes);
          String documentJson = new String(documentBytes, StandardCharsets.UTF_8);

          jdbcTemplate.update(UPSERT_DOCUMENT, listCode, version, documentJson, sha256);
          jdbcTemplate.update(UPDATE_CONTENT_HASH, sha256, listCode, version);
        });
  }

  private void assertRoot(String listCode, int version) {
    jdbcTemplate.query(
        ASSERT_ROOT,
        ps -> {
          ps.setString(1, listCode);
          ps.setInt(2, version);
        },
        rs -> null);
  }

  private ReferenceListDocumentInput load(String listCode, int version) {
    ListMetadata metadata =
        jdbcTemplate.queryForObject(
            LIST_METADATA,
            (rs, rowNum) ->
                new ListMetadata(
                    rs.getString("name_ar"),
                    rs.getString("name_en"),
                    rs.getBoolean("is_hierarchical"),
                    rs.getInt("item_count"),
                    rs.getString("root_item_code")),
            listCode,
            version);

    List<ReferenceDocumentItem> items =
        jdbcTemplate.query(
            ITEMS,
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

    return new ReferenceListDocumentInput(
        listCode,
        version,
        metadata.itemCount(),
        metadata.nameAr(),
        metadata.nameEn(),
        metadata.isHierarchical(),
        metadata.rootItemCode(),
        items);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed present on every JDK (java.security.MessageDigest javadoc,
      // "Every implementation of the Java platform is required to support the following
      // standard MessageDigest algorithms: ... SHA-256").
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private record ListVersion(String listCode, int version) {}

  private record ListMetadata(
      String nameAr, String nameEn, boolean isHierarchical, int itemCount, String rootItemCode) {}
}
