-- Resolves R-044 (S3-08): the per-channel counters on app.profile_channel
-- (wrong_code_attempts, resend_count, locked_at) stay per-session, reset on a Stage 1b
-- re-entry exactly as S3-07's upsertChannel already does -- confirmed, not changed here.
--
-- What DOES need new storage is customer.md Stage 2's own policy value: "Both phone
-- channels locked, or the only selected phone channel locked -> terminal ... 15 minutes,
-- escalating to 1 hour on a repeat in the same session." A channel that hit its 5-wrong-
-- attempts lock "cannot be retried" (V0007), so "try again later" can only mean a block on
-- the next Stage 1b re-entry for this account -- these two columns are read by
-- ContactChannelsService's re-entry path to refuse a re-entry attempted before
-- phone_lock_until, and written by OtpVerificationService when both required phone
-- channels lock. Unlike the per-channel counters, they deliberately do NOT reset on
-- re-entry -- resetting them would defeat the only thing that makes them a bound at all.
-- phone_lock_escalated never resets either: once true, every future lock is 1 hour, the
-- policy value's stated ceiling.
ALTER TABLE app.profile
  ADD COLUMN phone_lock_until     timestamptz,
  ADD COLUMN phone_lock_escalated boolean NOT NULL DEFAULT false;
