# Session: S1-05 — Stage B part 2, CLAUDE.md and the generated-file guard

Date: 2026-08-19 · Completed CLAUDE.md (Commands, Coverage, Architecture, hard rules),
wrote and tested the generated-file guard hook, created RISKS.md. No feature code, no
Uqudo dependency. Last infrastructure session before AD-002 research.

## 0. Commit check

`git log --oneline -3`:
```
526acd6 docs: record S1-07 commit/push proof in session report
47d69f6 chore: prove coverage gates bite; enforce mobile coverage (S1-07)
ce47d0c docs: record S1-06 commit/push proof in session report
```
`git status`: `On branch main. Your branch is up to date with 'origin/main'. nothing to
commit, working tree clean.`

S1-07's substantive commit `47d69f6` matched the expected hash exactly; `526acd6` is
S1-07's own follow-up docs-proof commit, already pushed. Nothing was pending; no
separate commit was needed before starting this session's work. S1-05 set to 🔵.

## 1. PROJECT_PLAN.md — Module map

Replaced the TBD with a four-bullet map covering the three tiers' responsibilities and
boundaries (mobile owns the on-device journey and forwards the raw JWS untouched;
backend owns every external integration and is the only tier holding Uqudo
credentials; backoffice reads backend-held profile data) plus a line noting no
feature/business-logic subdirectories exist yet. Also added a one-line "Plan files"
note under Overview naming RISKS.md as a fourth session-start file (§5 below).

## 2. CLAUDE.md — complete

Filled Commands (per-tier test / test+coverage / lint+analyze / build, plus the PATH
resolution facts: `fvm` at `C:\Users\DELL\AppData\Local\Pub\Cache\bin\fvm.bat` not on
PATH, `JAVA_HOME` must be `C:\Program Files\Android\Android Studio\jbr`, backoffice
needs the isolated Node 24.19.0 install since global Node is 22.22.2 and is refused by
`engine-strict`). Filled Coverage with the honest state (80% overall enforced in all
three tiers; 90% business-logic tier not enforced, S1-08; the untested-file coverage
gap, R-009). Filled Architecture with the condensed module map. Added the generated-file
paths, the server-side-only-JWS rule, the pre-commit gate rule, the plan-mode rule, and
the compaction-preservation rule to Hard rules. Added RISKS.md to the session-start
list.

**Line count: 116** (`wc -l CLAUDE.md`), well under the 200-line ceiling.

## 3. Generated-file guard hook

Wrote `.claude/hooks/block-generated.sh`: reads the PreToolUse JSON on stdin, extracts
`tool_input.file_path` via `python` (the only reliable JSON parser confirmed present on
this machine — no `jq`), normalizes backslashes to forward slashes, and checks it
against the exact generated-path list from CLAUDE.md §Hard rules. Exits 2 with an
explanation on stderr on a match, exits 0 (silently allowing the edit) on no match or on
malformed/empty input — a parse failure fails open, not closed, so a hook bug cannot
itself corrupt an unrelated tool call.

`chmod +x` applied. Wired into `.claude/settings.json` (committed, not
`settings.local.json`) as a PreToolUse hook matching `Edit|Write`, running
`bash .claude/hooks/block-generated.sh`.

Added `.gitattributes` (`*.sh text eol=lf`): this machine's `core.autocrlf=true` was
about to store the hook script with CRLF on its next checkout, which risks the same
class of failure S1-07 hit with a CRLF-created Java file under Spotless — except here
it would silently degrade the guard itself. Forced LF instead of discovering it later.

### Standalone script test (before wiring)

Piped a synthetic PreToolUse JSON for `mobile/pubspec.lock` directly into the script:
```
BLOCKED: 'c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\mobile\pubspec.lock' is a generated file (CLAUDE.md: NEVER hand-edit generated files). Edit the source and re-run the tool instead.
```
Exit 2. A malformed-JSON fixture (an earlier attempt where shell quoting had collapsed
the escaped backslashes) was also piped in as a fail-open check: exit 0, no crash —
confirms a hook bug degrades to "allow" rather than blocking all edits.

### Live test through the harness — three blocks

**mobile** — attempted `Edit` on `mobile/pubspec.lock`:
```
PreToolUse:Edit hook error: [bash .claude/hooks/block-generated.sh]: BLOCKED: 'c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\mobile\pubspec.lock' is a generated file (CLAUDE.md: NEVER hand-edit generated files). Edit the source and re-run the tool instead.
```

**backend** — attempted `Edit` on `backend/target/classes/application.properties`:
```
PreToolUse:Edit hook error: [bash .claude/hooks/block-generated.sh]: BLOCKED: 'c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\classes\application.properties' is a generated file (CLAUDE.md: NEVER hand-edit generated files). Edit the source and re-run the tool instead.
```

**backoffice** — attempted `Edit` on `backoffice/package-lock.json`:
```
PreToolUse:Edit hook error: [bash .claude/hooks/block-generated.sh]: BLOCKED: 'c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backoffice\package-lock.json' is a generated file (CLAUDE.md: NEVER hand-edit generated files). Edit the source and re-run the tool instead.
```
(First attempt against this file hit the Edit tool's own "2 matches, replace_all is
false" validation before the hook could be exercised meaningfully; retried with a
unique `old_string` to get the actual hook verdict above.)

### Live test through the harness — allowed

Attempted `Edit` on `mobile/lib/main.dart` (ordinary source file), adding then removing
a one-line comment marker:
```
The file c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\mobile\lib\main.dart has been updated successfully.
```
Allowed both ways. `git diff mobile/lib/main.dart` after the revert showed no changes —
confirmed back to original.

### Confirmation the three blocked edits never touched disk

```
$ git ls-files mobile/pubspec.lock backend/target/classes/application.properties backoffice/package-lock.json
backoffice/package-lock.json
mobile/pubspec.lock
$ grep -n "spring.application.name" backend/target/classes/application.properties
1:spring.application.name=backend
```
Content unchanged; `git status --short` after all four tests showed only the intended
plan-file/CLAUDE.md/RISKS.md changes plus the new `.claude/` directory — no leftover
edits to any generated file.

## 4. RISKS.md

Created at repo root with R-001 through R-010 exactly as specified in the task brief,
plus the three retired-at-creation items (node-oracledb vs Oracle 11g, MUI X licence
cliff, native-over-PWA) recorded so they are not re-raised.

## 5. Plan-file updates

- EXECUTION_PLAN.md: S1-08's task text broadened to cover both the 90% business-logic
  tier and the untested-file coverage gap; status kept ⚠️. S1-05 row set to ✅ (this
  session, §6 below passed).
- PROJECT_PLAN.md: added a "Plan files" line under Overview naming RISKS.md as a
  fourth file read at session start.
- CLAUDE.md: session-start hard rule now reads "PROJECT_PLAN.md, EXECUTION_PLAN.md,
  BACKLOG.md, RISKS.md."

## 6. Verify and report

Environment note, consistent with S1-07: this machine's shells don't have
`fvm`/`JAVA_HOME`/the isolated Node on PATH by default. All commands below resolved
them explicitly per the Commands section just written in CLAUDE.md.

### mobile — `fvm dart run tool/check_coverage.dart`
```
Running "C:\Users\DELL\fvm\versions\3.47.0/bin/flutter.bat test --coverage"...
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```
Exit 0.

### mobile — `fvm flutter analyze`
```
Analyzing mobile...
No issues found! (ran in 13.3s)
```
Exit 0.

### backend — `./mvnw verify` (JaCoCo + Spotless)
```
[INFO] --- surefire:3.5.6:test (default-test) @ backend ---
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```
Exit 0. (Built with JBR OpenJDK 21.0.8, `JAVA_HOME=C:\Program Files\Android\Android
Studio\jbr`.)

### backend — `./mvnw spotless:check`
```
[INFO] --- spotless:3.10.0:check (default-cli) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO] BUILD SUCCESS
```
Exit 0.

### backoffice — `npm run test:coverage`
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
Exit 0. (Node v24.19.0, npm 11.17.0, from the isolated install.)

### backoffice — `npm run lint`
```
> backoffice@0.0.0 lint
> oxlint

```
Exit 0.

### CLAUDE.md line count
```
$ wc -l CLAUDE.md
116 CLAUDE.md
```

All recorded invocations worked as written — no documentation fix was needed.

## What failed / notable friction

- Two harmless snags while testing the hook, neither a hook defect: (1) the first
  `backoffice/package-lock.json` edit attempt hit the Edit tool's own ambiguous
  `old_string` validation before the hook fired meaningfully — retried with a unique
  string to get a real verdict; (2) an early attempt to run the three tiers' gate
  commands in one parallel Bash batch raced on the persistent shell's working directory
  (a `cd mobile` from one call was still in effect when the next call's `cd backend`
  ran) — resolved by running sequentially with an explicit `cd` to the repo root
  between tiers.
- Confirming the JSON-parsing path in the hook script needed care: bash's `printf '%s'`
  with a single-quoted argument still collapsed `\\` to `\` in one earlier test fixture,
  producing invalid JSON. Using the `Write` tool for the fixture file instead of shell
  string construction avoided the issue and is the safer pattern for any future hook
  test fixtures.
- No PATH or command-invocation surprises beyond what S1-01/S1-06/S1-07 had already
  found and CLAUDE.md now documents.

## Status

S1-05 set to ✅ in EXECUTION_PLAN.md: CLAUDE.md is complete and 116 lines, RISKS.md
exists with all ten risks plus the three retired items, the hook is wired in the
committed `.claude/settings.json` and proven to block edits in all three tiers while
allowing an ordinary source edit, and all plan-file cross-references (RISKS.md as a
session-start file, S1-08's broadened scope) are in place.

## Final commit and push

`git log --oneline -1`:
```
687d55b chore: complete CLAUDE.md, add and prove generated-file guard hook (S1-05)
```

`git status`:
```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `526acd6..687d55b  main -> main`.

