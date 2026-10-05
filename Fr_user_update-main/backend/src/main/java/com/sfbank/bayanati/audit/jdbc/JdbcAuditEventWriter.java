package com.sfbank.bayanati.audit.jdbc;

import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes audit events as {@code fru_app}, through the append-only path the {@code audit} schema
 * already enforces (docs/components/persistence.md).
 *
 * <p>The single place in the application that knows the shape of {@code audit.audit_event}. Keeping
 * it single matters: the insert has to pass placeholder values for five columns that {@code
 * audit.chain_append()} owns and overwrites, and that fact should exist in exactly one file rather
 * than being reproduced by every future writer.
 */
@Repository
public class JdbcAuditEventWriter implements AuditEventWriter {

  /**
   * {@code seq}, {@code occurred_at}, {@code prev_hash}, {@code content_hash} and {@code row_hash}
   * are NOT NULL with no defaults, and are computed by the {@code audit_event_chain_append} BEFORE
   * INSERT trigger (V0003/V0004), which overwrites anything supplied. The literals below exist only
   * to satisfy NOT NULL and never reach the stored row — supplying them is not the application
   * asserting a sequence number or a hash.
   *
   * <p>Every placeholder carries an explicit cast so PostgreSQL never has to infer a parameter's
   * type from the INSERT target, and so a NULL uuid is unambiguously {@code NULL::uuid}. That lets
   * all nine parameters bind as VARCHAR, including the three uuids.
   *
   * <p>The chain is resolved by {@code (chain_kind, subject_id)} in the statement itself rather
   * than by a prior SELECT, so a missing chain yields zero rows inserted, which {@link #append}
   * turns into a failure. {@code fru_app} cannot create a chain (it holds SELECT only on {@code
   * audit_chain}, V0004); the {@code system}/{@code account_check} chain is seeded by V0034.
   */
  private static final String INSERT_EVENT =
      """
      INSERT INTO audit.audit_event
        (chain_id, seq, occurred_at, event_type, actor_kind, actor_id,
         profile_id, session_id, request_id, payload_json,
         prev_hash, content_hash, row_hash)
      SELECT c.chain_id, 0, clock_timestamp(), ?::text, ?::text, ?::text,
             ?::uuid, ?::uuid, ?::uuid, ?::text,
             ''::bytea, ''::bytea, ''::bytea
        FROM audit.audit_chain c
       WHERE c.chain_kind = ?::text AND c.subject_id = ?::text
      RETURNING audit_event_id
      """;

  private static final int[] ALL_VARCHAR = {
    Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
    Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
    Types.VARCHAR, Types.VARCHAR, Types.VARCHAR
  };

  /**
   * Resolves the chain the same way {@link #INSERT_EVENT} does, so a missing chain fails the same
   * way.
   */
  private static final String INSERT_ARTIFACT =
      """
      INSERT INTO audit.audit_artifact (chain_id, kind, media_type, body, byte_size, sha256)
      SELECT c.chain_id, ?::text, ?::text, ?::bytea, ?::bigint, ?::bytea
        FROM audit.audit_chain c
       WHERE c.chain_kind = ?::text AND c.subject_id = ?::text
      RETURNING artifact_id
      """;

  /** Identical to {@link #INSERT_EVENT} except it also sets {@code artifact_id}. */
  private static final String INSERT_EVENT_WITH_ARTIFACT =
      """
      INSERT INTO audit.audit_event
        (chain_id, seq, occurred_at, event_type, actor_kind, actor_id,
         profile_id, session_id, request_id, payload_json, artifact_id,
         prev_hash, content_hash, row_hash)
      SELECT c.chain_id, 0, clock_timestamp(), ?::text, ?::text, ?::text,
             ?::uuid, ?::uuid, ?::uuid, ?::text, ?::bigint,
             ''::bytea, ''::bytea, ''::bytea
        FROM audit.audit_chain c
       WHERE c.chain_kind = ?::text AND c.subject_id = ?::text
      RETURNING audit_event_id
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcAuditEventWriter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public long append(AuditEvent event) {
    List<Long> inserted =
        jdbcTemplate.query(
            INSERT_EVENT,
            new Object[] {
              event.eventType(),
              event.actorKind(),
              event.actorId(),
              text(event.profileId()),
              text(event.sessionId()),
              text(event.requestId()),
              event.payloadJson(),
              event.chainKind(),
              event.chainSubject()
            },
            ALL_VARCHAR,
            (rs, rowNum) -> rs.getLong("audit_event_id"));

    if (inserted.size() != 1) {
      // Fail closed. An action that should have been audited and was not is worse than one that
      // failed: throwing here lets a transactional caller roll back, and a non-transactional one
      // (see AccountCheckService, which deliberately holds no transaction) propagate the failure
      // instead of returning an unaudited answer.
      throw new IllegalStateException(
          "audit event '"
              + event.eventType()
              + "' was not written: no audit chain ("
              + event.chainKind()
              + ", "
              + event.chainSubject()
              + ") — check that the chain has been created (migration-seeded, or via"
              + " audit.ensure_profile_chain for a profile chain)");
    }
    return inserted.get(0);
  }

  @Override
  public long appendWithArtifact(AuditEvent event, AuditArtifact artifact) {
    byte[] sha256 = sha256(artifact.body());

    List<Long> artifactIds =
        jdbcTemplate.query(
            INSERT_ARTIFACT,
            new Object[] {
              artifact.kind(),
              artifact.mediaType(),
              artifact.body(),
              (long) artifact.body().length,
              sha256,
              event.chainKind(),
              event.chainSubject()
            },
            new int[] {
              Types.VARCHAR,
              Types.VARCHAR,
              Types.VARBINARY,
              Types.BIGINT,
              Types.VARBINARY,
              Types.VARCHAR,
              Types.VARCHAR
            },
            (rs, rowNum) -> rs.getLong("artifact_id"));
    if (artifactIds.size() != 1) {
      throw new IllegalStateException(
          "audit artifact '"
              + artifact.kind()
              + "' was not written: no audit chain ("
              + event.chainKind()
              + ", "
              + event.chainSubject()
              + ")");
    }

    List<Long> inserted =
        jdbcTemplate.query(
            INSERT_EVENT_WITH_ARTIFACT,
            new Object[] {
              event.eventType(),
              event.actorKind(),
              event.actorId(),
              text(event.profileId()),
              text(event.sessionId()),
              text(event.requestId()),
              event.payloadJson(),
              artifactIds.get(0),
              event.chainKind(),
              event.chainSubject()
            },
            new int[] {
              Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
              Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR
            },
            (rs, rowNum) -> rs.getLong("audit_event_id"));
    if (inserted.size() != 1) {
      throw new IllegalStateException(
          "audit event '"
              + event.eventType()
              + "' was not written: no audit chain ("
              + event.chainKind()
              + ", "
              + event.chainSubject()
              + ")");
    }
    return inserted.get(0);
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String text(UUID value) {
    return value == null ? null : value.toString();
  }
}
