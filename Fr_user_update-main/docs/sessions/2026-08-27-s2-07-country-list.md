# S2-07 — Seed the ISO 3166 country reference list

Date: 2026-08-27
Sprint 2.

---

## 0. Step-0 findings

**Commit check:**
```
$ git log --oneline -3
837586a docs: record S2-06 commit/push proof in session report
a24d839 docs: S2-06 field provenance matrix and journey update for 2026-08-23 decisions
7322153 docs: record S2-05 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```
S2-06's substantive commit `a24d839` confirmed, with `837586a` (proof recording) on top,
matching what was expected. Tree clean at session start.

---

## 1. The plan, and what changed after approval

Full plan: `C:\Users\DELL\.claude\plans\rosy-gathering-swing.md`. Approved without requested
changes before implementation. `@agent-reviewer` was run against the final diff (§9 below) and
found two informational notes, neither requiring a code change — detailed in §9.

**Sourcing — no network fetch, per CLAUDE.md.** Java 21 uses CLDR as its default locale
provider, so the plan was to generate the list from `java.util.Locale.getISOCountries()` +
`getDisplayCountry(Locale.forLanguageTag("ar"|"en"))` + `getISO3Country()`, entirely offline.

**Verified live before writing anything**, per the task's explicit "stop and report if the JDK
returns English fallbacks" instruction:

- JDK: `C:\Program Files\Android\Android Studio\jbr`, OpenJDK **21.0.8** (build
  `21.0.8+-14196175-b1038.72`), locale provider adapter type **`CLDR`** (confirmed via
  `LocaleProviderAdapter.getAdapter(...).getAdapterType()`), bundled CLDR **v43** (confirmed
  from `$JAVA_HOME/legal/java.base/cldr.md`: `## Unicode Common Local Data Repository (CLDR)
  v43`).
- `Locale.getISOCountries()` returns **249** codes.
- Spot-checked 8 codes (`SD, US, EG, SA, FR, JP, SS, AE`) via `getDisplayCountry(ar)` /
  `getDisplayCountry(en)`: all returned real, distinct Arabic text — `SD` → `السودان`
  (byte-identical to the country row S2-03 already seeded in `admin_division`), not an English
  fallback. First attempt at printing this to the console showed mangled bytes
  (`�������`) — traced to a Windows console/redirect codepage artifact, **not** a CLDR issue:
  writing UTF-8 bytes directly to a file with
  `new OutputStreamWriter(new FileOutputStream(...), StandardCharsets.UTF_8)` resolved it and
  every name rendered correctly (verified by reading the file back, and later by hex-decoding a
  string at the database layer — see §5's Arabic-fold proof).
- Full 249-row generation: `getISO3Country()` succeeded for all 249 codes, 0 failures.
- Scanned the full generated dataset for stray `'` (U+0027) characters that would need SQL
  escaping: **none found**. The one apostrophe-like case, Côte d'Ivoire, uses U+2019 RIGHT
  SINGLE QUOTATION MARK, confirmed by inspection — not U+0027.

This closes the task's verification requirement: real Arabic, sourced from a real, versioned
CLDR release already present on this machine, no network access, no English-fallback risk.

---

## 2. The migration — V0021 → V0022__seed_country.sql

No new registry/grants migration was needed. `ref.reference_list`, `ref.reference_list_version`,
`ref.reference_item`, `ref.ar_fold`, and `fru_app`'s read grants on all three tables already
exist generically since V0011/V0012 (S2-03) — this is purely a seed, the seventh list through an
already-proven mechanism.

- **Shape**: follows `V0017__seed_income_source.sql`'s exact combined-statement CTE pattern
  (flat, non-hierarchical — `is_hierarchical = false`, no `parent_code`, same shape as
  income_source/education_level/rejection_reason/branch).
- **`item_code`** = ISO 3166-1 **alpha-2** (e.g. `'SD'`). **`extra`** carries the alpha-3 code
  (e.g. `{"alpha3":"SDN"}`) — kept off the primary key, per the task, and — matching every one
  of the six S2-03 precedents' canonicalisation — deliberately **excluded** from the
  `content_hash` input.
- **`sort_ordinal`**: `row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu")`, the general
  alphabetical formula every list except `education_level` uses. The task states explicitly
  that countries are alphabetical, not ordinal.
- **`content_hash` canonicalisation**: exactly S2-03's unified format —
  `item_code || '|' || '' || '|' || label_ar || '|' || coalesce(label_en, '')`, ordered by
  `item_code COLLATE "C"`, joined with the literal two-character escape `E'\n'`.
- **`ref.reference_list_version`** row: `version = 1`, `item_count = 249`, `is_current = true`,
  `source_note` citing the exact JDK/CLDR provenance above.
- **Deferred FK**, per the task's explicit instruction not to guess column names: the migration's
  header comment names **S2-08** as the task that will add the FK from field 11 (country of
  residence) and both address hierarchies' country level to
  `ref.reference_item(list_code='country', version, item_code)`, once those columns exist. Unlike
  V0006/V0009's deferred FKs (which could pre-write the exact `ALTER TABLE` statement because
  their target columns already existed), this comment writes no `ALTER TABLE` and names no
  column, since S2-08 hasn't created them yet.

**The 249-row VALUES list was generated, not hand-transcribed.** A small Python script read the
already-verified, UTF-8-file-written Java output and emitted properly quoted SQL tuples,
asserting along the way: exactly 249 rows, every `item_code` exactly 2 uppercase letters, every
`alpha3` exactly 3 uppercase letters, no `'` in any label, and the `SD` row matching
`('SD', 'SDN', 'السودان', 'Sudan')` exactly.

**A real bug, caught before any proof was gathered.** The first assembled version of the file
had an actual embedded newline inside the `E'...'` separator — in both the header comment's
prose description *and* the live `canon` CTE's `string_agg` separator — instead of the literal
two-character escape `E'\n'`. This is the exact CRLF-hazard bug class `@agent-reviewer` found in
S2-03's V0014/V0016 (a Python string-escaping slip: `\\n` was expected to produce the two visible
characters `\` and `n`, but did not). Found by reading the assembled file back before running
anything against a database, and fixed with two targeted edits. Re-verified after the fix: the
file is LF-only with no CR bytes and no BOM (`python3` byte check), and `.gitattributes` already
pins `*.sql text eol=lf` (closed by S2-03/S2-05), so this class of hazard is now doubly guarded —
by the fix itself and by the repo-wide line-ending policy.

---

## 3. Test — extended the existing row-count guard

`AppSchemaConnectivityIntegrationTest.refSchemaSeedRowCountsMatchExpected()` already asserts row
counts for all six S2-03 lists. Added `"country", 249` to its `expectedRowCounts` map — the
exact one-line extension the map exists for. No FK assertion was added, since none exists this
session (by design — FK is S2-08's job). This is the same guard mechanism S2-03's own reviewer
required (an unguarded seed is not proven by a one-off session report alone), extended to a
seventh list, not a new entity/repository/service/endpoint.

---

## 4. Do NOT add the FK — confirmed

No FK to `ref.reference_item` was added anywhere in this diff. `app.profile_customer_data` and
the address tables that will eventually carry `country_code`/`country_version` columns do not
exist yet with those columns — they arrive in S2-08. Adding an FK now would mean guessing at
column names, which CLAUDE.md forbids.

---

## 5. Proofs (task §4), gathered against the migrated database

All proofs run against a container rebuilt from scratch (`docker compose down -v && up -d`) and
migrated with `./mvnw flyway:migrate` (see §6 for the Flyway output itself).

### Row count
```
 count
-------
   249
(1 row)
```
Matches exactly: `Locale.getISOCountries().length` = 249 (§1) and the seeded row count = 249.

### `SD` present
```
 item_code | label_ar | label_en |       extra
-----------+----------+----------+-------------------
 SD        | السودان  | Sudan    | {"alpha3": "SDN"}
(1 row)
```

### First 15 rows ordered by `sort_ordinal`
```
 sort_ordinal | item_code |      label_ar      |        label_en
--------------+-----------+--------------------+-------------------------
            1 | IS        | آيسلندا            | Iceland
            2 | ET        | إثيوبيا            | Ethiopia
            3 | AZ        | أذربيجان           | Azerbaijan
            4 | AM        | أرمينيا            | Armenia
            5 | AW        | أروبا              | Aruba
            6 | ER        | إريتريا            | Eritrea
            7 | ES        | إسبانيا            | Spain
            8 | AU        | أستراليا           | Australia
            9 | EE        | إستونيا            | Estonia
           10 | IL        | إسرائيل            | Israel
           11 | SZ        | إسواتيني           | Eswatini
           12 | AF        | أفغانستان          | Afghanistan
           13 | PS        | الأراضي الفلسطينية | Palestinian Territories
           14 | AR        | الأرجنتين          | Argentina
           15 | JO        | الأردن             | Jordan
(15 rows)
```
Confirmed Arabic-alphabetical: every row starts with an alef-family character under `ar-x-icu`
collation. `item_code` order (IS, ET, AZ, AM, AW, ER, ES, AU, EE, IL, SZ, AF, PS, AR, JO) is not
alphabetical by code, and the English-name order (Iceland, Ethiopia, Azerbaijan, Armenia,
Aruba...) is not alphabetical by English name either — proving the ordering is neither code order
nor English order.

### Arabic fold — the alef/hamza fold, byte-verified

The first attempt at this proof, run via `psql -c` with an inline Arabic literal typed directly
into the shell command, displayed a search string with the wrong final character on two words —
traced to a shell-argument transcription risk (typing Arabic through several quoting layers:
bash → `docker exec` → `psql -c`), not a data or fold-function problem. Redone by writing the
proof SQL to a UTF-8 file and running it with `psql -f`, with the raw input's UTF-8 bytes printed
alongside for certainty:
```
raw_input     | الامارات العربية المتحدة
raw_input_hex | d8a7d984d8a7d985d8a7d8b1d8a7d8aa20d8a7d984d8b9d8b1d8a8d98ad8a920d8a7d984d985d8aad8add8afd8a9
folded_input  | الامارات العربيه المتحده
stored_label  | الإمارات العربية المتحدة
folded_stored | الامارات العربيه المتحده
matches       | t
```
The hex decodes to exactly the plain-alef, teh-marbuta spelling intended (`الامارات العربية
المتحدة`, no hamza) — a customer typing the UAE's name without the hamza on the first alef, a
natural spelling variant. The stored label (`الإمارات العربية المتحدة`, with hamza, straight
from CLDR) is folded by `ref.ar_fold()` to the same string as the folded input, and the equality
holds (`matches = t`). This exercises the alef/hamza fold rule (`أإآٱ→ا`), the same fold class
S2-03 proved on `ضابط أمن`/`ضابط امن`.

### `content_hash` — computed and stable across independent recomputation
```
=== stored ===
 list_code | version |                         content_hash_hex                          | item_count
-----------+---------+--------------------------------------------------------------------+------------
 country   |       1 | 8c65842edb5b4d9f2754df375f3751a855e04962824b2a9b91ed7a30d838cd3d   |        249

=== recomputed from the stored reference_item rows, run 1 ===
 8c65842edb5b4d9f2754df375f3751a855e04962824b2a9b91ed7a30d838cd3d

=== recomputed again, run 2 ===
 8c65842edb5b4d9f2754df375f3751a855e04962824b2a9b91ed7a30d838cd3d
```
The stored hash and two independent recomputations from the stored rows (not the seed's own
literal VALUES list) agree exactly.

### Registry version row
```
 list_code | version | is_current | item_count
-----------+---------+------------+------------
 country   |       1 | t          |        249

 list_code | name_ar | name_en | is_hierarchical
-----------+---------+---------+-----------------
 country   | الدولة  | Country | f
```

---

## 6. Flyway output — from scratch and idempotent re-run, verbatim

From a freshly rebuilt container (`docker compose down -v && up -d`):
```
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] Schema history table "public"."flyway_schema_history" does not exist yet
[INFO] Successfully validated 22 migrations (execution time 00:00.129s)
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
[INFO] Migrating schema "public" to version "0022 - seed country"
[INFO] Successfully applied 22 migrations to schema "public", now at version v0022 (execution time 00:00.838s)
[INFO] BUILD SUCCESS
```

Second run, no changes in between (idempotent):
```
[INFO] Database: jdbc:postgresql://localhost:5432/fru (PostgreSQL 18.6)
[INFO] Successfully validated 22 migrations (execution time 00:00.091s)
[INFO] Current version of schema "public": 0022
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

---

## 7. `./mvnw test -Pdb-integration-test` — all three existing tests still pass

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 29.15 s -- in sd.gov.bank.fruserupdate.AppSchemaConnectivityIntegrationTest
[INFO] Running sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.887 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
Spring Boot's own `FlywayAutoConfiguration` ran all 22 migrations against a real, ephemeral
PostgreSQL 18 container. All three pre-existing `@Test` methods in
`AppSchemaConnectivityIntegrationTest` passed, including `refSchemaSeedRowCountsMatchExpected()`
with its extended map (now asserting `country` = 249 alongside the original six lists) — a
mismatch there would have failed this run.

---

## 8. Standard gates — all three tiers, verbatim

### backend/ — `./mvnw test`
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.55 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] BUILD SUCCESS
```

### backend/ — `./mvnw verify`
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.77 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 3 files clean - 0 needs changes to be clean, 1 were already clean, 2 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```
**JaCoCo bundle class count: still 0**, unchanged from S2-01 through S2-06. This session added
one SQL migration (V0022) and a one-line test-map extension, no main-source Java classes —
consistent with the pre-existing S1-07/S1-08/R-009 finding, still unresolved by this session and
tracked there. No entity/repository/service/controller was in scope (explicit OUT OF SCOPE).

### mobile/ (unchanged this session — not touched)
```
$ fvm flutter test
00:02 +1: All tests passed!

$ fvm dart run tool/check_coverage.dart
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

$ fvm flutter analyze
No issues found! (ran in 39.1s)
```

### backoffice/ (unchanged this session — not touched)
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

## 9. `@agent-reviewer` review, per CLAUDE.md's hard rule

Run against the full diff and the S2-07 task before marking it done. The agent independently
re-derived all 249 CLDR tuples from this machine's JDK and diffed them against the migration file
— zero field differences, zero missing/extra codes, zero Arabic-fallback rows. It also queried
the live, already-migrated container directly rather than trusting the migration's own comments.
Confirmed clean: no raw embedded newline anywhere in the file (only the two intended `E'\n'`
escapes at lines 40 and 316; the other 15 `E'` hits are `..E', '..` inside English names like
"United Arab Emirates"), canonicalisation byte-identical to V0017's, `sort_ordinal` genuinely
Arabic-alphabetical, all 249 codes correctly oriented (alpha-2 in `item_code`, alpha-3 in
`extra`), no unescaped `'`, the deferred-FK comment names no column and writes no `ALTER TABLE`,
the test edit is a faithful minimal extension, and nothing in the OUT OF SCOPE list was touched.

**Two findings, both informational — no code change applied, and none needed:**

1. **NOTE** — at review time, `EXECUTION_PLAN.md` already referenced this session report file,
   which did not yet exist (review ran before this report was written). Expected, given
   CLAUDE.md's session-end order (plan files → report → commit) — resolved simply by this report
   now existing before the commit. No further action needed; **not re-reviewed**, since the fix
   is definitionally satisfied by this file's own existence at commit time.
2. **NOTE** — `country` is the first seeded list where `extra` carries substantive data
   (`alpha3`) rather than a UI marker, and `content_hash` (matching S2-03's format everywhere
   else) does not cover `extra`, so a future alpha-3 correction wouldn't change the hash. Not a
   defect introduced by this task — the task explicitly required matching S2-03's unified format
   — and already flagged in V0014's own header as not a settled AD-002f wire contract. Recorded
   here for whoever settles AD-002f; no action taken this session.

**On the RISKS.md R-032 edit specifically** (made on this session's own initiative, since the
task's step 5 named only EXECUTION_PLAN.md's S2-08 Notes): the reviewer was asked directly and
called it **appropriate, not overreach** — the old clause ("blocked on the S2-07 country-list
seed") becomes factually false the instant this diff lands; RISKS.md is one of the four
session-start plan files CLAUDE.md requires keeping current; and the edit is contained to
correcting a now-false dependency clause, leaving R-032's status at 🔴 Live and settling nothing
substantive.

No findings required a fix, so there is nothing to re-review.

---

## 10. iOS package check

Not applicable — no Flutter package was added or touched this session (mobile tier untouched).

---

## 11. Files created / edited

**backend/**
- `src/main/resources/db/migration/V0022__seed_country.sql` (new) — the country seed migration,
  249 rows, deferred-FK comment naming S2-08.
- `src/test/java/sd/gov/bank/fruserupdate/AppSchemaConnectivityIntegrationTest.java` — one-line
  addition to `refSchemaSeedRowCountsMatchExpected()`'s `expectedRowCounts` map plus a comment
  noting the extension.

**Root**
- `EXECUTION_PLAN.md` — S2-07 row flipped to ✅ with session-report link; S2-08's Notes appended
  with the verbatim carry-over sentence from the task (AD-004 scope sentence and operator.md's
  single-profile view, both flagged out of scope by S2-06).
- `RISKS.md` — R-032's mitigation sentence corrected: the "blocked on the S2-07 country-list
  seed" clause is now false, since this task is that seed; reworded to state it's done. Not
  explicitly requested by the task's step 5, done on this session's own initiative and confirmed
  appropriate by `@agent-reviewer` (§9).

**docs/**
- `sessions/2026-08-27-s2-07-country-list.md` — this report.

---

## 12. What failed, summarized

- **A real embedded-newline bug**, the exact CRLF-hazard class S2-03's own reviewer found: the
  first assembled version of `V0022__seed_country.sql` had an actual line break inside two
  `E'...'` string-literal contexts instead of the literal two-character escape `E'\n'`. Found by
  reading the assembled file before running anything against a database; fixed with two targeted
  edits; re-verified with a byte-level check (no CR, no BOM) before proceeding. Never reached a
  committed or database-applied state.
- **A shell-argument transcription risk**, not a data bug: the first attempt at the Arabic-fold
  proof, typed as an inline literal in a `psql -c` command through several quoting layers, showed
  a subtly wrong character. Redone by writing the proof to a UTF-8 file and hex-dumping the raw
  input alongside the result, which confirmed the actual data and fold function were correct all
  along — the display artifact was purely a shell-argument-passing issue in the first attempt.
- **Console/redirect codepage garbling** when printing Arabic text directly to the terminal
  (both from `java` and later, briefly, when eyeballing `psql` output) — not a CLDR or database
  issue in any case; resolved every time by writing UTF-8 bytes directly to a file and reading
  the file back, or by hex-verifying the raw bytes at the SQL layer.
- No Docker, database, or toolchain issues this session — the container built cleanly every time
  it was rebuilt, and all three tiers' gates passed on first standard-gate run (excluding the
  embedded-newline fix above, caught before any gate or proof run reflected it).

---

## Commit/push proof

```
$ git log --oneline -1
3b0123b feat: S2-07 seed ISO 3166 country reference list

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main`: `837586a..3b0123b  main -> main`.

