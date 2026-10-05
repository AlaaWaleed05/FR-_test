# 2026-08-21 — S1-10: Reconcile stale plan-file text; resolve docs/list Docs/

Reconciliation session. No feature code, no scaffold changes, no new dependencies.

## Step 0 — commit check

`git log --oneline -3` at session start:

```
1d86579 docs: record S1-09 commit/push proof in session report
c64137b docs: file journey specification (S1-09) and reconcile plan files
d63b4c9 docs: record S1-05 commit/push proof in session report
```

S1-09 confirmed at `c64137b`, matching the expectation in the session prompt (with the
follow-up proof-recording commit `1d86579` on top of it).

`git status` at session start: clean, up to date with `origin/main`.

## Edits made

### EXECUTION_PLAN.md
Added the S1-10 row (🔵, then set to ✅ once step 7's verification passed).

### PROJECT_PLAN.md and CLAUDE.md — messaging is not completion-only
Both files' "Outbound SMS, WhatsApp and email on completion" line replaced with the
throughout-the-journey wording (OTP codes, submission notification, every status
transition), identical text in both files.

### PROJECT_PLAN.md — the back office is not read-only
- Overview paragraph's back-office sentence replaced to name the two write actions
  (approve/reject with a coded reason, manual completion) plus navigation, export and the
  dashboard.
- Module map `backoffice/` bullet replaced to name the two write actions, the four-eyes
  rule, and point at docs/journeys/operator.md.

### CLAUDE.md — Architecture, `backoffice/` bullet
Replaced with the same two-write-actions / four-eyes-rule wording, kept short for the
Architecture section's existing style.

### PROJECT_PLAN.md — OQ-005
Replaced with the ANSWERED wording from the session prompt, citing the journey
specification's design principle and docs/journeys/customer.md.

## docs/list Docs/ resolved

`git mv "docs/list Docs" docs/source-material` — no space in the path, so the
generated-file guard hook and any shell glob touching it now work correctly.

Wrote `docs/source-material/README.md` stating plainly that nothing in the directory is
authoritative: `docs/reference/` and `docs/journeys/` are authoritative;
`Sudan_Admin_Hierarchy.xlsx` and `Sudan_States_and_Localities.xlsx` are superseded drafts
of `docs/reference/NSudan_Admin_Hierarchy.xlsx` (itself interim per OQ-012); the paper-form
PDF and the two photographs are kept for provenance only.

Confirmed nothing outside `docs/source-material/` references the old `docs/list Docs`
path except two expected, non-broken mentions: the historical
`docs/sessions/2026-08-21-s1-09-journey-filing.md` (a record of what happened, not edited)
and the S1-10 row's own task description in EXECUTION_PLAN.md (describing the problem that
was found, not a path reference).

## Filename check

Actual filename on disk was `docs/reference/كود المهنة.xlsx` — **with a space** —
against `docs/journeys/customer.md`'s reference to `كود_المهنة.xlsx` (underscore).
`git mv`'d to the underscore form. `customer.md` was not edited: its existing reference
already used the underscore form and now matches the file on disk.

## Verify and report

### `grep -rn "on completion" PROJECT_PLAN.md CLAUDE.md`

```
PROJECT_PLAN.md:41:- Outbound SMS, WhatsApp and email throughout the journey, not only on completion: three
CLAUDE.md:18:- Outbound SMS, WhatsApp and email throughout the journey, not only on completion: three
```

The stale sentence ("Outbound SMS, WhatsApp and email on completion.") is gone in both
files. The only remaining matches are the phrase "not only on completion" inside the new
replacement wording, which is intentional.

### `grep -rln "list Docs"` across the repo

```
EXECUTION_PLAN.md
docs\sessions\2026-08-21-s1-09-journey-filing.md
```

Both are expected: the historical S1-09 report and the S1-10 task-description row
recording that the directory was found and renamed. No path in the repo still points at
`docs/list Docs/`.

### CLAUDE.md line count

**127 lines** (under the 200-line ceiling).

## Gate output — all three tiers, no regressions

### backend (`./mvnw verify`, `JAVA_HOME` set to the JBR)

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

### backoffice (`npm run test:coverage`, isolated Node v24.19.0)

```
 Test Files  1 passed (1)
      Tests  1 passed (1)

Statements   : 100% ( 1/1 )
Branches     : 100% ( 0/0 )
Functions    : 100% ( 1/1 )
Lines        : 100% ( 1/1 )
```

### backoffice (`npm run lint`)

```
> backoffice@0.0.0 lint
> oxlint
```
No issues reported.

### mobile (`fvm flutter test`)

```
00:00 +0: loading .../mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```

### mobile (`fvm flutter analyze`)

```
Analyzing mobile...
No issues found! (ran in 29.1s)
```

### mobile (`fvm dart run tool/check_coverage.dart`)

```
00:02 +1: All tests passed!
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

Nothing changed in any of the three tiers this session; all gates pass with the same shape
of output as prior sessions.

## Commit / push proof

```
$ git push
To https://github.com/Osmantou/Fr_user_update
   1d86579..10caf5c  main -> main

$ git log --oneline -1
10caf5c docs: reconcile stale plan-file text after journey filing (S1-10)

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
