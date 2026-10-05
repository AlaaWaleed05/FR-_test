# S2-01 — Local PostgreSQL, Flyway, audit schema with proven append-only enforcement

Date: 2026-08-22
Sprint 2, first product code in the repo.

---

## 0. Step-0 findings

**Commit check:**
```
$ git log --oneline -3
e6b213c docs: record S1-13 commit/push proof in session report
a091cbe docs: close AD-005, file persistence card, correct customer.md audit line
d1755e3 docs: record AD-005 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```
S1-13's substantive commit `a091cbe` confirmed, with the proof-recording commit `e6b213c`
on top, matching what was reported. Tree clean at session start.

**Docker check:** `docker --version` → 29.3.1, `docker compose version` → v5.1.1. Both
present, so the session proceeded per the task instructions. What happened next is
detailed in §1 below, since Docker instability consumed a large share of this session.

**Port check:** `Get-NetTCPConnection -LocalPort 5432` showed nothing listening — free.
Used the default port 5432 rather than a non-default one (this reverses an earlier
draft-plan decision to use 5433 defensively; the plan reviewer asked for the port to
actually be checked rather than assumed, and it was free).

---

## 1. Docker Desktop failure and recovery — a full session in itself

This section exists because it consumed the majority of the session and materially
changed nothing about the schema design, but is exactly the kind of thing CLAUDE.md's
"anything that failed" requirement wants on the record.

1. **Disk exhaustion.** The very first `docker compose up -d` (pulling `postgres:18`) ran
   the host's C: drive to **0 bytes free**. This corrupted Docker Desktop's internal
   containerd storage: `docker run --rm hello-world` failed with
   `write /var/lib/desktop-containerd/.../metadata.db: read-only file system`.
2. User freed ~2 GB; `docker desktop restart` then failed outright
   (`Failed to fire hook: ... There is not enough space on the disk`), confirming the
   root cause. Freed further to 6.8 GB.
3. At the user's request, also updated Docker Desktop **4.68.0 (223695) → 4.87.0
   (236836)** via `docker desktop update -q`, confirmed via `docker version` (Server:
   Docker Desktop 4.87.0 (236836)). This did not by itself fix the corruption — see next.
4. Removing and re-pulling the `postgres:18` image did **not** fix it: even a bare
   `docker run -e POSTGRES_PASSWORD=test postgres:18` (no compose, no volumes, nothing
   project-specific) failed with `exec /usr/local/bin/docker-entrypoint.sh: exec format
   error`. Root-caused by overriding the entrypoint and inspecting the file directly:
   ```
   $ docker run --rm --entrypoint sh postgres:18 -c "ls -la /usr/local/bin/docker-entrypoint.sh"
   -rwxr-xr-x 1 root root 0 Aug 13 19:14 /usr/local/bin/docker-entrypoint.sh
   ```
   The entrypoint script was **0 bytes** — torn writes from the disk-full event had
   corrupted Docker Desktop's actual WSL2 data disk, not the downloaded image layers, so
   every re-pull reproduced the same corruption on extraction.
5. `wsl --unregister docker-desktop` (the WSL distro visible via `wsl --list`) did **not**
   fix it either — `docker system df` still showed pre-existing images/volumes after the
   "reset," because Docker Desktop's actual image/volume storage lives in a separate file,
   `%LOCALAPPDATA%\Docker\wsl\disk\docker_data.vhdx` (3.5 GB), not tied to a distro name
   `wsl --list` surfaces.
6. With the user's explicit approval (this was flagged as a broader action before
   proceeding), stopped Docker Desktop fully (`docker desktop stop`, `wsl --shutdown`,
   force-killed remaining processes), **deleted `docker_data.vhdx` directly**, and
   relaunched. Docker Desktop rebuilt the data disk from scratch.
7. The user closed Docker Desktop mid-rebuild out of understandable fatigue with how long
   this had run, worried the machine might not be capable of running Docker at all. It
   wasn't a capability issue — offered three options (retry / postpone / native Windows
   PostgreSQL, the documented no-Docker fallback with its own unverified support caveat);
   user chose retry. Verified clean: `docker run --rm hello-world` succeeded, and
   `postgres:18` initialized and reached "database system is ready to accept connections"
   with **no exec format error** — the corruption was gone.
8. One more real bug, unrelated to the corruption: the first `docker compose up` under
   the *correct*, working Docker attempt failed with a genuine PostgreSQL 18 behavior
   change — 18+ images expect the volume mounted at `/var/lib/postgresql` (versioned
   `pg_ctlcluster`-style subdirectories), not `/var/lib/postgresql/data` as in
   pre-18 images. Fixed in `docker-compose.yml`.

None of this changed the schema, role, or trigger design — it's infrastructure recovery,
recorded here because a large fraction of session time went to it and CLAUDE.md's session
report requirements ask for anything that failed.

---

## 2. The plan, and what changed after approval

Full plan: `C:\Users\DELL\.claude\plans\glistening-bouncing-platypus.md`.

The plan review requested four changes before implementation, all applied:

1. **`fru_migrator` must be a distinct, non-superuser owning role**, not the container's
   bootstrap superuser (my initial draft collapsed the two). Implemented via a
   `docker-entrypoint-initdb.d` script (`db/init/01-create-migrator.sh`) run by the actual
   bootstrap superuser, creating `fru_migrator LOGIN CREATEROLE` (no `SUPERUSER`) and
   making it the database owner — resolving the chicken-and-egg problem (Flyway connects
   as `fru_migrator` from its very first migration) without conflating identities. This
   turned out to matter substantively during implementation — see §4's `CREATE EVENT
   TRIGGER` finding.
2. **Port 5432, not 5433** — checked, found free, used it.
3. **Hash-chain tampering proof extended**: re-enable the trigger with corrupted data
   still in place and re-verify before reverting, to prove detection doesn't depend on the
   guard being off. Done — see §5.
4. **Decision #6 (DB-independent `./mvnw test`/`verify`) approved with an explicit
   recorded gap**: the standard gates no longer prove the application can reach a
   database. Recorded here, now: **S2-02 is committed to adding an integration test that
   proves connectivity** (against the real docker-compose Postgres, or Testcontainers).

**Two further corrections made during implementation, not anticipated by the plan:**

- **`CREATE EVENT TRIGGER` requires actual PostgreSQL superuser** — a hard-coded engine
  check with no GRANT-based delegation, regardless of ownership or `CREATEROLE`. Since
  `fru_migrator` is deliberately not superuser (decision 1 above), it cannot create the
  Layer-3 event trigger, and neither can Flyway (which always connects as `fru_migrator`).
  Resolved by moving event-trigger creation out of the Flyway chain entirely, into a
  separate, documented, idempotent script (`db/post-migrate/01-audit-event-trigger.sql`)
  run once by the actual bootstrap superuser after `flyway:migrate` completes. This is a
  structural consequence of the non-superuser design the plan review asked for, not a
  compromise of it.
- **`pg_event_trigger_ddl_commands()` returns no rows for DROP commands.** Found live,
  during the step-7 proofs themselves: `ALTER TABLE audit.audit_event ADD COLUMN ...` was
  correctly blocked by Layer 3, but `DROP TABLE audit.audit_event` was **not** — it
  succeeded, dropping the table. Root-caused with a throwaway debug event trigger in
  `public` (not `audit`, to avoid tripping the real guard) confirming the DDL-commands
  catalog function fires for `CREATE`/`ALTER` but genuinely returns zero rows for `DROP`;
  `pg_event_trigger_dropped_objects()` on the separate `sql_drop` event is required.
  Fixed by registering **two** event triggers (`ddl_command_end` and `sql_drop`), both
  calling `audit.block_audit_ddl()`, which now branches on `TG_EVENT`. Full reset and
  re-run confirmed the fix — see §5. This is exactly the kind of gap "prove it blocks"
  exists to catch, and it worked.

---

## 3. Files created / edited

**Root**
- `docker-compose.yml` — `postgres:18`, named volume `fru_postgres_data` mounted at
  `/var/lib/postgresql` (not `.../data` — see §1.8), fixed port 5432, ICU init args
  (`--locale-provider=icu --icu-locale=ar`), mounts the init script.
- `db/init/01-create-migrator.sh` — creates `fru_migrator` (CREATEROLE, non-superuser),
  makes it the database owner. Runs once, as the bootstrap superuser, before Flyway ever
  connects.
- `db/post-migrate/01-audit-event-trigger.sql` — creates the two Layer-3 event triggers.
  Documented as a manual, idempotent, one-time step (not a Flyway migration — see §2).
- `.env.example` — placeholders for `DB_NAME`, `DB_PORT`, `DB_BOOTSTRAP_USER`,
  `DB_BOOTSTRAP_PASSWORD`, `DB_MIGRATOR_PASSWORD`, `FRU_APP_PASSWORD`.
- `.env` — real local-dev values (gitignored; root `.gitignore` already excluded
  `.env`/`.env.*` with `!.env.example` — verified via `git status` before every commit
  that it never appears). Values are throwaway local Docker credentials, not reproduced
  here even redacted, per CLAUDE.md.

**backend/**
- `pom.xml` — added `spring-boot-starter-jdbc`, `org.postgresql:postgresql` (runtime),
  `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` (all version-
  managed by the `spring-boot-starter-parent` 4.1.0 BOM); added `flyway-maven-plugin`
  12.4.0 for the manual proof runs, configured from `${env.*}` only — no literal
  credentials.
- `src/main/resources/application.properties` — `spring.datasource.*` (fru_app) and
  `spring.flyway.*` (fru_migrator) as two distinct connections, both env-var-sourced, no
  default on any password property.
- `src/main/resources/db/migration/V0001__roles_and_schemas.sql` — `CREATE ROLE fru_app`;
  three schemas `AUTHORIZATION fru_migrator`; `REVOKE ALL ON SCHEMA audit FROM PUBLIC`;
  `GRANT USAGE` to fru_app; `ALTER DEFAULT PRIVILEGES` for schema audit.
- `.../V0002__audit_tables.sql` — `audit_chain`, `audit_artifact`, `audit_event` (no FK to
  `app`; `profile_id` a plain `uuid`), `audit_seal`, plus the three indexes from AD-005
  §4.5. The `payload jsonb GENERATED ALWAYS AS (payload_json::jsonb) STORED` column — an
  item the AD-005 report marked [UNVERIFIED] — was accepted by PostgreSQL 18 without
  modification; no fallback needed.
- `.../V0003__audit_functions.sql` — `reject_mutation()`, `artifact_guard()`,
  `block_audit_ddl()` (now branching on `TG_EVENT`, see §2), `chain_append()` (SECURITY
  DEFINER), `chain_genesis()` and `verify_chain()` — the latter two are additions beyond
  the AD-005 report's elided SQL, necessary to prove the hash chain with pure SQL since no
  application/service code exists yet.
- `.../V0004__audit_grants_and_triggers.sql` — the trimmed Layer-1 grants (fru_app: SELECT
  only on `audit_chain`; INSERT+SELECT on `audit_event`/`audit_artifact`; nothing on
  `audit_seal`), Layer-2 triggers, Layer-4 triggers. Layer 3 deliberately absent — see §2.
- `src/test/java/.../BackendApplicationTests.java` — excludes
  `DataSourceAutoConfiguration`/`FlywayAutoConfiguration` so the context-loads smoke test
  stays DB-independent. Comment records the known gap and the S2-02 commitment.

**Plan files**
- `EXECUTION_PLAN.md` — Sprint 2 section added; S2-01 set to ✅ (all of §6/§7 below
  passed).
- `PROJECT_PLAN.md` — Constraints "Scale" paragraph replaced with the product owner's
  ~100,000-account figure; OQ-004 amended to ANSWERED 2026-08-22.

---

## 4. iOS package check

Not applicable — no Flutter package was added this session (mobile tier untouched).

---

## 5. Step-7 enforcement proofs, verbatim

All commands run via `docker exec ... psql` against the live container. Roles used exactly
as designed: `fru_app` (owns nothing), `fru_migrator` (owns the database, not superuser).

### 5a. As `fru_app` — all four blocked

```
=== 1. UPDATE as fru_app ===
ERROR:  permission denied for table audit_event

=== 2. DELETE as fru_app ===
ERROR:  permission denied for table audit_event

=== 3. TRUNCATE as fru_app ===
ERROR:  permission denied for table audit_event

=== 4. DROP TABLE as fru_app ===
ERROR:  must be owner of table audit_event

=== 4b. ALTER TABLE as fru_app ===
ERROR:  must be owner of table audit_event
```

### 5b. As `fru_migrator` (the owner, not superuser) — Layer 2 blocks the owner directly

```
=== UPDATE as fru_migrator (owner) — Layer 2 ===
ERROR:  audit.audit_event is append-only; UPDATE is not permitted
CONTEXT:  PL/pgSQL function audit.reject_mutation() line 3 at RAISE

=== DELETE as fru_migrator (owner) — Layer 2 ===
ERROR:  audit.audit_event is append-only; DELETE is not permitted
CONTEXT:  PL/pgSQL function audit.reject_mutation() line 3 at RAISE

=== TRUNCATE as fru_migrator (owner) — Layer 2 ===
ERROR:  audit.audit_event is append-only; TRUNCATE is not permitted
CONTEXT:  PL/pgSQL function audit.reject_mutation() line 3 at RAISE
```

This is the proof that matters most: `fru_migrator` owns the table (an owner always holds
all grant options — REVOKE is meaningless against it), yet the trigger still stops it.
Role separation alone is not the guard; this is what proves Layer 2 does real work.

### 5c. Bonus — Layer 3 (event trigger) against the owner, outside a migration session

Not explicitly itemized in the task's proof list, but performed per "a guard that has not
been seen to block is a comment, not a guard." This is also where the DROP-vs-ALTER gap
(§2) was actually found:

**First attempt (before the fix):**
```
=== ALTER TABLE as fru_migrator, outside a migration session ===
ERROR:  DDL against schema audit requires a migration session

=== DROP TABLE as fru_migrator, outside a migration session ===
DROP TABLE          <- NOT blocked. audit.audit_event was actually dropped.
```
Root-caused, fixed (two event triggers instead of one — see §2), database fully reset,
and retested:

**After the fix:**
```
=== ALTER TABLE as fru_migrator, outside migration session — Layer 3 ===
ERROR:  DDL against schema audit requires a migration session
CONTEXT:  PL/pgSQL function audit.block_audit_ddl() line 17 at RAISE

=== DROP TABLE as fru_migrator, outside migration session — Layer 3 (fixed) ===
ERROR:  DDL against schema audit requires a migration session
CONTEXT:  PL/pgSQL function audit.block_audit_ddl() line 11 at RAISE
```

### 5d. Hash chain: verify, tamper, detect with guard off, detect with guard back on, revert, confirm clean

Three events inserted as `fru_migrator`; the chain-maintaining trigger supplied `seq`,
`prev_hash`, `content_hash`, `row_hash` automatically — none were provided by the insert.

```
 audit_event_id | seq |       event_type
----------------+-----+-------------------------
              2 |   1 | account_check_attempted
              3 |   2 | session_created
              4 |   3 | otp_issued

 ok | checked | first_bad_seq | reason
----+---------+---------------+--------
 t  |       3 |               | ok
```

Disabling the trigger is itself DDL against schema `audit`, so it was **also** correctly
blocked by Layer 3 on the first attempt — a `SET fru.migration_in_progress = 'on'` bypass
(the same mechanism a real Flyway migration would use) was required to disable it at all:

```
ERROR:  DDL against schema audit requires a migration session   <- ALTER ... DISABLE TRIGGER, first try
ERROR:  audit.audit_event is append-only; UPDATE is not permitted  <- tamper attempt, trigger never actually disabled
 ok | checked | first_bad_seq | reason
----+---------+---------------+--------
 t  |       3 |               | ok         <- nothing was tampered; state still clean
```

With the migration-session bypass, tampering succeeded and was detected:

```
UPDATE 1
 audit_event_id |     event_type
----------------+---------------------
              3 | TAMPERED_EVENT_TYPE

 ok | checked | first_bad_seq |        reason
----+---------+---------------+-----------------------
 f  |       2 |             2 | content_hash mismatch
```

Trigger re-enabled with the **tampered data still in place** — detection persists, and a
fresh mutation attempt is blocked again:

```
 ok | checked | first_bad_seq |        reason
----+---------+---------------+-----------------------
 f  |       2 |             2 | content_hash mismatch

ERROR:  audit.audit_event is append-only; UPDATE is not permitted
```

Reverted (trigger disabled again, value restored, trigger re-enabled):

```
 ok | checked | first_bad_seq | reason
----+---------+---------------+--------
 t  |       3 |               | ok
```

---

## 6. Flyway output, from scratch and idempotent, verbatim

Fresh database (`docker compose down -v` + `up`), then `./mvnw flyway:migrate`:

```
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] Schema history table "public"."flyway_schema_history" does not exist yet
[INFO] Successfully validated 4 migrations (execution time 00:00.106s)
[INFO] Current version of schema "public": << Empty Schema >>
[INFO] Migrating schema "public" to version "0001 - roles and schemas"
[INFO] Migrating schema "public" to version "0002 - audit tables"
[INFO] Migrating schema "public" to version "0003 - audit functions"
[INFO] Migrating schema "public" to version "0004 - audit grants and triggers"
[INFO] Successfully applied 4 migrations to schema "public", now at version v0004 (execution time 00:00.249s)
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] BUILD SUCCESS
```

Run a second time, no changes in between:

```
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] Successfully validated 4 migrations (execution time 00:00.080s)
[INFO] Current version of schema "public": 0004
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] BUILD SUCCESS
```

(An earlier from-scratch run, before the Layer-3 DROP fix, failed cleanly and rolled back
in full at V0004 for an unrelated reason — `CREATE EVENT TRIGGER` needing superuser, §2 —
which is itself a proof point: `[ERROR] Migration of schema "public" to version "0004 -
audit grants and triggers" failed! Changes successfully rolled back.` PostgreSQL's
transactional DDL did exactly what AD-005 §1 said it would.)

---

## 7. Gate output, verbatim

### backend/ — `./mvnw test`

```
[INFO] Running sd.gov.bank.fruserupdate.BackendApplicationTests
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 18.86 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Results:
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] BUILD SUCCESS
```
Context loaded and the single test passed **without any database available** — confirms
the DB-independence design (§2, decision 4) works as intended.

### backend/ — `./mvnw verify`

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 1 were already clean, 1 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

**JaCoCo bundle note (asked for explicitly in the task):** still **0 classes**, unchanged
from before this session. Adding Flyway added zero Java classes — only SQL migrations,
POM/properties config, and one annotation change to the existing test — so there was
nothing new for JaCoCo to instrument. The coverage percentage figure is not meaningful at
0 classes (matches the pre-existing S1-07 finding that a 0-class bundle trivially "passes"
80%; this remains true and unresolved by S2-01, tracked under S1-08/R-009).

### mobile/ (unchanged — not touched this session)

```
$ fvm flutter test
00:02 +1: All tests passed!

$ fvm dart run tool/check_coverage.dart
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

$ fvm flutter analyze
No issues found! (ran in 54.4s)
```

### backoffice/ (unchanged — not touched this session)

```
$ npm run test
Test Files  1 passed (1)
Tests  1 passed (1)

$ npm run test:coverage
Statements   : 100% ( 1/1 )
Branches     : 100% ( 0/0 )
Functions    : 100% ( 1/1 )
Lines        : 100% ( 1/1 )

$ npm run lint
(clean, no output)
```

---

## 8. Known gap (decision #6, approved with this recorded)

`./mvnw test`/`./mvnw verify` now exclude `DataSourceAutoConfiguration` and
`FlywayAutoConfiguration` from the context-loads smoke test, specifically so the standard
gate commands don't require a running Docker Postgres. **This means the standard gates no
longer prove the application can actually reach a database.** That proof exists only in
this session's manual `flyway:migrate` runs and `psql` proofs (§5, §6), not in anything
`./mvnw verify` checks going forward.

**S2-02 is committed to adding an integration test that does prove connectivity** —
against the real docker-compose Postgres or via Testcontainers — before any repository or
entity code lands on top of this schema.

---

## 9. What failed, summarized

- Docker Desktop's data disk was corrupted by a disk-full event outside this task's
  control; recovered by deleting and rebuilding `docker_data.vhdx` (§1). Took most of the
  session's wall-clock time; no design impact.
- PostgreSQL 18's volume-mount path changed from `.../data` to the parent directory;
  caught and fixed on the first real `docker compose up`.
- `CREATE EVENT TRIGGER` requires actual superuser, with no GRANT-based delegation; moved
  Layer 3's creation out of Flyway into a documented, idempotent post-migrate step (§2).
- `pg_event_trigger_ddl_commands()` returns no rows for DROP commands — a real gap in the
  first Layer-3 implementation, caught only because DROP was actually tested (§2, §5c),
  not assumed to work from ALTER passing. Fixed with a second event trigger on `sql_drop`.

Everything in §5 and §6 reflects the **final, fixed** state, re-verified from a fully
clean database after every fix above.

---

## Commit/push proof

```
$ git log --oneline -1
c6670cf chore: record local Bash permission grant from S2-01 session

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Substantive S2-01 commit: `afc96d8` — "feat: S2-01 local PostgreSQL, Flyway, and audit
schema with proven append-only enforcement" (15 files changed). `c6670cf` is a small
follow-up recording a harmless, auto-generated local Bash permission entry in
`.claude/settings.json` (no secrets — checked before both commits; see §3).

