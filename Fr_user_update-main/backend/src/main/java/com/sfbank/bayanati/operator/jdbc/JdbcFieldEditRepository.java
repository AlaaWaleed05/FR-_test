package com.sfbank.bayanati.operator.jdbc;

import com.sfbank.bayanati.operator.domain.EditabilityFacts;
import com.sfbank.bayanati.operator.domain.EditableField;
import com.sfbank.bayanati.operator.domain.FieldEditRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link FieldEditRepository} over {@code app.profile_customer_data}, {@code
 * app.profile_income_source} and {@code app.profile_field_edit} (V0073).
 *
 * <p><strong>Column names are interpolated into SQL, and that is safe here for one specific
 * reason.</strong> No JDBC driver can bind a column name as a parameter, so a per-field write path
 * either interpolates or writes nineteen near-identical statements. What makes interpolation safe
 * is that the value never comes from the request: {@code EditableField.parse} resolves the wire's
 * {@code fieldKey} against a closed enum first, and an unknown key is a 400 before this class is
 * reached. {@link EditableField#column()} therefore returns one of nineteen compile-time constants.
 * If that enum ever gains a constant whose column is not a literal, this reasoning stops holding.
 */
@Repository
public class JdbcFieldEditRepository implements FieldEditRepository {

  private static final String LOCK_AND_READ_STATUS =
      "SELECT status FROM app.profile WHERE profile_id = ?::uuid FOR UPDATE";

  /**
   * The six facts {@code EditableFieldPolicy} needs, in one round trip.
   *
   * <p>The scan is resolved through the LATEST identity cycle ({@code ORDER BY seq DESC LIMIT 1}),
   * matching {@code JdbcProfileViewRepository}'s own PROFILE query exactly. The two must agree: if
   * the read path derived field 23's editability from a newer cycle than the write path, a chip
   * would appear that the write path then refuses, or the reverse.
   *
   * <p>{@code pcd.profile_id IS NOT NULL} is the {@code customerDataPresent} flag — the join is a
   * LEFT JOIN because a profile that never reached stage 3 has no row, and that profile has no
   * editable field at all rather than being an error.
   */
  private static final String EDITABILITY_FACTS =
      """
      SELECT (pcd.profile_id IS NOT NULL) AS customer_data_present,
             pcd.marital_status, pcd.birth_country_code, pcd.home_country_code,
             pcd.work_country_code, sr.birth_city,
             EXISTS (SELECT 1 FROM app.profile_income_source pis
                      WHERE pis.profile_id = p.profile_id
                        AND pis.source_code = 'OTHER') AS has_other_income
        FROM app.profile p
        LEFT JOIN app.profile_customer_data pcd ON pcd.profile_id = p.profile_id
        LEFT JOIN LATERAL (
          SELECT cycle_id FROM app.identity_cycle
           WHERE profile_id = p.profile_id ORDER BY seq DESC LIMIT 1
        ) ic ON true
        LEFT JOIN app.scan_result sr ON sr.cycle_id = ic.cycle_id
       WHERE p.profile_id = ?::uuid
      """;

  private static final String SELECT_INCOME_OTHER_TEXT =
      """
      SELECT other_text FROM app.profile_income_source
       WHERE profile_id = ?::uuid AND source_code = 'OTHER'
      """;

  private static final String UPDATE_INCOME_OTHER_TEXT =
      """
      UPDATE app.profile_income_source SET other_text = ?
       WHERE profile_id = ?::uuid AND source_code = 'OTHER'
      """;

  /**
   * Upsert, last edit winning. See V0073's header for why this is current state rather than a log:
   * the tamper-evident history is the {@code profile_field_edited} audit chain.
   */
  private static final String RECORD_EDIT =
      """
      INSERT INTO app.profile_field_edit
             (profile_id, field_key, field_number, previous_value, new_value, edited_by, edited_at)
      VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (profile_id, field_key) DO UPDATE
         SET previous_value = EXCLUDED.previous_value,
             new_value      = EXCLUDED.new_value,
             edited_by      = EXCLUDED.edited_by,
             edited_at      = EXCLUDED.edited_at
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcFieldEditRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<String> lockAndReadStatus(UUID profileId) {
    List<String> rows =
        jdbcTemplate.query(
            LOCK_AND_READ_STATUS, (rs, rowNum) -> rs.getString("status"), profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  @Override
  public Optional<EditabilityFacts> editabilityFacts(UUID profileId) {
    List<EditabilityFacts> rows =
        jdbcTemplate.query(
            EDITABILITY_FACTS,
            (rs, rowNum) ->
                new EditabilityFacts(
                    rs.getBoolean("customer_data_present"),
                    rs.getString("marital_status"),
                    rs.getString("birth_country_code"),
                    rs.getString("home_country_code"),
                    rs.getString("work_country_code"),
                    rs.getBoolean("has_other_income"),
                    rs.getString("birth_city")),
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public Optional<String> currentValue(UUID profileId, EditableField field) {
    String sql =
        switch (field.target()) {
          case CUSTOMER_DATA ->
              "SELECT "
                  + field.column()
                  + " AS value FROM app.profile_customer_data WHERE profile_id = ?::uuid";
          case INCOME_SOURCE_OTHER -> SELECT_INCOME_OTHER_TEXT;
        };
    String column = field.target() == EditableField.Target.CUSTOMER_DATA ? "value" : "other_text";
    List<String> rows =
        jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(column), profileId.toString());
    // Optional.ofNullable, not Optional.of: the column is legitimately NULL on a field the customer
    // left unset, and that is a previousValue of null rather than a missing row.
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  @Override
  public boolean updateValue(UUID profileId, EditableField field, String value, Instant now) {
    return switch (field.target()) {
      case CUSTOMER_DATA ->
          jdbcTemplate.update(
                  "UPDATE app.profile_customer_data SET "
                      + field.column()
                      + " = ?, updated_at = ? WHERE profile_id = ?::uuid",
                  value,
                  Timestamp.from(now),
                  profileId.toString())
              > 0;
      // No updated_at bump on the customer-data row: this write does not touch it, and moving its
      // timestamp would claim a row changed that did not. The edit's own time is on
      // app.profile_field_edit and on the audit event.
      case INCOME_SOURCE_OTHER ->
          jdbcTemplate.update(UPDATE_INCOME_OTHER_TEXT, value, profileId.toString()) > 0;
    };
  }

  @Override
  public void recordEdit(
      UUID profileId,
      EditableField field,
      String previousValue,
      String newValue,
      String editedBy,
      Instant editedAt) {
    jdbcTemplate.update(
        RECORD_EDIT,
        profileId.toString(),
        field.name(),
        field.fieldNumber(),
        previousValue,
        newValue,
        editedBy,
        Timestamp.from(editedAt));
  }
}
