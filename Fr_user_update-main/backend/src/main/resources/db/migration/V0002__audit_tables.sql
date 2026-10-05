-- Audit schema, per AD-005 report §4.5. No pgcrypto: gen_random_uuid() and sha256(bytea)
-- are both built into core PostgreSQL since v13/v14 — see the report §5/§6.

CREATE TABLE audit.audit_chain (
  chain_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  chain_kind text NOT NULL CHECK (chain_kind IN ('profile', 'operator', 'system')),
  subject_id text, -- profile_id, operator_id, or NULL for 'system'
  head_seq   bigint NOT NULL DEFAULT 0,
  head_hash  bytea NOT NULL, -- genesis = sha256(chain_id || chain_kind || subject); see V0003
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  closed_at  timestamptz,
  UNIQUE (chain_kind, subject_id)
);

CREATE TABLE audit.audit_artifact (
  artifact_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_id       uuid NOT NULL REFERENCES audit.audit_chain,
  kind           text NOT NULL CHECK (
    kind IN (
      'uqudo_scan_jws',
      'uqudo_face_jws',
      'civil_registry_response',
      'omni_check_request',
      'omni_check_response'
    )
  ),
  media_type     text NOT NULL, -- application/jose, application/json, text/xml...
  body           bytea, -- BYTE-IDENTICAL, never re-encoded; NULLable after a lawful purge
  byte_size      bigint NOT NULL,
  sha256         bytea NOT NULL, -- of the ORIGINAL body; survives a lawful purge
  received_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  body_purged_at timestamptz -- set when the 90-day PII rule erases the bytes
);

-- audit_event.profile_id is a PLAIN VALUE, deliberately with NO foreign key to app.profile.
-- The audit trail outlives the profile it describes (7-year retention against a 90-day
-- purge for abandoned profiles); a referential constraint would make that purge
-- impossible. See CLAUDE.md hard rules and the AD-005 report §4.5.
CREATE TABLE audit.audit_event (
  audit_event_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_id           uuid NOT NULL REFERENCES audit.audit_chain,
  seq                bigint NOT NULL, -- monotonic within the chain; trigger-set, see V0003
  occurred_at        timestamptz NOT NULL, -- SERVER time; trigger-set, client input ignored
  device_claimed_at  timestamptz, -- unverified device clock, explicitly marked as claimed
  event_type         text NOT NULL, -- controlled vocabulary — see AD-005 report §4.5
  actor_kind         text NOT NULL CHECK (actor_kind IN ('customer', 'operator', 'system')),
  actor_id           text,
  profile_id         uuid, -- NULL for account-check attempts that create no profile; no FK
  session_id         uuid,
  request_id         uuid,
  idempotency_key    text,
  payload_json       text NOT NULL, -- RFC 8785 canonical JSON, written once, what gets hashed
  payload            jsonb GENERATED ALWAYS AS (payload_json::jsonb) STORED, -- query-only
  artifact_id        bigint REFERENCES audit.audit_artifact,
  prev_hash          bytea NOT NULL,
  content_hash       bytea NOT NULL,
  row_hash           bytea NOT NULL,
  UNIQUE (chain_id, seq)
);
CREATE INDEX ON audit.audit_event USING brin (occurred_at);
CREATE INDEX ON audit.audit_event (profile_id, occurred_at);
CREATE INDEX ON audit.audit_event (event_type, occurred_at);

CREATE TABLE audit.audit_seal (
  seal_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sealed_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  chain_count    int NOT NULL,
  seal_hash      bytea NOT NULL, -- sha256 over (chain_id, head_seq, head_hash), ordered by chain_id
  prev_seal_hash bytea NOT NULL,
  exported_at    timestamptz,
  export_note    text -- where it was written: WORM bucket, printed register, email
);
