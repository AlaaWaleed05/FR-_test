-- customer.md Stage 10's own policy values: "Liveness attempts -- 5" and "Block after
-- liveness exhaustion -- 24 hours", plus "Face-match failure -- Same budget as liveness, no
-- separate block". Mirrors V0039's scan-retry-budget shape (counters + a block deadline
-- directly on app.profile), but with a SINGLE counter rather than per-document-type: unlike
-- the scan (which the customer can retry against either of two document types), liveness
-- and face-match retries all apply to the one accepted identity cycle already on file.
--
-- LivenessService resets liveness_attempts and clears liveness_blocked_until (and
-- transitions app.profile.status blocked_liveness -> in_progress, legal per V0020) the next
-- time the customer requests a liveness token after the deadline passes -- not on a timer,
-- the same "decide staleness by attempting, not by a background job" discipline V0039 used.
ALTER TABLE app.profile
  ADD COLUMN liveness_attempts      smallint NOT NULL DEFAULT 0,
  ADD COLUMN liveness_blocked_until timestamptz;

-- No new grant: app.profile already carries SELECT, INSERT, UPDATE for fru_app (V0010).
