-- app schema, per AD-005 report §4.1 (per-channel verification state).

CREATE TABLE app.profile_channel (            -- BACKEND-AUTHORITATIVE
  profile_id  uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  channel     text NOT NULL CHECK (channel IN ('sms','whatsapp','email')),
  state       text NOT NULL CHECK (state IN ('verified','declined','unverified')),
  verified_at timestamptz,
  locked_at   timestamptz,
  wrong_code_attempts smallint NOT NULL DEFAULT 0 CHECK (wrong_code_attempts <= 5),
  resend_count        smallint NOT NULL DEFAULT 0 CHECK (resend_count <= 3),
  PRIMARY KEY (profile_id, channel)
);
-- Deliberate split: the destination (phone/email) lives on profile_customer_data (device-
-- authoritative), the per-channel STATE lives here (backend-authoritative). Putting the
-- destination on this table would give it two owners and make the stage 12 reconcile rule
-- ambiguous per column (AD-005 report §4.1).

CREATE TABLE app.otp_challenge (
  challenge_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id   uuid NOT NULL REFERENCES app.profile,
  channel      text NOT NULL,
  code_hash    bytea NOT NULL,      -- sha256(salt || code); the code is NEVER stored
  salt         bytea NOT NULL,
  issued_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at   timestamptz NOT NULL,          -- issued_at + 5 minutes
  resend_index smallint NOT NULL,             -- 0,1,2 -> 30s/60s/120s unlock
  consumed_at  timestamptz
);
