-- Runs as fru_migrator (created by db/init/01-create-migrator.sh before Flyway's first
-- connection — see docs/components/persistence.md). fru_migrator owns the database and,
-- by extension, every schema it creates below.

-- fru_app: the application's runtime role. Owns nothing. Password supplied only via the
-- Flyway placeholder ${fru_app_password}, itself sourced from an environment variable —
-- never a literal in this file.
CREATE ROLE fru_app LOGIN PASSWORD '${fru_app_password}';

CREATE SCHEMA app AUTHORIZATION fru_migrator;
CREATE SCHEMA audit AUTHORIZATION fru_migrator;
CREATE SCHEMA ref AUTHORIZATION fru_migrator;

-- Layer 1 of the audit append-only guarantee (docs/sessions/2026-08-22-research-ad-005-
-- persistence.md §5): fru_app must not own the audit tables, since an owner always holds
-- all grant options and REVOKE against an owner is meaningless. Table-level INSERT/SELECT
-- grants to fru_app follow in V0004, once the tables exist.
REVOKE ALL ON SCHEMA audit FROM PUBLIC;
GRANT USAGE ON SCHEMA audit TO fru_app;

ALTER DEFAULT PRIVILEGES FOR ROLE fru_migrator IN SCHEMA audit
  REVOKE ALL ON TABLES FROM PUBLIC;
