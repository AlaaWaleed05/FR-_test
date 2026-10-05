-- ref schema, per AD-005 report §4.4 (reference-data version registry) and §9/§10
-- (Arabic fold, sort_ordinal, contentHash). Schema `ref` itself and its base REVOKE/
-- USAGE hygiene already exist since V0001; this migration adds its tables and functions.

-- Needed for the search_ar trigram index below (AD-005 report §10). Trusted extension
-- since PostgreSQL 13 -- installable by fru_migrator (schema owner, not superuser) without
-- delegation, unlike the audit schema's Layer-3 event trigger (see V0004's comment).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE ref.reference_list (
  list_code text PRIMARY KEY,   -- occupation | branch | admin_division | income_source
                                -- | rejection_reason | education_level
  name_ar text NOT NULL, name_en text NOT NULL,
  is_hierarchical boolean NOT NULL DEFAULT false
);

CREATE TABLE ref.reference_list_version (
  list_code    text NOT NULL REFERENCES ref.reference_list,
  version      int  NOT NULL CHECK (version > 0),
  published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  content_hash bytea NOT NULL,     -- sha256 over the canonical serialisation of all items
  item_count   int   NOT NULL,
  source_note  text,               -- provenance of the data, e.g. the supplied workbook
  is_current   boolean NOT NULL DEFAULT false,
  PRIMARY KEY (list_code, version)
);
CREATE UNIQUE INDEX ref_one_current ON ref.reference_list_version (list_code) WHERE is_current;

-- ref.ar_fold -- orthographic equivalence, NOT collation (AD-005 report §10: no collation
-- strength collapses ة/ه or ى/ي). Every equality/substring comparison uses this fold;
-- display ORDER BY uses COLLATE "ar-x-icu" instead, computed once into sort_ordinal below
-- at seed/publication time -- the device never attempts Arabic collation.
--
-- IMMUTABLE verified live before writing this function, per the report's own open item:
--   SELECT proname, provolatile FROM pg_proc
--   WHERE proname IN ('translate','regexp_replace','lower','normalize');
-- Every overload of all four returned 'i' (immutable) on this PostgreSQL 18 install (S2-03
-- session report §2) -- so the report's exact formula, including lower(), is safe to mark
-- IMMUTABLE with no fallback needed.
CREATE FUNCTION ref.ar_fold(t text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS $$
  SELECT lower(
    regexp_replace(
      translate($1,
        --  أ  إ  آ  ٱ  ة  ى     Arabic-Indic digits
        'أإآٱةى' || '٠١٢٣٤٥٦٧٨٩' || '۰۱۲۳۴۵۶۷۸۹',
        'ااااهي' || '0123456789'   || '0123456789'),
      -- tatweel, harakat, superscript alef, extended marks, bidi/zero-width controls
      '[ـً-ْٰۖ-ۭ​-‏‪-‮]', '', 'g')
  );
$$;

CREATE TABLE ref.reference_item (
  list_code    text NOT NULL,
  version      int  NOT NULL,
  item_code    text NOT NULL,             -- '86', '2'..'26', locality_id, 'REJ-03'
  parent_code  text,                      -- admin hierarchy: country->state->locality
  label_ar     text NOT NULL,
  label_en     text,
  -- Written directly as the generated column the report's §10 note describes it becoming
  -- ("populated as a generated column: ... GENERATED ALWAYS AS (ref.ar_fold(label_ar))
  -- STORED"), rather than the ALTER ... SET DEFAULT NULL placeholder the report prints
  -- first for search_ar in §4.4's own DDL block.
  search_ar    text GENERATED ALWAYS AS (ref.ar_fold(label_ar)) STORED,
  search_en    text,
  sort_ordinal int  NOT NULL,             -- server-computed under ar-x-icu; see §9
  is_active    boolean NOT NULL DEFAULT true,   -- false hides 133/139/135 duplicates
  extra        jsonb,                     -- customer-facing message for REJ codes, etc.
  PRIMARY KEY (list_code, version, item_code),
  FOREIGN KEY (list_code, version) REFERENCES ref.reference_list_version (list_code, version)
);
CREATE INDEX ON ref.reference_item (list_code, version, parent_code);
CREATE INDEX reference_item_search_trgm ON ref.reference_item USING gin (search_ar gin_trgm_ops);

-- Which version each submission actually used.
CREATE TABLE ref.profile_reference_version (
  profile_id uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  list_code  text NOT NULL,
  version    int  NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (profile_id, list_code),
  FOREIGN KEY (list_code, version) REFERENCES ref.reference_list_version (list_code, version)
);
