-- app schema, per AD-005 report §4.1 (customer-entered / device-authoritative fields).

CREATE TABLE app.profile_customer_data (      -- DEVICE-AUTHORITATIVE on reconcile
  profile_id            uuid PRIMARY KEY REFERENCES app.profile ON DELETE RESTRICT,
  phone_number          text,
  email_address         text,
  sex_declared          text CHECK (sex_declared IN ('m','f')),  -- interface only; NOT stored truth
  marital_status        text CHECK (marital_status IN ('single','married','divorced','widowed')),
  spouse_name           text,
  has_children          boolean,
  children_count        smallint CHECK (children_count IS NULL OR children_count >= 1),
  education_level       smallint CHECK (education_level BETWEEN 1 AND 7),

  occupation_code       text,
  occupation_version    int,
  -- Generated constant so a composite FK to a VERSIONED reference item is possible once the
  -- ref schema exists (ref.reference_item(list_code, version, item_code) — AD-005 report §4.1).
  occupation_list       text GENERATED ALWAYS AS ('occupation') STORED,

  monthly_expenses_sdg  numeric(14,2) CHECK (monthly_expenses_sdg >= 0),
  identity_type         text CHECK (identity_type IN ('passport','national_id')),

  home_country_code     text, home_state_code text, home_locality_code text,
  home_state_text       text, home_locality_text text,          -- non-Sudan fallback
  home_city text, home_area text, home_street text, home_block text, home_house_no text,

  employer_name         text,
  work_country_code     text, work_state_code text, work_locality_code text,
  work_state_text       text, work_locality_text text,
  work_city text, work_area text, work_street text, work_block text,   -- no house number

  admin_div_version     int,
  updated_at            timestamptz NOT NULL,
  device_claimed_at     timestamptz,     -- UNVERIFIED device clock, recorded as claimed

  -- occupation_fk (composite FK to ref.reference_item) is DEFERRED to S2-03: the ref schema
  -- and ref.reference_item do not exist yet in this session (OUT OF SCOPE — see the S2-02
  -- task and PROJECT_PLAN.md AD-005). Add in an S2-03 migration:
  --   ALTER TABLE app.profile_customer_data ADD CONSTRAINT occupation_fk
  --     FOREIGN KEY (occupation_list, occupation_version, occupation_code)
  --     REFERENCES ref.reference_item (list_code, version, item_code);
  CONSTRAINT sudan_uses_codes CHECK (
    home_country_code IS DISTINCT FROM 'SD' OR home_locality_code IS NOT NULL),
  CONSTRAINT children_consistent CHECK (
    has_children IS NOT TRUE OR children_count IS NOT NULL)
);

CREATE TABLE app.profile_income_source (
  profile_id  uuid NOT NULL REFERENCES app.profile_customer_data(profile_id) ON DELETE CASCADE,
  source_code text NOT NULL,
  is_primary  boolean NOT NULL DEFAULT false,
  other_text  text,
  PRIMARY KEY (profile_id, source_code),
  CONSTRAINT other_needs_text CHECK (source_code <> 'OTHER' OR other_text IS NOT NULL)
);
-- "One selection must be marked primary" (customer.md stage 4), enforced by the database:
CREATE UNIQUE INDEX profile_income_one_primary
  ON app.profile_income_source (profile_id) WHERE is_primary;
