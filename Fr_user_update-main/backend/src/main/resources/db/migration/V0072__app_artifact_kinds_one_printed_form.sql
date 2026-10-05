-- AD-022 (S9-01, 2026-09-16) ruling 2: the printed form's ATTRIBUTED/UNATTRIBUTED variant choice is
-- removed. There is ONE form, and therefore one artifact kind.
--
-- V0070 admitted two kinds, 'printed_form' and 'printed_form_attributed', because ticket 05
-- decision 2 wanted a stored row to say which of the two forms it held without anyone opening the
-- PDF. With one form there is nothing left to distinguish, and the product owner ruled (2026-09-16)
-- that the old kind is purged rather than kept readable alongside the new one.
--
-- V0070 IS NOT EDITED. It is applied -- staging has run it -- and BL-144 is the rule: an applied
-- migration is never amended, it is superseded by a later one.
--
-- THE UPDATE MUST COME FIRST, and this ordering is the whole correctness of this migration.
-- ALTER TABLE ... ADD CONSTRAINT validates existing rows by default, so re-adding the narrowed
-- constraint while a single 'printed_form_attributed' row survives fails outright with a check
-- violation and takes the whole migration down. Re-labelling first leaves nothing for the new
-- constraint to reject.
--
-- RE-LABELLED, NOT DELETED. The rows are filed bank documents: an operator printed them, the audit
-- trail records that they did, and app.artifact_ref is how the re-download endpoint still reaches
-- them. Deleting them would destroy the PDF an operator is recorded as having taken. What the purge
-- costs is the DISTINCTION -- after this migration a previously attributed print is indistinguishable
-- from an unattributed one by kind alone. That is the accepted cost of the ruling, and it is
-- recoverable from the audit trail if it is ever needed: every print before 2026-09-16 wrote a
-- `variant` member into its form_printed payload, and audit.audit_event is append-only.
UPDATE app.artifact_ref SET kind = 'printed_form' WHERE kind = 'printed_form_attributed';

-- Same shape as V0070 and V0026, deliberately: the constraint is dropped and re-added whole rather
-- than amended in place, because a CHECK cannot be extended or narrowed.
ALTER TABLE app.artifact_ref DROP CONSTRAINT artifact_ref_kind_check;
ALTER TABLE app.artifact_ref ADD CONSTRAINT artifact_ref_kind_check CHECK (kind IN (
  'doc_front', 'doc_back', 'doc_front_frame', 'doc_back_frame',
  'portrait_uqudo', 'portrait_registry', 'face_audit_trail',
  'signature', 'salary_certificate',
  'printed_form'));

-- The in-code half of this pair is printedform.domain.PrintedFormRepository#PRINTED_FORM_KIND,
-- which replaced PrintedFormVariant#artifactKind. Keep the two in step: a mismatch is a constraint
-- violation at print time, not at start-up.

-- DROP CONSTRAINT also discards V0070's COMMENT ON CONSTRAINT, so it is re-issued here rather than
-- silently lost. Nothing asserts a constraint comment exists, so no gate would have caught its
-- disappearance -- found by @agent-reviewer on this session's own diff.
COMMENT ON CONSTRAINT artifact_ref_kind_check ON app.artifact_ref IS
  'The artifact kinds this system stores. printed_form (V0070, BL-132; narrowed to one kind by '
  'V0072 when AD-022 removed the ATTRIBUTED/UNATTRIBUTED variant choice) is the operator''s '
  'printed update form: profile-keyed with cycle_id NULL, and with NO per-profile unique index, '
  'because every print is its own artifact and reprinting is the expected case (wayfinder ticket '
  '05 decision 2). Prints taken before 2026-09-16 under printed_form_attributed were re-labelled '
  'to printed_form by V0072 and remain re-downloadable; which variant they were is recoverable '
  'only from the form_printed audit payload.';
