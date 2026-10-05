-- Field 52 ("store BOTH [portraits], display BOTH", each labelled by origin) and field 49
-- (signature, mandatory, a file reference) per docs/journeys/field-provenance.md (S2-06/S2-08).
--
-- 'portrait' (singular, V0008) is replaced by 'portrait_uqudo'/'portrait_registry': the
-- operator UI derives the full label ("Uqudo -- passport" vs "Uqudo -- national ID") by
-- joining app.scan_result.document_type via cycle_id, so no extra column is needed to carry
-- which document was scanned.
--
-- 'signature' is added alongside the existing 'salary_certificate' kind, using the same shape:
-- profile_id set, cycle_id NULL -- neither is tied to an identity cycle (V0008's comment: "for
-- the salary certificate, which has no cycle"; a signature likewise belongs to the whole
-- profile, captured at stage 11 after liveness). Mandatoriness itself is a submission-time
-- application check, not a DB constraint -- consistent with how salary_certificate's
-- optionality is also not structurally different in this table.
ALTER TABLE app.artifact_ref DROP CONSTRAINT artifact_ref_kind_check;
ALTER TABLE app.artifact_ref ADD CONSTRAINT artifact_ref_kind_check CHECK (kind IN (
  'doc_front', 'doc_back', 'doc_front_frame', 'doc_back_frame',
  'portrait_uqudo', 'portrait_registry', 'face_audit_trail',
  'signature', 'salary_certificate'));
