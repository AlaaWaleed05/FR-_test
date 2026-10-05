-- Layer 1 (continued from V0001): table-level grants, now that the tables exist.
-- fru_app gets exactly INSERT+SELECT on audit_event/audit_artifact and SELECT only on
-- audit_chain (it never updates the chain head directly — chain_append() does that via
-- SECURITY DEFINER). Nothing is granted on audit_seal; it is written by a future export
-- job, out of scope for S2-01. Per AD-005 report §5, trimmed of fru_purge/fru_auditor,
-- which are not created in this sprint — see the S2-01 session report.
GRANT INSERT, SELECT ON audit.audit_event    TO fru_app;
GRANT INSERT, SELECT ON audit.audit_artifact TO fru_app;
GRANT SELECT          ON audit.audit_chain   TO fru_app;

-- Layer 2: immutability triggers.
CREATE TRIGGER audit_event_immutable
  BEFORE UPDATE OR DELETE ON audit.audit_event
  FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();

CREATE TRIGGER audit_event_no_truncate
  BEFORE TRUNCATE ON audit.audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_mutation();

CREATE TRIGGER audit_artifact_guard
  BEFORE UPDATE OR DELETE ON audit.audit_artifact
  FOR EACH ROW EXECUTE FUNCTION audit.artifact_guard();

CREATE TRIGGER audit_artifact_no_truncate
  BEFORE TRUNCATE ON audit.audit_artifact
  FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_mutation();

-- Layer 4: genesis hash on chain creation, then the hash chain itself on every event insert.
CREATE TRIGGER audit_chain_genesis
  BEFORE INSERT ON audit.audit_chain
  FOR EACH ROW EXECUTE FUNCTION audit.chain_genesis();

CREATE TRIGGER audit_event_chain_append
  BEFORE INSERT ON audit.audit_event
  FOR EACH ROW EXECUTE FUNCTION audit.chain_append();

-- Layer 3 (the event trigger against DDL on schema audit) is NOT created here.
-- PostgreSQL hard-requires actual superuser to run CREATE EVENT TRIGGER — no GRANT
-- delegates it, regardless of ownership or CREATEROLE. Since fru_migrator is
-- deliberately not superuser (see V0001 / db/init/01-create-migrator.sh and the S2-01
-- session report), it cannot create this object, so Flyway cannot either. It is created
-- once, by the actual bootstrap superuser, via db/post-migrate/01-audit-event-trigger.sql
-- — run manually after this migration, since it is structurally not a Flyway migration.
