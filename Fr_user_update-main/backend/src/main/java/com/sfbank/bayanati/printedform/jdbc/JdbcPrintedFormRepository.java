package com.sfbank.bayanati.printedform.jdbc;

import static java.util.stream.Collectors.joining;

import com.sfbank.bayanati.printedform.domain.PrintedFormImageSlot;
import com.sfbank.bayanati.printedform.domain.PrintedFormRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes as {@code fru_app}, which holds exactly the grants this needs and no DDL. */
@Repository
public class JdbcPrintedFormRepository implements PrintedFormRepository {

  /**
   * The newest committed row of each printed-form image kind.
   *
   * <p>{@code DISTINCT ON … ORDER BY kind, created_at DESC} rather than a plain filter: {@code
   * app.artifact_ref} is {@code UNIQUE (cycle_id, kind)}, so a profile with several identity cycles
   * holds several committed rows of the same kind and the oldest is the superseded one. The back
   * office's own contact sheet reaches the same answer client-side by taking the LAST match of an
   * ascending listing; this does it in the query, where a later reader cannot lose it.
   *
   * <p>Ownership is the same two-way test every other artifact read uses: profile-keyed rows
   * (signature) or rows on one of this profile's cycles (the four identity images).
   */
  private static final String IMAGES =
      """
      SELECT DISTINCT ON (ar.kind) ar.kind, app.artifact_read(ar.artifact_ref_id) AS body
        FROM app.artifact_ref ar
       WHERE (ar.profile_id = ?::uuid
              OR ar.cycle_id IN (SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ?::uuid))
         AND ar.state = 'committed'
         AND ar.kind = ANY (?::text[])
       ORDER BY ar.kind, ar.created_at DESC
      """;

  private static final String SALARY_CERTIFICATE =
      """
      SELECT ar.artifact_ref_id, ar.content_type, app.artifact_read(ar.artifact_ref_id) AS body
        FROM app.artifact_ref ar
       WHERE ar.profile_id = ?::uuid
         AND ar.kind = 'salary_certificate'
         AND ar.state = 'committed'
      """;

  private static final String PINNED_REFERENCE_VERSIONS =
      "SELECT list_code, version FROM ref.profile_reference_version WHERE profile_id = ?::uuid";

  /**
   * Served by the leading column of {@code app.profile_field_edit}'s primary key, which is why
   * V0073 declares no second index.
   */
  private static final String EDITED_FIELD_NUMBERS =
      "SELECT field_number FROM app.profile_field_edit WHERE profile_id = ?::uuid";

  /**
   * The highest seeded matrix version. {@code app.provenance_matrix_version} is a seed table with
   * one row per edition of {@code field-provenance.md} and no notion of "current" beyond being the
   * latest — unlike {@code ref.reference_list_version}, which carries an explicit {@code
   * is_current} flag.
   */
  private static final String CURRENT_PROVENANCE_MATRIX_VERSION =
      "SELECT max(version) FROM app.provenance_matrix_version";

  /**
   * {@code ON CONFLICT DO NOTHING}, not {@code DO UPDATE}: the row is written once and never
   * revised, which is both what V0027 designed and what the grant permits — {@code fru_app} holds
   * SELECT and INSERT on this table and no UPDATE at all. A second print therefore leaves the first
   * print's pin in place, and the RETURNING clause is empty on that path, which is why the
   * read-back below is unconditional rather than reading the insert's own result.
   */
  private static final String RECORD_PROVENANCE_MATRIX_VERSION =
      """
      INSERT INTO app.profile_provenance_matrix_version (profile_id, version)
      VALUES (?::uuid, ?::int)
      ON CONFLICT (profile_id) DO NOTHING
      """;

  private static final String READ_PROVENANCE_MATRIX_VERSION =
      "SELECT version FROM app.profile_provenance_matrix_version WHERE profile_id = ?::uuid";

  /**
   * A straight INSERT. Every print is its own artifact (ticket 05 decision 2) — no upsert, no
   * unique index, no supersession. {@code cycle_id} is left NULL, which is what makes a reprint
   * legal under V0008's {@code UNIQUE (cycle_id, kind)}; see V0070.
   *
   * <p>{@code storage_key} is required by the schema and read by nothing: V0053 narrowed its
   * meaning to "opaque, currently unused" once AD-004 put the bytes in {@code body}. A distinct
   * value per row keeps it honest as an identifier rather than a constant.
   */
  private static final String STORE_PRINTED_FORM =
      """
      INSERT INTO app.artifact_ref
        (profile_id, kind, storage_key, content_type, byte_size, sha256, body, state, created_at)
      VALUES (?::uuid, ?::text, ?::text, 'application/pdf', ?::bigint, sha256(?::bytea), ?::bytea,
              'committed', ?::timestamptz)
      RETURNING artifact_ref_id
      """;

  /**
   * A stored print, read back for a re-download. The kind filter is what keeps this endpoint from
   * becoming a second, ungated image endpoint: only the one printed-form kind is reachable through
   * it (there were two until AD-022/V0072), so an operator cannot walk it onto a portrait or a
   * payslip.
   */
  private static final String FIND_PRINTED_FORM =
      """
      SELECT ar.artifact_ref_id, ar.content_type, app.artifact_read(ar.artifact_ref_id) AS body
        FROM app.artifact_ref ar
       WHERE ar.artifact_ref_id = ?::uuid
         AND ar.profile_id = ?::uuid
         AND ar.state = 'committed'
         AND ar.kind = ANY (?::text[])
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcPrintedFormRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Map<PrintedFormImageSlot, byte[]> images(UUID profileId) {
    Map<String, PrintedFormImageSlot> byKind = new HashMap<>();
    for (PrintedFormImageSlot slot : PrintedFormImageSlot.inFormOrder()) {
      byKind.put(slot.artifactKind(), slot);
    }

    Map<PrintedFormImageSlot, byte[]> images = new EnumMap<>(PrintedFormImageSlot.class);
    jdbcTemplate.query(
        IMAGES,
        rs -> {
          byte[] body = rs.getBytes("body");
          // A NULL body is a legitimate absence, not a fault: AD-004 stores no bytes for some
          // capture frames at all, and V0055's purge NULLs a body while keeping the row.
          // app.artifact_read() returns NULL for both and raises only for a checksum mismatch.
          if (body != null) {
            images.put(byKind.get(rs.getString("kind")), body);
          }
        },
        profileId.toString(),
        profileId.toString(),
        arrayLiteral(PrintedFormImageSlot.artifactKinds()));
    return images;
  }

  @Override
  public Optional<StoredArtifact> salaryCertificate(UUID profileId) {
    return jdbcTemplate
        .query(SALARY_CERTIFICATE, JdbcPrintedFormRepository::mapArtifact, profileId.toString())
        .stream()
        .findFirst()
        .filter(artifact -> artifact.bytes() != null);
  }

  @Override
  public Map<String, Integer> pinnedReferenceVersions(UUID profileId) {
    Map<String, Integer> versions = new HashMap<>();
    jdbcTemplate.query(
        PINNED_REFERENCE_VERSIONS,
        rs -> {
          versions.put(rs.getString("list_code"), rs.getInt("version"));
        },
        profileId.toString());
    return versions;
  }

  @Override
  public Set<Integer> editedFieldNumbers(UUID profileId) {
    Set<Integer> numbers = new HashSet<>();
    jdbcTemplate.query(
        EDITED_FIELD_NUMBERS,
        rs -> {
          numbers.add(rs.getInt("field_number"));
        },
        profileId.toString());
    return numbers;
  }

  @Override
  public int recordProvenanceMatrixVersion(UUID profileId) {
    Integer current = jdbcTemplate.queryForObject(CURRENT_PROVENANCE_MATRIX_VERSION, Integer.class);
    if (current == null) {
      throw new IllegalStateException(
          "app.provenance_matrix_version is empty; V0027 and V0029 seed versions 1 and 2, so an"
              + " empty table means the migrations did not run");
    }
    jdbcTemplate.update(RECORD_PROVENANCE_MATRIX_VERSION, profileId.toString(), current);
    // Read back rather than trusting `current`: on a reprint the INSERT does nothing and the
    // recorded value is whatever the FIRST print pinned, which may now be an older edition of
    // field-provenance.md. That is the whole point -- "a reprint in a year says the same thing".
    //
    // No null branch: the INSERT above guarantees a row exists, and queryForObject RAISES on zero
    // rows rather than returning null, so a null-check here would be unreachable code presenting
    // an impossible case as a live one. An earlier draft had exactly that; found at review.
    return jdbcTemplate.queryForObject(
        READ_PROVENANCE_MATRIX_VERSION, Integer.class, profileId.toString());
  }

  @Override
  public UUID storePrintedForm(UUID profileId, String kind, byte[] bytes, Instant now) {
    String artifactId =
        jdbcTemplate.queryForObject(
            STORE_PRINTED_FORM,
            String.class,
            profileId.toString(),
            kind,
            "printed-form/" + UUID.randomUUID(),
            (long) bytes.length,
            bytes,
            bytes,
            now.toString());
    return UUID.fromString(Objects.requireNonNull(artifactId, "artifact_ref_id"));
  }

  @Override
  public Optional<StoredArtifact> findPrintedForm(UUID profileId, UUID artifactId) {
    return jdbcTemplate
        .query(
            FIND_PRINTED_FORM,
            JdbcPrintedFormRepository::mapArtifact,
            artifactId.toString(),
            profileId.toString(),
            arrayLiteral(List.of(PrintedFormRepository.PRINTED_FORM_KIND)))
        .stream()
        .findFirst()
        .filter(artifact -> artifact.bytes() != null);
  }

  private static StoredArtifact mapArtifact(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new StoredArtifact(
        UUID.fromString(rs.getString("artifact_ref_id")),
        rs.getString("content_type"),
        rs.getBytes("body"));
  }

  private static String arrayLiteral(List<String> values) {
    return values.stream().sorted().collect(joining(",", "{", "}"));
  }
}
