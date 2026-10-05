-- Closes AD-004 (docs/sessions/2026-09-02-s5-06-artifact-storage.md): artifact bytes live in
-- PostgreSQL, in this table, extended rather than duplicated into a new one -- V0008's own
-- design already gave app.artifact_ref its own purge-lifecycle boundary, separate from
-- app.profile and from audit.audit_artifact (7-year, append-only, a different lifecycle
-- entirely). TOAST already moves anything over ~2KB out-of-line automatically, so a query
-- against app.profile or app.scan_result never pays for image bytes it didn't ask for.
--
-- STORAGE EXTERNAL disables PostgreSQL's default compression attempt for this column: JPEGs
-- are already compressed, so EXTENDED's LZ pass (the type default) burns CPU on every write
-- for no space saved. EXTERNAL still allows out-of-line TOAST storage, just skips compression.
ALTER TABLE app.artifact_ref ADD COLUMN body bytea;
ALTER TABLE app.artifact_ref ALTER COLUMN body SET STORAGE EXTERNAL;

-- Derivatives (downscaled images for the operator's list and preview, per AD-004's own
-- requirement that originals are "never re-encoded" and derivatives are "clearly marked as
-- derived"): a separate row, self-referencing the original it was derived from. kind mirrors
-- the parent row's kind. NOT populated by any code this session -- no derivative-generation
-- code exists yet, same precedent as V0048's ref.reference_list_document sitting empty until
-- its publisher landed later. The eventual writer belongs with whoever builds the backoffice
-- image-viewing endpoint (R-046, still open).
ALTER TABLE app.artifact_ref
  ADD COLUMN derived_from_artifact_ref_id uuid REFERENCES app.artifact_ref(artifact_ref_id)
    ON DELETE CASCADE;

-- storage_key's meaning narrows now that AD-004 is closed: it no longer means "wherever
-- AD-004's eventual storage technology keeps the bytes" (that question is answered -- the
-- bytes are this row's own `body` column). It stays as an opaque, currently-unused
-- identifier, reserved for whatever addressing scheme R-046 (signed-URL-vs-blob, still open)
-- eventually needs for the backoffice image viewer.
COMMENT ON COLUMN app.artifact_ref.storage_key IS
  'Opaque identifier, currently unused by any reader. Previously "AD-004 owns its meaning" '
  'when storage technology was undecided; now AD-004 is closed (bytes live in this row''s '
  'own body column) and this column is reserved for R-046''s still-open addressing scheme.';

COMMENT ON COLUMN app.artifact_ref.body IS
  'The artifact bytes themselves, byte-identical to what was received -- never re-encoded. '
  'NULL once app.purge_abandoned_artifacts() has purged this row (state becomes ''purged''). '
  'Read only via app.artifact_read(), which verifies against sha256 before returning.';

COMMENT ON COLUMN app.artifact_ref.derived_from_artifact_ref_id IS
  'NULL for an original. Set on a derivative row (a downscaled image for list/preview '
  'contexts), pointing at the original artifact_ref row it was generated from.';

-- No new grant: fru_app already holds SELECT, INSERT, UPDATE on app.artifact_ref (V0010),
-- which covers new columns on the same table.
