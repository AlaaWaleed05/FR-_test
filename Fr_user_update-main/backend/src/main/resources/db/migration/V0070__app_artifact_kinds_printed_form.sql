-- BL-132: the printed update form (استمارة تحديث البيانات) becomes a stored artifact.
--
-- TWO kinds, not one, and that is wayfinder ticket 05 decision 2 as amended by ticket 10
-- decision 7: the form ships in an unattributed and an attributed variant, which differ in
-- content and in disclosure. app.artifact_ref has no generic metadata column, and `kind` is
-- already the enumeration of what an artifact IS, so a stored row says which of the two forms
-- it holds without anyone opening the PDF. The enum lives in code as
-- printedform.domain.PrintedFormVariant#artifactKind, which is where these two strings come
-- from -- keep the two in step.
--
-- Same shape as V0026, deliberately: the constraint is dropped and re-added whole rather than
-- amended in place, because a CHECK cannot be extended.
ALTER TABLE app.artifact_ref DROP CONSTRAINT artifact_ref_kind_check;
ALTER TABLE app.artifact_ref ADD CONSTRAINT artifact_ref_kind_check CHECK (kind IN (
  'doc_front', 'doc_back', 'doc_front_frame', 'doc_back_frame',
  'portrait_uqudo', 'portrait_registry', 'face_audit_trail',
  'signature', 'salary_certificate',
  'printed_form', 'printed_form_attributed'));

-- PROFILE-KEYED, cycle_id NULL -- the shape 'signature' and 'salary_certificate' already use
-- (V0008's own comment: "for the salary certificate, which has no cycle"). A print belongs to
-- the whole profile and to the moment an operator made it, never to an identity cycle.
--
-- AND DELIBERATELY NO UNIQUE INDEX, unlike V0046 (one signature per profile) and V0059 (one
-- salary certificate per profile). Ticket 05 decision 2: every print is its own artifact and
-- nothing is superseded -- a reprint has a different timestamp and possibly a different
-- operator, so it is genuinely a different document, and the audit question is always "what
-- did THIS operator hold, at that moment".
--
-- Why V0008's `UNIQUE (cycle_id, kind)` does not forbid that, checked rather than assumed:
-- PostgreSQL's default for a unique constraint is NULLS DISTINCT, so two rows both holding
-- (NULL, 'printed_form') do not conflict -- NULL is never equal to NULL. The constraint is
-- what makes a CYCLE-keyed print impossible to reprint, which is exactly why ticket 05
-- decision 2 calls the profile keying load-bearing rather than incidental. There is a live
-- assertion of this in AppSchemaConnectivityIntegrationTest
-- (#aProfileMayHoldManyPrintedFormsBecauseTheUniqueConstraintTreatsNullCyclesAsDistinct),
-- because a silent regression here would turn a second print into a 500 rather than a bug
-- anyone would spot in review.
--
-- Retention, stated so the next reader does not have to re-derive it: V0055's
-- app.purge_abandoned_artifacts() only reaches rows whose profile is 'abandoned', and ticket
-- 05 decision 8 restricts printing to 'submitted' and 'approved'. No stored print can ever sit
-- on a profile that later abandons, so the purge is not a threat to one and a printed form is
-- retained indefinitely alongside its profile until the profile-retention work in ticket 05
-- decision 3 exists to say otherwise.
COMMENT ON CONSTRAINT artifact_ref_kind_check ON app.artifact_ref IS
  'The artifact kinds this system stores. printed_form/printed_form_attributed (V0070, BL-132) '
  'are the two variants of the operator''s printed update form: profile-keyed with cycle_id '
  'NULL, and with NO per-profile unique index, because every print is its own artifact and '
  'reprinting is the expected case (wayfinder ticket 05 decision 2).';

-- No new grant: fru_app already holds SELECT, INSERT, UPDATE on app.artifact_ref (V0010), and
-- EXECUTE on app.artifact_read() (V0054), which is the whole of what the print path needs --
-- the same note V0053 and V0063 record for this table.
