package com.sfbank.bayanati.signature.jdbc;

import com.sfbank.bayanati.signature.domain.SignatureEligibility;
import com.sfbank.bayanati.signature.domain.SignatureRepository;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes the {@code signature} kind of {@code app.artifact_ref} as {@code fru_app}. */
@Repository
public class JdbcSignatureRepository implements SignatureRepository {

  private static final String CHECK_ELIGIBILITY =
      """
      SELECT sc.is_terminal, fr.passed AS face_passed
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
        LEFT JOIN app.identity_cycle ic
          ON ic.profile_id = p.profile_id AND ic.state = 'active' AND ic.accepted_at IS NOT NULL
        LEFT JOIN app.face_result fr ON fr.cycle_id = ic.cycle_id
       WHERE p.profile_id = ?::uuid
      """;

  private static final String UPSERT_SIGNATURE_ARTIFACT =
      """
      INSERT INTO app.artifact_ref
        (profile_id, kind, storage_key, content_type, byte_size, sha256, body, state, created_at)
      VALUES (?::uuid, 'signature', ?::text, ?::text, ?::bigint, ?::bytea, ?::bytea, 'committed',
              ?::timestamptz)
      -- state = EXCLUDED.state (found by @agent-reviewer while reviewing S4-06's identical
      -- salary-certificate upsert): without it, a re-submission after
      -- app.purge_abandoned_artifacts() (V0055) set state='purged' would restore body while
      -- leaving state stuck at 'purged' -- and V0055's own WHERE ar.state <> 'purged' would then
      -- permanently exclude this row from ever being purged again.
      ON CONFLICT (profile_id) WHERE kind = 'signature' DO UPDATE SET
        storage_key = EXCLUDED.storage_key,
        content_type = EXCLUDED.content_type,
        byte_size = EXCLUDED.byte_size,
        sha256 = EXCLUDED.sha256,
        body = EXCLUDED.body,
        state = EXCLUDED.state,
        created_at = EXCLUDED.created_at
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcSignatureRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<SignatureEligibility> checkEligibility(UUID profileId) {
    List<SignatureEligibility> rows =
        jdbcTemplate.query(
            CHECK_ELIGIBILITY,
            (rs, rowNum) -> {
              // ResultSet.wasNull() reflects only the most recently read column -- capture
              // face_passed's null-ness immediately, before is_terminal is read next.
              boolean facePassed = rs.getBoolean("face_passed");
              boolean facePassedWasNull = rs.wasNull();
              boolean isTerminal = rs.getBoolean("is_terminal");
              return new SignatureEligibility(isTerminal, !facePassedWasNull && facePassed);
            },
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void insertSignatureArtifact(
      UUID profileId,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] content,
      String storageKey,
      Instant now) {
    jdbcTemplate.update(
        UPSERT_SIGNATURE_ARTIFACT,
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
