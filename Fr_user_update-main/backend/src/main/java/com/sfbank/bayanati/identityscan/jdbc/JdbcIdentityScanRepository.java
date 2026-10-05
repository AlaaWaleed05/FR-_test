package com.sfbank.bayanati.identityscan.jdbc;

import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.identityscan.domain.AcceptedScanSnapshot;
import com.sfbank.bayanati.identityscan.domain.ActiveRegistryContext;
import com.sfbank.bayanati.identityscan.domain.DocumentTypes;
import com.sfbank.bayanati.identityscan.domain.IdentityScanRepository;
import com.sfbank.bayanati.identityscan.domain.ScanArtifact;
import com.sfbank.bayanati.identityscan.domain.ScanState;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code app.identity_cycle}, {@code app.scan_result}, {@code app.registry_result}, {@code
 * app.artifact_ref} and the stage-8 retry-budget columns on {@code app.profile} as {@code fru_app}.
 * The single place in the application that knows these tables' shapes.
 */
@Repository
public class JdbcIdentityScanRepository implements IdentityScanRepository {

  private static final String LOCK_AND_GET_SCAN_STATE =
      """
      SELECT p.status, sc.is_terminal, p.scan_attempts_national_id, p.scan_attempts_passport,
             p.scan_attempts_total, p.scan_blocked_until,
             p.pending_scan_session_id, p.pending_scan_nonce, p.scan_tokens_minted
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
       WHERE p.profile_id = ?::uuid
         FOR UPDATE OF p
      """;

  // LOCK_AND_GET_SCAN_STATE without the row lock -- S5-11's read-only Stage 9 resume endpoint
  // needs the profile's existence and terminality, and a read path must not take FOR UPDATE on
  // app.profile: that would block every concurrent writer of the same row for the duration of a
  // request whose whole contract is that it changes nothing. Same columns, same mapper, so the
  // two cannot drift apart.
  private static final String READ_SCAN_STATE =
      """
      SELECT p.status, sc.is_terminal, p.scan_attempts_national_id, p.scan_attempts_passport,
             p.scan_attempts_total, p.scan_blocked_until,
             p.pending_scan_session_id, p.pending_scan_nonce, p.scan_tokens_minted
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
       WHERE p.profile_id = ?::uuid
      """;

  private static final String RECORD_PENDING_SESSION =
      """
      UPDATE app.profile SET pending_scan_session_id = ?::text, pending_scan_nonce = ?::text
       WHERE profile_id = ?::uuid
      """;

  // BL-039. The pending session is single-use: spent with the attempt, not left standing. V0064
  // supersedes V0040's "deliberately never cleared after use" comment, which documented the
  // opposite rule.
  private static final String CONSUME_PENDING_SESSION =
      """
      UPDATE app.profile SET pending_scan_session_id = NULL, pending_scan_nonce = NULL
       WHERE profile_id = ?::uuid
      """;

  // BL-039 Slice B (V0065). Incremented inside the issuance transaction, so a request refused
  // before the mint counts nothing. Never decremented and never reset -- the whole point of the
  // column.
  private static final String INCREMENT_SCAN_TOKENS_MINTED =
      """
      UPDATE app.profile SET scan_tokens_minted = scan_tokens_minted + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String APPLY_SCAN_ATTEMPT_NATIONAL_ID =
      """
      UPDATE app.profile
         SET scan_attempts_national_id = scan_attempts_national_id + 1,
             scan_attempts_total = scan_attempts_total + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String APPLY_SCAN_ATTEMPT_PASSPORT =
      """
      UPDATE app.profile
         SET scan_attempts_passport = scan_attempts_passport + 1,
             scan_attempts_total = scan_attempts_total + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String APPLY_SCAN_BLOCK =
      """
      UPDATE app.profile
         SET status = 'blocked_scan', status_changed_at = ?::timestamptz,
             scan_blocked_until = ?::timestamptz, row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String RESUME_FROM_SCAN_BLOCK =
      """
      UPDATE app.profile
         SET status = 'in_progress', status_changed_at = ?::timestamptz,
             scan_attempts_national_id = 0, scan_attempts_passport = 0, scan_attempts_total = 0,
             scan_blocked_until = NULL, row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String TRANSITION_TO_AWAITING_REGISTRY =
      """
      UPDATE app.profile
         SET status = 'awaiting_registry', status_changed_at = ?::timestamptz,
             row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String TRANSITION_BACK_TO_IN_PROGRESS_FROM_REGISTRY =
      """
      UPDATE app.profile
         SET status = 'in_progress', status_changed_at = ?::timestamptz,
             row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String TRANSITION_TO_TERMINATED_MISMATCH =
      """
      UPDATE app.profile
         SET status = 'terminated_registry_mismatch', status_changed_at = ?::timestamptz,
             row_version = row_version + 1
       WHERE profile_id = ?::uuid
      """;

  private static final String INSERT_STATUS_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
              ?::text, ?::text, ?::text, ?::bigint)
      """;

  private static final String SUPERSEDE_ACTIVE_CYCLE =
      """
      UPDATE app.identity_cycle SET state = 'superseded', superseded_at = ?::timestamptz
       WHERE profile_id = ?::uuid AND state = 'active'
      """;

  private static final String INSERT_ABANDONED_CYCLE =
      """
      INSERT INTO app.identity_cycle (profile_id, seq, state, opened_at)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.identity_cycle WHERE profile_id = ?::uuid),
              'abandoned', ?::timestamptz)
      RETURNING cycle_id::text
      """;

  private static final String INSERT_ACTIVE_CYCLE =
      """
      INSERT INTO app.identity_cycle (profile_id, seq, state, opened_at)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.identity_cycle WHERE profile_id = ?::uuid),
              'active', ?::timestamptz)
      RETURNING cycle_id::text
      """;

  private static final String INSERT_SCAN_RESULT =
      """
      INSERT INTO app.scan_result
        (cycle_id, uqudo_jti, document_type, card_variant, identity_number, document_number,
         mrz_verified, nationality, sex_on_document, date_of_birth, date_of_issue, date_of_expiry,
         place_of_issue, issuing_country, name_ar_on_document, name_en_on_document, blood_type,
         birth_city, id_print_score, id_screen_score, id_photo_tampering_score, received_at)
      VALUES (?::uuid, ?::text, ?::text, ?::text, ?::text, ?::text, ?::boolean, ?::text, ?::text,
              ?::date, ?::date, ?::date, ?::text, ?::text, ?::text, ?::text, ?::text, ?::text,
              ?::smallint, ?::smallint, ?::smallint, ?::timestamptz)
      """;

  private static final String INSERT_ARTIFACT_REF =
      """
      INSERT INTO app.artifact_ref
        (cycle_id, kind, uqudo_image_id, uqudo_checksum, storage_key, content_type, byte_size,
         sha256, body, state, created_at)
      VALUES (?::uuid, ?::text, ?::text, ?::text, ?::text, ?::text, ?::bigint, ?::bytea, ?::bytea,
              'committed', ?::timestamptz)
      """;

  private static final String INSERT_REGISTRY_RESULT =
      """
      INSERT INTO app.registry_result
        (cycle_id, state, queried_at, attempts, name_ar_given, name_ar_father, name_ar_grandfather,
         name_ar_great_grandfather, name_ar_mother, name_ar_mother_father,
         name_ar_mother_grandfather, name_ar_mother_great_grandfather, first_names_en,
         last_name_en, sex_registry, date_of_birth, raw_address_ar, identity_number_returned)
      VALUES (?::uuid, ?::text, ?::timestamptz, ?::smallint, ?::text, ?::text, ?::text, ?::text,
              ?::text, ?::text, ?::text, ?::text, ?::text, ?::text, ?::text, ?::date, ?::text,
              ?::text)
      """;

  private static final String UPDATE_REGISTRY_RESULT_ON_RETRY =
      """
      UPDATE app.registry_result
         SET state = ?::text, queried_at = ?::timestamptz, attempts = ?::smallint,
             name_ar_given = ?::text, name_ar_father = ?::text, name_ar_grandfather = ?::text,
             name_ar_great_grandfather = ?::text, name_ar_mother = ?::text,
             name_ar_mother_father = ?::text, name_ar_mother_grandfather = ?::text,
             name_ar_mother_great_grandfather = ?::text, first_names_en = ?::text,
             last_name_en = ?::text, sex_registry = ?::text, date_of_birth = ?::date,
             raw_address_ar = ?::text, identity_number_returned = ?::text
       WHERE cycle_id = ?::uuid
      """;

  private static final String CURRENT_ACTIVE_REGISTRY_CONTEXT =
      """
      SELECT ic.cycle_id, sr.identity_number, sr.document_type, rr.state, rr.attempts
        FROM app.identity_cycle ic
        JOIN app.scan_result sr ON sr.cycle_id = ic.cycle_id
        JOIN app.registry_result rr ON rr.cycle_id = ic.cycle_id
       WHERE ic.profile_id = ?::uuid AND ic.state = 'active'
      """;

  // BL-034's dropped-acknowledgement read: the active cycle's stored jti alongside the whole
  // Stage 9 display payload, so a customer's own re-upload is answered from storage rather than by
  // repeating the Uqudo image download and the Civil Registry lookup. Scoped by profile_id and
  // state='active' -- NEVER keyed on the jti, which would match another profile's row and turn
  // scan_result.uqudo_jti's global replay guard into a success path. The inner joins are
  // deliberate: a cycle with no scan_result yet is the ordinary first submission and must return
  // empty so the caller falls through to the unchanged insert path.
  private static final String CURRENT_ACCEPTED_SCAN_SNAPSHOT =
      """
      SELECT ic.cycle_id, sr.uqudo_jti, sr.identity_number, sr.document_type,
             rr.state, rr.name_ar_given, rr.name_ar_father, rr.name_ar_grandfather,
             rr.name_ar_great_grandfather, rr.name_ar_mother, rr.name_ar_mother_father,
             rr.name_ar_mother_grandfather, rr.name_ar_mother_great_grandfather,
             rr.first_names_en, rr.last_name_en, rr.sex_registry, rr.date_of_birth,
             rr.raw_address_ar, rr.identity_number_returned
        FROM app.identity_cycle ic
        JOIN app.scan_result sr ON sr.cycle_id = ic.cycle_id
        JOIN app.registry_result rr ON rr.cycle_id = ic.cycle_id
       WHERE ic.profile_id = ?::uuid AND ic.state = 'active'
      """;

  private static final String MARK_CYCLE_ACCEPTED =
      "UPDATE app.identity_cycle SET accepted_at = ?::timestamptz WHERE cycle_id = ?::uuid";

  // Stage 9's review images. app.artifact_read() is the only sanctioned way to read bytes back
  // (V0054): it verifies them against the stored sha256 and raises on a mismatch rather than
  // handing back something that has changed underneath. Keyed on state='active' -- NOT on
  // accepted_at -- because Stage 9 is the screen where acceptance is still being decided.
  private static final String ACTIVE_CYCLE_ARTIFACT =
      """
      SELECT ar.kind, ar.content_type, app.artifact_read(ar.artifact_ref_id) AS body
        FROM app.identity_cycle ic
        JOIN app.artifact_ref ar ON ar.cycle_id = ic.cycle_id AND ar.kind = ?::text
       WHERE ic.profile_id = ?::uuid AND ic.state = 'active'
      """;

  // Metadata only -- deliberately does NOT call app.artifact_read(), so listing what exists never
  // pulls every image body out of TOAST just to answer "which ones are there".
  private static final String ACTIVE_CYCLE_ARTIFACT_KINDS =
      """
      SELECT ar.kind
        FROM app.identity_cycle ic
        JOIN app.artifact_ref ar ON ar.cycle_id = ic.cycle_id
       WHERE ic.profile_id = ?::uuid AND ic.state = 'active' AND ar.body IS NOT NULL
       ORDER BY ar.kind
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcIdentityScanRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ScanState> lockAndGetScanState(UUID profileId) {
    return scanState(LOCK_AND_GET_SCAN_STATE, profileId);
  }

  @Override
  public Optional<ScanState> readScanState(UUID profileId) {
    return scanState(READ_SCAN_STATE, profileId);
  }

  /** Shared by the locking and non-locking reads so the two can never map different columns. */
  private Optional<ScanState> scanState(String sql, UUID profileId) {
    List<ScanState> rows =
        jdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
              java.sql.Timestamp blockedUntil = rs.getTimestamp("scan_blocked_until");
              return new ScanState(
                  rs.getString("status"),
                  rs.getBoolean("is_terminal"),
                  rs.getInt("scan_attempts_national_id"),
                  rs.getInt("scan_attempts_passport"),
                  rs.getInt("scan_attempts_total"),
                  blockedUntil == null ? null : blockedUntil.toInstant(),
                  rs.getString("pending_scan_session_id"),
                  rs.getString("pending_scan_nonce"),
                  rs.getInt("scan_tokens_minted"));
            },
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public void recordPendingSession(UUID profileId, String sessionId, String nonce) {
    jdbcTemplate.update(RECORD_PENDING_SESSION, sessionId, nonce, profileId.toString());
  }

  @Override
  public void incrementScanTokensMinted(UUID profileId) {
    jdbcTemplate.update(INCREMENT_SCAN_TOKENS_MINTED, profileId.toString());
  }

  @Override
  public void consumePendingScanSession(UUID profileId) {
    jdbcTemplate.update(CONSUME_PENDING_SESSION, profileId.toString());
  }

  @Override
  public void applyScanAttempt(UUID profileId, String appDocumentType) {
    String sql =
        DocumentTypes.NATIONAL_ID.equals(appDocumentType)
            ? APPLY_SCAN_ATTEMPT_NATIONAL_ID
            : APPLY_SCAN_ATTEMPT_PASSPORT;
    jdbcTemplate.update(sql, profileId.toString());
  }

  @Override
  public void applyScanBlock(UUID profileId, Instant blockedUntil, Instant now, long auditEventId) {
    jdbcTemplate.update(
        APPLY_SCAN_BLOCK, now.toString(), blockedUntil.toString(), profileId.toString());
    insertHistory(profileId, "in_progress", "blocked_scan", "system", auditEventId);
  }

  @Override
  public void resumeFromScanBlock(UUID profileId, Instant now, long auditEventId) {
    jdbcTemplate.update(RESUME_FROM_SCAN_BLOCK, now.toString(), profileId.toString());
    insertHistory(profileId, "blocked_scan", "in_progress", "customer", auditEventId);
  }

  @Override
  public void transitionToAwaitingRegistry(UUID profileId, Instant now, long auditEventId) {
    jdbcTemplate.update(TRANSITION_TO_AWAITING_REGISTRY, now.toString(), profileId.toString());
    insertHistory(profileId, "in_progress", "awaiting_registry", "system", auditEventId);
  }

  @Override
  public void transitionBackToInProgressFromRegistry(
      UUID profileId, Instant now, long auditEventId) {
    jdbcTemplate.update(
        TRANSITION_BACK_TO_IN_PROGRESS_FROM_REGISTRY, now.toString(), profileId.toString());
    insertHistory(profileId, "awaiting_registry", "in_progress", "system", auditEventId);
  }

  @Override
  public void transitionToTerminatedMismatch(UUID profileId, Instant now, long auditEventId) {
    jdbcTemplate.update(TRANSITION_TO_TERMINATED_MISMATCH, now.toString(), profileId.toString());
    insertHistory(
        profileId, "in_progress", "terminated_registry_mismatch", "customer", auditEventId);
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
  public void supersedeActiveCycleIfAny(UUID profileId, Instant now) {
    jdbcTemplate.update(SUPERSEDE_ACTIVE_CYCLE, now.toString(), profileId.toString());
  }

  @Override
  public UUID insertAbandonedCycle(UUID profileId, Instant now) {
    String cycleId =
        jdbcTemplate.queryForObject(
            INSERT_ABANDONED_CYCLE,
            String.class,
            profileId.toString(),
            profileId.toString(),
            now.toString());
    return UUID.fromString(cycleId);
  }

  @Override
  public UUID insertAcceptedCycle(UUID profileId, Instant now) {
    supersedeActiveCycleIfAny(profileId, now);
    String cycleId =
        jdbcTemplate.queryForObject(
            INSERT_ACTIVE_CYCLE,
            String.class,
            profileId.toString(),
            profileId.toString(),
            now.toString());
    return UUID.fromString(cycleId);
  }

  @Override
  public void insertScanResult(UUID cycleId, ParsedEnrolmentResult parsed, Instant receivedAt) {
    jdbcTemplate.update(
        INSERT_SCAN_RESULT,
        cycleId.toString(),
        parsed.jti(),
        parsed.documentType(),
        parsed.cardVariant(),
        parsed.identityNumber(),
        parsed.documentNumber(),
        parsed.mrzVerified(),
        parsed.nationality(),
        parsed.sexOnDocument(),
        parsed.dateOfBirth() == null ? null : parsed.dateOfBirth().toString(),
        parsed.dateOfIssue() == null ? null : parsed.dateOfIssue().toString(),
        parsed.dateOfExpiry() == null ? null : parsed.dateOfExpiry().toString(),
        parsed.placeOfIssue(),
        parsed.issuingCountry(),
        parsed.nameArOnDocument(),
        parsed.nameEnOnDocument(),
        parsed.bloodType(),
        parsed.placeOfBirthCity(),
        parsed.idPrintScore(),
        parsed.idScreenScore(),
        parsed.idPhotoTamperingScore(),
        receivedAt.toString());
  }

  @Override
  public void insertArtifactRef(
      UUID cycleId,
      String kind,
      String uqudoImageId,
      String uqudoChecksum,
      String storageKey,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] body,
      Instant now) {
    jdbcTemplate.update(
        INSERT_ARTIFACT_REF,
        new Object[] {
          cycleId.toString(),
          kind,
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
          Types.VARCHAR,
          Types.BIGINT,
          Types.VARBINARY,
          Types.VARBINARY,
          Types.VARCHAR
        });
  }

  @Override
  public void insertRegistryResult(
      UUID cycleId,
      String state,
      Instant queriedAt,
      int attempts,
      RegistryLookupResult fields,
      String identityNumberReturned) {
    jdbcTemplate.update(
        INSERT_REGISTRY_RESULT,
        cycleId.toString(),
        state,
        queriedAt.toString(),
        attempts,
        fields == null ? null : fields.nameArGiven(),
        fields == null ? null : fields.nameArFather(),
        fields == null ? null : fields.nameArGrandfather(),
        fields == null ? null : fields.nameArGreatGrandfather(),
        fields == null ? null : fields.nameArMother(),
        fields == null ? null : fields.nameArMotherFather(),
        fields == null ? null : fields.nameArMotherGrandfather(),
        fields == null ? null : fields.nameArMotherGreatGrandfather(),
        fields == null ? null : fields.firstNamesEn(),
        fields == null ? null : fields.lastNameEn(),
        fields == null ? null : fields.sexRegistry(),
        fields == null || fields.dateOfBirth() == null ? null : fields.dateOfBirth().toString(),
        fields == null ? null : fields.rawAddressAr(),
        identityNumberReturned);
  }

  @Override
  public void updateRegistryResultOnRetry(
      UUID cycleId,
      String state,
      Instant queriedAt,
      int attempts,
      RegistryLookupResult fields,
      String identityNumberReturned) {
    jdbcTemplate.update(
        UPDATE_REGISTRY_RESULT_ON_RETRY,
        state,
        queriedAt.toString(),
        attempts,
        fields == null ? null : fields.nameArGiven(),
        fields == null ? null : fields.nameArFather(),
        fields == null ? null : fields.nameArGrandfather(),
        fields == null ? null : fields.nameArGreatGrandfather(),
        fields == null ? null : fields.nameArMother(),
        fields == null ? null : fields.nameArMotherFather(),
        fields == null ? null : fields.nameArMotherGrandfather(),
        fields == null ? null : fields.nameArMotherGreatGrandfather(),
        fields == null ? null : fields.firstNamesEn(),
        fields == null ? null : fields.lastNameEn(),
        fields == null ? null : fields.sexRegistry(),
        fields == null || fields.dateOfBirth() == null ? null : fields.dateOfBirth().toString(),
        fields == null ? null : fields.rawAddressAr(),
        identityNumberReturned,
        cycleId.toString());
  }

  @Override
  public Optional<ActiveRegistryContext> currentActiveRegistryContext(UUID profileId) {
    List<ActiveRegistryContext> rows =
        jdbcTemplate.query(
            CURRENT_ACTIVE_REGISTRY_CONTEXT,
            (rs, rowNum) ->
                new ActiveRegistryContext(
                    UUID.fromString(rs.getString("cycle_id")),
                    rs.getString("identity_number"),
                    rs.getString("document_type"),
                    rs.getString("state"),
                    rs.getInt("attempts")),
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public Optional<AcceptedScanSnapshot> currentAcceptedScanSnapshot(UUID profileId) {
    List<AcceptedScanSnapshot> rows =
        jdbcTemplate.query(
            CURRENT_ACCEPTED_SCAN_SNAPSHOT,
            (rs, rowNum) ->
                new AcceptedScanSnapshot(
                    UUID.fromString(rs.getString("cycle_id")),
                    rs.getString("uqudo_jti"),
                    rs.getString("identity_number"),
                    rs.getString("document_type"),
                    rs.getString("state"),
                    registryFields(rs)),
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Rebuilds the stored Civil Registry values from {@code app.registry_result} (V0023, V0062). Null
   * unless the lookup actually succeeded — the columns are all nullable and carry nothing
   * meaningful for a {@code pending}/{@code not_found}/{@code unreachable} row. The photograph is
   * deliberately not read: its bytes live in {@code app.artifact_ref} and Stage 9 fetches them
   * through the image endpoint.
   */
  private static RegistryLookupResult registryFields(ResultSet rs) throws SQLException {
    if (!"ok".equals(rs.getString("state"))) {
      return null;
    }
    return new RegistryLookupResult(
        rs.getString("identity_number_returned"),
        rs.getString("name_ar_given"),
        rs.getString("name_ar_father"),
        rs.getString("name_ar_grandfather"),
        rs.getString("name_ar_great_grandfather"),
        rs.getString("name_ar_mother"),
        rs.getString("name_ar_mother_father"),
        rs.getString("name_ar_mother_grandfather"),
        rs.getString("name_ar_mother_great_grandfather"),
        rs.getString("first_names_en"),
        rs.getString("last_name_en"),
        rs.getString("sex_registry"),
        toLocalDate(rs, "date_of_birth"),
        rs.getString("raw_address_ar"),
        null);
  }

  private static java.time.LocalDate toLocalDate(ResultSet rs, String column) throws SQLException {
    java.sql.Date date = rs.getDate(column);
    return date == null ? null : date.toLocalDate();
  }

  @Override
  public void markCycleAccepted(UUID cycleId, Instant acceptedAt) {
    jdbcTemplate.update(MARK_CYCLE_ACCEPTED, acceptedAt.toString(), cycleId.toString());
  }

  @Override
  public Optional<ScanArtifact> activeCycleArtifact(UUID profileId, String kind) {
    List<ScanArtifact> rows =
        jdbcTemplate.query(
            ACTIVE_CYCLE_ARTIFACT,
            (rs, rowNum) ->
                new ScanArtifact(
                    rs.getString("kind"), rs.getString("content_type"), rs.getBytes("body")),
            // The kind binds first: it sits in the JOIN clause, ahead of the WHERE.
            kind,
            profileId.toString());
    // A row with a NULL body is a purged artifact (app.artifact_read returns NULL for one), which
    // is an absence, not an error -- the same outcome as no row at all.
    return rows.isEmpty() || rows.get(0).bytes() == null
        ? Optional.empty()
        : Optional.of(rows.get(0));
  }

  @Override
  public List<String> activeCycleArtifactKinds(UUID profileId) {
    return jdbcTemplate.queryForList(
        ACTIVE_CYCLE_ARTIFACT_KINDS, String.class, profileId.toString());
  }
}
