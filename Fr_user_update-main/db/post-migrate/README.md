# db/post-migrate

Scripts here are **not** Flyway migrations. They exist because `CREATE EVENT TRIGGER` requires
actual PostgreSQL superuser — a hard-coded engine check with no GRANT-based delegation — and
`fru_migrator` (the role Flyway always connects as) is deliberately not superuser. See the
S2-01 session report and `docs/components/persistence.md`.

## `01-audit-event-trigger.sql`

Creates Layer 3 of the audit append-only enforcement (an event trigger blocking DDL against
schema `audit` outside a migration session). Run once, after `flyway:migrate` has applied every
migration up to and including `V0004__audit_grants_and_triggers.sql`, as the container's
**bootstrap superuser** (`DB_BOOTSTRAP_USER`, never `fru_migrator` or `fru_app`):

```
docker exec -e PGPASSWORD=<DB_BOOTSTRAP_PASSWORD> fru_postgres \
  psql -U <DB_BOOTSTRAP_USER> -d <DB_NAME> -f /dev/stdin < db/post-migrate/01-audit-event-trigger.sql
```

Safe to re-run — it `DROP EVENT TRIGGER IF EXISTS` before each `CREATE`.

## Why this matters — nothing fails loudly if you skip it (R-028)

`flyway:migrate` succeeds whether or not this script has ever been run. A deployment that runs
migrations and forgets this step ends up with Layers 1, 2 and 4 of the audit append-only
guarantee, but **not** Layer 3 — DDL against the `audit` schema (e.g. an accidental
`DROP TABLE audit.audit_event` from a stray script or psql session) would go unguarded, and
nothing in the migration output says so.

**Always verify after running it, and periodically thereafter** (a full local reset via
`docker compose down -v` wipes the database, including this script's effect — Flyway migrations
re-apply automatically on the next `up`, but this step does not):

```sql
SELECT evtname, evtevent, evtenabled
  FROM pg_event_trigger
 WHERE evtname LIKE '%audit%';
```

Expect exactly 2 rows:

| evtname | evtevent | evtenabled |
|---|---|---|
| fru_audit_ddl_guard | ddl_command_end | O |
| fru_audit_drop_guard | sql_drop | O |

Zero rows means Layer 3 is absent. Any deployment runbook must include this check as a gate,
not an afterthought.

## `02-publish-reference-documents.md`

AD-002f's reference-data publication step — populates `ref.reference_list_document` and
`ref.reference_list_version.content_hash`. Unlike the script above, this one is Java, not SQL
(see that file for why), invoked from the built application jar with a dedicated Spring profile
rather than `psql`. Full runbook, idempotency proof, and what skipping it does and does not
leave silently stale: `02-publish-reference-documents.md`.

## `03-create-operator-account.md`

AD-002e's operator/viewer/admin account provisioning step — a profile-gated CLI runner, same
shape as the script above, that both bootstraps the first admin account and creates every later
operator/viewer account. No dedicated `application-*.properties` override needed this time —
`fru_app` already holds the one grant (`INSERT` on `app.operator_user`) it requires. Full runbook:
`03-create-operator-account.md`.
