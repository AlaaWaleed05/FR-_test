-- Closes AD-004's own open item on app.scan_result.face_reference_image (V0041/V0042, S3-13,
-- R-047). Before this migration, app.artifact_ref (S3-12) already inserted a row for
-- kind='portrait_uqudo' in the SAME transaction this column was written -- but that row held
-- only a checksum and metadata, never bytes (AD-004 was unresolved). V0053 (this session)
-- gives that same row a body column, so as of THIS migration set -- not retroactively -- the
-- insert that used to populate the transient bridge column also durably stores the bytes in
-- artifact_ref. Going forward, that makes the bridge column redundant on all three of V0042's
-- clearing paths; it does not mean those triggers were clearing a spare copy in the past --
-- before this session, they were clearing the only durable byte-copy of the portrait this
-- backend held. See docs/sessions/2026-09-02-s5-06-artifact-storage.md and RISKS.md R-047
-- (retired) for the full per-path reasoning -- none of the three protected anything beyond
-- that transient copy, which is why dropping them now is safe.
--
-- Order matters: drop both triggers, then both functions, then the column. A PL/pgSQL
-- function body is not statically re-validated when a column it references is dropped
-- elsewhere -- leaving V0042's functions in place pointing at a dropped column would fail at
-- next invocation, not at migration time.
DROP TRIGGER identity_cycle_clears_face_reference_image ON app.identity_cycle;
DROP TRIGGER profile_clears_face_reference_image_on_terminal ON app.profile;
DROP FUNCTION app.clear_face_reference_image_on_supersede();
DROP FUNCTION app.clear_face_reference_image_on_terminal();

ALTER TABLE app.scan_result DROP COLUMN face_reference_image;
