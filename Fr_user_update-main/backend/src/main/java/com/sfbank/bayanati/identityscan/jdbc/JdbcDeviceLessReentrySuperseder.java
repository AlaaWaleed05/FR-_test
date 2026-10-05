package com.sfbank.bayanati.identityscan.jdbc;

import com.sfbank.bayanati.identityscan.domain.DeviceLessReentrySuperseder;
import com.sfbank.bayanati.identityscan.domain.SupersededIdentity;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * AD-008 / BL-041 as {@code fru_app}. Runs inside the Stage 1b re-entry transaction, under the row
 * lock {@code ContactChannelsService} already takes on {@code app.profile}.
 */
@Repository
public class JdbcDeviceLessReentrySuperseder implements DeviceLessReentrySuperseder {

  private static final String AWAITING_REGISTRY = "awaiting_registry";

  // The device-less trigger and the supersede are ONE statement on purpose: the "does an
  // inheritable identity exist" test is the WHERE clause, so it cannot be evaluated against a
  // state that changes before the write lands.
  //
  // Keyed on state = 'active' alone, deliberately NOT on accepted_at IS NOT NULL. INSERT_ACTIVE_
  // CYCLE (JdbcIdentityScanRepository) inserts with accepted_at NULL and only Stage 9's "Accept"
  // stamps it, so `active AND accepted_at IS NULL` is an ordinary, reachable state: the scan
  // landed and the customer is still on the registry-review screen. Restricting the supersede to
  // accepted cycles would leave that one inheritable -- and although the submission gates all
  // require accepted_at and so could not be passed, the Stage 9 review screen would show a
  // re-entering impostor the previous customer's extracted document data, Civil Registry record
  // and document images, and let them stamp accepted_at on that cycle themselves. AD-008's rule is
  // that the identity-scan stage is ATOMIC: a device-less re-entry does not resume INSIDE it, at
  // any point. 'abandoned' and 'superseded' cycles are untouched -- they are already un-inherited.
  //
  // No same-device route into Stage 1b can hold an active cycle: the "wrong phone number?"
  // correction lives on the Stage 2 screen, and every other in-journey route back to account entry
  // calls abandon()/_clearSession() first -- so local state is already gone. See the port's
  // Javadoc.
  private static final String SUPERSEDE_ACTIVE_CYCLE =
      """
      UPDATE app.identity_cycle SET state = 'superseded', superseded_at = ?::timestamptz
       WHERE profile_id = ?::uuid AND state = 'active'
      """;

  // The one artifact no cycle supersede can reach: V0026 gives 'signature' profile_id with
  // cycle_id NULL. V0063 added the 'superseded' state this sets, and JdbcSubmissionRepository's
  // has_signature filter is what actually revokes it.
  //
  // Retention caveat, stated rather than assumed (found by @agent-reviewer): the row survives this
  // UPDATE, but V0046's unique index is UNIQUE (profile_id) WHERE kind='signature' -- NOT scoped by
  // state -- and JdbcSignatureRepository upserts on that same target, so the NEXT signature drawn
  // on
  // this profile overwrites the superseded bytes. The supersession itself stays on the permanent
  // record as the identity_superseded audit event; the superseded IMAGE does not outlive a redraw.
  // Cycle-keyed artifacts have no such gap (a rescan inserts a new cycle, so the old rows persist).
  // Scoping V0046 and the upsert to state='committed' would close it, at the cost of changing
  // purge/upsert semantics this security fix has no reason to touch -- filed as BL-062.
  private static final String SUPERSEDE_SIGNATURE_ARTIFACT =
      """
      UPDATE app.artifact_ref SET state = 'superseded'
       WHERE profile_id = ?::uuid AND kind = 'signature' AND state = 'committed'
      """;

  // AD-008: "The per-type scan-attempt budget RESETS on this forced restart ... an interrupted
  // session is not the customer's failure."
  //
  // Deliberately NOT RESUME_FROM_SCAN_BLOCK (JdbcIdentityScanRepository), which also zeroes
  // scan_attempts_total and clears scan_blocked_until. AD-008's own bound depends on those two
  // surviving: "the total-based 24-hour block still keys on cumulative attempts across sessions,
  // so abuse stays bounded despite the reset." Since POST /api/v1/contact-channels is
  // unauthenticated by design (R-051), reusing that statement would let anyone holding an account
  // number clear the 24-hour block on demand, one re-entry at a time.
  //
  // The pending scan/face session handles go too: they are single-use challenge material minted
  // for the superseded cycle's attempt (V0040, V0044), and leaving them would keep a stale
  // sessionId/nonce pair replayable against the profile after its identity was revoked.
  //
  // liveness_attempts and liveness_blocked_until are NOT reset: AD-008 is silent on them, and they
  // are the same family of per-profile abuse bound as scan_attempts_total. No status write, so
  // V0020's transition guard is not engaged.
  //
  // scan_tokens_minted and face_tokens_minted (V0065, BL-039 Slice B) are NOT reset either, and
  // are named here so their absence reads as a decision rather than an omission. They are the
  // strongest members of exactly that family: lifetime mint caps that never reset at all, whose
  // entire purpose is to survive both the 24-hour block and this re-entry. Adding them to the SET
  // list above would hand anyone holding an account number an unlimited supply of billable Uqudo
  // tokens, one re-entry at a time -- the same argument that keeps scan_attempts_total out.
  private static final String RESET_PER_TYPE_SCAN_BUDGET =
      """
      UPDATE app.profile
         SET scan_attempts_national_id = 0,
             scan_attempts_passport    = 0,
             pending_scan_session_id   = NULL,
             pending_scan_nonce        = NULL,
             pending_face_session_id   = NULL,
             row_version = row_version + 1
       WHERE profile_id = ?::uuid
      RETURNING status
      """;

  // V0020 declares awaiting_registry -> in_progress legal; the deferred constraint trigger requires
  // the matching history row, actor 'system' -- the supersede is the backend's own consequence, not
  // something the customer asked for. Mirrors transitionBackToInProgressFromRegistry exactly.
  private static final String CLEAR_REGISTRY_PAUSE =
      """
      UPDATE app.profile
         SET status = 'in_progress', status_changed_at = ?::timestamptz,
             row_version = row_version + 1
       WHERE profile_id = ?::uuid AND status = 'awaiting_registry'
      """;

  private static final String INSERT_STATUS_HISTORY =
      """
      INSERT INTO app.profile_status_history
        (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)
      VALUES (?::uuid,
              (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
              'awaiting_registry', 'in_progress', 'system', ?::bigint)
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcDeviceLessReentrySuperseder(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public SupersededIdentity supersedeInheritedIdentity(UUID profileId, Instant now) {
    int cyclesSuperseded =
        jdbcTemplate.update(SUPERSEDE_ACTIVE_CYCLE, now.toString(), profileId.toString());

    if (cyclesSuperseded == 0) {
      // No inheritable identity: either a first entry, or a re-entry before the scan -- which is
      // every same-device route back into Stage 1b. Nothing else runs, so the same-device path
      // keeps its budget, its pending handles and its signature exactly as before this change.
      return SupersededIdentity.NOTHING;
    }

    int signaturesSuperseded =
        jdbcTemplate.update(SUPERSEDE_SIGNATURE_ARTIFACT, profileId.toString());

    // RETURNING status, so the caller learns in the same round trip whether this profile was parked
    // at the Stage 9 registry pause -- a status whose every exit needs the cycle just superseded.
    String statusAfterReset =
        jdbcTemplate.queryForObject(RESET_PER_TYPE_SCAN_BUDGET, String.class, profileId.toString());

    return new SupersededIdentity(
        true, signaturesSuperseded > 0, AWAITING_REGISTRY.equals(statusAfterReset));
  }

  @Override
  public void clearRegistryPauseAfterSupersession(UUID profileId, Instant now, long auditEventId) {
    int moved = jdbcTemplate.update(CLEAR_REGISTRY_PAUSE, now.toString(), profileId.toString());
    if (moved == 0) {
      // The status changed under us between the supersede and here. Both statements run inside the
      // re-entry transaction, holding lockAndCheckStillEligibleForReentry's FOR UPDATE OF p, so
      // this is not reachable today -- but writing a history row for a transition that did not
      // happen would corrupt the chain, and the deferred trigger would reject it anyway.
      return;
    }
    jdbcTemplate.update(
        INSERT_STATUS_HISTORY,
        new Object[] {profileId.toString(), profileId.toString(), auditEventId},
        new int[] {Types.VARCHAR, Types.VARCHAR, Types.BIGINT});
  }
}
