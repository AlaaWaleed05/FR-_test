-- AD-002f (docs/sessions/2026-08-31-research-ad-002f-reference-data.md §3.3 C4) — the 'SD'
-- literal in app.profile_customer_data.sudan_uses_codes (V0006) is one of the four places R-045
-- found the code hardcoded. Two of the other three are now tied together by V0049's declared
-- root + composite FK + constraint trigger; the third (DataEntryService.SUDAN_CODE) is replaced
-- in this session's Java diff by a ReferenceCatalog.currentRootItemCode lookup against those same
-- columns. This CHECK is deliberately NOT rewritten to match: it is a single-row CHECK
-- (references only home_country_code/home_locality_code on the row being checked), and
-- PostgreSQL does not support a CHECK that reads another table's data
-- [DOC postgresql.org/docs/18/ddl-constraints.html] -- exactly the constraint V0049's own header
-- comment cites as the reason a trigger, not a CHECK, enforces the cascade root. Left as a
-- now-stale literal, flagged here rather than silently left unexplained, per the report's
-- explicit recommendation (§3.3 C4): "recommend a comment, not a migration; it does not break
-- under an SDN root, it merely stops meaning anything."
--
-- Touches schema app only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed.

COMMENT ON CONSTRAINT sudan_uses_codes ON app.profile_customer_data IS
  'The ''SD'' this CHECK compares home_country_code against is no longer the source of truth for '
  '"is this address in Sudan" -- that invariant now lives in '
  'ref.reference_list_version.root_item_code (admin_division''s current version), declared data '
  'guarded by a composite FK + DEFERRABLE constraint trigger (V0049, AD-002f, R-045). This CHECK '
  'still runs and still happens to agree with that declared value today, but it is not rewritten '
  'to read it: PostgreSQL CHECK constraints cannot reference another table''s rows '
  '(see V0049''s header comment). If OQ-012''s corrected admin_division dataset ever ships a '
  'root other than ''SD'', this CHECK will silently stop meaning anything rather than fail -- '
  'the trigger is what fails loudly instead. See docs/components/reference-data.md.';
