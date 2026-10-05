package com.sfbank.bayanati.reference.jdbc;

import com.sfbank.bayanati.reference.domain.ManifestHasher;
import com.sfbank.bayanati.reference.domain.ManifestListEntry;
import com.sfbank.bayanati.reference.domain.ReferenceDocumentStore;
import com.sfbank.bayanati.reference.domain.ReferenceManifest;
import com.sfbank.bayanati.reference.domain.StoredDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries backing the two reference read endpoints (AD-002f §5.1/§6.6) — {@code fru_app}
 * holds {@code SELECT} on {@code reference_list}/{@code reference_list_version} already (V0012) and
 * on {@code reference_list_document} as of V0052. No writes; publication stays {@code
 * ReferenceDocumentPublisher}'s job, run only as {@code fru_migrator}.
 */
@Repository
public class JdbcReferenceDocumentStore implements ReferenceDocumentStore {

  private static final String CURRENT_LISTS =
      """
      SELECT v.list_code, v.version, v.item_count, v.content_hash, l.is_hierarchical,
             v.root_item_code, v.root_country_version, v.published_at
        FROM ref.reference_list_version v
        JOIN ref.reference_list l ON l.list_code = v.list_code
       WHERE v.is_current = true
       ORDER BY v.list_code
      """;

  private static final String DOCUMENT =
      """
      SELECT document_json, sha256, published_at
        FROM ref.reference_list_document
       WHERE list_code = ? AND version = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcReferenceDocumentStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public ReferenceManifest currentManifest() {
    List<ManifestListEntry> lists =
        jdbcTemplate.query(
            CURRENT_LISTS,
            (rs, rowNum) ->
                new ManifestListEntry(
                    rs.getString("list_code"),
                    rs.getInt("version"),
                    rs.getInt("item_count"),
                    HexFormat.of().formatHex(rs.getBytes("content_hash")),
                    rs.getBoolean("is_hierarchical"),
                    rs.getString("root_item_code"),
                    (Integer) rs.getObject("root_country_version"),
                    rs.getTimestamp("published_at").toInstant()));
    return new ReferenceManifest(ManifestHasher.catalogHash(lists), Instant.now(), lists);
  }

  @Override
  public Optional<StoredDocument> document(String listCode, int version) {
    List<StoredDocument> rows =
        jdbcTemplate.query(
            DOCUMENT,
            (rs, rowNum) ->
                new StoredDocument(
                    rs.getString("document_json").getBytes(StandardCharsets.UTF_8),
                    HexFormat.of().formatHex(rs.getBytes("sha256")),
                    rs.getTimestamp("published_at").toInstant()),
            listCode,
            version);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }
}
