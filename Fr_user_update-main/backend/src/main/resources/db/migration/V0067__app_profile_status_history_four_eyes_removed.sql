-- The four-eyes rule is REMOVED (AD-013, product-owner decision 2026-09-13, BL-131). An operator
-- may now approve a profile they themselves manually completed. There is no longer a second pair
-- of eyes anywhere in the chain from manual completion to approval.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT TO V0009.
-- V0009 has been applied to every environment, and Flyway's validateOnMigrate defaults to true.
-- Editing its bytes changes its checksum and breaks migration on every already-migrated database.
-- The trap is that no gate would catch it: Testcontainers starts from an empty database and
-- re-applies the edited file, so its checksum always matches in test and only the deployed
-- database refuses. Same reasoning as V0066, and the same resolution.
--
-- WHAT IN V0009 IS NOW HISTORICAL TEXT, superseded by this file rather than edited:
--   V0009:1     "the four-eyes rule's own table" in the header.
--   V0009:16    the trailing comment "drives the four-eyes rule" on the column declaration.
--   V0009:35-50 the illustrative conditional UPDATE with its `AND NOT EXISTS (...)  -- four-eyes`
--               conjunct, and the closing claim "Zero rows affected means either a lost race or a
--               four-eyes violation. This makes the control impossible to bypass by calling the
--               API directly."
-- Read all of it as what was true between August 2026 and 2026-09-13. The live approve statement
-- is now operator.jdbc.JdbcReviewRepository#APPROVE, which keeps the status guard and has dropped
-- the NOT EXISTS conjunct entirely.
--
-- TWO OTHER APPLIED MIGRATIONS NAME THE RULE IN PASSING and are likewise superseded, not edited:
--   V0042:4  cites the four-eyes rule as an example of "an invariant this important does not live
--            in Java alone". The irony is now on the record: four-eyes was the one item in that
--            list that lived in Java alone, which is exactly why removing it needed no DDL. V0042's
--            actual subject -- the face-reference-image clearing triggers -- is untouched and its
--            point about V0020 and append-only enforcement still stands.
--   V0057:71 justifies "an operator account is NEVER deleted" by the four-eyes predicate plus every
--            audit attribution. The CONCLUSION IS UNCHANGED and must not be relaxed: actor_id is
--            still written into append-only app.profile_status_history and audit.audit_event rows,
--            so a deleted account still orphans every attribution. Only the first of its two
--            reasons is gone.
--
-- NO DDL IS NEEDED, AND NONE IS DONE. The rule never lived in a database constraint: it was a
-- conjunct in a WHERE clause written in Java. Nothing in this schema -- no CHECK, no trigger, no
-- function, no rule, no policy, no view -- ever referenced app.profile_status_history
-- .is_manual_completion in an enforcing position. Grants are table-level (V0010:47) and unaffected.
--
-- THE COLUMN IS DELIBERATELY RETAINED AND STILL WRITTEN.
-- operator.jdbc.JdbcManualCompletionRepository continues to insert `true` on every manual
-- completion, and the back office continues to display it on the status-history timeline. This is
-- the point: the rule can be switched back on by restoring one SQL conjunct, with no migration and
-- no blind spot for profiles completed while it was off. R-054 carries the accepted cost of the
-- removal -- no separation of duties now remains over the business action, and the audit trail is
-- the sole compensating control.
COMMENT ON COLUMN app.profile_status_history.is_manual_completion IS
  'True on the history row written when an operator manually completes a profile at a branch. '
  'Recorded permanently as provenance, and shown to operators on the status-history timeline. '
  'Until 2026-09-13 it also DROVE THE FOUR-EYES RULE: approve''s conditional UPDATE carried an '
  'AND NOT EXISTS (...) conjunct refusing an approve by the same actor_id. AD-013 removed that '
  'rule; the column is retained and still written so it can be restored without a migration. '
  'See V0009 for the original predicate, and RISKS.md R-054.';

-- No DDL, no grant, no data change: a column comment is metadata only, so this migration is safe
-- to apply to a running system and nothing downstream depends on it having run.
