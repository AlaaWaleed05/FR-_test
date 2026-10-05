package com.sfbank.bayanati.submission.jdbc;

import com.sfbank.bayanati.submission.domain.ListVersionUsed;
import com.sfbank.bayanati.submission.domain.SubmissionRepository;
import com.sfbank.bayanati.submission.domain.SubmissionState;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes the customer's own digital submission (Stage 12) as {@code fru_app}. */
@Repository
public class JdbcSubmissionRepository implements SubmissionRepository {

  // has_signature's `ar.state = 'committed'` is load-bearing for AD-008/BL-041, not tidiness.
  // The signature is profile-keyed (V0026: cycle_id NULL), so superseding a device-less
  // re-entry's inherited identity cycle cannot reach it. Without this filter, an impostor who
  // re-entered another customer's account, rescanned and passed liveness -- building a fresh
  // active cycle and face_result, which is exactly what AD-008 forces them to do -- would still
  // read has_signature = true from the VICTIM's row: currentJourneyPointer would skip SIGNATURE,
  // answer SUBMIT, and submit() would pass its SignatureMissingException check, producing a
  // submission carrying a signature drawn by someone else. V0063 added the 'superseded' state
  // that JdbcDeviceLessReentrySuperseder sets; 'committed' is used rather than <> 'superseded'
  // because it also excludes 'staged' and 'purged', matching JdbcProfileViewRepository's filter.
  private static final String STATE_QUERY_BASE =
      """
      SELECT p.status, sc.is_terminal, p.reference_number, fr.passed AS face_passed,
             p.liveness_blocked_until,
             (ic.cycle_id IS NOT NULL) AS has_accepted_cycle,
             EXISTS (SELECT 1 FROM app.artifact_ref ar
                      WHERE ar.profile_id = p.profile_id AND ar.kind = 'signature'
                        AND ar.state = 'committed') AS has_signature
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
        LEFT JOIN app.identity_cycle ic
          ON ic.profile_id = p.profile_id AND ic.state = 'active' AND ic.accepted_at IS NOT NULL
        LEFT JOIN app.face_result fr ON fr.cycle_id = ic.cycle_id
       WHERE p.profile_id = ?::uuid
      """;

  private static final String CHECK_STATE = STATE_QUERY_BASE;

  private static final String LOCK_AND_CHECK_STATE = STATE_QUERY_BASE + " FOR UPDATE OF p";

  private static final String NEXT_REFERENCE_NUMBER =
      "SELECT 'SFB-' || lpad(nextval('app.reference_number_seq')::text, 9, '0')";

  private static final String SUBMIT =
      """
      UPDATE app.profile
         SET status = 'submitted', status_changed_at = ?::timestamptz, submitted_at = ?::timestamptz,
             reference_number = ?::text, row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String INSERT_STATUS_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
              'in_progress', 'submitted', 'customer', ?::bigint)
      """;

  private static final String USED_REFERENCE_LIST_VERSIONS =
      """
      SELECT occupation_version, admin_div_version, income_source_version,
             coalesce(country_of_residence_version, birth_country_version,
                      home_country_version, work_country_version) AS country_version
        FROM app.profile_customer_data
       WHERE profile_id = ?::uuid
      """;

  private static final String INSERT_REFERENCE_VERSION =
      """
      INSERT INTO ref.profile_reference_version (profile_id, list_code, version)
      VALUES (?::uuid, ?::text, ?::int)
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcSubmissionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<SubmissionState> checkState(UUID profileId) {
    return queryState(CHECK_STATE, profileId);
  }

  @Override
  public Optional<SubmissionState> lockAndCheckState(UUID profileId) {
    return queryState(LOCK_AND_CHECK_STATE, profileId);
  }

  private Optional<SubmissionState> queryState(String sql, UUID profileId) {
    List<SubmissionState> rows =
        jdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
              // ResultSet.wasNull() reflects only the most recently read column -- capture
              // face_passed's null-ness immediately, before any other column is read.
              boolean facePassed = rs.getBoolean("face_passed");
              boolean facePassedWasNull = rs.wasNull();
              String status = rs.getString("status");
              boolean isTerminal = rs.getBoolean("is_terminal");
              String referenceNumber = rs.getString("reference_number");
              boolean hasSignature = rs.getBoolean("has_signature");
              boolean hasAcceptedCycle = rs.getBoolean("has_accepted_cycle");
              Timestamp livenessBlockedUntil = rs.getTimestamp("liveness_blocked_until");
              return new SubmissionState(
                  status,
                  isTerminal,
                  referenceNumber,
                  !facePassedWasNull && facePassed,
                  hasSignature,
                  livenessBlockedUntil == null ? null : livenessBlockedUntil.toInstant(),
                  hasAcceptedCycle);
            },
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public String nextReferenceNumber() {
    return jdbcTemplate.queryForObject(NEXT_REFERENCE_NUMBER, String.class);
  }

  @Override
  public void submit(UUID profileId, String referenceNumber, Instant now, long auditEventId) {
    jdbcTemplate.update(
        SUBMIT, now.toString(), now.toString(), referenceNumber, profileId.toString());
    jdbcTemplate.update(
        INSERT_STATUS_HISTORY,
        new Object[] {profileId.toString(), profileId.toString(), auditEventId},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.BIGINT});
  }

  @Override
  public List<ListVersionUsed> usedReferenceListVersions(UUID profileId) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(USED_REFERENCE_LIST_VERSIONS, profileId.toString());
    List<ListVersionUsed> versions = new ArrayList<>();
    addIfPresent(versions, "occupation", (Number) row.get("occupation_version"));
    addIfPresent(versions, "admin_division", (Number) row.get("admin_div_version"));
    addIfPresent(versions, "income_source", (Number) row.get("income_source_version"));
    addIfPresent(versions, "country", (Number) row.get("country_version"));
    return versions;
  }

  private static void addIfPresent(List<ListVersionUsed> versions, String listCode, Number value) {
    if (value != null) {
      versions.add(new ListVersionUsed(listCode, value.intValue()));
    }
  }

  @Override
  public void recordReferenceVersions(UUID profileId, List<ListVersionUsed> versions) {
    for (ListVersionUsed version : versions) {
      jdbcTemplate.update(
          INSERT_REFERENCE_VERSION, profileId.toString(), version.listCode(), version.version());
    }
  }
}
