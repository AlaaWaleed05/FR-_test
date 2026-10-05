-- app.profile_customer_data additions per docs/journeys/field-provenance.md (S2-06/S2-08,
-- R-032), plus closing the two country-level FKs S2-07 deferred (V0022's header comment).
-- Follows V0013's exact precedent for a composite FK against ref.reference_item under MATCH
-- SIMPLE: a *_code column with its *_version NULL would otherwise satisfy the FK trivially
-- (any NULL member skips the check), so every nullable code/version pair gets an explicit
-- pairing CHECK alongside its FK.

ALTER TABLE app.profile_customer_data
  -- Field 10: free text, no source supplies it. Nullable like this table's other
  -- mandatory-per-journey columns (marital_status, education_level) -- stages 3-6 save
  -- progressively, so completeness is a submission-time application check, not a column
  -- constraint.
  ADD COLUMN ethnicity text,
  -- Field 11: mandatory, FK to the country list (S2-07). Same generated-list-constant +
  -- version + code shape occupation_code/occupation_version/occupation_list already use.
  ADD COLUMN country_of_residence_code text,
  ADD COLUMN country_of_residence_version int,
  ADD COLUMN country_of_residence_list text GENERATED ALWAYS AS ('country') STORED,
  -- Field 24: the Sudan state list when birth country is Sudan, free text otherwise --
  -- matching the home/work address pattern. Birth country (app.scan_result.birth_country_code,
  -- V0024) lives in a different table, system-derived rather than customer-entered, so unlike
  -- home/work address there is no same-table CHECK tying this to it: Postgres CHECK
  -- constraints cannot cross tables, and no such cross-table validation exists elsewhere in
  -- this schema. Left to the application layer -- which must compare against the alpha-3
  -- 'SDN', not 'SD': see V0024's note on birth_country_code's alphabet.
  ADD COLUMN birth_state_code text,
  ADD COLUMN birth_state_text text,
  -- Field 23's S3 fallback ("S2 placeOfBirth, else free text"): plain free text, no code/FK,
  -- matching how home_city/work_city are already plain free text rather than a code+text
  -- pair (only the country/state/locality levels get that treatment). Placed here rather than
  -- on app.scan_result alongside the S2 column it falls back from, for the same reason
  -- birth_state_text sits here and not on scan_result: this half is customer-entered, not
  -- system-derived.
  ADD COLUMN birth_city_text text,
  -- Closes the FK V0022's header comment deferred: the country level of the home address
  -- hierarchy. home_country_code itself already exists (V0006); only the version/list/FK are
  -- new.
  ADD COLUMN home_country_version int,
  ADD COLUMN home_country_list text GENERATED ALWAYS AS ('country') STORED,
  -- Same for the work address hierarchy's country level.
  ADD COLUMN work_country_version int,
  ADD COLUMN work_country_list text GENERATED ALWAYS AS ('country') STORED;

-- Field 19: "digits only, in SDG" -- constrained in the schema, not only the UI hint.
-- Verified live: a quoted digits-only string ('12000') is accepted and a quoted non-numeric
-- string ('abc' or '12.5') is rejected with "invalid input syntax for type bigint", because an
-- unknown-typed string literal is resolved via bigint's own strict-integer input parser. This
-- is the actual entry path for a value coming from a "digits only" text field. bigint does NOT
-- guarantee rejection of every fractional numeric INPUT irrespective of path: an already
-- numeric-typed value (an unquoted literal like 1234.56, or a BigDecimal bound by a future
-- JDBC caller) undergoes PostgreSQL's ordinary numeric->bigint assignment cast, which rounds
-- rather than errors -- verified live (`1234.56` stored as `1235`, no error). numeric(14,2)
-- previously allowed fractional SDG amounts outright; bigint at least forces the stored value
-- to be a whole number and rejects the string-typed non-digit case the UI actually produces.
ALTER TABLE app.profile_customer_data
  ALTER COLUMN monthly_expenses_sdg TYPE bigint USING monthly_expenses_sdg::bigint;

ALTER TABLE app.profile_customer_data ADD CONSTRAINT country_of_residence_code_version_paired
  CHECK ((country_of_residence_code IS NULL) = (country_of_residence_version IS NULL));
ALTER TABLE app.profile_customer_data ADD CONSTRAINT home_country_code_version_paired
  CHECK ((home_country_code IS NULL) = (home_country_version IS NULL));
ALTER TABLE app.profile_customer_data ADD CONSTRAINT work_country_code_version_paired
  CHECK ((work_country_code IS NULL) = (work_country_version IS NULL));

ALTER TABLE app.profile_customer_data ADD CONSTRAINT country_of_residence_fk
  FOREIGN KEY (country_of_residence_list, country_of_residence_version, country_of_residence_code)
  REFERENCES ref.reference_item (list_code, version, item_code);
ALTER TABLE app.profile_customer_data ADD CONSTRAINT home_country_fk
  FOREIGN KEY (home_country_list, home_country_version, home_country_code)
  REFERENCES ref.reference_item (list_code, version, item_code);
ALTER TABLE app.profile_customer_data ADD CONSTRAINT work_country_fk
  FOREIGN KEY (work_country_list, work_country_version, work_country_code)
  REFERENCES ref.reference_item (list_code, version, item_code);
