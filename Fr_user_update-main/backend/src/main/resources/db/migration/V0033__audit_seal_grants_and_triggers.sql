-- Layer 3 bypass — see V0031's header comment. CREATE TRIGGER was found live in review not to
-- require it (pg_event_trigger_ddl_commands() does not report schema_name='audit' for it), but
-- it is set anyway for uniform safety: relying on that undocumented behaviour across future
-- PostgreSQL versions is a worse bet than setting a flag this migration already needs to be
-- consistent with V0031/V0032.
SET LOCAL fru.migration_in_progress = 'on';

-- Grants for fru_sealer, the role that writes the audit seal — distinct from fru_app (which
-- gains nothing here) and fru_migrator. See docs/sessions/2026-08-27-s2-04-audit-seal.md.
-- audit_artifact SELECT is required by seal_verify_prefix() (V0032), which reads
-- audit_artifact.sha256 for every event that carries one — found live in review: without it,
-- fru_sealer itself (the very role meant to run verification) gets "permission denied for
-- table audit_artifact" on any chain containing an artifact-linked event, which in practice is
-- every real profile chain (Uqudo JWS, Civil Registry response, ProcessOmniCheckAct).
GRANT USAGE ON SCHEMA audit TO fru_sealer;
GRANT SELECT ON audit.audit_chain, audit.audit_event, audit.audit_artifact,
                audit.audit_seal, audit.audit_seal_chain
  TO fru_sealer;
GRANT INSERT ON audit.audit_seal, audit.audit_seal_chain TO fru_sealer;
-- Column-level only: the one-time exported_at/export_note set, enforced by audit.seal_guard()
-- below regardless of what the grant alone would otherwise allow.
GRANT UPDATE (exported_at, export_note) ON audit.audit_seal TO fru_sealer;
-- No UPDATE/DELETE/TRUNCATE beyond that, on either table, for fru_sealer or fru_app.

-- Layer 2 for the seal tables: audit_seal allows exactly the one-time exported_at/export_note
-- update (audit.seal_guard(), V0032); audit_seal_chain allows no UPDATE/DELETE at all, same as
-- audit_event (audit.reject_mutation(), V0003). Without these, the seal tables would be the
-- weak link a rewriter could patch to match tampered audit_event data.
CREATE TRIGGER audit_seal_guard
  BEFORE UPDATE OR DELETE ON audit.audit_seal
  FOR EACH ROW EXECUTE FUNCTION audit.seal_guard();

CREATE TRIGGER audit_seal_no_truncate
  BEFORE TRUNCATE ON audit.audit_seal
  FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_mutation();

CREATE TRIGGER audit_seal_chain_immutable
  BEFORE UPDATE OR DELETE ON audit.audit_seal_chain
  FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();

CREATE TRIGGER audit_seal_chain_no_truncate
  BEFORE TRUNCATE ON audit.audit_seal_chain
  FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_mutation();

-- Layer 3 (the DDL event trigger against schema audit, db/post-migrate/01-audit-event-
-- trigger.sql) already covers every table in this schema by schema name, not by an enumerated
-- list — no change needed there for these two new tables.
