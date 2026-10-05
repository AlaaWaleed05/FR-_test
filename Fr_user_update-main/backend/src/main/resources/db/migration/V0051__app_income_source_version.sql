-- S4-04 (AD-002f wire-contract version pinning, research report §6.1/§6.2) -- income_source is
-- validated at the application layer like admin_division (no DB-level FK -- see S3-11's own note
-- on admin_division for the same reasoning), and app.profile_income_source is a multi-row,
-- full-replace-per-submission table with no per-row version column. ref.profile_reference_version
-- needs ONE version value per (profile, list) to copy at submission time, so -- mirroring
-- admin_div_version's existing precedent of one scalar column recording "the version this whole
-- write was validated against" -- income_source gets the same shape here.
--
-- No FK, matching admin_div_version: item-code existence for income_source is checked in
-- DataEntryService, not by the database.
--
-- Touches schema app only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed.

ALTER TABLE app.profile_customer_data ADD COLUMN income_source_version int;
