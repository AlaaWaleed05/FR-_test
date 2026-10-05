-- AD-022 (S9-01, 2026-09-16): manual completion is REMOVED, and this corrects what the database
-- catalogue says about the column it leaves behind.
--
-- V0067 applied a COMMENT ON COLUMN reading "the column is retained and still written so it can be
-- restored without a migration". Both halves of that are now false: AD-022 deleted
-- operator.jdbc.JdbcManualCompletionRepository, which was the ONLY writer of `true` into this
-- column anywhere in the system, so nothing writes it; and with no writer, restoring the four-eyes
-- rule would need a write path rebuilt as well as the predicate restored, which is not free.
--
-- V0067 IS NOT EDITED. It is applied (BL-144, and CLAUDE.md's rule on generated/applied artifacts),
-- and a comment is re-issued by replacement rather than by amendment -- COMMENT ON COLUMN simply
-- overwrites. The history of what the column once meant stays readable in V0067 and V0009.
--
-- THE COLUMN ITSELF IS DELIBERATELY KEPT, and this migration does not touch it. Profiles completed
-- manually BEFORE this ruling keep their history, and this column is how they stay distinguishable
-- from a mobile submission on the status-history timeline. It becomes write-never, read-still: the
-- back office still renders the «إكمال يدوي» tag from it, and app.profile_status_history is
-- append-only, so those rows are permanent. Dropping it would destroy the only record that those
-- profiles were completed differently.
COMMENT ON COLUMN app.profile_status_history.is_manual_completion IS
  'WRITE-NEVER, READ-STILL since 2026-09-16 (AD-022). True on the history row written when an '
  'operator manually completed a profile at a branch -- a capability that no longer exists. '
  'Recorded permanently as provenance, and still shown to operators on the status-history '
  'timeline, which is why the column is retained rather than dropped. Nothing writes it: its only '
  'writer, operator.jdbc.JdbcManualCompletionRepository, was deleted with the feature, so every '
  'row created after that date is false. Until 2026-09-13 it also DROVE THE FOUR-EYES RULE: '
  'approve''s conditional UPDATE carried an AND NOT EXISTS (...) conjunct refusing an approve by '
  'the same actor_id. AD-013 removed that rule; restoring it would now need a writer as well as '
  'the predicate. See V0009 for the original predicate, V0067 for the four-eyes removal, and '
  'RISKS.md R-054.';

-- No DDL, no grant, no data change: a column comment is metadata only, so this migration is safe
-- to apply to a running system and nothing downstream depends on it having run.
