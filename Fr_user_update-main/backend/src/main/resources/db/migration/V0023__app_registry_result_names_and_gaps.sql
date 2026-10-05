-- Amends app.registry_result to match docs/journeys/field-provenance.md (S2-06/S2-08, R-032).
-- The V0008 shape assumed a single Arabic name string, a single English name string, a single
-- mother's-name string, and a "citizenship" field. The observed Civil Registry contract
-- (docs/components/civil-registry.md, 2026-08-23 sample) returns none of those shapes.

ALTER TABLE app.registry_result
  DROP COLUMN full_name_ar,
  DROP COLUMN full_name_en,
  DROP COLUMN mother_name,
  -- No field in the observed CR response corresponds to "citizenship" -- nationality (field 4)
  -- is Uqudo-sourced (S2) and already lives on app.scan_result.nationality.
  DROP COLUMN citizenship,
  -- birth_country/birth_state/birth_city were wrongly placed here: the CR response has no
  -- birth-place field at all (civil-registry.md's observed fields: IDENTITY_NUMBER, the two
  -- name chains, FIRST_NAMES/LAST_NAME, BIRTH_DATE, GENDER, ADDRESS, PHOTOGRAPH). Per
  -- field-provenance.md, birth country and city are S2 (Uqudo) and birth state is S3
  -- (customer entry, no source) -- see V0024 and V0025.
  DROP COLUMN birth_country,
  DROP COLUMN birth_state,
  DROP COLUMN birth_city;

ALTER TABLE app.registry_result
  -- Field 5: the registry's four-part Arabic paternal chain (NAME, FATHER_NAME,
  -- GRAND_FATHER_NAME, GRE_GRA_FATHER_NAME). Store the parts, not a composed string --
  -- composition is always possible, decomposition is not.
  ADD COLUMN name_ar_given text,
  ADD COLUMN name_ar_father text,
  ADD COLUMN name_ar_grandfather text,
  ADD COLUMN name_ar_great_grandfather text,
  -- Field 8: the maternal chain, matching the paternal chain -- four parts, not one
  -- (MOTHER_NAME, MOT_FATHER_NAME, MOT_GRA_FATHER_NAME, MOT_GRE_GRA_FATHER_NAME).
  ADD COLUMN name_ar_mother text,
  ADD COLUMN name_ar_mother_father text,
  ADD COLUMN name_ar_mother_grandfather text,
  ADD COLUMN name_ar_mother_great_grandfather text,
  -- Field 6: the registry's separate Latin pair (FIRST_NAMES, LAST_NAME) -- absent from the
  -- older national-ID card version, so the registry covers a real gap.
  ADD COLUMN first_names_en text,
  ADD COLUMN last_name_en text,
  -- Field 9 gap-fill: the matrix's Source column is S1 for sex ("the bank uses this label for
  -- sex... Registry GENDER, a lowercase single character") but no column anywhere held an
  -- S1-authoritative value -- app.profile_customer_data.sex_declared is explicitly documented
  -- as "interface only; NOT stored truth" (V0006), and app.scan_result.sex_on_document (V0008)
  -- is S2, not S1. This is what customer.md Stage 3 means by "the Civil Registry value is what
  -- the profile stores".
  ADD COLUMN sex_registry char(1) CHECK (sex_registry IN ('m','f')),
  -- Field 21 gap-fill: Source is S1 ("the registry returns DD/MM/YYYY, not ISO 8601 -- parse
  -- explicitly"), but only app.scan_result.date_of_birth (S2) existed before this migration.
  -- The registry's BIRTH_DATE is a genuinely independent value from the document's, unlike the
  -- identity-number echo below, so it gets its own column rather than being derived.
  ADD COLUMN date_of_birth date,
  -- The registry's one comma-separated free-text Arabic address string (civil-registry.md's
  -- ADDRESS field), stored for comparison. Per field-provenance.md fields 35-42: "The registry
  -- string is stored alongside for comparison and never populates the profile address."
  ADD COLUMN raw_address_ar text;

-- Field 7 (national number) deliberately gets NO new column here: civil-registry.md documents
-- IDENTITY_NUMBER as "Echo of the request NID" -- it is definitionally identical to
-- app.scan_result.identity_number (the value that generated the query), so the matrix's S1
-- precedence has nothing to resolve. A duplicate column would just be redundant storage that
-- could never legitimately diverge from what is already there.
