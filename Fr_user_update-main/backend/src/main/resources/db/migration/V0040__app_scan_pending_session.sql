-- Closes a real gap found by @agent-reviewer in S3-12's first pass: IdentityScanService minted
-- sessionId/nonce per attempt but never persisted them, so submitScan validated the returned JWS
-- against whatever sessionId/nonce the REQUEST claimed rather than what the backend actually
-- issued -- an attacker holding any valid JWS (harvested, proxied, another customer's) could post
-- it against their own profile with the session/nonce read out of that JWS, and every check would
-- pass. uqudo-sdk.md's own requirement is explicit: "jti == the sessionId WE passed to
-- setSessionId ... data.nonce == the nonce WE issued" -- "we" means the backend's own record, not
-- the caller's say-so.
--
-- These two columns are that record: written by IdentityScanRepository at token-issuance time,
-- read back and compared against the JWS's own claims at scan-submission time. Nullable, and
-- deliberately never cleared after use -- a resubmission of the exact same (sessionId, nonce, jws)
-- triple is harmless (idempotent verification of a JWS the backend already issued for), and the
-- real replay guard is app.scan_result.uqudo_jti's own UNIQUE constraint (V0008) once a scan is
-- actually accepted.
ALTER TABLE app.profile
  ADD COLUMN pending_scan_session_id text,
  ADD COLUMN pending_scan_nonce      text;

-- No new grant: app.profile already carries SELECT, INSERT, UPDATE for fru_app (V0010).
