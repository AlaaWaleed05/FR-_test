-- Mirrors V0040's pending-scan-session pattern for stage 10: LivenessService mints (via
-- uqudoClient.createFaceSession) a fresh Uqudo Face Session id per attempt and must persist
-- it so submitFaceResult validates the returned JWS's jti against what the backend actually
-- issued, never against what the request merely claims -- the same replay-protection
-- reasoning V0040's own comment gives for pending_scan_session_id.
--
-- Nullable, and deliberately never cleared after use -- a resubmission of the exact same
-- (faceSessionId, jws) pair is harmless (idempotent verification of a JWS the backend
-- already issued for), and the real replay guard is app.face_result.uqudo_jti's own UNIQUE
-- constraint (V0008).
ALTER TABLE app.profile
  ADD COLUMN pending_face_session_id text;

-- No new grant: app.profile already carries SELECT, INSERT, UPDATE for fru_app (V0010).
