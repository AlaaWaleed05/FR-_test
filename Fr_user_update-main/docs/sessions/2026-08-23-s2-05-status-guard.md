# S2-05 — Status-transition guard, otp_challenge CHECK, .gitattributes gap, Civil Registry card

Date: 2026-08-23
Sprint 2.

---

## 0. Step-0 findings

**Commit check:**
```
$ git log --oneline -3
25ab757 docs: record S2-03 commit/push proof in session report
3e78cf2 feat: S2-03 ref schema and reference-data seed
df4588c docs: record S2-02 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
S2-03's substantive commit `3e78cf2` confirmed, as expected, with `25ab757` (proof recording)
on top. Tree clean at session start.

---

## 1. The plan

Full plan: `C:\Users\DELL\.claude\plans\reflective-zooming-raven.md`. Approved without
requested changes before implementation.

---

## 2. `.gitattributes` gap closed

Added `*.sql text eol=lf` alongside the existing `*.sh text eol=lf` line. Verified every
tracked SQL file in `db/` and `backend/src/main/resources/db/`:

```
$ git ls-files --eol db backend/src/main/resources/db
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0001__roles_and_schemas.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0002__audit_tables.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0003__audit_functions.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0004__audit_grants_and_triggers.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0005__app_status_and_profile.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0006__app_customer_data.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0007__app_channels_and_otp.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0008__app_identity_artifacts.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0009__app_status_history.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0010__app_grants_and_triggers.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0011__ref_registry_and_fold.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0012__ref_grants.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0013__close_deferred_reference_fks.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0014__seed_occupation.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0015__seed_branch.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0016__seed_admin_division.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0017__seed_income_source.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0018__seed_education_level.sql
i/lf    w/lf    attr/text eol=lf      	backend/src/main/resources/db/migration/V0019__seed_rejection_reason.sql
i/lf    w/lf    attr/text eol=lf      	db/init/01-create-migrator.sh
i/lf    w/lf    attr/text eol=lf      	db/post-migrate/01-audit-event-trigger.sql
i/lf    w/lf    attr/                 	db/post-migrate/README.md
```
Every tracked `.sql` file already shows `i/lf w/lf` — none needed renormalising. (V0020 and
V0021, added this session, are not yet tracked at the point this command ran; they inherit
the same `.gitattributes` rule on `git add`.) `db/post-migrate/README.md` has no `.sql`
extension and is unaffected, as expected.

---

## 3. The status-transition guard — migrations V0020 and V0021

**`V0020__app_status_transition_guard.sql`**:
- `app.status_transition` — legal-transitions table (`from_status` nullable, `to_status` NOT
  NULL, both `REFERENCES app.status_code(code)`, `note text NOT NULL`,
  `UNIQUE NULLS NOT DISTINCT (from_status, to_status)`).
- `app.check_status_transition()` + `profile_status_transition_guard` — `BEFORE INSERT OR
  UPDATE ON app.profile`. Treats NULL as "no prior status" on INSERT; on UPDATE, returns
  immediately (no validation) if `OLD.status IS NOT DISTINCT FROM NEW.status`, so updates to
  `resume_stage`/`last_activity_at`/etc. that don't touch `status` are unaffected. Otherwise
  raises `ERRCODE 23514` if the `(from,to)` pair is absent from `app.status_transition`.
- `app.require_status_history()` + `profile_status_requires_history` — a `CREATE CONSTRAINT
  TRIGGER ... AFTER INSERT OR UPDATE ... DEFERRABLE INITIALLY DEFERRED`, same short-circuit
  logic, raising `ERRCODE 23514` at COMMIT time if the profile's **most recent**
  `app.profile_status_history` row (highest `seq`) doesn't match `(from_status, to_status)`
  for this change. See §3b for why "most recent" rather than "any row ever matching this
  pair" — the first draft used the latter and a real bypass was found and fixed this session.

**Mechanism choice — a deferred constraint trigger, per the task's own hint.** The AD-005
report's approve statement (`V0009`'s comment block, proven live in S2-02 §7c) is a single
`UPDATE app.profile`, with the caller expected to `INSERT` the matching
`app.profile_status_history` row as a second statement in the same transaction — the order
between the two is not fixed by that statement's own text. A plain `BEFORE` trigger would
fire before the history row necessarily exists. `DEFERRABLE INITIALLY DEFERRED` runs the
check once at `COMMIT`, after every statement in the transaction has executed in whatever
order the caller issued them. This imposes no new obligation beyond what the journey's
governing rule ("no silent state changes") already required of the caller — insert the
history row before committing — and the AD-005 approve/reject statements' own SQL text is
untouched (see §6 below).

**`V0021__otp_challenge_channel_check.sql`**: `ALTER TABLE app.otp_challenge ADD CONSTRAINT
otp_challenge_channel_check CHECK (channel IN ('sms', 'whatsapp', 'email'));` — byte-identical
value set to `app.profile_channel.channel`'s existing constraint (V0007). Confirmed via
`pg_get_constraintdef` that both constraints normalize to identical text.

### 3a. Legal transitions derived, with source lines

| From | To | Source |
|---|---|---|
| NULL | `in_progress` | customer.md Stage 1b: "the session is created" — profile creation, no prior status |
| `in_progress` | `awaiting_registry` | customer.md Stage 9: "Civil Registry unreachable or returning nothing" |
| `awaiting_registry` | `in_progress` | customer.md Stage 9/12: "On resume the backend simply retries the lookup" |
| `in_progress` | `blocked_scan` | customer.md Stage 8: "Either budget exhausted → temporary block" |
| `blocked_scan` | `in_progress` | customer.md Stage 8/12: 24h block expires, "customer returns and resumes at the scan" |
| `in_progress` | `blocked_liveness` | customer.md Stage 10: "Budget exhausted ... temporary block on this stage only" |
| `blocked_liveness` | `in_progress` | customer.md Stage 10/12: 24h block expires, resumes at liveness |
| `in_progress` | `terminated_registry_mismatch` | customer.md Stage 9: "The number is right but my details are wrong" → terminal |
| `in_progress` | `abandoned` | customer.md Policy values: "30 days of inactivity → status `abandoned`" |
| `awaiting_registry` | `abandoned` | same 30-day rule; operator.md groups `abandoned` with the other in-flight statuses |
| `blocked_scan` | `abandoned` | same 30-day rule |
| `blocked_liveness` | `abandoned` | same 30-day rule |
| `abandoned` | `in_progress` | customer.md Stage 1a: "An incomplete profile exists" continues through 1b and the review flow; operator.md classifies `abandoned` as "In flight", so it is an incomplete profile under this rule |
| `in_progress` | `submitted` | customer.md Stage 11: "Backend sets status to `submitted`" (digital completion) |
| `awaiting_registry`/`blocked_scan`/`blocked_liveness`/`abandoned` | `submitted` | operator.md "Manual completion": states no precondition status, so any in-flight status is a legal source |
| `submitted` | `approved` | operator.md "Review": "Approve → status becomes `approved`" |
| `submitted` | `rejected` | operator.md "Review": "Reject → status becomes `rejected`" |
| `rejected` | `approved` | task spec + operator.md "Re-approving a rejected profile": "the operator can then move the profile `rejected → approved`" |

**Terminal (no outgoing row anywhere): `approved`, `terminated_registry_mismatch`.**
`rejected` has exactly the one outgoing arc above (→ `approved`); no document text describes
any other way out of `rejected`, or any way out of `terminated_registry_mismatch`, so none is
invented for either.

The "any in-flight status → `submitted`" generalisation for manual completion is a derived
inference, not a literal quote: operator.md's "Manual completion" section states the action
applies to "a customer who began in the app and then completed the process at a branch"
without naming a precondition status, so all four non-`in_progress` in-flight statuses are
included as legal sources alongside `in_progress` itself (which was already needed for the
digital path). `@agent-reviewer` cross-checked all 21 rows against every relevant journey-doc
section and confirmed no missing pair and no invented pair beyond this one labelled inference.

### 3b. A real bug found in review, and the fix

`@agent-reviewer`'s first pass (§10 below) found that the original `require_status_history()`
bound its check to "does **any** `profile_status_history` row exist anywhere for this profile
matching `(from_status, to_status)`" — not to *this specific* transition. Every reversible
pair this migration seeds (`in_progress ↔ awaiting_registry`, `in_progress ↔ blocked_scan`,
`in_progress ↔ blocked_liveness`, `abandoned → in_progress` followed later by `in_progress →
submitted`, etc.) is repeatable, so a **second occurrence** of an already-used pair could
reuse the stale history row from its first occurrence and pass the guard with **no new
history row inserted at all** — exactly what R-030 exists to prevent, just on the second hop
instead of the first. Reproduced live by the reviewer, then independently reproduced again
after the fix as proof 7 below.

**Fix**: bind the check to the profile's *most recent* history row (`ORDER BY seq DESC LIMIT
1`, using the existing `UNIQUE (profile_id, seq)`) rather than to pair-existence anywhere in
history. This assumes at most one status change per profile per transaction, which is already
how every write path in this schema is built — the AD-005 approve/reject statements are each
a single `UPDATE` paired with a single history `INSERT`. Documented in the migration's own
comment (§3 above). Re-verified with a full container rebuild and all seven proofs re-run
clean (§4 below is the post-fix state).

---

## 4. Proofs — all seven, verbatim (post-fix state)

Run against the local `fru_postgres` container, freshly rebuilt (`docker compose down -v &&
up -d`) and migrated to V0021 (post-fix) before any proof ran. Proofs 1–4, 6 and 7 run as
`fru_migrator`; proof 5's two approve attempts run as `fru_app` (the real application role),
per the task.

Output below is the actual terminal output with stdout and stderr merged (`2>&1`); psql
flushes stderr (the `ERROR:` lines) independently of stdout, so an `ERROR:` line can appear
adjacent to a different section than the statement that produced it in the merged stream. The
query results at each step are unambiguous regardless of this interleaving.

### Proof 1 — legal transition succeeds: `submitted → approved`, with a history row

```
=== PROOF 1: legal transition submitted -> approved, with history row -> expect UPDATE 1 ===
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 1
COMMIT
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
--- setup done (profile now submitted). The actual proof: approve, with matching history row, same transaction ---
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
              profile_id              |  status  
--------------------------------------+----------
 aaaaaaaa-0000-0000-0000-000000000001 | approved
(1 row)
```

### Proof 2 — illegal transition rejected: exact S2-02 §7b raw UPDATE, `in_progress → approved`

```
=== PROOF 2: illegal transition in_progress -> approved, exact S2-02 §7b raw UPDATE -> expect REJECTED ===
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 1
COMMIT
--- (a raw UPDATE, not the guarded approve statement) ---
ERROR:  illegal status transition on app.profile aaaaaaaa-0000-0000-0000-000000000002: in_progress -> approved
CONTEXT:  PL/pgSQL function app.check_status_transition() line 19 at RAISE
--- result: did it succeed? (expect still in_progress) ---
              profile_id              |   status    
--------------------------------------+-------------
 aaaaaaaa-0000-0000-0000-000000000002 | in_progress
(1 row)
```
The exact statement that succeeded in S2-02 §7b now fails immediately, before ever reaching
`COMMIT`.

### Proof 3 — status change with no history row rejected

```
=== PROOF 3: legal pair (submitted -> approved) with NO history row -> expect REJECTED AT COMMIT ===
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 1
COMMIT
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
--- setup done (profile now submitted). The actual proof: legal UPDATE, then COMMIT with NO history row ---
BEGIN
UPDATE 1
--- UPDATE itself succeeded (trigger 1 passed -- legal pair). Now COMMIT with no history row inserted ---
ERROR:  status change on app.profile aaaaaaaa-0000-0000-0000-000000000003 (submitted -> approved) has no matching (most recent) app.profile_status_history row
CONTEXT:  PL/pgSQL function app.require_status_history() line 22 at RAISE
--- result: did the transaction commit? (expect still submitted -- COMMIT rolled back) ---
              profile_id              |  status   
--------------------------------------+-----------
 aaaaaaaa-0000-0000-0000-000000000003 | submitted
(1 row)
```
The `UPDATE` itself succeeded (a legal `(from,to)` pair passes trigger 1), but `COMMIT` failed
because the deferred constraint trigger found no matching most-recent `profile_status_history`
row — the whole transaction rolled back and status is unchanged at `submitted`.

### Proof 4 — `rejected → approved` succeeds (branch resolution)

```
=== PROOF 4: rejected -> approved succeeds (branch resolution) ===
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 1
COMMIT
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
--- setup done (profile now rejected, real REJ-01/version-1 reason). The actual proof: rejected -> approved via the AD-005 §4.3 statement ---
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
              profile_id              |  status  
--------------------------------------+----------
 aaaaaaaa-0000-0000-0000-000000000004 | approved
(1 row)
```
Profile taken `in_progress → submitted → rejected` (with a real seeded `REJ-01`/version-1
reason from `ref.reference_item`), then the unmodified AD-005 approve statement moves it
`rejected → approved` successfully, with its matching history row inserted in the same
transaction.

### Proof 5 — AD-005 §4.3 approve statement re-run as `fru_app`, four-eyes rule unchanged

Setup (as `fru_migrator`): a profile in `submitted`, with a manual-completion history row for
`operator_A`:
```
=== PROOF 5 setup: submitted profile with a manual-completion history row by operator_A ===
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 1
COMMIT
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
=== history row recorded ===
              profile_id              |  actor_id  | is_manual_completion 
--------------------------------------+------------+----------------------
 aaaaaaaa-0000-0000-0000-000000000005 | operator_A | t
(1 row)

=== profile status before any approve attempt ===
              profile_id              |  status   
--------------------------------------+-----------
 aaaaaaaa-0000-0000-0000-000000000005 | submitted
(1 row)
```

The exact compare-and-set approve statement from AD-005 report §4.3, unmodified, run **as
`fru_app`** (the real application role):
```
########## APPROVE as fru_app, operator_A (expect: blocked by four-eyes, UPDATE 0) ##########
BEGIN
UPDATE 0
COMMIT
=== profile status after operator_A's attempt (expect: still submitted, blocked) ===
              profile_id              |  status   
--------------------------------------+-----------
 aaaaaaaa-0000-0000-0000-000000000005 | submitted
(1 row)

########## APPROVE as fru_app, operator_B (expect: allowed, UPDATE 1) ##########
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
=== profile status after operator_B's attempt (expect: approved, allowed) ===
              profile_id              |  status  
--------------------------------------+----------
 aaaaaaaa-0000-0000-0000-000000000005 | approved
(1 row)
```
Zero rows for the operator who performed the manual completion; one row for a different
operator, exactly matching S2-02 §7c's outcome. `@agent-reviewer` independently re-ran this
exact approve statement text against a throwaway container and confirmed it is byte-identical
to `V0009`'s comment block and to S2-02 §7c's proof — the only change is that operator_B's
transaction now also inserts the matching `profile_status_history` row before `COMMIT`,
required by the new deferred guard, not by any change to the approve statement's own SQL.

### Proof 6 — `otp_challenge.channel` CHECK constraint

```
=== PROOF 6: otp_challenge.channel CHECK constraint ===
--- invalid channel value: expect REJECTED ---
--- valid channel value: expect SUCCESS ---
ERROR:  new row for relation "otp_challenge" violates check constraint "otp_challenge_channel_check"
DETAIL:  Failing row contains (3456e944-d104-45a3-9326-096708b67265, aaaaaaaa-0000-0000-0000-000000000001, telegram, \x2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a48..., \xa1fce4363854ff888cff4b8e7875d600c2682390412a8cf79b37d0b11148b0..., 2026-08-23 21:29:07.90808+00, 2026-08-23 21:34:07.90808+00, 0, null).
INSERT 0 1
=== final otp_challenge rows for this profile ===
              profile_id              | channel 
--------------------------------------+---------
 aaaaaaaa-0000-0000-0000-000000000001 | sms
(1 row)
```
`channel = 'telegram'` rejected by the new CHECK constraint; `channel = 'sms'` accepted.

### Proof 7 (added post-review) — the stale-history-row bypass is closed

Reproduces exactly the scenario `@agent-reviewer` found live against the first draft of
`require_status_history()`, now run against the fixed migration:

```
=== PROOF 7: stale-history-row bypass on a repeatable pair -- expect REJECTED on the third hop ===
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 1
COMMIT
--- E1: in_progress -> blocked_scan, WITH history row (expect OK) ---
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
--- E2: blocked_scan -> in_progress, WITH history row (expect OK) ---
BEGIN
UPDATE 1
INSERT 0 1
INSERT 0 1
COMMIT
--- E3: in_progress -> blocked_scan AGAIN, with NO new history row (the SAME pair as E1) -- expect REJECTED AT COMMIT ---
BEGIN
ERROR:  status change on app.profile aaaaaaaa-0000-0000-0000-000000000007 (in_progress -> blocked_scan) has no matching (most recent) app.profile_status_history row
CONTEXT:  PL/pgSQL function app.require_status_history() line 22 at RAISE
UPDATE 1
--- result: status and history row count (expect: still in_progress, 3 history rows -- E3 rolled back) ---
              profile_id              |   status    
--------------------------------------+-------------
 aaaaaaaa-0000-0000-0000-000000000007 | in_progress
(1 row)

 history_rows 
--------------
            3
(1 row)
```
E1 and E2 (a legitimate block/unblock cycle, each with its own history row) both succeed. E3
repeats E1's exact `(from,to)` pair with no new history row — under the original code this
would have silently reused E1's stale row and committed; under the fix it is rejected at
`COMMIT`, and the history row count stays at 3 (the three legitimate transitions only).

---

## 5. `docs/components/civil-registry.md`

New component card, structured like `docs/components/persistence.md`. Records the observed
endpoint (`POST http://196.1.223.27:9090/CRSAPI/Services/GetCRSData`, request `{"NID": ...}`,
no authentication present), the full response field list, and the flagged items: not
REST-shaped/no TLS, no error contract (feeds R-031), `ADDRESS` not mapping to the structured
admin-division hierarchy, `PHOTOGRAPH` as a second portrait alongside Uqudo's, bank-internal-
only access, and the four-generation name model the `app` schema does not yet accommodate
(feeds R-032).

**Provenance markers corrected after review** (§10 finding): the first draft put a blanket
`[OBSERVED]` header over the "What this contract is not" section, but three of its six
bullets were analysis or supplied context, not raw observation (the `app.registry_result`
schema-mismatch claims, the "second portrait" / AD-004 consequence, and the "bank-internal
access" statement). Each bullet now carries its own accurate marker. Also corrected: the
`ADDRESS` gap was originally located against `app.registry_result`'s birthplace columns
specifically; `docs/components/uqudo-api-findings.md` line 91 shows Uqudo's SDN_ID exposing
`placeOfBirth` and `address` as two *separate* fields, so which one CR's single `ADDRESS`
corresponds to is itself unverified — and if it is a residence address, the schema has no
column for it at all, a larger gap than originally stated. The AD-002a attribution was also
corrected: the `identityNumber`-not-`documentNumber` finding is from the S1-12 research
(`uqudo-api-findings.md`), which itself corrects an AD-002a assumption, not AD-002a's own
text.

---

## 6. PROJECT_PLAN.md and RISKS.md

- **AD-002b's line** amended: notes the contract is now observed once, points at the new
  card, and states explicitly that the decision itself stays **OPEN** — the error contract
  and the access route are unresolved.
- **R-031** added: the Civil Registry response shape is known from exactly one successful
  sample, no error contract exists. 🔴 Live.
- **R-032** added: the `app` schema does not accommodate the real Civil Registry name/address
  model. 🔴 Live.
- **R-030 left 🔴 Live, not retired.** The first draft implementation would have justified
  retirement; `@agent-reviewer` found the stale-history-row bypass described in §3b before
  that happened. R-030's mitigation column now describes what shipped and was proven (V0020/
  V0021, all seven proofs), but the row stays open rather than retired — a single session's
  proof, including a proof added specifically to close a bug the same session's review found,
  is not the bar for retiring a database-integrity risk. Retiring it was never explicitly
  asked for by the task in any case (only R-031/R-032 were).

---

## 7. Files created / edited

**Root**
- `.gitattributes` — `*.sql text eol=lf` added.
- `PROJECT_PLAN.md` — AD-002b line amended.
- `RISKS.md` — R-031, R-032 added; R-030's mitigation column updated, left Live.
- `EXECUTION_PLAN.md` — S2-05 row set to ✅; S2-03's row corrected from a stale ⬜ to ✅
  (found by the reviewer — it was committed and complete but the row was never updated).
- `.claude/settings.json` — auto-recorded local Bash permission-allowlist additions from tool
  use this session (flyway/fvm invocations) — not an application change, same as S2-03 §7's
  precedent. `@agent-reviewer` flagged this as outside the task's stated scope; kept per that
  established precedent rather than dropped, since it carries no secrets and reflects nothing
  but tool-invocation patterns already used in this session.

**backend/**
- `src/main/resources/db/migration/V0020__app_status_transition_guard.sql` — new; includes
  the post-review fix described in §3b.
- `src/main/resources/db/migration/V0021__otp_challenge_channel_check.sql` — new.
- `src/test/java/sd/gov/bank/fruserupdate/AppSchemaConnectivityIntegrationTest.java` — new
  `@Test statusTransitionGuardIsWiredAndEnforced()`, added after review flagged the new DDL
  had no automated regression guard (the same gap S2-03 closed for its own migrations).
  Asserts the `app.status_transition` row count (21), both trigger names present in
  `pg_trigger`, and that the illegal `in_progress → approved` transition raises — run on its
  own JDBC connection with autocommit off and an explicit rollback, so it needs no history-row
  setup and leaves no data behind.

**docs/**
- `components/civil-registry.md` — new, provenance markers corrected after review (§5).
- `sessions/2026-08-23-s2-05-status-guard.md` — this report.

---

## 8. iOS package check

Not applicable — no Flutter package was added this session (mobile tier untouched).

---

## 9. Flyway output — from scratch and idempotent re-run, verbatim (post-fix state)

From a freshly rebuilt container (`docker compose down -v && up -d`):
```
[INFO] --- flyway:12.4.0:migrate (default-cli) @ backend ---
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] Schema history table "public"."flyway_schema_history" does not exist yet
[INFO] Successfully validated 21 migrations (execution time 00:00.202s)
[INFO] Creating Schema History table "public"."flyway_schema_history" ...
[INFO] Current version of schema "public": << Empty Schema >>
[INFO] Migrating schema "public" to version "0001 - roles and schemas"
[INFO] Migrating schema "public" to version "0002 - audit tables"
[INFO] Migrating schema "public" to version "0003 - audit functions"
[INFO] Migrating schema "public" to version "0004 - audit grants and triggers"
[INFO] Migrating schema "public" to version "0005 - app status and profile"
[INFO] Migrating schema "public" to version "0006 - app customer data"
[INFO] Migrating schema "public" to version "0007 - app channels and otp"
[INFO] Migrating schema "public" to version "0008 - app identity artifacts"
[INFO] Migrating schema "public" to version "0009 - app status history"
[INFO] Migrating schema "public" to version "0010 - app grants and triggers"
[INFO] Migrating schema "public" to version "0011 - ref registry and fold"
[INFO] Migrating schema "public" to version "0012 - ref grants"
[INFO] Migrating schema "public" to version "0013 - close deferred reference fks"
[INFO] Migrating schema "public" to version "0014 - seed occupation"
[INFO] Migrating schema "public" to version "0015 - seed branch"
[INFO] Migrating schema "public" to version "0016 - seed admin division"
[INFO] Migrating schema "public" to version "0017 - seed income source"
[INFO] Migrating schema "public" to version "0018 - seed education level"
[INFO] Migrating schema "public" to version "0019 - seed rejection reason"
[INFO] Migrating schema "public" to version "0020 - app status transition guard"
[INFO] Migrating schema "public" to version "0021 - otp challenge channel check"
[INFO] Successfully applied 21 migrations to schema "public", now at version v0021 (execution time 00:01.243s)
[INFO] BUILD SUCCESS
```

Second run, no changes in between (idempotent):
```
[INFO] --- flyway:12.4.0:migrate (default-cli) @ backend ---
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] Successfully validated 21 migrations (execution time 00:00.169s)
[INFO] Current version of schema "public": 0021
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

---

## 10. `./mvnw test -Pdb-integration-test`

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 59.29 s -- in sd.gov.bank.fruserupdate.AppSchemaConnectivityIntegrationTest
[INFO] Running sd.gov.bank.fruserupdate.BackendApplicationTests
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.242 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
Three `AppSchemaConnectivityIntegrationTest` methods (the original connectivity assertion,
S2-03's `refSchemaSeedRowCountsMatchExpected`, and this session's new
`statusTransitionGuardIsWiredAndEnforced`) plus `BackendApplicationTests`, all passing through
the full 21-migration chain, post-fix.

---

## 11. `@agent-reviewer` review

Run against the full diff and the S2-05 task before marking it done, per CLAUDE.md's hard
rule. Findings and what was done about each:

**BLOCKER — `require_status_history()` accepted a stale history row on repeatable pairs.**
Reproduced live by the reviewer (`in_progress → blocked_scan → in_progress → blocked_scan`
again, the third hop with no new history row, committed silently). **Fixed**: the check now
binds to the profile's most-recent history row rather than to pair-existence anywhere in
history. See §3b. Re-verified with a full rebuild and all proofs re-run, plus a new proof 7
reproducing the exact scenario against the fix.

**BLOCKER — the session report the diff pointed to did not exist yet.** True at the moment
the review ran: the report (this file) was written after the reviewer was launched, as part
of the same session's normal flow, not before. It exists now with every claim it makes backed
by output pasted in this report.

**SHOULD FIX — `.claude/settings.json` change outside the task's stated scope.** Kept, per
the precedent set in S2-03 §7 (same kind of auto-recorded permission entries, documented
there as "not an application change"). See §7 above for the specific reasoning.

**SHOULD FIX — new DDL carried no automated test.** Fixed: added
`statusTransitionGuardIsWiredAndEnforced()` to `AppSchemaConnectivityIntegrationTest`. See §7
and §10.

**SHOULD FIX — provenance markers over-claimed in the Civil Registry card.** Fixed: the
blanket `[OBSERVED]` section header was replaced with per-bullet markers distinguishing raw
observation from analysis and supplied context. See §5.

**NOTE — inference presented as observation, and mis-attributed** (the `identityNumber`
finding wrongly attributed to AD-002a itself rather than the S1-12 research that corrects
it). **Fixed.** See §5.

**NOTE — the `ADDRESS` gap was located against the wrong/narrower columns.** **Fixed**: the
card now notes the ambiguity between `placeOfBirth` and `address` as two separate Uqudo
fields, and that a residence-address reading leaves the schema with no column at all rather
than a birthplace mismatch alone. See §5.

**NOTE — the observed contract's authentication was never addressed.** **Fixed**: the card
now states plainly that no authentication is present on the observed request and that
whether one is required under other conditions is unverified. See §5.

**NOTE — R-030 retirement was not asked for, and (combined with the BLOCKER) overstated what
shipped.** **Fixed**: reverted to 🔴 Live. See §6.

**NOTE — the adjacent S2-03 row in EXECUTION_PLAN.md was stale** (committed and complete,
still shown ⬜). **Fixed**: corrected to ✅. See §7.

**Verified correct by the reviewer, no changes needed**: V0021's CHECK is byte-identical to
`profile_channel`'s via `pg_get_constraintdef`; `UNIQUE NULLS NOT DISTINCT` is valid PG18
syntax; the BEFORE trigger's NULL-handling and no-op short-circuit both work; the R-030
scenario is blocked; the AD-005 §4.3 approve statement's SQL text is completely unchanged and
still works, including `submitted → rejected → approved` in one chain; no misfire on
multi-row transactions (deferred `OLD` is the correct pre-update image, per-row); no seeded
row has `approved` or `terminated_registry_mismatch` as a source; the transition set was
cross-checked against every relevant journey-doc section with no missing or invented pair;
grants are sufficient and all behavioural tests ran as `fru_app`, not owner; the
`.gitattributes` fix is genuine (`i/lf w/lf` for all 19 pre-existing files); no hard-rule
violations (no generated-file paths, no `../FIB` content, no secrets, no real PII in the
Civil Registry card).

---

## 12. Standard gates — all three tiers, verbatim

### backend/ — `./mvnw test`
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 18.14 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] BUILD SUCCESS
```

### backend/ — `./mvnw verify`
```
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 3 files clean - 0 needs changes to be clean, 0 were already clean, 3 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```
An initial `./mvnw verify` run failed Spotless on the new test file (an unused `java.sql.Connection`
import left over from an earlier draft of `statusTransitionGuardIsWiredAndEnforced()`). Fixed
by running `./mvnw spotless:apply` (per CLAUDE.md: edit the source and re-run the tool, never
hand-format), which removed the unused import; the run above is the clean re-run.

**JaCoCo bundle class count: still 0**, unchanged — this session added SQL migrations and one
JUnit test class, no main-source Java. Consistent with the pre-existing S1-07/S1-08/R-009
finding, still tracked there.

### mobile/ (unchanged — not touched this session)
```
$ fvm flutter test
00:00 +0: loading .../widget_test.dart
00:00 +0: Counter increments smoke test
00:02 +1: All tests passed!

$ fvm dart run tool/check_coverage.dart
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 83.4s)
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

## 13. What failed, summarized

- **A real bypass in the deferred constraint trigger**, found by `@agent-reviewer`: the
  matching-history-row check bound to "any row ever matching this pair" rather than "the most
  recent row", letting a repeatable status pair reuse a stale history row and skip the guard
  on its second occurrence. Fixed before this report was finalized; see §3b, §4 proof 7, §11.
- **An unused import (`java.sql.Connection`)** left in the new integration test after an
  earlier draft of `statusTransitionGuardIsWiredAndEnforced()`, caught by `./mvnw verify`'s
  Spotless check. Fixed with `./mvnw spotless:apply`.
- Everything else — the six originally-required proofs, the Flyway runs, the connectivity
  integration test, and all three tiers' standard gates — passed on first run.

---

## Commit/push proof

```
$ git log --oneline -1
c784c9b feat: S2-05 status-transition guard, otp_challenge CHECK, gitattributes gap, CR card

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main`: `25ab757..c784c9b  main -> main`.
