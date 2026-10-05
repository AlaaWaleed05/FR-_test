# Session: S1-07 — Make every coverage gate provably bite

Date: 2026-08-19 · Found during S1-06: the backend JaCoCo gate passed against zero
classes and had never been seen to fail, mobile coverage was measured but unenforced,
and the 90% business-logic tier in PROJECT_PLAN.md was not implemented anywhere. No
feature code, no Uqudo dependency, no hook (that is S1-05).

## 0. Commit check

`git log --oneline -3`:
```
ce47d0c docs: record S1-06 commit/push proof in session report
e2ad399 chore: gate infrastructure — test runner, linter, coverage in every tier (S1-06)
857e03f chore: stage B part 1 — three-tier scaffold and toolchain pins (S1-01)
```
`git status`: `On branch main. Your branch is up to date with 'origin/main'. nothing to
commit, working tree clean.`

S1-06 was already committed and pushed at `e2ad399`, with a follow-up docs commit
`ce47d0c` recording its proof, matching the expectation in the task brief. Nothing was
pending; no separate commit was needed before starting this session's work.

## 1. EXECUTION_PLAN.md — S1-07 row added

Added the S1-07 row exactly as specified, status ✅ (step 5 below passed). Also added
the S1-08 row (§4) at the same time since both rows were specified together in the task
brief.

## 2. backend/ — narrowed the JaCoCo exclusion, proved it bites

**Exclusion narrowed.** `backend/pom.xml`'s JaCoCo `<excludes>` changed from the glob
`**/*Application.class` (which would have silently excluded any future class whose name
happens to end in "Application", not just the Spring Boot entry point) to the single
fully-qualified class `sd/gov/bank/fruserupdate/BackendApplication.class`.

**Proof it bites — deliberate failure.** Added a temporary class
`backend/src/main/java/sd/gov/bank/fruserupdate/TempCoverageProof.java` with one method
containing a branch (`if (a > b) ... else ...`), called by no test. Ran `./mvnw verify`:
JaCoCo's `report` goal first showed "Analyzed bundle 'backend' with 1 classes" (up from
0 — the new, non-excluded class was picked up), and the `check` goal then failed with
`Rule violated for bundle backend: lines covered ratio is 0.00, but expected minimum is
0.80` — a real measured ratio, not an empty-bundle trivial pass. See the full output in
§5.

Note: the first `./mvnw verify` against the newly added file failed earlier, at
Spotless, not JaCoCo — Spotless's Google Java Format requires LF line endings and the
file was created with CRLF. Ran `./mvnw spotless:apply` once to normalize it (this is
the same zero-config formatter behavior already established in S1-06, not a new
finding), then re-ran `./mvnw verify` to reach the JaCoCo failure shown above.

Deleted `TempCoverageProof.java` and re-ran `./mvnw verify`: clean pass, bundle back to
0 classes, `BUILD SUCCESS`. See §5.

## 3. mobile/ — enforced the coverage threshold

**Route chosen: a small Dart script, not a third-party package.** Researched
currently-maintained pub.dev options for enforcing an lcov threshold in a Flutter
project (2026-08-19):

| Package | Latest version | Last published | Verdict |
|---|---|---|---|
| `coverage` (dart-lang, verified publisher `tools.dart.dev`) | 1.15.1 | 2 months ago | Actively maintained, and `format_coverage` gained a `--fail-under` flag in 1.13.0 — but it operates on raw VM-service coverage JSON from `collect_coverage`, not on an already-generated `lcov.info`. `flutter test --coverage` (the flow already wired in S1-06) writes `lcov.info` directly and does not go through `format_coverage`. Adopting `--fail-under` would mean replacing the whole coverage-collection pipeline with the lower-level `collect_coverage`/`format_coverage` flow, a materially bigger change than "enforce a threshold," so this was not used as-is. |
| `check_coverage` | 0.0.8 | 20 months ago | Reads an lcov trace directly and does exactly this job, but has not been published in 20 months — not treated as actively maintained. |
| `dlcov` | 4.2.1 | 4 years ago | Same shape as `check_coverage`, stale. |
| `test_cov_console` | 0.2.2 | 4 years ago | Same, stale. |
| `pull_request_coverage` | 2.1.5 | 4 months ago, in maintenance mode | Actively maintained, but it solves a different problem: coverage of the lines changed in a git diff (PR review gating), not an overall-bundle threshold like the 80% floor already enforced in backend/backoffice. Needs a git diff as input. Not a fit for "does this codebase clear 80% overall." |

No actively-maintained package does the specific job (overall-lcov-threshold, against
the `lcov.info` `flutter test --coverage` already produces) without either a pipeline
rewrite or solving a different problem. Per the task's own decision tree, wrote
`mobile/tool/check_coverage.dart` instead.

**What it does.** A single Dart script, run as `fvm dart run tool/check_coverage.dart`
from `mobile/`:
1. Locates the Flutter SDK's `flutter[.bat]` by walking up from
   `Platform.resolvedExecutable` (the running `dart`, which lives at
   `<sdk>/bin/cache/dart-sdk/bin/dart` inside the pinned Flutter SDK) looking for
   `bin/flutter[.bat]`, so it resolves whichever SDK actually ran it — the fvm pin from
   S1-01 — rather than an unrelated `flutter` elsewhere on PATH.
2. Runs `flutter test --coverage`, streaming its output; aborts with the same exit code
   if tests fail.
3. Parses `coverage/lcov.info`, summing the `LF:`/`LH:` records across the file to get
   overall line coverage.
4. Exits non-zero and prints the actual percentage if it's below 80; prints
   `PASSED`/exit 0 otherwise.

This is the single command that both runs tests-with-coverage and enforces the
threshold, as the task asked for.

**Proof it bites — deliberate failure.** Added a temporary, never-called function
`uncoveredDemoFn` to `mobile/lib/main.dart` with several branches. First attempt (a
small 2-branch function) landed exactly on 80.00% (24/30 lines) — passing, since the
rule is `>= 80`, not a meaningful failure demonstration. Expanded it to more branches so
real coverage genuinely dropped under the floor: `fvm dart run tool/check_coverage.dart`
then printed `Line coverage: 70.59% (24/34 lines), threshold 80%` and `FAILED: coverage
70.59% is below the 80% threshold.`, exit 1. Reverted `main.dart` to its original
content (confirmed via `git diff` showing no changes) and re-ran the check: clean
92.31% pass. See §5 for verbatim output of both runs.

## 4. The 90% business-logic tier — recorded, not faked

No business-logic directories exist yet (module map is still TBD, per S1-05), so no
rule was scoped to anything real, per the task instruction not to invent directory
conventions. Recorded as blocked work:
- EXECUTION_PLAN.md: S1-08 row added (§1).
- PROJECT_PLAN.md: added under Constraints, immediately after the "Regulatory regime..."
  line: "Coverage gates: 80% overall is enforced in backend (JaCoCo) and backoffice
  (Vitest), and in mobile from S1-07. The 90% business-logic tier is NOT yet enforced —
  no business-logic directories exist to scope a rule to. Tracked as S1-08."

## 5. Verify and report

Environment note: this machine's shells don't have `fvm`/`flutter`/`dart`/`JAVA_HOME` on
PATH by default in a fresh session (git-bash's `fvm` lookup fails even though
`C:\Users\DELL\AppData\Local\Pub\Cache\bin\fvm.bat` exists; PowerShell needed
`JAVA_HOME` set explicitly to the JBR OpenJDK 21.0.8 at
`C:\Program Files\Android\Android Studio\jbr`, the same JDK identified in S1-06). All
commands below were run with those resolved explicitly; this isn't a new gap, just a
per-session setup detail.

### mobile — `fvm dart run tool/check_coverage.dart`, passing
```
Running "C:\Users\DELL\fvm\versions\3.47.0/bin/flutter.bat test --coverage"...
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:02 +1: All tests passed!
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```
Exit 0.

### mobile — same command failing against the temporary uncovered function
```
Running "C:\Users\DELL\fvm\versions\3.47.0/bin/flutter.bat test --coverage"...
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:03 +1: All tests passed!
Line coverage: 70.59% (24/34 lines), threshold 80%
FAILED: coverage 70.59% is below the 80% threshold.
```
Exit 1.

### backend — `./mvnw verify` failing on JaCoCo against the temporary class
```
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 1 classes
...
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 3 files clean - 0 needs changes to be clean, 0 were already clean, 3 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 1 classes
[WARNING] Rule violated for bundle backend: lines covered ratio is 0.00, but expected minimum is 0.80
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.15:check (jacoco-check) on project backend: Coverage checks have not been met. See log for details. -> [Help 1]
```
Exit 1. (Built with JBR OpenJDK 21.0.8, `JAVA_HOME=C:\Program Files\Android\Android
Studio\jbr`.)

### backend — `./mvnw verify` passing after the temporary class is removed
```
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 0 classes
...
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```
Exit 0.

### backoffice — `npm run test:coverage`, unchanged
```
> backoffice@0.0.0 test:coverage
> vitest run --coverage

 RUN  v4.1.11 C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/backoffice
      Coverage enabled with v8

 Test Files  1 passed (1)
      Tests  1 passed (1)

 % Coverage report from v8
----------|---------|----------|---------|---------|-------------------
File      | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s 
----------|---------|----------|---------|---------|-------------------
----------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 100% ( 1/1 )
Branches     : 100% ( 0/0 )
Functions    : 100% ( 1/1 )
Lines        : 100% ( 1/1 )
================================================================================
```
Exit 0. No changes made to backoffice this session; confirms nothing regressed.

## Cleanup confirmation

Every temporary file, function, and threshold change introduced for the proofs was
reverted before this diff was staged:
- `backend/src/main/java/sd/gov/bank/fruserupdate/TempCoverageProof.java` — deleted.
- `mobile/lib/main.dart`'s `uncoveredDemoFn` — removed; `git diff` on this file shows no
  changes versus HEAD.
- No threshold values were changed in this session (unlike S1-06's backoffice proof,
  the mobile script's 80% constant was never edited to demonstrate the failure — a
  bigger temporary function was enough).

`git status` before staging showed only the intended changes: `EXECUTION_PLAN.md`,
`PROJECT_PLAN.md`, `backend/pom.xml` modified, and the new `mobile/tool/` directory
untracked. No leftover proof artifacts.

## What failed / notable friction

- `fvm`, `flutter`, `dart` and `JAVA_HOME` are not on PATH by default in this session's
  shells — had to locate `fvm.bat` under the pub-cache bin directory and the JBR JDK
  under Android Studio's install and invoke/set them explicitly. Not a new gap (S1-06
  hit the same JDK requirement); recorded here since it cost real time this session.
- The first coverage-threshold failure demo (a 2-branch temporary function) landed
  exactly on the 80% boundary and passed, which would have been a vacuous "failure"
  demonstration — same shape of problem S1-06 hit with the backoffice proof. Resolved
  by widening the temporary function until real coverage genuinely dropped below 80%,
  rather than editing the threshold itself.
- Spotless (LF-only Google Java Format) flagged the temporary backend proof file before
  JaCoCo ever ran, because it was created with CRLF line endings. Ran
  `./mvnw spotless:apply` once to normalize it so the intended JaCoCo failure could be
  observed — expected interaction between the two gates, not a defect in either.

## Status

S1-07 set to ✅ in EXECUTION_PLAN.md: the JaCoCo exclusion is narrowed to the
fully-qualified entry-point class, mobile coverage is now enforced by
`mobile/tool/check_coverage.dart`, both gates are shown failing against a real measured
ratio and then passing cleanly after cleanup, backoffice is confirmed unregressed, and
the 90% business-logic tier is recorded (not faked) as S1-08 in both plan files.

## Final commit and push

`git log --oneline -1`:
```
47d69f6 chore: prove coverage gates bite; enforce mobile coverage (S1-07)
```

`git status`:
```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `ce47d0c..47d69f6  main -> main`.
