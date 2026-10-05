-- S2-09: field 22 (birth country) source correction, per docs/journeys/field-provenance.md
-- Version 2 (2026-08-27). S2-08 (V0024) implemented Version 1: a generated column mirroring
-- issuing_country (the MRZ `issuer`). That was wrong -- the MRZ issuer is the document's
-- issuing country, not where the person was born, and since only Sudanese documents are
-- accepted it would read 'SDN' for every customer including one born in Cairo. Neither Uqudo
-- (placeOfBirth is the city, field 23) nor the Civil Registry supplies a true birth country, so
-- this is now customer entry against the ISO 3166 list, same as fields 11/28/35.

-- Drop the S2-08 column and its generated dependency on issuing_country.
ALTER TABLE app.scan_result DROP COLUMN birth_country_code;

-- issuing_country (V0008, field 48) is untouched by this migration and remains exactly what it
-- always was: the MRZ issuer, alpha-3. After this drop, it is the ONLY alpha-3 country column
-- in the whole schema -- every other *_country_code column (country_of_residence, home, work,
-- and now birth) is alpha-2 against the ref.reference_item 'country' list. This is correct and
-- deliberate: do not "fix" issuing_country to alpha-2 later, and do not assume any
-- *_country_code column matches it without checking which alphabet it uses.

-- New customer-entered birth country (field 22), following V0025's exact
-- country_of_residence_code/_version/_list + pairing CHECK + FK pattern -- same alpha-2 ISO
-- 3166 list and alphabet as fields 11, 28 and 35.
ALTER TABLE app.profile_customer_data
  ADD COLUMN birth_country_code text,
  ADD COLUMN birth_country_version int,
  ADD COLUMN birth_country_list text GENERATED ALWAYS AS ('country') STORED;

ALTER TABLE app.profile_customer_data ADD CONSTRAINT birth_country_code_version_paired
  CHECK ((birth_country_code IS NULL) = (birth_country_version IS NULL));

ALTER TABLE app.profile_customer_data ADD CONSTRAINT birth_country_fk
  FOREIGN KEY (birth_country_list, birth_country_version, birth_country_code)
  REFERENCES ref.reference_item (list_code, version, item_code);

-- Supersedes V0025's own comment on birth_state_code (that migration is applied and
-- checksum-protected, so the correction is recorded here, forward, rather than by editing it).
-- V0025 told a future implementer that birth country "lives in a different table,
-- system-derived rather than customer-entered" and that an app-layer Sudan check must compare
-- against the alpha-3 'SDN' (V0024's now-dropped column). Both statements are false as of this
-- migration: birth_country_code now lives on THIS table, profile_customer_data, alongside
-- birth_state_code, and the correct Sudan test is `birth_country_code = 'SD'` (alpha-2) on the
-- same row -- not 'SDN'.
--
-- That also means the cross-table barrier V0025 cited as the sole reason no CHECK ties
-- birth_state_code to birth country no longer exists -- both columns are on
-- profile_customer_data now, and V0006's sudan_uses_codes CHECK is exactly this precedent for
-- the home address. Deliberately NOT added here: docs/journeys/customer.md's Stage 3 field
-- list (where sex, ethnicity and country of residence are collected) does not collect birth
-- country, birth state or birth city at all -- fields 22-24 have no journey stage yet, a gap
-- that predates this migration (S2-06/S2-08 already made 23/24 customer-entry-capable with
-- the same gap). A CHECK constraining a field the journey cannot yet submit is premature; add
-- it together with whichever session wires that journey stage.
