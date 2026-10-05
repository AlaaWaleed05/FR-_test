-- Provenance-matrix versioning, per the S2-08 task's step 2 instruction to apply the same
-- pattern ref.reference_list_version / ref.profile_reference_version already use: record which
-- version of docs/journeys/field-provenance.md resolved a given profile's fields, so a later
-- change to the matrix does not leave historical profiles ambiguous about which rule applied.
--
-- Placed in `app`, not `ref`: this versions an internal derivation-rule document, never data
-- delivered to the mobile app, so it does not belong in the reference-data delivery contract
-- AD-002f governs.
--
-- field-provenance.md itself carries no version marker today -- out of scope to add one here
-- (the task explicitly says not to edit that file); see the session report for the marker this
-- table now expects the document to gain.

CREATE TABLE app.provenance_matrix_version (
  version        int PRIMARY KEY,
  effective_date date NOT NULL,
  note           text NOT NULL
);

INSERT INTO app.provenance_matrix_version (version, effective_date, note) VALUES
  (1, '2026-08-23',
   'docs/journeys/field-provenance.md as filed at S2-06 and amended into the schema at S2-08 '
   || '-- product-owner decisions dated 2026-08-23');

-- One row per profile, mirroring ref.profile_reference_version's one-row-per-list shape.
-- Written once by whatever backend code later resolves a profile's fields (no such code exists
-- yet -- OUT OF SCOPE for this session, same as every other entity/service/repository).
CREATE TABLE app.profile_provenance_matrix_version (
  profile_id  uuid PRIMARY KEY REFERENCES app.profile ON DELETE RESTRICT,
  version     int  NOT NULL REFERENCES app.provenance_matrix_version(version),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

GRANT SELECT ON app.provenance_matrix_version TO fru_app;
GRANT SELECT, INSERT ON app.profile_provenance_matrix_version TO fru_app;
