-- S4-05 (AD-002e): the operator/viewer/admin user and role schema. Touches schema app only, not
-- audit -- no SET LOCAL fru.migration_in_progress guard needed (that guard, audit.block_audit_ddl(),
-- fires only on DDL against schema audit; confirmed by reading V0034 (the DML-only precedent),
-- V0049 and V0051 (non-audit-schema DDL that also carries no guard)).
--
-- Conventions this follows, all precedented elsewhere in this schema: a closed code set gets a
-- lookup table, never a PostgreSQL ENUM (V0005 status_code; docs/components/persistence.md "Do NOT
-- use"); uuid PRIMARY KEY DEFAULT gen_random_uuid() and timestamptz NOT NULL DEFAULT
-- clock_timestamp() (V0005); per-table GRANT to fru_app with a comment justifying each verb, no
-- DELETE unless earned (V0010).

CREATE TABLE app.operator_role (
  code           text PRIMARY KEY,
  label_ar       text NOT NULL,
  label_en       text NOT NULL,
  -- true for the two roles that may reach /api/v1/operator/**; false for 'admin', which manages
  -- accounts and reaches no operator endpoint (docs/journeys/operator.md, AD-002e).
  is_back_office boolean NOT NULL
);

INSERT INTO app.operator_role (code, label_ar, label_en, is_back_office) VALUES
  ('viewer',   'مطّلع',       'Viewer',        true),
  ('operator', 'مشغّل',       'Operator',      true),
  ('admin',    'مدير النظام', 'Administrator', false);

CREATE TABLE app.operator_user (
  user_id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- ASCII, lowercase, stored as typed. Deliberately NOT a generated lower()/ar_fold() column:
  -- docs/components/persistence.md forbids indexing a user-text column under a non-C collation,
  -- and a bank employee identifier has no need of Arabic folding.
  username             text NOT NULL,
  display_name         text NOT NULL,               -- Arabic display name, never a lookup key

  -- EXACTLY ONE role per account. This is what makes 'admin' mutually exclusive with
  -- 'viewer'/'operator' -- the forbidden combination is not merely rejected, it is inexpressible.
  -- A role-SET table would need a trigger instead: PostgreSQL does not support a CHECK
  -- referencing other rows [DOC postgresql.org/docs/18/ddl-constraints.html] -- the same finding
  -- AD-002f/V0049 already relied on.
  role                 text NOT NULL REFERENCES app.operator_role(code),

  -- DelegatingPasswordEncoder form, e.g. '{bcrypt}$2a$12$...'. Never a bare hash: the {id}
  -- prefix is what lets the algorithm or work factor change later without a data migration.
  password_hash        text NOT NULL,
  must_change_password boolean NOT NULL DEFAULT true,
  password_changed_at  timestamptz,

  is_enabled           boolean NOT NULL DEFAULT true,
  disabled_at          timestamptz,                 -- when it was LAST disabled; not a state flag

  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  -- NULL for every account created by auth.config.CreateOperatorAccountRunner (S4-05) -- an
  -- out-of-band CLI act with no admin session to attribute it to. Populated only if a future
  -- admin-facing HTTP endpoint creates an account from within an authenticated admin session
  -- (see BACKLOG.md) -- no such endpoint exists yet.
  created_by           uuid REFERENCES app.operator_user(user_id),
  last_sign_in_at      timestamptz,
  row_version          bigint NOT NULL DEFAULT 1,

  CONSTRAINT operator_user_username_unique UNIQUE (username),
  CONSTRAINT operator_user_username_shape  CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$')
);

-- operator_role: a fixed, migration-maintained code set. Read-only to the application.
GRANT SELECT ON app.operator_role TO fru_app;

-- operator_user: created by auth.config.CreateOperatorAccountRunner; updated on sign-in
-- (last_sign_in_at), password change and (once built) admin actions. NEVER deleted --
-- app.profile_status_history.actor_id and audit.audit_event.actor_id both reference user_id as
-- plain text forever, and both tables are append-only, so a deleted account would orphan the
-- four-eyes predicate (V0009) and every audit attribution. Disable, do not delete -- same
-- reasoning as app.profile.
GRANT SELECT, INSERT, UPDATE ON app.operator_user TO fru_app;
