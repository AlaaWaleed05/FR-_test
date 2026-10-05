-- BL-039, settled 2026-09-06 (docs/sessions/2026-09-06-bl039-budget-investigation.md, DECISION).
--
-- V0040 added pending_scan_session_id/pending_scan_nonce and stated they are "deliberately never
-- cleared after use". That rule is now reversed, and V0040's comment would otherwise stand as a
-- direct contradiction of the code. V0040 itself is applied and is never hand-edited (CLAUDE.md);
-- this migration supersedes its rationale the way V0050 and V0061 supersede earlier ones -- by
-- writing the current truth onto the columns themselves, where anyone reading the schema finds it.
--
-- What changed. V0040's reasoning was that a resubmission of the exact same (sessionId, nonce, jws)
-- triple is harmless, because app.scan_result.uqudo_jti's UNIQUE constraint (V0008) is the real
-- replay guard once a scan is ACCEPTED. That is still true of an accepted scan -- and is exactly
-- why the accept path still does not clear these columns. It was never true of a FAILED one: the
-- per-type budget was consulted only at token issuance, so a session that never expired let one
-- token absorb an unbounded number of failed posts, driving scan_attempts_* past their limits and
-- stranding the customer on the 24-hour block with the other document type untouched.
--
-- Touches schema app only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed.
-- No DDL and no data change: comments only.

COMMENT ON COLUMN app.profile.pending_scan_session_id IS
  'The sessionId the backend minted for the current scan attempt, compared against the returned '
  'JWS''s own jti at submission so a JWS is validated against what WAS issued rather than what the '
  'request claims (V0040). SINGLE-USE since V0064 (BL-039): consumed together with '
  'pending_scan_nonce at the moment an attempt is SPENT -- inside '
  'IdentityScanService.recordFailedAttempt and reportWrongNumber, beside applyScanAttempt -- so '
  'one token backs exactly one attempt. Deliberately NOT cleared on a successful scan, nor on the '
  'exempt failures (camera denied, ARTIFACT_EXPIRED, IMAGES_UNAVAILABLE), which cost the customer '
  'nothing: BL-034''s upload retry re-posts the same (sessionId, nonce, jws) triple through the '
  'session-equality check, so clearing on acceptance would turn a lost acknowledgement into '
  'INVALID_SCAN_SESSION. Also cleared by AD-008''s device-less re-entry supersede, which already '
  'treated the pair as single-use challenge material. Supersedes V0040''s "deliberately never '
  'cleared after use".';

COMMENT ON COLUMN app.profile.pending_scan_nonce IS
  'The nonce issued with pending_scan_session_id, checked against the JWS''s data.nonce at '
  'submission (V0040). Written, read and consumed as one unit with that column -- see its comment '
  'for the single-use rule V0064 (BL-039) introduced and what deliberately does not consume it.';
