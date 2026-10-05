# S2-09 — Field 22 source correction; provenance matrix version 2; parser sequencing constraint

Date: 2026-08-27. Sprint 2.

---

## 0. Commit check

```
$ git log --oneline -3
ce54c96 docs: record S2-08 commit/push proof in session report
8d11970 feat: S2-08 schema amendment for the corrected provenance mapping
c99da1a docs: record S2-07 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
Changes not staged for commit:
        modified:   docs/journeys/field-provenance.md
```

HEAD is `ce54c96` as expected (S2-08's proof-recording commit), in sync with `origin/main`.
The one uncommitted change at session start, `docs/journeys/field-provenance.md`, predates this
session — it already carried the Version 2 content the task describes (field 22's corrected
row, the `Version: 2 · Effective 2026-08-27` marker, and a closing-paragraph reference to the
still-outstanding schema gap). Diffed and confirmed against the task's own description of
Version 2 before doing anything else; not an edit I made.

---

## 1. EXECUTION_PLAN.md

Added the S2-09 row, per the task, status ✅ (all of step 6's gates passed — see §7 below).

---

## 2. The field 22 correction

S2-08 implemented field 22 as `app.scan_result.birth_country_code`, a generated column
mirroring `issuing_country` (the MRZ `issuer`, alpha-3). Wrong: the MRZ issuer is the
*document's* issuing country, not birth country, and since only Sudanese documents are accepted
it read `'SDN'` for every customer including one born abroad.

Two new migrations, `backend/src/main/resources/db/migration/`:

**V0028__app_birth_country_customer_entry.sql**
- `ALTER TABLE app.scan_result DROP COLUMN birth_country_code;` — removes the S2-08 column.
  `issuing_country` (V0008) is untouched and is now explicitly documented as the schema's only
  alpha-3 country column — every other `*_country_code` column (country_of_residence, home,
  work, and now birth) is alpha-2 against `ref.reference_item`'s `country` list.
- Adds customer-entered birth country to `app.profile_customer_data`, reproducing V0025's
  `country_of_residence_code/_version/_list` pattern exactly: `birth_country_code text`,
  `birth_country_version int`, `birth_country_list text GENERATED ALWAYS AS ('country') STORED`,
  a pairing CHECK (`birth_country_code_version_paired`), and a composite FK
  (`birth_country_fk`) to `ref.reference_item (list_code, version, item_code)`.

**V0029__app_provenance_matrix_version_2.sql**
- Seeds `app.provenance_matrix_version` row 2, `effective_date = '2026-08-27'`, `note`
  recording that field 22 moved from S2 to S3 and why.

V0023–V0027 (S2-08's already-applied migrations) are untouched — Flyway checksum-protects
applied migrations, so their stale in-file comments about `birth_country_code`'s alpha-3
alphabet are not hand-edited. Instead V0028 carries a forward-looking correction paragraph
naming V0025's specific stale claims (birth country "lives in a different table", the Sudan
check must compare against `'SDN'`) and stating what's true now: `birth_country_code` lives on
`app.profile_customer_data` itself, alongside `birth_state_code`, and the correct Sudan test is
`birth_country_code = 'SD'` (alpha-2), not `'SDN'`.

### Judgment call: no same-table CHECK tying birth_state to birth_country

V0025's comment cited the cross-table barrier — birth country then lived on `scan_result`,
birth state on `profile_customer_data`, and Postgres CHECK constraints can't cross tables — as
the *sole* reason no such CHECK existed. V0028 removes that barrier: both columns are now on
`profile_customer_data`, and `V0006`'s `sudan_uses_codes` CHECK is exactly this precedent for
the home address.

Deliberately not added, and the reasoning is now recorded in V0028's own comment: **the journey
does not collect field 22 at all yet.** `docs/journeys/customer.md`'s Stage 3 field list (sex,
ethnicity, country of residence) has no birth-country/state/city step — a pre-existing gap that
S2-06/S2-08 already opened for fields 23/24 and that this session extends rather than
introduces. A CHECK constraining a field the journey cannot yet submit is premature; it belongs
with whichever future session wires that journey stage. `@agent-reviewer` confirmed this
disposition is correct (see §8) and flagged that the reasoning needed to be written down, not
just decided — done, in V0028's header.

### R-034 and the CLAUDE.md hard rule

Both added verbatim per the task text — unrelated to field 22, bundled into this task by the
task author. R-034 (Uqudo JWS parser unvalidated until S1-02 runs) added to RISKS.md directly
after R-033. The quarantine rule added to CLAUDE.md's Hard rules, directly after the existing
"Uqudo results are a JWS compact string... SERVER-SIDE ONLY" rule (same topic).

### R-032 wording

`@agent-reviewer` flagged that field-provenance.md's closing paragraph originally said "R-032
is downgraded to Watching" — but R-032 was already 🟡 Watching since S2-08; this session doesn't
change its status. Corrected to "R-032 remains Watching". Separately, added one sentence to
R-032's own mitigation column in RISKS.md noting that S2-08's field 22 implementation was itself
wrong and was corrected here.

---

## 3. RISKS.md — R-034

Added verbatim per the task text, status 🔴 Live, positioned directly after R-033 and before the
"Retired at creation" block.

---

## 4. CLAUDE.md hard rule

Added verbatim per the task text, directly after the existing JWS server-side-only rule.

---

## 5. Proofs

All proofs below were re-run against a container rebuilt from scratch (`docker compose down -v
&& up -d`) after V0028 gained its final correction paragraph (§2), so what's pasted here matches
the final file content exactly — not a pre-fix run.

### `birth_country_code` gone from `app.scan_result`

```
=== birth_country_code gone from app.scan_result ===
 column_name
-------------
(0 rows)
```

### `birth_country_code`/`_version`/`_list` present on `app.profile_customer_data`

```
      column_name      | data_type | is_generated
-----------------------+-----------+--------------
 birth_country_code    | text      | NEVER
 birth_country_list    | text      | ALWAYS
 birth_country_version | integer   | NEVER
(3 rows)
```

### New FK rejecting an unknown code, accepting a known one

```
=== birth_country_fk: unknown code ZZ -- must be REJECTED ===
ERROR:  insert or update on table "profile_customer_data" violates foreign key constraint "birth_country_fk"
DETAIL:  Key (birth_country_list, birth_country_version, birth_country_code)=(country, 1, ZZ) is not present in table "reference_item".
=== birth_country_fk: known code SD -- must be ACCEPTED ===
 birth_country_code | birth_country_version | birth_country_list
--------------------+-----------------------+--------------------
 SD                 |                     1 | country
(1 row)

UPDATE 1
```

### Pairing CHECK closing the MATCH SIMPLE NULL-member bypass

```
=== pairing CHECK: birth_country_code set, version NULL -- must be REJECTED ===
ERROR:  new row for relation "profile_customer_data" violates check constraint "birth_country_code_version_paired"
DETAIL:  Failing row contains (00000000-0000-0000-0000-0000000000f1, ... SD, null, country).
```

### `app.provenance_matrix_version` — rows 1 and 2

```
 version | effective_date |                                                                                                                                    note
---------+----------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
       1 | 2026-08-23     | docs/journeys/field-provenance.md as filed at S2-06 and amended into the schema at S2-08 -- product-owner decisions dated 2026-08-23
       2 | 2026-08-27     | docs/journeys/field-provenance.md Version 2 -- field 22 (birth country) corrected from S2 (MRZ issuer) to S3 (customer entry, ISO 3166 list): the MRZ issuer is the document's issuing country, which reads SDN for every customer including those born abroad. See S2-09.
(2 rows)
```

### Regression — status-transition guard, S2-05/V0020

```
=== REGRESSION: status-transition guard rejects raw in_progress -> approved ===
ERROR:  illegal status transition on app.profile 00000000-0000-0000-0000-0000000000f1: in_progress -> approved
CONTEXT:  PL/pgSQL function app.check_status_transition() line 19 at RAISE
```

### Regression — deferred constraint trigger, no matching history row, S2-05/V0020

```
=== REGRESSION: legal transition (in_progress -> submitted) with NO matching history row -- must fail at COMMIT ===
BEGIN
UPDATE 1
ERROR:  status change on app.profile 00000000-0000-0000-0000-0000000000f1 (in_progress -> submitted) has no matching (most recent) app.profile_status_history row
CONTEXT:  PL/pgSQL function app.require_status_history() line 22 at RAISE
```

### Regression — four-eyes rule, S2-02 §7c's exact approve statement, as `fru_app`

```
########## APPROVE as fru_app, operator_A (the manual completer) ##########
UPDATE 0
=== status after operator_A's attempt (expect: still submitted) ===
  status
-----------
 submitted
(1 row)

########## APPROVE as fru_app, operator_B (a different operator) ##########
BEGIN
UPDATE 1
INSERT 0 1
COMMIT
=== status after operator_B (expect: approved) ===
  status
----------
 approved
(1 row)
```

### Regression — `profile_status_history` append-only against the table owner, S2-02 §7e

```
=== UPDATE as fru_migrator (table owner) -- must be REJECTED ===
ERROR:  app.profile_status_history is append-only; UPDATE is not permitted
=== DELETE as fru_migrator (table owner) -- must be REJECTED ===
ERROR:  app.profile_status_history is append-only; DELETE is not permitted
=== TRUNCATE as fru_migrator (table owner) -- must be REJECTED ===
ERROR:  app.profile_status_history is append-only; TRUNCATE is not permitted
=== rows still intact ===
              profile_id              | seq |  actor_id  |  to_status
--------------------------------------+-----+------------+-------------
 00000000-0000-0000-0000-0000000000f1 |   1 |            | in_progress
 00000000-0000-0000-0000-0000000000f1 |   2 |            | submitted
 00000000-0000-0000-0000-0000000000f1 |   3 | operator_A | submitted
 00000000-0000-0000-0000-0000000000f1 |   4 | operator_B | approved
(4 rows)
```

No regression. All S2-02/S2-05 controls behave identically against the amended schema.

---

## 6. Integration test extension

`AppSchemaConnectivityIntegrationTest`:
- `refSchemaSeedRowCountsMatchExpected`: added `birth_country_fk` to the FK-name `IN (...)`
  list, expected count 5 → 6.
- `provenanceMatrixVersionIsSeeded`: expected row count 1 → 2, plus a new assertion that the
  seeded versions are exactly `{1, 2}`.

---

## 7. Verify, gates, review

### Flyway — from scratch (final state, post review-fix)

```
[INFO] Schema history table "public"."flyway_schema_history" does not exist yet
[INFO] Successfully validated 29 migrations (execution time 00:00.095s)
[INFO] Current version of schema "public": << Empty Schema >>
[INFO] Migrating schema "public" to version "0001 - roles and schemas"
...
[INFO] Migrating schema "public" to version "0027 - app provenance matrix version"
[INFO] Migrating schema "public" to version "0028 - app birth country customer entry"
[INFO] Migrating schema "public" to version "0029 - app provenance matrix version 2"
[INFO] Successfully applied 29 migrations to schema "public", now at version v0029 (execution time 00:00.952s)
[INFO] BUILD SUCCESS
```

### Flyway — idempotent re-run

```
[INFO] Successfully validated 29 migrations (execution time 00:00.129s)
[INFO] Current version of schema "public": 0029
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

### `./mvnw test -Pdb-integration-test` (final, post review-fix)

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
5 tests: `springBootFlywayMigratesAgainstRealPostgresAndAppAuditSchemasArePresent`,
`refSchemaSeedRowCountsMatchExpected` (now asserting 6 FKs), `provenanceMatrixVersionIsSeeded`
(now asserting 2 rows), `statusTransitionGuardIsWiredAndEnforced`, `BackendApplicationTests`.

### backend/ — `./mvnw test`

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.65 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
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
0 classes, unchanged from every prior session this sprint — no entity/service/controller code
exists yet (explicit OUT OF SCOPE).

### mobile/ (unchanged — not touched this session)

```
$ fvm flutter test
00:01 +1: All tests passed!

$ fvm dart run tool/check_coverage.dart
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

$ fvm flutter analyze
No issues found! (ran in 40.0s)
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

### field-provenance.md's closing paragraph

Updated (only that paragraph — no other edit) to state the schema now matches Version 2 in
full: S2-09 closed the field 22 gap, and R-032 remains Watching (not "is downgraded" — it was
already Watching before this session, per `@agent-reviewer`'s finding, see §8).

---

## 8. `@agent-reviewer` findings and disposition

Run against the full diff (both new migrations read in full, since they're untracked and don't
show in `git diff`) and the S2-09 task text. Verified live: migrated a real container end to
end (29 migrations, `flyway:migrate` exit 0) and ran `./mvnw spotless:check` (exit 0).

**Confirmed correct, no finding:** V0028 reproduces V0025's `country_of_residence` pattern
exactly (code + version + generated-list + pairing CHECK + FK); alpha-2 is the correct alphabet
for the new column (`V0022` seeds `item_code` as alpha-2, alpha-3 lives in `extra`); migration
numbering V0028/V0029 is contiguous and correctly ordered; V0023–V0027 untouched; V0010's
table-level grants already cover the new columns, so no grant migration is needed; the test's
new `List` import was already present; no secrets, no generated files touched, nothing outside
the stated scope; `field-provenance.md`'s Version 2 content and row-22 edit pre-date this
session (confirmed against the session-start git diff), so the only S2-09 edit to that file is
the closing paragraph, in scope.

**SHOULD-FIX 1 — V0025's stale birth-country comment was not forward-corrected.** The task's
own out-of-scope note requires corrections to V0023–V0027's stale comments to happen as new
forward migrations, not hand-edits to applied history. The first draft of V0028 warned
generically that alphabets differ but never named V0025's specific claims (birth country "lives
in a different table"; the Sudan check must compare against `'SDN'`) or stated what's true now.
**Fixed:** added a paragraph to V0028 explicitly superseding those two claims and stating the
correct test (`birth_country_code = 'SD'` on `profile_customer_data`). Re-verified live
(from-scratch + idempotent Flyway runs, all proofs, all regressions — §5/§7 above are the
post-fix output).

**NOTE 2 — verdict requested on the omitted same-table CHECK.** Reviewer's own assessment:
correctly left out (no journey stage collects field 22 yet, so a CHECK on an uncollectable
field is premature), but the reasoning wasn't written down anywhere and V0025's comment named
the now-obsolete cross-table barrier as the sole reason. **Fixed:** folded the decision and its
actual reason (customer.md Stage 3 has no birth-country/state/city step; `sudan_uses_codes` in
V0006 is the existing precedent for when it's wired) into V0028's header comment. No schema
change — no BACKLOG entry opened either, since journey-stage design is outside every version of
this task's stated scope and the gap already existed for fields 23/24 since S2-06/S2-08.

**NOTE 3 — journey never asks for field 22 (or 23/24).** Pre-existing gap, extended not
introduced by this session; journey edits are not among the task's deliverables. Recorded here,
not acted on.

**NOTE 4 — R-032 wording said "downgraded" when it was already Watching.** Fixed in both
field-provenance.md's closing paragraph and RISKS.md's R-032 mitigation column — see §2.

**NOTE 5 — R-034's mitigation describes a parser and stub that don't exist yet
(`backend/src/main/java` has only `BackendApplication.java`).** The row's text was mandated
verbatim by the task; no correction made, consistent with the reviewer's own "no correction
required if text was mandated as-is."

**NOTE 6 — no session report existed at review time.** Expected at that point in the task's
ordering; this file is that report.

The reviewer could not independently verify that R-034's and the CLAUDE.md rule's text
reproduce the task's given wording verbatim, since it did not have the original task text.
Confirmed directly: both were copied character-for-character from the task.

---

## 9. Files created / edited

**New:**
- `backend/src/main/resources/db/migration/V0028__app_birth_country_customer_entry.sql`
- `backend/src/main/resources/db/migration/V0029__app_provenance_matrix_version_2.sql`
- `docs/sessions/2026-08-27-s2-09-field-22-correction.md` (this file)

**Edited:**
- `EXECUTION_PLAN.md` — S2-09 row added, → ✅.
- `RISKS.md` — R-034 added; R-032's mitigation column updated.
- `CLAUDE.md` — Uqudo JWS parser quarantine hard rule added.
- `docs/journeys/field-provenance.md` — closing paragraph only, updated to state the schema now
  matches Version 2 in full, and to correct "downgraded" to "remains" for R-032.
- `backend/src/test/java/sd/gov/bank/fruserupdate/AppSchemaConnectivityIntegrationTest.java` —
  FK-count assertion (5 → 6); provenance-version assertion (1 → 2 rows, versions {1,2}).

## 10. iOS package check

Not applicable — no Flutter package was added this session (mobile tier untouched).

## 11. What failed, summarized

Nothing failed outright. Two things were caught and corrected mid-session, both re-verified
live:
- `docker exec` without `-i` silently drops stdin, so the first attempt at a multi-statement
  `psql` heredoc for setting up a test profile did nothing (no error, no rows) — not a defect,
  a tooling mistake, fixed by adding `-i`.
- The reviewer's SHOULD-FIX 1 in §8 — fixed on the first correction, re-verified with a full
  from-scratch Flyway rebuild, all proofs and all regressions re-run against the final content
  (not just re-read).

---

## Commit/push proof

```
$ git log --oneline -1
12f0a0f feat: S2-09 field 22 source correction; provenance matrix v2

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main`: `ce54c96..12f0a0f  main -> main`.

