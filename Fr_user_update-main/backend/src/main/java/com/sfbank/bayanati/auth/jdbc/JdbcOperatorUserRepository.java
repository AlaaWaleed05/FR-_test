package com.sfbank.bayanati.auth.jdbc;

import com.sfbank.bayanati.auth.domain.DuplicateUsernameException;
import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code app.operator_user} (V0057), as {@code fru_app}. */
@Repository
public class JdbcOperatorUserRepository implements OperatorUserRepository {

  private static final String COLUMNS =
      "user_id::text, username, display_name, role, password_hash, must_change_password,"
          + " is_enabled";

  private static final String FIND_BY_USERNAME =
      "SELECT " + COLUMNS + " FROM app.operator_user WHERE username = ?";

  private static final String FIND_ACTIVE_BY_ID =
      "SELECT " + COLUMNS + " FROM app.operator_user WHERE user_id = ?::uuid AND is_enabled = true";

  private static final String INSERT =
      """
      INSERT INTO app.operator_user
        (username, display_name, role, password_hash, must_change_password, created_by)
      VALUES (?, ?, ?, ?, true, ?::uuid)
      RETURNING user_id::text
      """;

  private static final String UPDATE_PASSWORD =
      """
      UPDATE app.operator_user
         SET password_hash = ?, must_change_password = false, password_changed_at = ?::timestamptz,
             row_version = row_version + 1
       WHERE user_id = ?::uuid
      """;

  private static final String RECORD_SIGN_IN =
      "UPDATE app.operator_user SET last_sign_in_at = ?::timestamptz WHERE user_id = ?::uuid";

  private static final String ENSURE_OPERATOR_CHAIN = "SELECT audit.ensure_operator_chain(?)";

  private final JdbcTemplate jdbcTemplate;

  public JdbcOperatorUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<OperatorAccount> findByUsername(String username) {
    return oneRow(FIND_BY_USERNAME, username);
  }

  @Override
  public Optional<OperatorAccount> findActiveById(UUID userId) {
    return oneRow(FIND_ACTIVE_BY_ID, userId.toString());
  }

  private Optional<OperatorAccount> oneRow(String sql, String param) {
    List<OperatorAccount> rows = jdbcTemplate.query(sql, JdbcOperatorUserRepository::mapRow, param);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static OperatorAccount mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OperatorAccount(
        UUID.fromString(rs.getString("user_id")),
        rs.getString("username"),
        rs.getString("display_name"),
        OperatorRole.valueOf(rs.getString("role").toUpperCase(Locale.ROOT)),
        rs.getString("password_hash"),
        rs.getBoolean("must_change_password"),
        rs.getBoolean("is_enabled"));
  }

  @Override
  public UUID create(
      String username, String displayName, OperatorRole role, String passwordHash, UUID createdBy) {
    try {
      String userId =
          jdbcTemplate.queryForObject(
              INSERT,
              String.class,
              username,
              displayName,
              role.name().toLowerCase(Locale.ROOT),
              passwordHash,
              createdBy == null ? null : createdBy.toString());
      return UUID.fromString(userId);
    } catch (DuplicateKeyException e) {
      throw new DuplicateUsernameException("username '" + username + "' is already taken");
    }
  }

  @Override
  public void updatePassword(UUID userId, String passwordHash, Instant changedAt) {
    jdbcTemplate.update(UPDATE_PASSWORD, passwordHash, changedAt.toString(), userId.toString());
  }

  @Override
  public void recordSignIn(UUID userId, Instant at) {
    jdbcTemplate.update(RECORD_SIGN_IN, at.toString(), userId.toString());
  }

  @Override
  public void ensureOperatorChain(UUID userId) {
    jdbcTemplate.query(ENSURE_OPERATOR_CHAIN, (rs, rowNum) -> null, userId.toString());
  }
}
