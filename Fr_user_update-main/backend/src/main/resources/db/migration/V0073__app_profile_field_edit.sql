-- BL-135 / AD-015: per-field provenance storage. The back office becomes a data-entry tier, and
-- this is the record of what an operator keyed.
--
-- Nothing of this shape existed before. `grep -rn 'entered_by|edited_by|field_edit|source_flag'`
-- over db/migration/ returned nothing: app.profile.provenance (V0005) is ONE flag for the whole
-- profile, and app.profile_provenance_matrix_version (V0027) is not per-field provenance despite
-- its name -- it versions which edition of field-provenance.md resolved a profile.
--
-- THIS TABLE IS WHAT MAKES PROVENANCE DERIVED AGAIN. AD-022 deleted JdbcManualCompletionRepository,
-- the only writer of provenance='manual' anywhere in the system, so between S9-01 and this
-- migration the column was a constant 'digital' and the list filter, the export column and the
-- detail Alert were all dead (BL-155). The read sites now derive provenance as
--     p.provenance = 'manual' OR EXISTS (a row here for that profile)
-- rather than anything writing the column. The OR is load-bearing: the three profiles completed
-- manually BEFORE AD-022 carry 'manual' in the column and have no rows here, and they must stay
-- distinguishable. app.profile.provenance therefore becomes WRITE-NEVER, READ-STILL -- the same
-- disposition V0071 gave app.profile_status_history.is_manual_completion, for the same reason.
--
-- CURRENT STATE, NOT A LOG. PRIMARY KEY (profile_id, field_key) means one row per edited field,
-- overwritten by a later edit of the same field. The full history is already kept: every edit
-- writes a `profile_field_edited` audit event carrying previousValue and newValue on the profile's
-- hash chain, which is the tamper-evident record. A second append-only copy here would duplicate
-- it without adding evidence, and the two consumers -- the derived provenance predicate above, and
-- S9-03's per-field «معدَّل» marker on the printed form -- both ask "was this field edited", not
-- "how many times".
--
-- previous_value/new_value are kept even though the audit chain holds them, because the printed
-- form must render the marker without reading the audit schema: printedform code reads `app`, and
-- crossing into `audit` to render a document would put a PII-bearing join in the rendering path.
--
-- edited_by is text, not a FK to app.operator_user. It mirrors app.profile_status_history.actor_id
-- (V0009:11), which is also bare text: OperatorIdentity.operatorId is a USERNAME, and the same
-- decision applies here -- an operator row deleted later must not make a filed edit unreadable or
-- block the delete. No FK, deliberately.
--
-- field_number is denormalised from docs/journeys/field-provenance.md so a reader of this table
-- alone can map a row to the bank's paper form without resolving field_key against Java source.
-- It is the number, not a foreign key: field-provenance.md is a document, not a table.
CREATE TABLE app.profile_field_edit (
  profile_id     uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  field_key      text NOT NULL,
  field_number   int  NOT NULL CHECK (field_number BETWEEN 1 AND 54),
  previous_value text,
  new_value      text,
  edited_by      text NOT NULL,
  edited_at      timestamptz NOT NULL,
  PRIMARY KEY (profile_id, field_key),
  CONSTRAINT edited_by_present CHECK (edited_by <> '')
);

-- The derived-provenance predicate is an EXISTS on profile_id alone, which the primary key's own
-- index already serves as a prefix. No second index.

-- SELECT for the predicate and for S9-03's marker; INSERT and UPDATE for the upsert. No DELETE:
-- an edit that happened is not unmade by a later edit, and nothing in the journey retracts one.
GRANT SELECT, INSERT, UPDATE ON app.profile_field_edit TO fru_app;

COMMENT ON TABLE app.profile_field_edit IS
  'BL-135/AD-015. One row per field an operator has keyed on a profile, overwritten by a later edit of the same field. Two consumers: the derived app.profile.provenance predicate (a profile is ''manual'' iff the stored column says so OR a row exists here), and S9-03''s per-field «معدَّل» marker on the printed form. The tamper-evident history is the `profile_field_edited` audit chain, not this table.';

COMMENT ON COLUMN app.profile_field_edit.field_key IS
  'operator.domain.EditableField''s enum name. Only fields whose Source is S3 in docs/journeys/field-provenance.md and that the customer typed as FREE TEXT are ever written here -- the 6 Civil Registry fields, the 7 Uqudo fields and every list-picked value are permanently unwritable by anyone (AD-015 as narrowed 2026-09-14, AD-021). That bound is what R-054 relies on: no edit can invent a scan, a face match or a registry record.';

COMMENT ON COLUMN app.profile_field_edit.edited_by IS
  'The operator''s username (OperatorIdentity.operatorId), matching app.profile_status_history.actor_id''s shape. Deliberately not a foreign key onto app.operator_user -- see this migration''s header.';

-- ---------------------------------------------------------------------------------------------
-- The derived provenance rule, in ONE place.
--
-- AD-022 §2.1: "Provenance stays meaningful and must not be hardcoded to digital. AD-015 makes
-- provenance derived: `manual` the moment any single field was keyed by an operator." Four query
-- sites need that answer -- the single profile view, the profile list, the CSV export, and the
-- list's provenance FILTER -- and four copies of a CASE expression is how a rule drifts. It lives
-- here instead, next to the table it reads.
--
-- The `p_stored = 'manual'` disjunct is not redundant. Three profiles were completed manually
-- BEFORE AD-022 removed manual completion; they carry 'manual' in app.profile.provenance and have
-- no rows in app.profile_field_edit, and they must stay distinguishable. Dropping the disjunct
-- would silently re-label them 'digital' -- rewriting history to make a column tidy.
--
-- STABLE, not IMMUTABLE: it reads a table, so its result can change between statements. That is
-- what STABLE means and it is what lets the planner still use it in a WHERE clause.
--
-- The stored column becomes WRITE-NEVER, READ-STILL from here on -- the same disposition V0071
-- gave app.profile_status_history.is_manual_completion. Nothing writes app.profile.provenance;
-- every reader goes through this function.
CREATE FUNCTION app.derived_provenance(p_profile_id uuid, p_stored text) RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT CASE
           WHEN p_stored = 'manual'
             OR EXISTS (SELECT 1 FROM app.profile_field_edit e WHERE e.profile_id = p_profile_id)
           THEN 'manual'
           ELSE 'digital'
         END
$$;

REVOKE EXECUTE ON FUNCTION app.derived_provenance(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.derived_provenance(uuid, text) TO fru_app;

COMMENT ON FUNCTION app.derived_provenance(uuid, text) IS
  'BL-135/BL-155/AD-022. A profile is ''manual'' iff app.profile.provenance already said so (the pre-AD-022 manually completed profiles) OR an operator has keyed at least one field on it. The single source for the rule -- the profile view, the profile list, the CSV export and the list filter all call this rather than repeating a CASE expression. app.profile.provenance itself is write-never since V0073.';
