-- AD-008 / BL-041: a device-less re-entry must not inherit the previous session's identity
-- artifacts. Every other derived artifact is keyed to app.identity_cycle, so superseding the
-- cycle removes it from every customer-facing read (each one joins ic.state = 'active').
-- The signature is the exception: V0026 gave it profile_id with cycle_id NULL ("a signature
-- belongs to the whole profile, captured at stage 11 after liveness"), so no cycle supersede
-- can reach it, and JdbcSubmissionRepository's has_signature reads it by profile_id alone.
--
-- Left unfixed, that is the whole vulnerability rather than a corner of it: after a
-- device-less re-entry the impostor rescans and passes liveness (AD-008 requires exactly
-- that), which builds a fresh active cycle and face_result -- and has_signature is still true
-- from the PREVIOUS person's row, so SubmissionService.currentJourneyPointer skips SIGNATURE,
-- answers SUBMIT, and the submitted profile carries a signature drawn by someone else.
--
-- 'superseded' is added rather than the row being deleted: customer.md l.174-175 requires the
-- prior artifacts be "retained in the backend, marked superseded, so the audit trail records
-- that a scan occurred and was replaced". Non-inheritance comes from the readers, not from
-- destroying evidence. fru_app holds no DELETE on this table anyway (V0010), by the same
-- retention reasoning.
--
-- One limit on that retention, for the signature specifically (found by @agent-reviewer, not
-- assumed away): V0046's index is UNIQUE (profile_id) WHERE kind='signature', not scoped by
-- state, and JdbcSignatureRepository upserts on that target -- so a superseded signature's BYTES
-- are overwritten the next time anyone signs on this profile. The supersession stays on the
-- record as the identity_superseded audit event; the image does not survive a redraw. Every
-- cycle-keyed artifact IS fully retained, because a rescan opens a new cycle. Closing the gap
-- means scoping V0046 and the upsert to state='committed' -- filed as BL-062.
--
-- No new grant: fru_app already holds SELECT, INSERT, UPDATE on app.artifact_ref (V0010),
-- which is exactly what the state write needs -- the same note V0053 records for this table.
--
-- Interaction with the two existing state consumers, both checked:
--   * V0055's purge predicate is `WHERE ar.state <> 'purged'`, so a 'superseded' row stays
--     purgeable on the normal retention path and is not stranded.
--   * JdbcSignatureRepository's upsert carries `state = EXCLUDED.state` with EXCLUDED.state
--     = 'committed' (added for the identical 'purged' case), so a customer who redraws their
--     signature at Stage 11 returns the row to 'committed' with no extra code.
ALTER TABLE app.artifact_ref DROP CONSTRAINT artifact_ref_state_check;
ALTER TABLE app.artifact_ref ADD CONSTRAINT artifact_ref_state_check
  CHECK (state IN ('staged', 'committed', 'purged', 'superseded'));

COMMENT ON COLUMN app.artifact_ref.state IS
  'staged -> committed is the stage-8 upload reconciliation (V0008). purged is V0055''s retention sweep (body nulled, row kept). superseded is AD-008/BL-041: the artifact is retained as audit evidence but must no longer be read as this profile''s current one -- set on the profile-keyed signature when a device-less re-entry supersedes the identity cycle.';
