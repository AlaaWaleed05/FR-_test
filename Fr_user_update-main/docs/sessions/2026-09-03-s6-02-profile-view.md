# S6-02 — Single profile view, approve/reject, manual completion (closes BL-024, BL-013)

## 0. Commit check

`git log --oneline -3` at session start: `0f71db4` (S6-01 proof append) at HEAD. `git status` clean.
`git merge-base --is-ancestor 8f47370 main` exit 0 — S6-01's stated commit is an ancestor of `main`.

## 1. BL-024 — the first-admin runner

**Root cause**: `auth.config.CreateOperatorAccountRunner` declared two 3-argument constructors
(production: `OperatorUserRepository, PasswordEncoder, ConfigurableApplicationContext`; test-only:
`..., IntConsumer`), neither `@Autowired`. Spring's constructor resolution requires one unambiguous
candidate absent an `@Autowired` constructor; with two, and no no-arg fallback, it fails at context
refresh. The only mechanism that provisions an operator account — including the first admin — could
never actually run through `java -jar ... --spring.profiles.active=create-operator-account`.

**Fix**: `@Autowired` on the production constructor only. Committed alone first (`dab0884`), before
starting steps 3-4, per the task's own suggestion.

**Regression test** — `CreateOperatorAccountRunnerSpringWiringTest`: a real
`AnnotationConfigApplicationContext` (genuine `AutowiredAnnotationBeanPostProcessor`/
`ConstructorResolver`, not a mock) with the profile active, asserting the bean resolves. Deliberately
does **not** boot the full application or invoke `run()` — `ApplicationRunner` invocation is
`SpringApplication`'s job, never a plain `ApplicationContext.refresh()`'s, so this proves constructor
resolution without ever risking the production exiter's real `System.exit`. Revert-proof (CLAUDE.md):

```
Caused by: java.lang.NoSuchMethodException: sd.gov.bank.fruserupdate.auth.config.CreateOperatorAccountRunner.<init>()
```

reproduced live with `@Autowired` removed, confirmed before restoring the fix.

**Live proof**, empty database:

```
$ docker exec fru_postgres psql -U fru_migrator -d fru -c "TRUNCATE app.operator_user;"
TRUNCATE TABLE
$ docker exec fru_postgres psql -U fru_migrator -d fru -c "SELECT count(*) FROM app.operator_user;"
 count
-------
     0

$ java -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=create-operator-account --spring.main.web-application-type=none \
    --username=s602.admin1 --display-name="S602 Admin" --role=admin
...
Created admin account 4c64eef4-c4bc-4c5b-b5d6-884d083c54dc
username: s602.admin1
one-time password (must be changed on first sign-in): <redacted — never repeated in this file, per CLAUDE.md's no-secrets rule>
```

Then, the full backend started normally (web mode, same DB), curl end to end:

```
1) GET /api/v1/auth/me unauthenticated (also primes CSRF)         -> 401
2) POST /api/v1/auth/login  (username + the printed one-time pw)  -> 200 {"mustChangePassword":true,"role":"admin"}
3) POST /api/v1/auth/password (forced first change, same session) -> 204
4) GET /api/v1/auth/me on the SAME session, no re-login            -> 200 {"username":"s602.admin1","displayName":"S602 Admin","role":"admin","mustChangePassword":false}
```

No workaround, no direct SQL insert — the documented `db/post-migrate/03-create-operator-account.md`
procedure, run for real, for the first time.

## 2. Steps 3-4 — no backend changes

Confirmed by reading the whole `operator` package: S4-01/S4-02 already built `GET
/api/v1/operator/profiles/{id}` (with server-computed `canApprove`, four-eyes, BL-013's other half),
`POST .../approve`, `.../reject`, `.../manual-complete`. This session is backoffice-only past step 1.

Eligible statuses used by the new buttons, read from the actual services, not guessed: approve =
{submitted, rejected} (`OperatorProfileViewService.APPROVE_ELIGIBLE_STATUSES`) gated by `canApprove`;
reject = {submitted} only (`JdbcReviewRepository`); manual-complete = {in_progress,
awaiting_registry, blocked_scan, blocked_liveness, abandoned} (`ManualCompletionService`'s derived
set, from V0020).

New files: `profiles/ProfileDetailPage.tsx`, `RejectModal.tsx`, `ManualCompleteModal.tsx`,
`detailLabels.ts`, `portraitPlaceholder.ts`, plus tests for each. Changed:
`api/{types,profiles,reference}.ts` (new DTOs/calls/`useReferenceLabelMap`), `App.tsx` (new route),
`ProfileListPage.tsx` (rows navigate to the detail page; date-column bidi fix, see §4),
`layout/AppShell.tsx` (nav highlighting on sub-routes).

**Design decisions, stated rather than assumed**:
- Portrait/artifact `label` strings ("Civil Registry", "Uqudo — passport") are rendered **verbatim**
  from the backend — operator.md's own quoted origin labels, a system-origin tag, not translatable
  customer-facing copy.
- Reference-coded customer-data fields (occupation, admin_division, country, income_source,
  education_level) are resolved against each list's **current** published version — the same
  approach `ProfileListPage` already uses for branch/rejection-reason. The response carries no
  per-field version to resolve against instead except `occupationVersion`. Filed as **BL-025**.
- Document images are out of scope (R-046/AD-002d still open). `Image.PreviewGroup` is built now,
  behind a local SVG placeholder (no network fetch), so a real `src` slots in later without
  restructuring — exactly what the task asked to leave built-but-empty.

## 3. RTL findings

`docs/components/backoffice-components.md`'s open `Timeline`/`Image.PreviewGroup` RTL item: **closed
live** (Playwright, real browser). `Timeline` at its default mode already places the dot/connector on
the right of each entry, matching reading direction — no LTR-mirrored artifact. `Image.PreviewGroup`'s
prev/next switches are RTL-consistent in function: opening the DOM-first (visually rightmost, under
the RTL flex row) image correctly disables "prev" and enables "next", and the enabled "next" sits on
the **left** — the direction actually advanced toward in RTL. Same "physically consistent, not
mirrored" pattern S6-01 already found for `DatePicker.RangePicker`. The chevron glyph itself is not
flipped (cosmetic, not functional).

**New finding, this session's own screenshots**: the browser's bidi algorithm visually reorders plain
digit/punctuation strings with no strong-direction (letter) character to anchor them inside the RTL
page. Live evidence, before the fix:

- `+249912340001` (a phone number) rendered as `249912340001+`
- `dayjs().format('YYYY-MM-DD HH:mm')` rendered as `11:40 2026-09-01` (time first)

Closed with `<bdi>` (native HTML bidi isolation, no CSS/JS) around every affected field: dates, the
phone number, bare ISO dates (scan/registry dates of birth/issue/expiry), and — found under review,
see §5 — the face-match confidence figures. `ProfileListPage`'s `submittedAt` column carried the
identical defect since S6-01 (never screenshotted at pixel level before); fixed there too. Verified
live after the fix:

```
PHONE_CORRECT_ORDER: true   (+249912340001)
DATE_CORRECT_ORDER: true    (2026-09-01 11:40)
```

Genuinely Arabic text is untouched — `<bdi>` only changes behaviour for content with no strong-
direction anchor, and `orDash` (the Arabic-content path) was never touched.

## 4. Live proofs — real running backend, real browser (Playwright/Chromium)

Backend jar (stub external clients, same posture as S6-01), Postgres at V0059. Fixture data: one
newly built profile with a full digital identity cycle (`app.identity_cycle`/`scan_result`/
`face_result`/`registry_result`/`artifact_ref`, inserted through the legal
`audit.ensure_profile_chain`/`audit.chain_append`/V0020-history path S6-01 already established, each
status hop its own transaction — synthetic data throughout, no real national numbers) plus the seven
profiles S6-01 left in place across every other status. Three operator accounts provisioned through
the now-fixed CLI runner (`s602.viewer`, `s602.opa`, `s602.opb`).

24 checks, all passed:
- Full profile view: reference number, status/provenance tags, resolved occupation/admin-division/
  country/income-source/education-level labels, per-channel state, liveness reported separately from
  face-match with its confidence figure, Civil Registry raw address, both portraits with
  distinguishable labels, `Image.PreviewGroup` prev/next arrows present and correctly enabled/
  disabled, viewer sees no action buttons.
- Manual vs. digital: list shows both tags; the manual profile's detail view carries the permanent
  banner.
- **Four-eyes end to end**: operator A manually completes an in-progress profile (status →
  `submitted`) → A's own Approve is **disabled** (button state) **and** a direct `fetch()` call to
  the approve endpoint from A's own authenticated session gets **403** (the UI reflects, a client
  ignoring it is still refused) → operator B's Approve **succeeds** (status → `approved`).
- Reject: no reason code selected → refused client-side; REJ-01 selected → succeeds (status →
  `rejected`).
- `rejected → approved`: succeeds.

One gap found mid-proof, not a UI bug: two of S6-01's synthetic profiles had no
`app.profile_customer_data` row, and `ProfileRepository.currentContactDetails` throws
`IllegalStateException` when one is absent (every real profile has one from Stage 1b onward) — reject
and approve both call it for the notification lookup. Fixed by inserting minimal customer-data/channel
rows for those two profiles (synthetic, disclosed here, not a code change).

## 5. Review

`@agent-reviewer` ran twice per CLAUDE.md's rule.

**First pass** — 4 SHOULD-FIX + 5 NOTE, all fixed:
| # | Finding | Fix |
|---|---|---|
| 1 | Marital status shown as raw `married`/`single`, not translated (helper existed, unwired) | Wired `maritalStatusLabel`; added `identityType` row too |
| 2 | 12 fields fetched/typed, never rendered (identityType + 7 scan + 4 registry name fields) | Added all 12 rows |
| 3 | Face-match confidence figures had the same bidi bug as the date/phone fix | Wrapped in `<bdi>` |
| 4 | Reference-list load failures degraded silently (no error/retry, unlike `ProfileListPage`) | `useReferenceLabelMap` now returns `retry`; combined warning `Alert` added |
| 5 | Row-click navigation untested | Test added |
| 6 | `rejected → approved` untested | Test added |
| 7 | Action failure / `alreadyDone` paths uncovered | Tests added (reject, manual-complete; approve missed — see pass 2) |
| 8 | `ManualCompleteModal` accepted whitespace-only justification | `whitespace: true` added |
| 9 | `Tooltip` on a `disabled` `Button` may never fire (`pointer-events: none` blocks the hover listener) | Wrapped in `<span>`; verified live |

**Second pass, against the fixed diff — real gaps in the first pass's own fixes**, this project's
now-familiar pattern:
- The approve failure path was still uncovered (#7 only closed reject/manual-complete) — test added.
- Fix #1 had no test that could fail against the bug: `detailLabels.test.ts` exercises the pure
  functions in isolation and passed identically before the wiring existed; reverting the wiring left
  the suite green. Added assertions on the rendered `متزوج`/`بطاقة وطنية` text (fixture's
  `identityType` changed to `national_id`, distinct from `scanResult.documentType`'s `PASSPORT`, so
  the two labels can't coincidentally agree).
- Fix #2's 12 new rows were asserted nowhere. Added label + distinctive-value assertions.
- Fix #8 had no test typing an actual whitespace-only value (the two existing cases — empty, real
  content — both pass with the rule removed). Added one.
- `sexLabel`'s fallback returned the **lowercased** raw value for an unrecognised input instead of
  the original — `scanResult.sexOnDocument` is unconstrained Uqudo free text, not CHECK-bound like
  `sex_declared`; a value like `'MALE'` would display as `'male'`, case-mutating evidence data. Fixed
  to look up lowercased but return the original on fallback; regression test added.
- Two in-source comments still cited BL-017 instead of the newly filed BL-025. Corrected.

Every fix re-verified live where the finding was itself a live-only class of bug (the `<span>`/
Tooltip fix): hovering the disabled Approve button on a real four-eyes-blocked profile in a real
browser now shows "غير مسموح لك باعتماد هذا الملف". Not re-run: the full 24-check Playwright script
(profile states had already moved past their starting points from the first run); the field-rendering
and label fixes were re-verified live individually instead (§below).

```
PASS - marital status now translated (متزوج)
PASS - identity type shown (نوع الهوية)
PASS - scan cardVariant/sexOnDocument/placeOfIssue/bloodType rows present
PASS - registry mother-line names present
PASS - face match figures correct order live (4 / 5, 3 / 5)
```

**Neither pass found**: the UI reimplementing four-eyes (only ever gates on `canApprove`, confirmed
by reading `ProfileDetailPage.tsx` and by the live 403 proof in §4), or any generated Arabic string
carrying a count needing numeral agreement (checked status history, channel/artifact/income-source
lists, reject/manual-complete copy — all count-free or bare-digit, matching `ProfileListPage`'s own
`showTotal` precedent).

## 6. Gates

**Backend** (touched only for BL-024): `./mvnw verify -Pdb-integration-test` — **639/639**, up from
S4-06's 637 (the two new wiring-test methods). Spotless clean. JaCoCo checks met. `./mvnw verify`
alone (487/487) fails the bundle threshold at ~60%, as CLAUDE.md's Coverage section already
documents (R-050) — not a regression.

The task's own text says "report the count against 617"; EXECUTION_PLAN.md's own last recorded
figure at S4-06 was 637, and this session's fix adds 2 more. Reporting the real measured count
(639) rather than reconciling against the stated 617, which does not match this project's own prior
recorded state.

**Backoffice**: `npx tsc -b --noEmit` clean. `npm run lint` (oxlint) — 0 errors, 10 warnings, all
pre-existing categories (`react/set-state-in-effect` on data-fetching effects, `only-export-
components` on `AuthContext.tsx`, `react/globals` on test-only capture variables). `npm run
test:coverage`, run 5 times consecutively: 4 clean, 1 hit a single timing-sensitive failure in an
already-passing test under coverage instrumentation load — isolated re-run of that exact file was
immediately green, and the failure pattern (1-in-5, coverage-instrumentation-related) matches what
S6-01's own session report already documented as an accepted flake class, not a new regression. Final
clean run:

```
Test Files  18 passed (18)
     Tests  140 passed (140)

 % Coverage report from v8
-------------------|---------|----------|---------|---------|
All files          |   97.09 |    86.44 |   97.46 |    98.9 |
-------------------|---------|----------|---------|---------|
Statements   : 97.09% ( 501/516 )
Branches     : 86.44% ( 319/369 )
Functions    : 97.46% ( 154/158 )
Lines        : 98.9% ( 452/457 )
```

All four well above the 80% gate.

## 7. Left open / disclosed, not fixed

- **BL-025 (new)**: reference-code resolution uses each list's current version, not the version
  pinned at data-entry time — no per-field version exists in the response to resolve against instead
  except `occupationVersion`. Same shape as BL-017, distinct cause.
- Document images: out of scope (R-046/AD-002d). `Image.PreviewGroup` built behind a local
  placeholder, ready for a real `src`.
- BL-014 (TypeScript `ar_fold` port): `RejectModal`'s reason-code `Select` deliberately uses a plain
  `Select`, not `showSearch`, on a 7-item list — avoids adding a second instance of the gap, doesn't
  close it.
- Export, admin account management (S6-03); the dashboard (BL-001); AD-002b/AD-002d/AD-003 — all
  explicitly out of scope, untouched.

## 8. Commit and push

Two commits this session: `dab0884` (BL-024 fix, committed alone first per the task's own
suggestion) and `f269413` (steps 3-4, the profile view and review actions). Pushed straight to
`main`, per CLAUDE.md's session-end rule — no feature branch.

```
$ git push
To https://github.com/Osmantou/Fr_user_update
   0f71db4..f269413  main -> main

$ git log --oneline -1
f269413 feat: S6-02 -- single profile view, approve/reject, manual completion

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
