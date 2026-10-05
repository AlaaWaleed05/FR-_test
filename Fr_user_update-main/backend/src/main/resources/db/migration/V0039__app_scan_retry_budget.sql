-- customer.md Stage 8's own policy values: "Scan attempts per document type — 3" and "Total
-- scan attempts per session — 6", plus the resulting "Either budget exhausted -> temporary
-- block ... 24 hours". Nothing before S3-12 needed to count or bound a scan attempt, so no
-- prior migration added these columns. Mirrors V0037's phone_lock_until/escalated shape:
-- counters and a block deadline live directly on app.profile, read and written by
-- ProfileRepository exactly like the phone-lock pair.
--
-- Unlike phone_lock_escalated (which never resets -- the point of that column is that it
-- never resets), these three counters DO reset once scan_blocked_until has passed: the
-- 24-hour block is customer.md's own "try later" promise, and a block that left the budget
-- permanently spent would make "try later" mean nothing. IdentityScanService resets them
-- (and clears scan_blocked_until, and transitions app.profile.status blocked_scan ->
-- in_progress, legal per V0020) the next time the customer requests a scan token after the
-- deadline passes -- not on a timer, the same "decide staleness by attempting, not by a
-- background job" discipline the rest of this schema uses.
ALTER TABLE app.profile
  ADD COLUMN scan_attempts_national_id smallint NOT NULL DEFAULT 0,
  ADD COLUMN scan_attempts_passport    smallint NOT NULL DEFAULT 0,
  ADD COLUMN scan_attempts_total       smallint NOT NULL DEFAULT 0,
  ADD COLUMN scan_blocked_until        timestamptz;

-- No new grant: app.profile already carries SELECT, INSERT, UPDATE for fru_app (V0010),
-- which covers new columns on the same table.
