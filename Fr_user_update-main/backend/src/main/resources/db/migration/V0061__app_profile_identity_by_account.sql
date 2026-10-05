-- BL-032. A profile's identity is the account number alone; the branch is descriptive data.
--
-- V0005 declared profile_one_per_account as UNIQUE (branch_code, account_number) and called it
-- "the database-level expression of the campaign rule 'one update per account'". The core-banking
-- CheckAccount call carries no branch (AD-007, OQ-024): the account number is the customer's
-- identity bank-wide, so a customer who re-enters with the same account under a different branch
-- selection is the same customer and must land on the same profile. Under the composite
-- constraint they would not: findExisting would miss, a second row would be inserted, and the
-- one-update-per-account rule would hold per (branch, account) instead of per account. The product
-- owner settled this on 2026-09-04 (BACKLOG.md BL-032): account number alone.
--
-- Same constraint name on purpose. Existing code, tests and reports refer to it by name, and
-- V0005's own comment becomes literally true once the column list is just account_number.
--
-- Touches schema app only, not audit: no R-035 SET LOCAL fru.migration_in_progress flag needed.
-- Zero real rows exist; nothing to migrate or de-duplicate.
ALTER TABLE app.profile DROP CONSTRAINT profile_one_per_account;

ALTER TABLE app.profile DROP COLUMN branch_code;

ALTER TABLE app.profile
    ADD CONSTRAINT profile_one_per_account UNIQUE (account_number);


COMMENT ON CONSTRAINT profile_one_per_account ON app.profile IS
  'One profile per account number: the database-level expression of the campaign rule "one update per account" (customer.md Stage 1a). UNIQUE (account_number) since V0061 (BL-032); V0005 declared it over (branch_code, account_number).';

