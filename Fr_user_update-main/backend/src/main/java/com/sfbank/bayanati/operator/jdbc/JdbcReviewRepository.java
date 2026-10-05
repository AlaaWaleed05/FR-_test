package com.sfbank.bayanati.operator.jdbc;

import com.sfbank.bayanati.operator.domain.ReviewRepository;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code app.profile}/{@code app.profile_status_history} for approve and reject, as {@code
 * fru_app}.
 *
 * <p>{@link #APPROVE} was V0009's documented conditional {@code UPDATE ... WHERE status IN (...)
 * AND NOT EXISTS (...)} verbatim. AD-013 (2026-09-13) removed the four-eyes rule outright, so the
 * {@code NOT EXISTS} half is gone and what remains is the status guard alone — still safe to call
 * on its own with no preceding lock, since an ordinary {@code UPDATE}'s {@code WHERE} clause is
 * always evaluated against the row's current state by construction. V0009's own comment still shows
 * the original predicate; V0067 records that it is historical. See R-054 for the accepted cost of
 * the removal.
 *
 * <p>The paired history INSERT's {@code from_status} must be the row's ACTUAL prior status — not
 * guessed from whichever of {@code submitted}/{@code rejected} the caller expected (V0020's
 * deferred {@code profile_status_requires_history} trigger rejects a wrong guess at COMMIT). An
 * earlier version tried to capture it via a {@code WITH old AS (SELECT status ...) ... RETURNING
 * old.status} CTE, reasoning that the CTE and the {@code UPDATE} share one per-statement snapshot —
 * true, but PostgreSQL's EvalPlanQual re-check (READ COMMITTED, a concurrent update lands on this
 * row between the CTE's read and this statement actually running) re-evaluates the {@code UPDATE}'s
 * own {@code WHERE} against the row's new version while reusing the CTE scan's already-fetched,
 * now-stale tuple — so {@code old.status} could report a status the row was never actually updated
 * FROM, silently mismatching {@link #approve}'s caller-supplied history row and tripping V0020's
 * trigger at commit. Fixed by having the caller ({@code OperatorReviewService}) take {@code SELECT
 * ... FOR UPDATE} on the row as the transaction's FIRST statement (matching every other
 * status-changing slice in this codebase, e.g. {@code JdbcSubmissionRepository
 * #LOCK_AND_CHECK_STATE}) and pass the locked-and-read status in as {@code fromStatus} — while that
 * lock is held, nothing else can change the row before this {@code UPDATE} runs, so the value is
 * guaranteed current, and {@link #APPROVE}'s own {@code WHERE} clause is untouched.
 */
@Repository
public class JdbcReviewRepository implements ReviewRepository {

  private static final String CURRENT_STATUS =
      "SELECT status FROM app.profile WHERE profile_id = ?::uuid";

  private static final String LOCK_AND_READ_STATUS =
      "SELECT status FROM app.profile WHERE profile_id = ?::uuid FOR UPDATE";

  private static final String APPROVE =
      """
      UPDATE app.profile p
         SET status = 'approved', status_changed_at = ?::timestamptz, row_version = row_version + 1
       WHERE p.profile_id = ?::uuid
         AND p.status IN ('submitted','rejected')
      """;

  private static final String REJECT =
      """
      UPDATE app.profile
         SET status = 'rejected', status_changed_at = ?::timestamptz, row_version = row_version + 1
       WHERE profile_id = ?::uuid AND status = 'submitted'
      """;

  private static final String INSERT_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, actor_id,
         reason_code, reason_version, internal_note, is_manual_completion, audit_event_id)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
              ?::text, ?::text, 'operator', ?::text, ?::text, ?::int, ?::text, false, ?::bigint)
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcReviewRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<String> currentStatus(UUID profileId) {
    List<String> rows =
        jdbcTemplate.query(CURRENT_STATUS, (rs, n) -> rs.getString("status"), profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  @Override
  public Optional<String> lockAndReadStatus(UUID profileId) {
    List<String> rows =
        jdbcTemplate.query(
            LOCK_AND_READ_STATUS, (rs, n) -> rs.getString("status"), profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  @Override
  public boolean approve(
      UUID profileId, String operatorId, String fromStatus, Instant now, long auditEventId) {
    int updated = jdbcTemplate.update(APPROVE, now.toString(), profileId.toString());
    if (updated == 0) {
      return false;
    }
    insertHistory(profileId, fromStatus, "approved", operatorId, null, null, null, auditEventId);
    return true;
  }

  @Override
  public boolean reject(
      UUID profileId,
      String reasonCode,
      int reasonVersion,
      String internalNote,
      String operatorId,
      Instant now,
      long auditEventId) {
    int updated = jdbcTemplate.update(REJECT, now.toString(), profileId.toString());
    if (updated == 0) {
      return false;
    }
    insertHistory(
        profileId,
        "submitted",
        "rejected",
        operatorId,
        reasonCode,
        reasonVersion,
        internalNote,
        auditEventId);
    return true;
  }

  /**
   * {@code reasonCode}/{@code reasonVersion}/{@code internalNote} are {@code null} for an approve
   * (which has no rejection reason) — bound with explicit {@link Types}, since a bare {@code null}
   * through the plain varargs overload leaves PostgreSQL unable to resolve the {@code ?::int}
   * cast's parameter type.
   */
  private void insertHistory(
      UUID profileId,
      String fromStatus,
      String toStatus,
      String operatorId,
      String reasonCode,
      Integer reasonVersion,
      String internalNote,
      long auditEventId) {
    jdbcTemplate.update(
        INSERT_HISTORY,
        new Object[] {
          profileId.toString(),
          profileId.toString(),
          fromStatus,
          toStatus,
          operatorId,
          reasonCode,
          reasonVersion,
          internalNote,
          auditEventId
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.INTEGER,
          Types.VARCHAR,
          Types.BIGINT
        });
  }
}
