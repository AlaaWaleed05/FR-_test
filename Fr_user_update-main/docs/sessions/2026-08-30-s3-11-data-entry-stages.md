# S3-11 — Data-entry endpoints (stages 3-6) + E.164 fix (BL-016)

Session date: 2026-08-30. Backend only — backoffice and mobile were not touched, so their gates
were not run.

## 0. Commit check

`git log --oneline -3` at session start showed `535fe03` (HEAD, "docs: record S3-10 commit/push
proof") on top of `34a6aae` ("feat: S3-10 — close AD-006..."), both ancestors of `main`, tree
clean. S3-10's stated hash confirmed present.

## Part 1 — BL-016: E.164 at the `/api/v1/contact-channels` boundary

`ContactChannelsRequest.phoneNumber` was an unvalidated `String` since S3-06. New
`contactchannels.domain.PhoneNumberNormalizer` (pure logic, no Spring/I/O, same style as
`DestinationMasker`) and `InvalidPhoneNumberException`, wired into `ContactChannelsController`
right after the existing `clean()` call — so the existing control-character/length checks still
fire first — and before the call into `ContactChannelsService`, which is otherwise untouched.

Handles, in order: an Arabic-Indic or Extended Arabic-Indic digit anywhere in the input is
rejected outright, never transliterated (BL-016's own requirement); `+<digits>` and `00<digits>`
pass through after stripping a mistyped Sudan trunk zero (`+249 0912345678` → `+249912345678`) and
rejecting a `0`-leading country code (no ITU E.164 code begins with `0`); a bare Sudan local number
(`0` + 9 digits) converts to `+249…`. Deliberately scoped to Sudan local-format conversion only —
an already-`+`/`00`-prefixed foreign number is trusted and passed through, since customer.md's
"verified over WhatsApp but abroad" case must stay servable and this class has no basis to
reformat a country code it cannot validate.

Decided not to pull in a third-party phone-parsing library: this is small, fully-specified,
self-contained parsing, not an external SDK/API integration, so CLAUDE.md's
`@agent-researcher`-first rule (scoped to Uqudo/the Oracle procedure/the Civil Registry) doesn't
apply.

**Regression check done up front, not after the fact**: every existing phone fixture in
`ContactChannelsControllerTest`/`ContactChannelsIntegrationTest` is already `+249900004821`-shaped
— a no-op pass-through — so none needed changing. `ContactChannelsServiceTest` calls
`service.submit(...)` directly, never through the controller boundary, so it's unaffected by
construction. Confirmed by the full regression run in §6.

## Part 2 — Reference-list validation

New top-level package `reference/` (shared infrastructure beside `corebanking`/`audit`, not scoped
to one feature): `reference.domain.ReferenceCatalog` (`currentVersion`, `exists`, `parentCode`)
and `reference.jdbc.JdbcReferenceCatalog` — the first application code to read the `ref` schema.
`fru_app` already holds the needed `SELECT` grants (V0012); no new grants required.

**Design decision**: the server always validates a submitted code against the list's *current*
published version, never a client-supplied one. AD-002f (reference-data delivery/version-checking
to the client) is explicitly open and out of scope for this task, and no mobile client exists yet
to disagree — inventing a client-sent-version wire field now would mean designing half of AD-002f's
contract by accident. Every coded field this task writes already has a `_code`/`_version` column
pair (occupation, every `*_country_code`); the server resolves "current" and stores that.

## Part 3 — `admin_division` and R-045

`home_state_code`/`home_locality_code`, `work_state_code`/`work_locality_code` and
`birth_state_code` have **no DB-level FK at all** — unlike occupation/country, S2-03's schema left
them as plain text with no per-field version column, only the single shared
`admin_div_version` column. This task's application-level check (existence + immediate
parent-chain within the `admin_division` list) is therefore the *only* protection these fields get.

That check necessarily depends on `admin_division`'s root code (`'SD'`) and the separate `country`
list's Sudan row (`'SD'`) being the same string — R-045's exact unenforced gap. Per the task's
instruction, this is not asserted as a new constraint, but the dependency is marked with a comment
on the `SUDAN_CODE` constant in `DataEntryService`, not silently assumed.

**Filed as BL-017**: `admin_div_version` is one column shared by birth/home/work addresses, so
whichever of stages 3/5/6 is submitted last overwrites it with whatever version *that* submission
validated against. No migration proposed — out of this task's scope.

## Part 4 — Data-entry endpoints

New feature package `dataentry/` (`domain`/`service`/`jdbc`/`web`), one controller
(`DataEntryController`, four `@PostMapping`s under `/api/v1/data-entry/stage3..6`,
`profileId` in the request body per `OtpVerifyRequest`'s precedent). No migrations — every column
these endpoints write already existed from S2-06/S2-08/S2-09.

Unlike `ContactChannelsService`, nothing here has an external side effect, so each submission fits
in one transaction: reference-code checks run first (plain reads, fail fast before opening a
transaction); then lock the profile row (`SELECT ... FOR UPDATE`), reject an unknown profile (404)
or terminal one (409); read the previous column values; append the stage's audit event; write the
columns; reactivate an `abandoned` profile or just bump `last_activity_at` (reusing
`ProfileRepository`'s two already-generic methods, not duplicated).

Every submission — first time or resubmission — is its own audit event
(`stage3_data_submitted`/…/`stage6_data_submitted`), flat payload, every new value plus a
`previous<Field>` counterpart (`null` on first submission), matching
`ContactChannelsService.sessionReenteredEvent`'s existing pattern. Income sources (a list, not a
scalar) flatten to a sorted comma-joined `incomeSourceCodes` string plus
`primaryIncomeSourceCode`/`otherIncomeText` — `CanonicalJson` has no array support by design.

A terminal rejection is recorded **after** the (otherwise empty) transaction commits, not inside
the callback that then throws — copied deliberately from `ContactChannelsService`'s own
S3-07-derived reasoning, since writing it inside would roll the event back with everything else.

Validation traced field-by-field against customer.md stages 3-6 and field-provenance.md: sex
(`m`/`f`, interface-only — the Civil Registry value is what the profile stores, out of scope here),
ethnicity, country of residence, marital status with its conditional spouse-name/has-children/
children-count table, education level (1-7), birth country defaulting to Sudan with a Sudan/
free-text state split, birth city (always free text at this stage — Uqudo's `placeOfBirth`
supersedes later); occupation against the bank's coded list, income sources with exactly one
primary and `OTHER` requiring free text, monthly expenses as ASCII-digits-only; the home/work
address cascades (Sudan → `admin_division` codes, else free text), all fields mandatory per
customer.md's "mandatory fields: all of them" policy.

**Out of scope, flagged for review**: Stage 6's optional salary-certificate attachment has no
field on this endpoint at all. `app.artifact_ref` (kind `salary_certificate`) needs a real
`storage_key`/`byte_size`/`sha256` from an actual upload, and AD-004 (object storage) is still
open and blocked on hosting — there is nowhere real to write it yet.

## 5. Review — `@agent-reviewer`, two passes

**First pass** found three real defects, all fixed before commit:

| Finding | Fix |
|---|---|
| `childrenCount` had no upper bound — an implausible value (e.g. 40000) passed every check and would fail as a raw `22003 smallint out of range` 500, not a 400 | Added `MAX_CHILDREN_COUNT = 30` and a range check in `DataEntryService.validateMaritalStatus` |
| The terminal-rejection-audited-after-commit ordering guard was proven only by `DataEntryServiceTest`, whose `PlatformTransactionManager` is a Mockito mock — it could not fail even if the S3-07-style bug (audit write moved inside the rolled-back callback) were reintroduced | Added `DataEntryIntegrationTest.terminalProfileRejectionIsStillAuditedAgainstARealTransaction` — a real Testcontainers-backed transition to `submitted`, a real 409, and a real read-back of the `data_entry_rejected` event from `audit.audit_event` |
| `PhoneNumberNormalizer` left a false-delta gap: `+249 0912345678` (Sudan's country code with the local trunk zero mistakenly kept) normalised to `+2490912345678`, different from the same number entered locally (`0912345678` → `+249912345678`) | Added `stripSudanTrunkZero`, applied only when the digit string is exactly `"249"` + `"0"` + 9 digits — narrow enough not to touch a number that legitimately starts `2490...` for an unrelated reason |

Also two NOTE-level items: `app.profile.resume_stage` ("last completed journey stage") is never
written by any code in the repository, and these are the first slices to complete a journey stage
past 1b/2 — **not fixed**, filed as **BL-018**, since no resume endpoint exists yet to read it back
and writing it now with no reader is speculative. And a stale "five"/"five classes" count in
`AbstractPostgresIntegrationTest`'s Javadoc after this session's sixth integration class — fixed.

**Second pass** (after the three fixes above) found no BLOCKER or SHOULD FIX, and confirmed each
fix actually closes its failure scenario (including that the new live integration test genuinely
fails against a mentally-reverted ordering bug, since `JdbcAuditEventWriter` shares the same
transaction-bound connection). It surfaced two more NOTEs:

- The same false-delta class remained open one input further: `+0912345678`/`000912345678` (a bare
  `0` used as if it were a country code) would store verbatim and diverge from the same number's
  local-format form. **Fixed**: `rejectLeadingZeroCountryCode` — no ITU E.164 country code begins
  with `0`, so this is an outright rejection, not a guess.
- `DataEntryIntegrationTest`'s own class Javadoc claimed account range `0000000301`-`0000000309`
  while the shared map in `AbstractPostgresIntegrationTest` (the actual collision-prevention source
  of truth) said `...307`. **Fixed**: both now say `...307`, matching the seven account numbers
  the class's tests actually use.

Every fix above has its own new unit test (`PhoneNumberNormalizerTest`:
`sudanCountryCodeWithATrunkZeroKeptInIsNormalisedWithoutIt`,
`leadingZeroAfterPlusIsRejected`, `leadingZeroAfterInternationalPrefixIsRejected`;
`DataEntryServiceTest.stage3ChildrenCountTooLargeIsRejected`) plus the live integration test named
above.

## 6. Proofs

- **Each stage submitted and persisted, with its audit event** —
  `DataEntryIntegrationTest.stage3FirstSubmissionPersistsAndAuditsWithNoPreviousValues` (live: real
  columns, real `stage3_data_submitted` event, `previousEthnicity:null`).
- **Re-submission after back-navigation** —
  `DataEntryIntegrationTest.resubmittingAStageStoresTheNewValueAndKeepsThePreviousOneInAudit`
  (live: new value in the column, previous value recoverable from the newest audit event).
- **Unknown occupation/country code, fractional monthly expenses, two primary income sources —
  each rejected at the boundary** — `DataEntryIntegrationTest.unknownOccupationCodeIsRejectedAt-
  TheBoundary`, `unknownCountryCodeIsRejectedAtTheBoundary`,
  `fractionalMonthlyExpensesIsRejectedAtTheBoundary`, `twoPrimaryIncomeSourcesAreRejected` (all
  live, all assert no partial write).
- **E.164**: `DataEntryIntegrationTest.localFormatPhoneNumberIsNormalisedToE164`,
  `arabicIndicDigitPhoneNumberIsRejected`,
  `reentryWithTheSamePhoneInADifferentFormatProducesNoFalseDeltaInAudit` (live: the
  `session_reentered` payload's `previousPhoneNumber` equals the newly submitted, differently
  formatted, same number).
- **Terminal-profile rejection audited despite the exception** —
  `DataEntryIntegrationTest.terminalProfileRejectionIsStillAuditedAgainstARealTransaction` (live,
  added under review — see §5).
- **Regression — named, not re-proven live** (CLAUDE.md: a named integration test that already
  exercises the guard is the proof): S3-06's three-channel/deselection proofs —
  `ContactChannelsIntegrationTest.threeChannelsSelectedCreatesEverythingLiveAndAuditsOnTheProfiles-
  OwnChain` and its sibling deselection test; S3-07's re-entry round trip and terminal bypass —
  `ContactChannelsIntegrationTest`'s re-entry and rejection tests; S3-08's lockout escalation —
  `OtpVerificationIntegrationTest`; the four-eyes rule and status-transition guard — DB-level
  (V0020/AD-005), untouched by this task; audit append-only — S2-01's proofs, untouched. All
  re-ran unchanged in §7's full suite.

No migration was added, so Flyway-from-scratch and an idempotent-re-run proof don't apply here —
every integration test already runs every migration from an empty Testcontainers database on
every invocation, which is the from-scratch proof.

## 7. Gates — final output, verbatim

`./mvnw test -Pdb-integration-test` (Docker running, live PostgreSQL 18):

```
[INFO] Tests run: 354, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

354 against the prior baseline of 293 — 61 new tests (`PhoneNumberNormalizerTest` 13,
`DataEntryServiceTest` 22, `DataEntryControllerTest` 10, `DataEntryIntegrationTest` 10,
`JdbcReferenceCatalogTest` 6).

`./mvnw verify` (Spotless + JaCoCo, standard/non-integration tier):

```
[INFO] Tests run: 302, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 139 files clean - 0 needs changes to be clean, 0 were already clean, 139 were skipped because caching determined they were already clean
...
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

backoffice/ and mobile/ gates were not run — this task touched only backend/.

## 8. CLAUDE.md amendment

Resolved the tension the S3-10 reviewer flagged and correctly left open: the session-start rule
required reading customer.md "in full" for customer-facing work, while the "name sections" rule
used that same 1204-line file as its example of a document too long to name whole. Replaced the
session-start bullet's "must also read docs/journeys/customer.md and docs/journeys/operator.md"
with "Customer-facing or operator-facing work reads the journey stages it touches plus the resume
rules (customer.md stage 13), not the whole document." — the exact wording the task specified.
CLAUDE.md stayed at 199 lines (unchanged; the edit was a like-for-like line replacement).

## 9. Plan-file updates

- EXECUTION_PLAN.md: S3-11 row added, ✅.
- BACKLOG.md: BL-016 closed (kept as a row per BL-009's precedent, marked what closed it and
  where); BL-017 filed (the shared `admin_div_version` column); BL-018 filed (`resume_stage` never
  written, found under review).
- No new RISKS.md entry: R-045 already covers the admin_division/country cross-list dependency
  this task depends on without resolving.

## 10. Commit and push proof

```
$ git log --oneline -1
b35e37d feat: S3-11 — data-entry endpoints (stages 3-6); fix E.164 (BL-016)

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `535fe03..b35e37d  main -> main`.
