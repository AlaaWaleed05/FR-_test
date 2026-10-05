-- BL-039 Slice B, settled 2026-09-06 (docs/sessions/2026-09-06-bl039-budget-investigation.md,
-- DECISION). Slice A bounded how many ATTEMPTS a token can back; this bounds how many TOKENS a
-- profile can ever mint.
--
-- The hole these close (F-5 in that investigation): token issuance was gated by a counter it never
-- incremented. Request a token, never use it, repeat -- nothing counted, nothing blocked, and the
-- surface is unauthenticated by design (R-051). Slice A does not touch that: it counts spent
-- attempts, and a mint that is never spent still costs a real Uqudo operation while moving no
-- counter at all.
--
-- Two counters, not one, because the two stages block independently: crossing the scan cap applies
-- blocked_scan and crossing the face cap applies blocked_liveness, each reusing its own side's
-- existing 24-hour block rather than introducing a new error code, screen or copy.
--
-- These NEVER reset. That is the point of them, and it is what distinguishes them from
-- scan_attempts_* (zeroed by resumeFromScanBlock on the "try later" promise) and puts them in the
-- same family as phone_lock_escalated (V0037). In particular they are deliberately absent from
-- AD-008's device-less re-entry reset (JdbcDeviceLessReentrySuperseder): that statement already
-- leaves scan_attempts_total and scan_blocked_until alone for exactly this reason -- Stage 1b is
-- unauthenticated, so a bound anyone holding an account number can clear on demand is not a bound.
--
-- Sized at 20 per profile, about two full budget cycles (10 launches each). A customer who
-- exhausts both document types, waits out the 24-hour block and exhausts both again reaches the
-- cap, after which only a branch visit remains. Exempt mints -- a declined camera permission, a
-- double-tap, a dropped upload -- come out of the same 20. That is the accepted trade-off, recorded
-- so that if real customers start hitting it, the NUMBER gets revisited rather than the design.
--
-- smallint, matching V0039/V0043's counters: 20 fits, and so does any plausible revision.
--
-- Touches schema app only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed.
-- No grant needed: app.profile already carries SELECT, INSERT, UPDATE for fru_app (V0010).
ALTER TABLE app.profile
  ADD COLUMN scan_tokens_minted smallint NOT NULL DEFAULT 0,
  ADD COLUMN face_tokens_minted smallint NOT NULL DEFAULT 0;

COMMENT ON COLUMN app.profile.scan_tokens_minted IS
  'How many Stage 8 scan tokens this profile has EVER minted. Incremented inside the issuance '
  'transaction, beside recordPendingSession, so a request refused before the mint counts nothing. '
  'Never reset -- not by resumeFromScanBlock, and deliberately not by AD-008''s device-less '
  're-entry. At ScanAttemptBudget.LIFETIME_TOKEN_CAP the profile is refused with the EXISTING '
  '24-hour blocked_scan response; the cap is checked BEFORE the expired-block lift, so a profile '
  'already blocked and over the cap is refused without writing anything.';

COMMENT ON COLUMN app.profile.face_tokens_minted IS
  'The Stage 10 counterpart of scan_tokens_minted, capped independently at '
  'LivenessAttemptBudget.LIFETIME_TOKEN_CAP and refused with the existing blocked_liveness '
  'response. Incremented once per issued face session, in the transaction that records the pending '
  'face session -- note that path spends TWO Uqudo operations (createFaceSession plus the token '
  'mint) but counts one, because what is capped is the token.';
