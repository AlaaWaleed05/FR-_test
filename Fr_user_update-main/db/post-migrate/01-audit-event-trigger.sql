-- Layer 3 of the audit append-only enforcement (docs/sessions/2026-08-22-research-ad-005-
-- persistence.md §5): an event trigger blocking DDL against schema audit outside a
-- migration session.
--
-- NOT a Flyway migration. PostgreSQL requires actual superuser to run CREATE EVENT
-- TRIGGER — this is a hard-coded engine check with no GRANT-based delegation, regardless
-- of ownership or CREATEROLE. fru_migrator is deliberately not superuser (see V0001 and
-- db/init/01-create-migrator.sh), so it cannot create this object and neither can Flyway,
-- which always connects as fru_migrator.
--
-- Run once, after `./mvnw flyway:migrate` has applied V0001-V0004, as the container's
-- bootstrap superuser (POSTGRES_USER — never fru_migrator or fru_app):
--
--   docker exec -e PGPASSWORD=<DB_BOOTSTRAP_PASSWORD> fru_postgres \
--     psql -U <DB_BOOTSTRAP_USER> -d <DB_NAME> -f /dev/stdin < db/post-migrate/01-audit-event-trigger.sql
--
-- Two triggers, not one: pg_event_trigger_ddl_commands() (used via ddl_command_end)
-- returns no rows for DROP commands — verified live, a DROP TABLE passed through
-- unblocked when only ddl_command_end was registered. DROP needs the separate sql_drop
-- event and pg_event_trigger_dropped_objects(); audit.block_audit_ddl() branches on
-- TG_EVENT to use the right one. Idempotent to re-run: DROP EVENT TRIGGER IF EXISTS first.
DROP EVENT TRIGGER IF EXISTS fru_audit_ddl_guard;
CREATE EVENT TRIGGER fru_audit_ddl_guard
  ON ddl_command_end
  EXECUTE FUNCTION audit.block_audit_ddl();

DROP EVENT TRIGGER IF EXISTS fru_audit_drop_guard;
CREATE EVENT TRIGGER fru_audit_drop_guard
  ON sql_drop
  EXECUTE FUNCTION audit.block_audit_ddl();

-- Verify (idempotent, read-only): confirm both event triggers exist and are enabled after
-- running this script. Expect exactly 2 rows, both with evtenabled = 'O' (origin — the
-- default "enabled" state). Zero rows means this script was never run, or was run and then
-- undone by a later full database reset (e.g. `docker compose down -v`) — see R-028. Run it
-- any time to check, not only right after applying this script:
--
--   SELECT evtname, evtevent, evtenabled
--     FROM pg_event_trigger
--    WHERE evtname LIKE '%audit%';
--
-- Expected:
--   evtname               | evtevent         | evtenabled
--   fru_audit_ddl_guard    | ddl_command_end  | O
--   fru_audit_drop_guard   | sql_drop         | O
