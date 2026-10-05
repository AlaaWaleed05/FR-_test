package com.sfbank.bayanati.operator.jdbc;

import static java.util.stream.Collectors.joining;

import com.sfbank.bayanati.operator.domain.OperatorImage;
import com.sfbank.bayanati.operator.domain.OperatorImagePolicy;
import com.sfbank.bayanati.operator.domain.OperatorImageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * BL-075's read path — one statement that authorises and reads at the same time.
 *
 * <p><strong>Why authorisation and the read are a single SELECT, and why the order inside it
 * matters.</strong> PostgreSQL evaluates a query's target list only for rows that survive its
 * {@code WHERE}, so {@code app.artifact_read()} is never called for an artifact this profile does
 * not own, or for one in the wrong state. That is what keeps the two failure modes from being
 * distinguishable from outside: a row that does not exist and a row belonging to someone else both
 * produce ZERO ROWS, and neither reaches the function. Read the bytes first and check ownership
 * afterwards and the endpoint becomes an existence oracle — a nonexistent id raises (500) while
 * another customer's id is refused (404), which tells an attacker which artifact ids are real. Same
 * shape as {@code identityscan.jdbc.JdbcIdentityScanRepository}'s Stage 9 read.
 *
 * <p>The ownership predicate is the one already proven in {@code JdbcProfileViewRepository}'s
 * {@code ARTIFACTS} listing, narrowed to a single artifact. Both halves are needed: cycle-keyed
 * artifacts (the scan images and portraits) hang off {@code app.identity_cycle}, while the
 * signature and the salary certificate are profile-keyed with a NULL {@code cycle_id} (V0026).
 *
 * <p><strong>The two share the OWNERSHIP predicate only.</strong> The fetch additionally applies
 * the kind allow-list below; the listing deliberately does not, so it still returns an {@code
 * artifactRefId} for {@code doc_back} and both capture frames — rows this endpoint 404s. That is a
 * recorded decision, not an oversight (filtering the listing would change {@code
 * ProfileDetailResponse} and its consumers), and it has a consequence for whoever builds the
 * operator's contact sheet: <em>do not link every listed row</em>. Link the six kinds {@link
 * OperatorImagePolicy} allows, or the largest row in the table becomes a broken image again — the
 * exact failure BL-075 requirement (a) exists to prevent, one level up. ({@code salary_certificate}
 * was one of the refused rows until S8-24 and is now among the allowed; the listing's lack of a
 * kind filter is unchanged either way.)
 *
 * <p>{@code state = 'committed'} refuses the other three states, each for its own reason: {@code
 * staged} was never reconciled, {@code purged} has had its body NULLed by V0055's retention sweep,
 * and {@code superseded} (V0063) is the signature of a customer a device-less re-entry replaced —
 * retained as audit evidence precisely so it is NOT read as this profile's current one
 * (AD-008/BL-041).
 *
 * <p>The kind allow-list is applied HERE, in the same {@code WHERE}, rather than by the caller
 * refusing a row it has already fetched. Artifact bodies are TOASTed and one was measured live at
 * 1.7 MB; pulling a {@code doc_back} out of TOAST only to throw it away is work done for a request
 * that was never going to be served. The list itself still has exactly one definition — {@link
 * OperatorImagePolicy#viewableKinds()} — bound as a parameter, never restated in SQL.
 */
@Repository
public class JdbcOperatorImageRepository implements OperatorImageRepository {

  private static final String ARTIFACT_BYTES =
      """
      SELECT ar.kind, ar.content_type, app.artifact_read(ar.artifact_ref_id) AS body
        FROM app.artifact_ref ar
       WHERE ar.artifact_ref_id = ?::uuid
         AND (ar.profile_id = ?::uuid
              OR ar.cycle_id IN (SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ?::uuid))
         AND ar.state = 'committed'
         AND ar.kind = ANY (?::text[])
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcOperatorImageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<OperatorImage> find(UUID profileId, UUID artifactRefId) {
    List<OperatorImage> rows =
        jdbcTemplate.query(
            ARTIFACT_BYTES,
            (rs, n) ->
                new OperatorImage(
                    rs.getString("kind"), rs.getString("content_type"), rs.getBytes("body")),
            artifactRefId.toString(),
            profileId.toString(),
            profileId.toString(),
            viewableKindsArrayLiteral());

    // A NULL body is a legitimate absence, not a fault: AD-004 stores no bytes for the capture
    // frames at all, and V0055's purge NULLs a body while keeping the row. app.artifact_read()
    // returns NULL for both and raises only for a checksum mismatch, so anything that gets here
    // with no bytes is genuinely "not available", never "present and wrong".
    return rows.stream().findFirst().filter(image -> image.bytes() != null);
  }

  /**
   * The allow-list as a PostgreSQL array literal. Every kind is a fixed identifier from {@link
   * OperatorImagePolicy} -- no request input reaches this string -- and the sorted order keeps the
   * rendered SQL parameter stable between runs, so a query log or a test assertion does not churn
   * on {@code Set} iteration order.
   */
  private static String viewableKindsArrayLiteral() {
    return OperatorImagePolicy.viewableKinds().stream().sorted().collect(joining(",", "{", "}"));
  }
}
