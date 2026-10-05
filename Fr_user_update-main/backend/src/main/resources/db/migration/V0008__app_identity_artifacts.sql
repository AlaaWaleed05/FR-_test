-- app schema, per AD-005 report §4.2 (system-derived identity data, with supersede semantics).

-- The unit that gets superseded when a device-less resume forces a rescan
-- (customer.md "System-derived identity artifacts are not inherited").
CREATE TABLE app.identity_cycle (
  cycle_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id   uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  seq          int  NOT NULL,
  state        text NOT NULL CHECK (state IN ('pending','active','superseded','abandoned')),
  opened_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  accepted_at  timestamptz,
  superseded_at timestamptz,
  UNIQUE (profile_id, seq)
);
CREATE UNIQUE INDEX identity_one_active
  ON app.identity_cycle (profile_id) WHERE state = 'active';

CREATE TABLE app.scan_result (
  cycle_id        uuid PRIMARY KEY REFERENCES app.identity_cycle ON DELETE RESTRICT,
  uqudo_jti       text NOT NULL UNIQUE,       -- global replay guard (uqudo-sdk.md)
  document_type   text NOT NULL,              -- SDN_ID | PASSPORT, as requested and echoed
  card_variant    text,                       -- SDN_ID previous | latest
  identity_number text NOT NULL,              -- the Civil Registry lookup key
  document_number text,                       -- MRZ value — NOT the registry key
  mrz_verified    boolean,                    -- integrity signal
  nationality     text,
  sex_on_document text,                       -- THIS is the stored sex, not sex_declared
  date_of_birth   date,
  date_of_issue   date, date_of_expiry date,  -- expiry gates nothing (stage 9, RESOLVED)
  place_of_issue  text, issuing_country text,
  name_ar_on_document text, name_en_on_document text, blood_type text,
  id_print_score smallint, id_screen_score smallint, id_photo_tampering_score smallint,
  received_at     timestamptz NOT NULL
);

CREATE TABLE app.face_result (
  cycle_id           uuid PRIMARY KEY REFERENCES app.identity_cycle ON DELETE RESTRICT,
  uqudo_jti          text NOT NULL UNIQUE,
  face_session_id    text NOT NULL,           -- minted by us, validated back out of the JWS
  match              boolean NOT NULL,
  match_level        smallint NOT NULL CHECK (match_level BETWEEN 1 AND 5),
  threshold_applied  smallint NOT NULL,       -- server-side, starts at 3; recorded per row
  passed             boolean  GENERATED ALWAYS AS (match AND match_level >= threshold_applied) STORED,
  received_at        timestamptz NOT NULL
);
-- NOTE: there is deliberately NO liveness score column. Uqudo returns none; a liveness
-- failure produces no JWS at all and is recorded in audit as a terminated attempt (AD-002a).

CREATE TABLE app.registry_result (
  cycle_id        uuid PRIMARY KEY REFERENCES app.identity_cycle ON DELETE RESTRICT,
  state           text NOT NULL CHECK (state IN ('pending','ok','not_found','unreachable')),
  queried_at      timestamptz,
  attempts        smallint NOT NULL DEFAULT 0,
  full_name_ar text, full_name_en text, mother_name text, citizenship text,
  birth_country text, birth_state text, birth_city text
);

CREATE TABLE app.omni_check (            -- ProcessOmniCheckAct outcome; also for no-profile attempts
  omni_check_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  profile_id    uuid REFERENCES app.profile,     -- NULL for -1 and 2 outcomes
  branch_code   text NOT NULL,
  account_hash  bytea NOT NULL,                  -- sha256; the raw number lives in audit only
  result_code   smallint NOT NULL CHECK (result_code IN (1, 2, -1)),
  called_at     timestamptz NOT NULL
);

-- References only. Bytes live wherever AD-004 decides.
CREATE TABLE app.artifact_ref (
  artifact_ref_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cycle_id      uuid REFERENCES app.identity_cycle ON DELETE RESTRICT,
  profile_id    uuid REFERENCES app.profile,     -- for the salary certificate, which has no cycle
  kind          text NOT NULL CHECK (kind IN ('doc_front','doc_back','doc_front_frame',
                    'doc_back_frame','portrait','face_audit_trail','salary_certificate')),
  uqudo_image_id text,
  uqudo_checksum text,                           -- "sha256:<digest>" exactly as supplied
  storage_key   text NOT NULL,                   -- opaque; AD-004 owns its meaning
  content_type  text NOT NULL,
  byte_size     bigint NOT NULL,
  sha256        bytea  NOT NULL,
  state         text NOT NULL CHECK (state IN ('staged','committed','purged')),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (cycle_id, kind)
);
-- The staged/committed state is the reconciliation hook for the stage 8 chain (AD-005 report
-- §6B): rows are allocated before the download so the storage key is known, and only promoted
-- when the accept transaction commits. A sweeper (not built this session) reclaims anything
-- left staged.
