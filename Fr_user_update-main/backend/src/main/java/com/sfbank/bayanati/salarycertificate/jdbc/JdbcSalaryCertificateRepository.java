package com.sfbank.bayanati.salarycertificate.jdbc;

import com.sfbank.bayanati.salarycertificate.domain.SalaryCertificateRepository;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes the {@code salary_certificate} kind of {@code app.artifact_ref} as {@code fru_app}. */
@Repository
public class JdbcSalaryCertificateRepository implements SalaryCertificateRepository {

  private static final String CHECK_TERMINAL =
      """
      SELECT sc.is_terminal
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
       WHERE p.profile_id = ?::uuid
      """;

  private static final String UPSERT_SALARY_CERTIFICATE_ARTIFACT =
      """
      INSERT INTO app.artifact_ref
        (profile_id, kind, storage_key, content_type, byte_size, sha256, body, state, created_at)
      VALUES (?::uuid, 'salary_certificate', ?::text, ?::text, ?::bigint, ?::bytea, ?::bytea,
              'committed', ?::timestamptz)
      -- state = EXCLUDED.state (found by @agent-reviewer, S4-06): without it, a re-upload after
      -- app.purge_abandoned_artifacts() (V0055) set state='purged' would restore body while
      -- leaving state stuck at 'purged' -- and V0055's own WHERE ar.state <> 'purged' would then
      -- permanently exclude this row from ever being purged again.
      ON CONFLICT (profile_id) WHERE kind = 'salary_certificate' DO UPDATE SET
        storage_key = EXCLUDED.storage_key,
        content_type = EXCLUDED.content_type,
        byte_size = EXCLUDED.byte_size,
        sha256 = EXCLUDED.sha256,
        body = EXCLUDED.body,
        state = EXCLUDED.state,
        created_at = EXCLUDED.created_at
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcSalaryCertificateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<Boolean> checkTerminal(UUID profileId) {
    List<Boolean> rows =
        jdbcTemplate.query(
            CHECK_TERMINAL, (rs, rowNum) -> rs.getBoolean("is_terminal"), profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void upsertSalaryCertificateArtifact(
      UUID profileId,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] content,
      String storageKey,
      Instant now) {
    jdbcTemplate.update(
        UPSERT_SALARY_CERTIFICATE_ARTIFACT,
        new Object[] {
          profileId.toString(), storageKey, contentType, byteSize, sha256, content, now.toString()
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.BIGINT,
          Types.VARBINARY,
          Types.VARBINARY,
          Types.VARCHAR
        });
  }
}
