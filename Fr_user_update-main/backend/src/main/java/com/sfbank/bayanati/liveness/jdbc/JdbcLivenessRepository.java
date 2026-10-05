package com.sfbank.bayanati.liveness.jdbc;

import com.sfbank.bayanati.liveness.domain.LivenessRepository;
import com.sfbank.bayanati.liveness.domain.LivenessState;
import com.sfbank.bayanati.liveness.domain.ReferenceImage;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code app.face_result}, the {@code face_audit_trail} {@code app.artifact_ref} rows, and
 * the stage-10 retry-budget columns on {@code app.profile} as {@code fru_app}. The single place in
 * the application that knows these tables' shapes for this feature.
 */
@Repository
public class JdbcLivenessRepository implements LivenessRepository {

  private static final String LOCK_AND_GET_LIVENESS_STATE =
      """
      SELECT p.status, sc.is_terminal, p.liveness_attempts, p.liveness_blocked_until,
             p.pending_face_session_id, p.face_tokens_minted,
             ic.cycle_id AS accepted_cycle_id, fr.passed AS face_passed
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
        LEFT JOIN app.identity_cycle ic
          ON ic.profile_id = p.profile_id AND ic.state = 'active' AND ic.accepted_at IS NOT NULL
        LEFT JOIN app.face_result fr ON fr.cycle_id = ic.cycle_id
       WHERE p.profile_id = ?::uuid
         FOR UPDATE OF p
      """;

  // AD-004 closed at S5-06: the portrait now lives durably in app.artifact_ref
  // (kind='portrait_uqudo', matching ParsedImage.PORTRAIT_UQUDO), read back through
  // app.artifact_read() -- checksum-verified, raises rather than silently returning corrupted
  // bytes, but returns NULL (not an error) for a body that is legitimately absent (purged by
  // app.purge_abandoned_artifacts(), or never stored). Replaces V0041/V0042's transient
  // scan_result.face_reference_image bridge column, now dropped (see RISKS.md R-047).
  private static final String CURRENT_ACCEPTED_CYCLE_REFERENCE_IMAGE =
      """
      SELECT ic.cycle_id, app.artifact_read(ar.artifact_ref_id) AS body
        FROM app.identity_cycle ic
        JOIN app.artifact_ref ar ON ar.cycle_id = ic.cycle_id AND ar.kind = 'portrait_uqudo'
       WHERE ic.profile_id = ?::uuid AND ic.state = 'active' AND ic.accepted_at IS NOT NULL
      """;

  private static final String RECORD_PENDING_FACE_SESSION =
      "UPDATE app.profile SET pending_face_session_id = ?::text WHERE profile_id = ?::uuid";

  private static final String CURRENT_FACE_RESULT_JTI =
      "SELECT uqudo_jti FROM app.face_result WHERE cycle_id = ?::uuid";

  private static final String APPLY_LIVENESS_BLOCK =
      """
      UPDATE app.profile
         SET status = 'blocked_liveness', status_changed_at = ?::timestamptz,
             liveness_blocked_until = ?::timestamptz, row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String RESUME_FROM_LIVENESS_BLOCK =
      """
      UPDATE app.profile
         SET status = 'in_progress', status_changed_at = ?::timestamptz,
             liveness_attempts = 0, liveness_blocked_until = NULL, row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  // BL-039 Slice B (V0065). One increment per issued face session -- this path spends TWO Uqudo
  // operations (createFaceSession plus the token mint) but counts one, because what is capped is
  // the token. Never decremented, never reset.
  private static final String INCREMENT_FACE_TOKENS_MINTED =
      "UPDATE app.profile SET face_tokens_minted = face_tokens_minted + 1"
          + " WHERE profile_id = ?::uuid";

  private static final String APPLY_LIVENESS_ATTEMPT =
      "UPDATE app.profile SET liveness_attempts = liveness_attempts + 1 WHERE profile_id = ?::uuid";

  private static final String INSERT_STATUS_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
              ?::text, ?::text, ?::text, ?::bigint)
      """;

  private static final String UPSERT_FACE_RESULT =
      """
      INSERT INTO app.face_result
        (cycle_id, uqudo_jti, face_session_id, match, match_level, threshold_applied, received_at)
      VALUES (?::uuid, ?::text, ?::text, ?::boolean, ?::smallint, ?::smallint, ?::timestamptz)
      ON CONFLICT (cycle_id) DO UPDATE SET
        uqudo_jti = EXCLUDED.uqudo_jti,
        face_session_id = EXCLUDED.face_session_id,
        match = EXCLUDED.match,
        match_level = EXCLUDED.match_level,
        threshold_applied = EXCLUDED.threshold_applied,
        received_at = EXCLUDED.received_at
      """;

  private static final String UPSERT_FACE_AUDIT_TRAIL_ARTIFACT =
      """
      INSERT INTO app.artifact_ref
        (cycle_id, kind, uqudo_image_id, uqudo_checksum, storage_key, content_type, byte_size,
         sha256, body, state, created_at)
      VALUES (?::uuid, 'face_audit_trail', ?::text, ?::text, ?::text, ?::text, ?::bigint, ?::bytea,
              ?::bytea, 'committed', ?::timestamptz)
      ON CONFLICT (cycle_id, kind) DO UPDATE SET
        uqudo_image_id = EXCLUDED.uqudo_image_id,
        uqudo_checksum = EXCLUDED.uqudo_checksum,
        storage_key = EXCLUDED.storage_key,
        content_type = EXCLUDED.content_type,
        byte_size = EXCLUDED.byte_size,
        sha256 = EXCLUDED.sha256,
        body = EXCLUDED.body,
        created_at = EXCLUDED.created_at
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcLivenessRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<LivenessState> lockAndGetLivenessState(UUID profileId) {
    List<LivenessState> rows =
        jdbcTemplate.query(
            LOCK_AND_GET_LIVENESS_STATE,
            (rs, rowNum) -> {
              // ResultSet.wasNull() reflects only the MOST RECENTLY read column, and Java
              // evaluates constructor arguments left-to-right -- so facePassed's own null-ness
              // must be captured into a local right after reading it, before any other rs.getXxx()
              // call runs, or a later column's null-ness would be reported instead.
              String status = rs.getString("status");
              boolean isTerminal = rs.getBoolean("is_terminal");
              int livenessAttempts = rs.getInt("liveness_attempts");
              java.sql.Timestamp blockedUntil = rs.getTimestamp("liveness_blocked_until");
              String pendingFaceSessionId = rs.getString("pending_face_session_id");
              String acceptedCycleId = rs.getString("accepted_cycle_id");
              // Read BEFORE face_passed on purpose: wasNull() below must reflect face_passed and
              // nothing else, so no rs.getXxx() call may come between the two.
              int faceTokensMinted = rs.getInt("face_tokens_minted");
              boolean facePassed = rs.getBoolean("face_passed");
              boolean facePassedWasNull = rs.wasNull();
              return new LivenessState(
                  status,
                  isTerminal,
                  livenessAttempts,
                  blockedUntil == null ? null : blockedUntil.toInstant(),
                  pendingFaceSessionId,
                  acceptedCycleId == null ? null : UUID.fromString(acceptedCycleId),
                  facePassedWasNull ? null : facePassed,
                  faceTokensMinted);
            },
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public Optional<ReferenceImage> currentAcceptedCycleReferenceImage(UUID profileId) {
    List<ReferenceImage> rows =
        jdbcTemplate.query(
            CURRENT_ACCEPTED_CYCLE_REFERENCE_IMAGE,
            (rs, rowNum) ->
                new ReferenceImage(UUID.fromString(rs.getString("cycle_id")), rs.getBytes("body")),
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void recordPendingFaceSession(UUID profileId, String faceSessionId) {
    jdbcTemplate.update(RECORD_PENDING_FACE_SESSION, faceSessionId, profileId.toString());
  }

  @Override
  public Optional<String> currentFaceResultJti(UUID cycleId) {
    List<String> rows =
        jdbcTemplate.query(
            CURRENT_FACE_RESULT_JTI, (rs, rowNum) -> rs.getString("uqudo_jti"), cycleId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void applyLivenessBlock(
      UUID profileId, Instant blockedUntil, Instant now, long auditEventId) {
    jdbcTemplate.update(
        APPLY_LIVENESS_BLOCK, now.toString(), blockedUntil.toString(), profileId.toString());
    insertHistory(profileId, "in_progress", "blocked_liveness", "system", auditEventId);
  }

  @Override
  public void resumeFromLivenessBlock(UUID profileId, Instant now, long auditEventId) {
    jdbcTemplate.update(RESUME_FROM_LIVENESS_BLOCK, now.toString(), profileId.toString());
    insertHistory(profileId, "blocked_liveness", "in_progress", "customer", auditEventId);
  }

  @Override
  public void applyLivenessAttempt(UUID profileId) {
    jdbcTemplate.update(APPLY_LIVENESS_ATTEMPT, profileId.toString());
  }

  @Override
  public void incrementFaceTokensMinted(UUID profileId) {
    jdbcTemplate.update(INCREMENT_FACE_TOKENS_MINTED, profileId.toString());
  }

  private void insertHistory(
      UUID profileId, String fromStatus, String toStatus, String actorKind, long auditEventId) {
    jdbcTemplate.update(
        INSERT_STATUS_HISTORY,
        new Object[] {
          profileId.toString(), profileId.toString(), fromStatus, toStatus, actorKind, auditEventId
        },
        new int[] {
          Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT
        });
  }

  @Override
  public void upsertFaceResult(
      UUID cycleId,
      String jti,
      String faceSessionId,
      boolean match,
      int matchLevel,
      int thresholdApplied,
      Instant receivedAt) {
    jdbcTemplate.update(
        UPSERT_FACE_RESULT,
        cycleId.toString(),
        jti,
        faceSessionId,
        match,
        matchLevel,
        thresholdApplied,
        receivedAt.toString());
  }

  @Override
  public void upsertFaceAuditTrailArtifact(
      UUID cycleId,
      String uqudoImageId,
      String uqudoChecksum,
      String storageKey,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] body,
      Instant now) {
    jdbcTemplate.update(
        UPSERT_FACE_AUDIT_TRAIL_ARTIFACT,
        new Object[] {
          cycleId.toString(),
          uqudoImageId,
          uqudoChecksum,
          storageKey,
          contentType,
          byteSize,
          sha256,
          body,
          now.toString()
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
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
