package com.sfbank.bayanati.reference.jdbc;

import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import com.sfbank.bayanati.reference.domain.ReferenceItemDetail;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries against {@code ref.reference_item} / {@code ref.reference_list_version} —
 * {@code fru_app} already holds {@code SELECT} on both (V0012). No writes; this package never
 * publishes or amends a reference list, only reads one.
 */
@Repository
public class JdbcReferenceCatalog implements ReferenceCatalog {

  private static final String CURRENT_VERSION =
      "SELECT version FROM ref.reference_list_version WHERE list_code = ? AND is_current = true";

  private static final String VERSION_EXISTS =
      "SELECT 1 FROM ref.reference_list_version WHERE list_code = ? AND version = ?";

  private static final String EXISTS =
      """
      SELECT 1 FROM ref.reference_item
       WHERE list_code = ? AND version = ? AND item_code = ? AND is_active = true
      """;

  private static final String PARENT_CODE =
      "SELECT parent_code FROM ref.reference_item WHERE list_code = ? AND version = ? AND item_code = ?";

  private static final String FIND =
      """
      SELECT label_ar, label_en, extra::text AS extra_text
        FROM ref.reference_item
       WHERE list_code = ? AND version = ? AND item_code = ?
      """;

  /**
   * upper() on both sides so an MRZ that arrives lower case still resolves; the seeded values are
   * upper case. LIMIT 1 because alpha-3 is unique across the list in fact but is not declared so by
   * any constraint -- extra is jsonb, and nothing stops a future seed repeating a code. ORDER BY
   * makes the choice a property of the QUERY rather than of the seed: without it a duplicate would
   * resolve to whichever row the plan happened to reach first, and differently between runs.
   */
  private static final String FIND_BY_ALPHA3 =
      """
      SELECT label_ar, label_en, extra::text AS extra_text
        FROM ref.reference_item
       WHERE list_code = ? AND version = ? AND upper(extra->>'alpha3') = upper(?)
       ORDER BY item_code
       LIMIT 1
      """;

  private static final String CURRENT_ROOT_ITEM_CODE =
      """
      SELECT root_item_code FROM ref.reference_list_version
       WHERE list_code = ? AND is_current = true
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcReferenceCatalog(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int currentVersion(String listCode) {
    List<Integer> rows =
        jdbcTemplate.query(CURRENT_VERSION, (rs, rowNum) -> rs.getInt("version"), listCode);
    if (rows.isEmpty()) {
      throw new NoSuchElementException("no current version published for list " + listCode);
    }
    return rows.get(0);
  }

  @Override
  public boolean versionExists(String listCode, int version) {
    List<Integer> rows = jdbcTemplate.query(VERSION_EXISTS, (rs, rowNum) -> 1, listCode, version);
    return !rows.isEmpty();
  }

  @Override
  public boolean exists(String listCode, int version, String itemCode) {
    List<Integer> rows = jdbcTemplate.query(EXISTS, (rs, rowNum) -> 1, listCode, version, itemCode);
    return !rows.isEmpty();
  }

  @Override
  public Optional<String> parentCode(String listCode, int version, String itemCode) {
    List<String> rows =
        jdbcTemplate.query(
            PARENT_CODE, (rs, rowNum) -> rs.getString("parent_code"), listCode, version, itemCode);
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  @Override
  public Optional<ReferenceItemDetail> find(String listCode, int version, String itemCode) {
    List<ReferenceItemDetail> rows =
        jdbcTemplate.query(
            FIND,
            (rs, rowNum) ->
                new ReferenceItemDetail(
                    rs.getString("label_ar"), rs.getString("label_en"), rs.getString("extra_text")),
            listCode,
            version,
            itemCode);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public Optional<ReferenceItemDetail> findByAlpha3(String listCode, int version, String alpha3) {
    List<ReferenceItemDetail> rows =
        jdbcTemplate.query(
            FIND_BY_ALPHA3,
            (rs, rowNum) ->
                new ReferenceItemDetail(
                    rs.getString("label_ar"), rs.getString("label_en"), rs.getString("extra_text")),
            listCode,
            version,
            alpha3);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public Optional<String> currentRootItemCode(String listCode) {
    List<String> rows =
        jdbcTemplate.query(
            CURRENT_ROOT_ITEM_CODE, (rs, rowNum) -> rs.getString("root_item_code"), listCode);
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }
}
