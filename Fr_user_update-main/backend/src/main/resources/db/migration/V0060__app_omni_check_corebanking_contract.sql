-- S3-02 (re-scoped), BL-031, AD-007. app.omni_check (V0008 lines 58-65) still models the
-- superseded Oracle ProcessOmniCheckAct contract: branch_code NOT NULL and result_code
-- CHECK (1, 2, -1). After AD-007 the check is an HTTPS/JSON CheckAccount call to the bank's
-- middleware that carries NO branch (OQ-024), and whose code set is closed by decision at
-- 1 found / 0 not found / -1 system error (OQ-025) -- so 0 could not be stored, 2 can never
-- occur, and branch_code cannot honestly be required.
--
-- Touches schema app only, not audit: no R-035 SET LOCAL fru.migration_in_progress flag needed.
--
-- Zero rows exist in this table and nothing in the codebase writes it -- S3-01 deliberately
-- audited the attempt instead, and the re-scoped S3-02 keeps it that way, because
-- docs/journeys/customer.md Stage 1a states that nothing is written to the profile database at
-- this stage. This migration only makes the table's constraints truthful about the contract; the
-- open question of whether the table should be written at all, or dropped, is recorded on
-- docs/components/core-banking.md, not settled here.
--
-- Constraint name: PostgreSQL auto-names an inline column CHECK <table>_<column>_check; V0026 is
-- the in-repo precedent for dropping and re-adding one by that name.

ALTER TABLE app.omni_check ALTER COLUMN branch_code DROP NOT NULL;

ALTER TABLE app.omni_check DROP CONSTRAINT omni_check_result_code_check;
ALTER TABLE app.omni_check ADD CONSTRAINT omni_check_result_code_check
  CHECK (result_code IN (1, 0, -1));

COMMENT ON COLUMN app.omni_check.branch_code IS
  'The branch the customer selected at Stage 1a. NOT sent to the core-banking CheckAccount call (OQ-024): branch is profile data only. Nullable since V0060; V0008 required it because the superseded Oracle contract took branch + account.';

COMMENT ON COLUMN app.omni_check.profile_id IS
  'NULL when no profile exists for this account at check time: for every 0 (not found) and -1 (system error) outcome, and for a 1 (found) outcome on an account with no profile yet. V0008''s inline comment said "NULL for -1 and 2 outcomes", naming the superseded Oracle code set (2 = inactive); corrected here because V0008 is already applied.';

COMMENT ON COLUMN app.omni_check.result_code IS
  'The middleware CheckAccount Response_Code, verbatim: 1 Account Found, 0 Account not Found, -1 System Error (AD-007, OQ-025, closed by product-owner decision on the observed set). Any other value is fail-closed in the adapter and never reaches this table.';
