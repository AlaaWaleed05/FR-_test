# S2-08 — Schema amendment for the corrected provenance mapping

Date: 2026-08-27. Sprint 2.

---

## 0. Commit check

```
$ git log --oneline -3
c99da1a docs: record S2-07 commit/push proof in session report
3b0123b feat: S2-07 seed ISO 3166 country reference list
837586a docs: record S2-06 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```

The task text expected S2-07's substantive commit at `66e4ab4`. The actual feat commit is
`3b0123b` (`docs: S2-07 seed ISO 3166 country reference list`), with the proof-recording commit
`c99da1a` on top — same pattern every prior session used, just a different hash than the task
text stated. No other sign of a problem; tree was clean at session start.

---

## 1. RISKS.md — R-033, PROJECT_PLAN.md — AD-002f

Added R-033 (reference-list `content_hash` doesn't cover `extra`) verbatim as specified, and
appended the one sentence to AD-002f's description in PROJECT_PLAN.md, verbatim as specified.

---

## 2. Provenance versioning — the approach and why

Applied the same pattern `ref.reference_list_version` / `ref.profile_reference_version` already
use (AD-005, S2-03), in the `app` schema rather than `ref`:

```sql
CREATE TABLE app.provenance_matrix_version (
  version        int PRIMARY KEY,
  effective_date date NOT NULL,
  note           text NOT NULL
);
CREATE TABLE app.profile_provenance_matrix_version (
  profile_id  uuid PRIMARY KEY REFERENCES app.profile ON DELETE RESTRICT,
  version     int  NOT NULL REFERENCES app.provenance_matrix_version(version),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
```

**Why `app`, not `ref`:** this versions an internal derivation-rule document — which source
resolves which field — never data delivered to the mobile app. It doesn't belong in the
reference-data delivery contract AD-002f governs.

**Why one row per profile, not per field or per source:** mirrors
`ref.profile_reference_version`'s one-row-per-list shape. The matrix is one governing document;
a profile is resolved against one version of it as a whole, not field-by-field.

Seeded `version = 1`, `effective_date = '2026-08-23'` (the date on the product-owner decisions
field-provenance.md itself records), `note` pointing at the document and this session.
**field-provenance.md itself carries no version marker today** — per the task's explicit
instruction not to edit that file, this was not added. **The user should add something like
`Version: 1 (2026-08-23)` near the document's header** so this table's `version = 1` has
something concrete to point at; until then it references the document's content as filed, not
a labelled version of it.

No entity/service/repository code exists yet to actually write a
`profile_provenance_matrix_version` row (OUT OF SCOPE, same as everything else this session) —
this is the storage the future resolution code will use.

---

## 3. Schema amendment — migrations V0023–V0027

Five new migrations, `backend/src/main/resources/db/migration/`:

- **V0023__app_registry_result_names_and_gaps.sql** — decomposes `app.registry_result`'s
  single `full_name_ar`/`full_name_en`/`mother_name` into the real four-part paternal chain
  (fields 5), four-part maternal chain (field 8), and separate Latin pair (field 6). Drops
  `citizenship` (no CR field corresponds to it). Adds `sex_registry` and `date_of_birth`
  (fields 9 and 21 gap-fill — see below) and `raw_address_ar` (the registry's raw address
  string, stored for comparison, never populating the profile address).
- **V0024__app_scan_result_birth_data.sql** — adds `birth_city` (field 23, S2) and
  `birth_country_code` (field 22) to `app.scan_result`.
- **V0025__app_customer_data_new_fields_and_country_fks.sql** — adds `ethnicity` (field 10),
  `country_of_residence_code`/`_version`/`_list` + FK (field 11), `birth_state_code`/`_text`
  and `birth_city_text` (field 24 and field 23's S3 fallback), closes the two country FKs
  S2-07 deferred (`home_country_fk`, `work_country_fk`), and changes
  `monthly_expenses_sdg` from `numeric(14,2)` to `bigint` (field 19).
- **V0026__app_artifact_kinds_signature_and_portraits.sql** — replaces `artifact_ref`'s
  single `'portrait'` kind with `'portrait_uqudo'`/`'portrait_registry'` (field 52) and adds
  `'signature'` (field 49).
- **V0027__app_provenance_matrix_version.sql** — the two tables from §2.

### Judgment calls made (not left as open questions)

- **Field 22 (birth country) is a generated column mirroring field 48's `issuing_country`**
  (`GENERATED ALWAYS AS (issuing_country) STORED`): field-provenance.md cites the same "MRZ
  issuer field" as the source for both, so this avoids a second independently-written column
  that could silently drift from the first.
  - **Correction after review:** this column inherits `issuing_country`'s alphabet, which is
    the MRZ `issuer` field under ICAO 9303 — a **three-letter** code (e.g. `'SDN'`), not the
    two-letter ISO 3166-1 alpha-2 the country reference list and every other `*_country_code`
    column in this amendment use. It is deliberately **not** FK'd to `ref.reference_item` for
    this reason, and both V0024 and V0025 now carry an explicit comment warning that an
    app-layer "is birth country Sudan" check against `birth_state_code` must compare against
    `'SDN'`, not `'SD'`.
- **Gap-fill beyond the task's explicit bullets, required by "do not omit any":** fields 9
  (sex) and 21 (date of birth) both have Source = S1, but no column anywhere held an
  S1-authoritative value before this session (`profile_customer_data.sex_declared` is
  documented "interface only; NOT stored truth"; `scan_result.date_of_birth` is S2's value).
  Added `registry_result.sex_registry` and `registry_result.date_of_birth`.
- **Field 7 (national number) gets no new column.** civil-registry.md documents
  `IDENTITY_NUMBER` as "echo of the request NID" — definitionally identical to
  `scan_result.identity_number`, so S1's precedence has nothing to resolve.
- **`registry_result.citizenship` dropped** — no field in the observed Civil Registry
  contract corresponds to it; nationality (field 4) is Uqudo-sourced and already lives on
  `scan_result.nationality`.
- **Field 24 (birth state) has no schema-level CHECK tying it to birth country.** Birth
  country lives on `scan_result` (system-derived); birth state on `profile_customer_data`
  (customer-entered, no source) — different tables, and Postgres CHECK constraints can't
  cross tables. Left to the application layer.
- **Field 23's S3 fallback (`birth_city_text`) placed on `profile_customer_data`**, plain
  free text with no code/FK — matching how `home_city`/`work_city` are already plain free
  text (only the country/state/locality levels get a code+text split).
  - **Added after review** — the first draft only added `birth_city` to `scan_result` (the
    S2 half) and left the "else free text" S3 half with nowhere to live, an inconsistency
    with the adjacent `birth_state_text` decision the reviewer caught.
- **`ethnicity`/`country_of_residence_code` stay nullable at the DB level**, matching the
  existing convention for other mandatory-per-journey columns on this table
  (`marital_status`, `education_level`) — completeness is a submission-time application
  check, stages 3–6 save progressively.
- **`monthly_expenses_sdg` type change comment corrected after review.** The first draft
  claimed `bigint` "makes non-integer input a type error at the boundary". Verified live this
  is only true for a quoted/unknown-typed string literal (the actual entry path for a
  "digits only" text field — `bigint`'s own input parser rejects `'12.5'`). An already
  numeric-typed value (an unquoted literal, or a future JDBC `BigDecimal` parameter)
  undergoes PostgreSQL's ordinary assignment cast instead, which **rounds rather than
  errors** (`1234.56` stored as `1235`, no error — reproduced live). The migration comment
  now states this precisely instead of overclaiming.

### Fields requiring no schema change (already correct since S2-02/S2-03)

1–4, 12–18, 20, 25–34 (existing work-address columns besides the new country FK), 35–42
(deliberately customer-entry only, per field-provenance.md's own override —
`registry_result.raw_address_ar` is the registry counterpart), 43–48 (identity document;
`issuing_country` also backs field 22), 50 (`salary_certificate`, already optional), 51 (not
collected — satisfied by the Uqudo scan), 53–54 (`doc_front`/`doc_back`/frames/
`face_audit_trail`, unchanged).

### Full field-by-field trace (all 54 fields)

| # | Field | Column(s) | Status |
|---|---|---|---|
| 1 | Form date | `app.profile.created_at`/`submitted_at` | Unchanged (S2-02) |
| 2 | Bank branch | `app.profile.branch_code` | Unchanged |
| 3 | Customer number | `app.profile.account_number` | Unchanged |
| 4 | Nationality | `app.scan_result.nationality` | Unchanged |
| 5 | Full name, Arabic (paternal chain) | `app.registry_result.name_ar_given/father/grandfather/great_grandfather` | **V0023, decomposed** |
| 6 | Full name, English | `app.registry_result.first_names_en`, `last_name_en` | **V0023, decomposed** |
| 7 | National number | `app.scan_result.identity_number` | Unchanged — S1 is a definitional echo, no new column |
| 8 | Mother's name (maternal chain) | `app.registry_result.name_ar_mother/mother_father/mother_grandfather/mother_great_grandfather` | **V0023, decomposed** |
| 9 | Sex | `app.registry_result.sex_registry` (authoritative); `profile_customer_data.sex_declared` (interface only, unchanged); `scan_result.sex_on_document` (unchanged) | **V0023, gap-fill** |
| 10 | Ethnicity | `app.profile_customer_data.ethnicity` | **V0025, new** |
| 11 | Country of residence | `app.profile_customer_data.country_of_residence_code/_version/_list` + FK | **V0025, new + FK** |
| 12 | Marital status | `app.profile_customer_data.marital_status` | Unchanged |
| 13/14 | Husband's/Wife's name | `app.profile_customer_data.spouse_name` | Unchanged |
| 15 | Has children | `app.profile_customer_data.has_children` | Unchanged |
| 16 | Number of children | `app.profile_customer_data.children_count` | Unchanged |
| 17 | Education level | `app.profile_customer_data.education_level` | Unchanged |
| 18 | Occupation | `app.profile_customer_data.occupation_code/_version/_list` + FK | Unchanged (S2-02/S2-03) |
| 19 | Monthly expenses | `app.profile_customer_data.monthly_expenses_sdg` | **V0025, type changed to bigint** |
| 20 | Income source | `app.profile_income_source` | Unchanged |
| 21 | Date of birth | `app.registry_result.date_of_birth` (authoritative); `scan_result.date_of_birth` (unchanged, S2) | **V0023, gap-fill** |
| 22 | Birth country | `app.scan_result.birth_country_code` (generated, mirrors `issuing_country`) | **V0024, new** |
| 23 | Birth city | `app.scan_result.birth_city` (S2); `profile_customer_data.birth_city_text` (S3 fallback) | **V0024 + V0025, new** |
| 24 | Birth state | `app.profile_customer_data.birth_state_code/_text` | **V0025, new** |
| 25 | Phone | `app.profile_customer_data.phone_number` | Unchanged |
| 26 | Email | `app.profile_customer_data.email_address` | Unchanged |
| 27 | Employer | `app.profile_customer_data.employer_name` | Unchanged |
| 28 | Work country | `app.profile_customer_data.work_country_code` + `_version`/`_list` + FK | **V0025, FK closed** |
| 29 | Work state | `app.profile_customer_data.work_state_code` | Unchanged (no FK, per task scope) |
| 30 | Work province | `app.profile_customer_data.work_locality_code` | Unchanged |
| 31 | Work area | `app.profile_customer_data.work_area` | Unchanged |
| 32 | Work city | `app.profile_customer_data.work_city` | Unchanged |
| 33 | Work street | `app.profile_customer_data.work_street` | Unchanged |
| 34 | Work block | `app.profile_customer_data.work_block` | Unchanged |
| 35 | Home country | `app.profile_customer_data.home_country_code` + `_version`/`_list` + FK | **V0025, FK closed** |
| 36 | Home state | `app.profile_customer_data.home_state_code` | Unchanged |
| 37 | Home province | `app.profile_customer_data.home_locality_code` | Unchanged |
| 38 | Home area | `app.profile_customer_data.home_area` | Unchanged |
| 39 | Home city | `app.profile_customer_data.home_city` | Unchanged |
| 40 | Home street | `app.profile_customer_data.home_street` | Unchanged |
| 41 | Home block | `app.profile_customer_data.home_block` | Unchanged |
| 42 | House number | `app.profile_customer_data.home_house_no` | Unchanged |
| — | (registry's raw address, fields 35–42's S1 counterpart) | `app.registry_result.raw_address_ar` | **V0023, new** — stored for comparison, never populates 35–42 |
| 43 | Document type | `profile_customer_data.identity_type` + `scan_result.document_type` | Unchanged |
| 44 | Document number | `app.scan_result.document_number` | Unchanged |
| 45 | Issue date | `app.scan_result.date_of_issue` | Unchanged |
| 46 | Place of issue | `app.scan_result.place_of_issue` | Unchanged |
| 47 | Expiry date | `app.scan_result.date_of_expiry` | Unchanged |
| 48 | Issuing country | `app.scan_result.issuing_country` | Unchanged — also backs field 22 |
| 49 | Signature | `app.artifact_ref` kind=`'signature'` | **V0026, new** |
| 50 | Salary certificate | `app.artifact_ref` kind=`'salary_certificate'` | Unchanged |
| 51 | Identity documents | — | Not collected (unchanged reasoning) |
| 52 | Portrait (both) | `app.artifact_ref` kind=`'portrait_registry'` / `'portrait_uqudo'` | **V0026, split from singular `'portrait'`** |
| 53 | Document images | `app.artifact_ref` kind IN (`doc_front`,`doc_back`,`doc_front_frame`,`doc_back_frame`) | Unchanged |
| 54 | Liveness audit image | `app.artifact_ref` kind=`'face_audit_trail'` | Unchanged |

---

## 4. Proofs — all verbatim, against the final (post-review-fix) schema

### Monthly expenses — digits only

```
=== monthly_expenses_sdg: non-numeric literal -- must be REJECTED ===
ERROR:  invalid input syntax for type bigint: "not-a-number"
=== monthly_expenses_sdg: numeric literal -- must be ACCEPTED ===
INSERT 0 1
 monthly_expenses_sdg
----------------------
               150000
(1 row)
```

### Country-of-residence FK

```
=== country_of_residence_fk: unknown code ZZ -- must be REJECTED ===
ERROR:  insert or update on table "profile_customer_data" violates foreign key constraint "country_of_residence_fk"
DETAIL:  Key (country_of_residence_list, country_of_residence_version, country_of_residence_code)=(country, 1, ZZ) is not present in table "reference_item".
=== country_of_residence_fk: known code SD -- must be ACCEPTED ===
UPDATE 1
```

### Pairing CHECKs — the MATCH SIMPLE NULL-member bypass, closed on all three new FKs

```
=== pairing CHECK: country_of_residence_code set, version NULL -- must be REJECTED ===
ERROR:  new row for relation "profile_customer_data" violates check constraint "country_of_residence_code_version_paired"
=== pairing CHECK: home_country_code set, version NULL -- must be REJECTED ===
ERROR:  new row for relation "profile_customer_data" violates check constraint "home_country_code_version_paired"
=== pairing CHECK: work_country_code set, version NULL -- must be REJECTED ===
ERROR:  new row for relation "profile_customer_data" violates check constraint "work_country_code_version_paired"
=== home/work country FK+pairing: known codes (non-Sudan, avoids the unrelated sudan_uses_codes check), matching versions -- must be ACCEPTED ===
UPDATE 1
 country_of_residence_code | home_country_code | home_country_version | work_country_code | work_country_version
----------------------------+-------------------+-----------------------+--------------------+----------------------
 SD                        | EG                |                    1 | EG                |                    1
(1 row)
=== home_country_fk: unknown code with a version -- must be REJECTED ===
ERROR:  insert or update on table "profile_customer_data" violates foreign key constraint "home_country_fk"
DETAIL:  Key (home_country_list, home_country_version, home_country_code)=(country, 1, ZZ) is not present in table "reference_item".
```

### Regression — status-transition guard, S2-05/V0020

```
=== REGRESSION: status-transition guard rejects raw in_progress -> approved ===
ERROR:  illegal status transition on app.profile 00000000-0000-0000-0000-0000000000e1: in_progress -> approved
CONTEXT:  PL/pgSQL function app.check_status_transition() line 19 at RAISE
```

### Regression — deferred constraint trigger, no matching history row, S2-05/V0020

```
=== REGRESSION: legal transition (in_progress -> submitted) with NO matching history row -- must fail at COMMIT ===
BEGIN
UPDATE 1
ERROR:  status change on app.profile 00000000-0000-0000-0000-0000000000e1 (in_progress -> submitted) has no matching (most recent) app.profile_status_history row
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
INSERT 0 1
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
 00000000-0000-0000-0000-0000000000e1 |   1 |            | in_progress
 00000000-0000-0000-0000-0000000000e1 |   2 | operator_A | submitted
 00000000-0000-0000-0000-0000000000e1 |   3 | operator_B | approved
(3 rows)
```

No regression. All four S2-02/S2-05 controls behave identically against the amended schema.

---

## 5. Two carried-over doc items

**PROJECT_PLAN.md AD-004 scope sentence** — rewritten from "six Uqudo images ... plus one
optional salary certificate" to state the corrected count: the same six Uqudo images, plus the
Civil Registry portrait, plus the mandatory signature file, plus the optional salary
certificate — **nine artifact kinds per profile**. The same stale sentence was found duplicated
in `docs/journeys/journey-open-items.md` (a second, uncorrected copy of the same text S2-06's
review already caught once elsewhere) and corrected there too.

**docs/journeys/operator.md single-profile view** — added both portraits with origin labelling
and the signature:
```
- Document images and both portraits — one from the Civil Registry, one extracted by Uqudo —
  each labelled with its origin: "Civil Registry", "Uqudo — passport" or "Uqudo — national ID"
- The signature captured at stage 11
- The optional salary certificate, where supplied
```
Also corrected the Access model role table one paragraph up, which still read "the extracted
portrait" (singular) after the bullets below it were updated — found in review.

---

## 6. Integration test extension

`AppSchemaConnectivityIntegrationTest.refSchemaSeedRowCountsMatchExpected` extended: the FK-name
`IN (...)` list now includes `country_of_residence_fk`, `home_country_fk`, `work_country_fk`
alongside the existing `occupation_fk`/`reason_code_fk`, expected count raised 2 → 5. Added a
new test `provenanceMatrixVersionIsSeeded` asserting `app.provenance_matrix_version` has exactly
1 row.

---

## 7. Verify, gates, review

### Flyway — from scratch (post review-fixes, final state)

```
[INFO] Schema history table "public"."flyway_schema_history" does not exist yet
[INFO] Successfully validated 27 migrations (execution time 00:00.079s)
[INFO] Current version of schema "public": << Empty Schema >>
[INFO] Migrating schema "public" to version "0001 - roles and schemas"
...
[INFO] Migrating schema "public" to version "0022 - seed country"
[INFO] Migrating schema "public" to version "0023 - app registry result names and gaps"
[INFO] Migrating schema "public" to version "0024 - app scan result birth data"
[INFO] Migrating schema "public" to version "0025 - app customer data new fields and country fks"
[INFO] Migrating schema "public" to version "0026 - app artifact kinds signature and portraits"
[INFO] Migrating schema "public" to version "0027 - app provenance matrix version"
[INFO] Successfully applied 27 migrations to schema "public", now at version v0027 (execution time 00:01.022s)
[INFO] BUILD SUCCESS
```

### Flyway — idempotent re-run

```
[INFO] Successfully validated 27 migrations (execution time 00:00.121s)
[INFO] Current version of schema "public": 0027
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

### `./mvnw test -Pdb-integration-test` (final, post review-fixes)

```
[INFO] Migrating schema "public" to version "0027 - app provenance matrix version"
[INFO] Successfully applied 27 migrations to schema "public", now at version v0027 (execution time 00:01.022s)
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
5 tests: the original connectivity test, the extended `refSchemaSeedRowCountsMatchExpected`
(now asserting 5 FKs), the new `provenanceMatrixVersionIsSeeded`, `statusTransitionGuardIs...`,
and `BackendApplicationTests`.

### backend/ — `./mvnw test`

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.48 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
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
0 classes, unchanged from every prior session this sprint — no entity/service/controller code
exists yet (explicit OUT OF SCOPE), matching the pre-existing S1-07/S1-08/R-009 finding.

### mobile/ (unchanged — not touched this session)

```
$ fvm flutter test
00:02 +1: All tests passed!

$ fvm dart run tool/check_coverage.dart
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

$ fvm flutter analyze
No issues found! (ran in 41.4s)
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

## 8. `@agent-reviewer` findings and disposition

Run against the full diff and the S2-08 task. All findings below were from the review of the
**first draft**; every MUST-FIX and SHOULD-FIX was applied and then **re-verified live** —
re-ran the full Flyway-from-scratch + idempotent-rerun + all proofs + all regressions +
`./mvnw test -Pdb-integration-test` sequence against the corrected migrations (§7 above shows
the post-fix output). NOTE items were applied and are visible in the diff; none required
re-verification beyond a read-back.

**Confirmed correct, no finding:** the `V0026` `DROP CONSTRAINT artifact_ref_kind_check` name
(verified live against `pg_get_constraintdef`, not a silent no-op); the V0025 FK trio (all
three of country_of_residence/home_country/work_country have code+version+generated-list+
pairing-CHECK+FK, no gaps); V0027's grants (`has_table_privilege` confirmed SELECT-only vs
SELECT+INSERT matches the V0010/V0012 pattern); the V0023 column drops (all seven genuinely
superseded per civil-registry.md); the AD-004 rewrite ("nine artifact kinds" matches V0026's
list exactly).

**MUST-FIX 1 — field 23's S3 fallback had nowhere to live.** The first draft added `birth_city`
to `scan_result` only (S2), leaving no customer-entry column for the "else free text" half —
inconsistent with the adjacent `birth_state_text` decision in the same migration set. **Fixed:**
added `app.profile_customer_data.birth_city_text`, re-verified live.

**SHOULD-FIX 2 — the `monthly_expenses_sdg` "digits only" comment overstated the guarantee.**
Verified live: `bigint` rejects a quoted non-numeric string (the actual UI entry path) but
silently **rounds** an already-numeric-typed fractional value via PostgreSQL's assignment cast
(`1234.56` → `1235`, no error). **Fixed:** corrected the migration comment to state precisely
what is and isn't caught, backed by the live reproduction.

**SHOULD-FIX 3 — `birth_country_code`'s alphabet (alpha-3 MRZ issuer) silently differs from
every other `*_country_code` column (alpha-2 ISO list) in the same amendment**, with no
documentation, and the app-layer birth-state Sudan-check depends on comparing them correctly.
**Fixed:** added explicit comments to both V0024 and V0025 stating the alphabet and the correct
literal (`'SDN'`, not `'SD'`) to compare against.

**SHOULD-FIX 4 — R-033 rendered outside the RISKS.md table** (a blank line before it terminated
the markdown table). **Fixed:** removed the blank line.

**SHOULD-FIX 5 — R-032 left stale**, still 🔴 Live with a mitigation pointing at S2-08 as future
work, in the same diff that completes S2-08. **Fixed:** updated R-032's mitigation text and
downgraded to 🟡 Watching (not retired — one session's work isn't the bar, matching R-030's own
stated reasoning), referencing the actual V0023 columns that close it.

**SHOULD-FIX 6 — a second, uncorrected copy of AD-004's old scope sentence** in
`docs/journeys/journey-open-items.md`, the same duplication S2-06's review already caught once
before. **Fixed:** same rewrite applied there.

**SHOULD-FIX 7 — two claims in `docs/components/civil-registry.md` made false by this
session's own changes** ("no column for it at all" re: the raw address; "the schema amendment
is a later task, not this one" re: the name chains). **Fixed:** both passages updated to state
the closure, dated and referenced to S2-08.

**NOTE 8 — operator.md's Access-model role table** still read "the extracted portrait"
(singular) after the single-profile-view bullets were updated. **Fixed.**

**NOTE 9 — the seeded `provenance_matrix_version = 1` points at a document with no version
marker.** Self-declared and already covered in §2 above; no action beyond stating it for the
user.

**NOTE 10 — this session report didn't exist yet at review time.** Expected at that point in
the task's ordering; not an issue.

---

## 9. Files created / edited

**New:**
- `backend/src/main/resources/db/migration/V0023__app_registry_result_names_and_gaps.sql`
- `backend/src/main/resources/db/migration/V0024__app_scan_result_birth_data.sql`
- `backend/src/main/resources/db/migration/V0025__app_customer_data_new_fields_and_country_fks.sql`
- `backend/src/main/resources/db/migration/V0026__app_artifact_kinds_signature_and_portraits.sql`
- `backend/src/main/resources/db/migration/V0027__app_provenance_matrix_version.sql`
- `docs/sessions/2026-08-27-s2-08-schema-amendment.md` (this file)

**Edited:**
- `RISKS.md` — R-033 added; R-032 updated to reflect S2-08's closure.
- `PROJECT_PLAN.md` — AD-002f description append; AD-004 scope sentence corrected.
- `EXECUTION_PLAN.md` — S2-08 row → ✅.
- `docs/journeys/operator.md` — single-profile view (both portraits + signature); role table
  wording fixed.
- `docs/journeys/journey-open-items.md` — stale AD-004 sentence corrected (second copy found
  in review).
- `docs/components/civil-registry.md` — two claims the schema amendment made false, corrected.
- `backend/src/test/java/sd/gov/bank/fruserupdate/AppSchemaConnectivityIntegrationTest.java` —
  extended FK-count assertion (2 → 5); new `provenanceMatrixVersionIsSeeded` test.

## 10. iOS package check

Not applicable — no Flutter package was added this session (mobile tier untouched).

## 11. What failed, summarized

Nothing failed outright. Two things were caught and fixed:
- Flyway's `flyway:migrate` initially failed with `Unable to resolve environment variable:
  'DB_PORT'` — the Maven plugin needed the `.env` values exported into the shell environment
  first (`set -a && source .env && set +a`), same as every prior session; not a defect, just a
  step that has to be redone per shell session.
- The reviewer-caught findings in §8 — none were re-attempts after a failure, all were fixed on
  the first correction and re-verified live.

---

## Commit/push proof

```
$ git log --oneline -1
8d11970 feat: S2-08 schema amendment for the corrected provenance mapping

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```

Pushed to `origin/main`: `c99da1a..8d11970  main -> main`.
