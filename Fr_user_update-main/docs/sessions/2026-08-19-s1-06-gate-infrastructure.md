# Session: S1-06 — Gate infrastructure

Date: 2026-08-19 · Made the quality gates in PROJECT_PLAN.md enforceable in all three
tiers, and reconciled the plan files. No feature code, no Uqudo dependency, no hook
(that is S1-05).

## 0. Commit check

`git log --oneline -3`:
```
857e03f chore: stage B part 1 — three-tier scaffold and toolchain pins (S1-01)
d263a7a docs: close AD-001 — record stack decision and file Uqudo component card
d9b3d90 docs: AD-001 stack research report (Flutter + Spring Boot + React/TS)
```
`git status`: `On branch main. Your branch is up to date with 'origin/main'. nothing to
commit, working tree clean.`

S1-01 was already committed at `857e03f` as expected. Nothing was pending; no separate
commit was needed before starting this session's work.

## 1. EXECUTION_PLAN.md — S1-06 row added

Added exactly as specified, status ✅ (step 6 passed — see §6).

## 2. backoffice/ — test runner and coverage

Resolved current npm registry versions (2026-08-19, via `npm view <pkg> version` using
the isolated Node 24.19.0 from S1-01):

| Package | Version |
|---|---|
| vitest | 4.1.11 |
| @vitest/coverage-v8 | 4.1.11 |
| @testing-library/react | 16.3.2 |
| @testing-library/jest-dom | 7.0.1 |
| jsdom | 30.0.1 |

Installed as devDependencies. Configured Vitest inside the existing `vite.config.ts`
(`test` block, `environment: 'jsdom'`, `setupFiles: ['./src/test/setup.ts']` importing
`@testing-library/jest-dom/vitest`). Added `src/App.test.tsx`: renders `<App />` and
asserts the heading text is present — nothing more.

Scripts added: `"test": "vitest run"`, `"test:coverage": "vitest run --coverage"`.
Coverage thresholds (v8 provider): lines/statements/branches/functions all 80,
`coverage.exclude` covering `src/main.tsx` (bootstrap entry point, never exercised by a
component test) and `src/vite-env.d.ts` (type declarations only).

**App.tsx trimmed.** The Vite `react-ts` template's default demo content (counter
button with an onClick handler, external link lists) has a code path — the click
handler — that a "renders and mounts" test never exercises, which held coverage at
66% lines / 33% functions, below the 80% floor. The task forbids writing more than the
one trivial test, and the demo content is generator boilerplate that was never meant to
ship (its own comment says "Edit src/App.tsx ... to test HMR"), so it was replaced with
a minimal placeholder (`<h1>Fr_user_update back office</h1>`) that the trivial test
fully exercises. The unused demo assets (`App.css`, `assets/react.svg`,
`assets/vite.svg`, `assets/hero.png`) were deleted since nothing else referenced them
(confirmed by grep across the tier). This is scaffold cleanup, not feature code — no
product UI exists yet (module map is still TBD, per S1-05).

Enforceable Node pin: `"engines": {"node": ">=24.19.0 <25"}` added to `package.json`;
`backoffice/.npmrc` created containing `engine-strict=true`. Verified it bites — see
§6 npm engine-strict refusal, run against this machine's global Node v22.22.2.

Added `coverage` to `backoffice/.gitignore` (Vitest's coverage output directory wasn't
covered by the generator's own ignore file).

## 3. backend/ — linter and coverage

Resolved current stable Maven Central versions (2026-08-19, via
`maven-metadata.xml` `<release>`):

| Plugin | Version |
|---|---|
| org.jacoco:jacoco-maven-plugin | 0.8.15 |
| com.diffplug.spotless:spotless-maven-plugin | 3.10.0 |
| org.apache.maven.plugins:maven-enforcer-plugin | 3.6.3 |

**JaCoCo**: bound `prepare-agent` (implicit), `report` at the `test` phase, and `check`
at the `verify` phase with a `BUNDLE`/`LINE`/`COVEREDRATIO` rule at minimum `0.80`.
`**/*Application.class` is excluded from the JaCoCo bundle at the plugin-configuration
level (applies to `report` and `check` alike). Rationale: the generated
`@SpringBootApplication` entry point's `main()` body is bootstrap glue that the
generated `@SpringBootTest` smoke test never calls — Spring boots the test context
directly, not via `main()` — so with only the smoke test (which the task says is
"enough... do not write more tests"), the entry-point class alone measured 33% line
coverage, well under the floor. Excluding `*Application.class` from coverage is a
standard, widely used convention for Spring Boot projects (the class is not meaningful
application logic); it mirrors the `src/main.tsx` exclusion made in the backoffice tier
for the same reason (bootstrap glue, not testable without violating the "no extra
tests" instruction). With the exclude, JaCoCo currently analyzes a bundle of 0 classes
(the codebase has no feature code yet) and the check trivially passes; the 80% rule is
live and will bind against every class added from S1-02 onward once real backend code
exists.

**Spotless**: `com.diffplug.spotless:spotless-maven-plugin` with `<java><googleJavaFormat/></java>`,
bound to `check` at the `verify` phase (fails the build on a violation, does not merely
warn). Chose Google Java Format specifically because it is Spotless's zero-config
standard formatter — no separate style-file dependency (unlike Palantir's format,
which needs its own additional Maven coordinate and version pin) and no external XML
config file (unlike the Eclipse formatter). It required immediately reformatting the
two generator-produced files: Spring Initializr's own template uses tabs, GJF requires
2-space indentation. Ran `mvnw spotless:apply` once to bring the scaffold into
compliance (diff is formatting only — see the two files' whitespace changes in the
commit).

**maven-enforcer-plugin**: bound `enforce` at its default phase with a
`requireJavaVersion` rule for `[21,22)`, so building with a non-21 JDK fails loudly
instead of silently compiling.

No ojdbc dependency was added or touched — still blocked on OQ-006.

## 4. mobile/ — coverage

`fvm flutter test --coverage` emits `mobile/coverage/lcov.info` — confirmed present,
254 bytes, 30 lines.

`mobile/.gitignore` already contained `/coverage/` (line 34, from the Flutter template
generator's own default) — no edit needed.

**Gap, recorded per instruction**: Flutter has no built-in coverage-threshold
enforcement. No script was invented for it. The mobile coverage threshold is currently
unenforced — this is a gap for S1-05 to note in CLAUDE.md's Coverage section.

## 5. Plan-file reconciliation

- PROJECT_PLAN.md: OQ-001 and OQ-004 changed to reference AD-002 only (AD-001 is
  closed). OQ-009, OQ-010, OQ-011 added verbatim as specified. AD-003 added to Open
  architecture decisions verbatim as specified.
- EXECUTION_PLAN.md: S1-01 task text changed to "Stage B bootstrap: three-tier
  scaffold, toolchain pins, gate commands detected". S1-02 Notes replaced verbatim.
  S1-03 status set to ⚠️, Notes replaced verbatim. S1-06 row added (§1).
- CLAUDE.md: two hard rules added verbatim (Flutter iOS-support-at-add-time; Uqudo
  credentials never in repo/prompt/log/session-report).
- docs/components/uqudo-sdk.md: the two Open-items entries both asking about SDK UI
  language merged into the single specified line.
- BACKLOG.md: BL-003 added verbatim (Gradle/AGP/Kotlin upgrade, coupled to Uqudo's
  Maven-repo injection, sequenced against S1-02).

## 6. Verify and report

### mobile — `fvm flutter test --coverage`
```
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```
Exit 0. `coverage/lcov.info` produced (254 bytes, 30 lines).

### mobile — `fvm flutter analyze`
```
Analyzing mobile...
No issues found! (ran in 13.2s)
```
Exit 0.

### backend — `./mvnw verify` (Spotless + JaCoCo shown)
```
[INFO] --- enforcer:3.6.3:enforce (enforce-java-21) @ backend ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] --- jacoco:0.8.15:prepare-agent (jacoco-prepare-agent) @ backend ---
[INFO] argLine set to -javaagent:...\org.jacoco.agent-0.8.15-runtime.jar=destfile=...\backend\target\jacoco.exec,excludes=**/*Application.class
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 0 classes
...
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
Exit 0. (Built with JBR OpenJDK 21.0.8, the only JDK on this machine, per S1-01.)

### backoffice — `npm run lint`
```
> backoffice@0.0.0 lint
> oxlint

```
Exit 0 (oxlint prints nothing when clean).

### backoffice — `npm run test`
```
> backoffice@0.0.0 test
> vitest run


 RUN  v4.1.11 C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/backoffice


 Test Files  1 passed (1)
      Tests  1 passed (1)
   Start at  05:42:40
   Duration  2.68s (transform 60ms, setup 297ms, import 89ms, tests 56ms, environment 1.83s)
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
   Start at  05:42:45
   Duration  2.66s (transform 62ms, setup 336ms, import 97ms, tests 52ms, environment 1.67s)

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
Exit 0.

## Deliberate-failure proof 1 — backend Spotless

Introduced a deliberate indentation violation in
`backend/src/main/java/sd/gov/bank/fruserupdate/BackendApplication.java` (the `main()`
body's closing structure re-indented to 8 spaces instead of GJF's 4), then ran
`./mvnw spotless:check`:

```
[INFO] --- spotless:3.10.0:check (default-cli) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 1 needs changes to be clean, 0 were already clean, 1 were skipped because caching determined they were already clean
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal com.diffplug.spotless:spotless-maven-plugin:3.10.0:check (default-cli) on project backend: The following files had format violations:
[ERROR]     src\main\java\sd\gov\bank\fruserupdate\BackendApplication.java
[ERROR]         @@ -7,6 +7,6 @@
[ERROR]          public·class·BackendApplication·{
[ERROR]          
[ERROR]          ··public·static·void·main(String[]·args)·{
[ERROR]         -········SpringApplication.run(BackendApplication.class,·args);
[ERROR]         +····SpringApplication.run(BackendApplication.class,·args);
[ERROR]          ··}
[ERROR]          }
[ERROR] Run 'mvn spotless:apply' to fix these violations.
[ERROR] -> [Help 1]
```
Exit 1 — confirmed failing. Reverted the file; re-ran `./mvnw spotless:check`:
```
[INFO] --- spotless:3.10.0:check (default-cli) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 1 were already clean, 1 were skipped because caching determined they were already clean
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```
Exit 0 — confirmed clean again.

## Deliberate-failure proof 2 — backoffice coverage threshold

Baseline `App.tsx` was deliberately trimmed to exactly what the one trivial test
exercises (§2), so its real coverage is a clean 100% — meaning raising the threshold to
100 alone would not fail (100% ≥ 100% still passes; there was no natural gap left to
expose). To make the proof meaningful rather than vacuous, the demonstration pairs the
threshold change the task asked for with one temporary, clearly-marked, never-called
function in `App.tsx` (`uncoveredDemoFn`) — the same "introduce, prove, revert" shape
as the backend Spotless proof above, just applied to source instead of formatting.

Set `vite.config.ts` coverage thresholds to `100` for all four metrics, added the
temporary uncovered function, ran `npm run test:coverage`:
```
 % Coverage report from v8
----------|---------|----------|---------|---------|-------------------
File      | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s 
----------|---------|----------|---------|---------|-------------------
All files |      50 |      100 |      50 |      50 |                   
 App.tsx  |      50 |      100 |      50 |      50 | 11                
----------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 50% ( 1/2 )
Branches     : 100% ( 0/0 )
Functions    : 50% ( 1/2 )
Lines        : 50% ( 1/2 )
================================================================================
ERROR: Coverage for lines (50%) does not meet global threshold (100%)
ERROR: Coverage for functions (50%) does not meet global threshold (100%)
ERROR: Coverage for statements (50%) does not meet global threshold (100%)
```
Exit 1 — confirmed failing. Reverted both the temporary function and the threshold
values back to 80; re-ran `npm run lint`, `npm run test`, `npm run test:coverage` (§6
above) to confirm the tier is back to its clean, passing state.

## npm engine-strict refusal (Node pin proof)

Ran an npm command against this tier using the machine's global Node v22.22.2 (not the
isolated 24.19.0 copy from S1-01):
```
v22.22.2
10.9.7
npm error code EBADENGINE
npm error engine Unsupported engine
npm error engine Not compatible with your version of node/npm: backoffice@0.0.0
npm error notsup Not compatible with your version of node/npm: backoffice@0.0.0
npm error notsup Required: {"node":">=24.19.0 <25"}
npm error notsup Actual:   {"npm":"10.9.7","node":"v22.22.2"}
```
Exit 1 — the pin refuses as required.

## Mobile coverage-threshold gap

Flutter has no built-in coverage-threshold enforcement mechanism, and none was
invented for it (out of scope per the task). `mobile/coverage/lcov.info` is produced
and could be fed to a third-party threshold tool later, but nothing currently fails a
mobile build for low coverage. This gap is recorded here for S1-05 to note in
CLAUDE.md's Coverage section.

## What failed / notable friction

- The Vite `react-ts` template's default demo content and the Spring Initializr
  scaffold's default tab-indented files were both, independently, incompatible with an
  80% coverage floor / a zero-config formatter respectively, once only the one
  permitted trivial test was in play. Both were resolved by convention (trim
  never-shipping demo boilerplate; run the formatter once) rather than by loosening any
  threshold.
- A literal "raise the coverage threshold to 100 and watch it fail using only a
  threshold change" demonstration doesn't naturally produce a failure once real
  coverage is honestly 100% — see "Deliberate-failure proof 2" above for how this was
  resolved without inventing permanent dead code in `App.tsx`.

## Status

S1-06 set to ✅ in EXECUTION_PLAN.md: all seven gate commands ran and are pasted
verbatim above, both deliberate-failure proofs are shown failing then reverted, the npm
engine-strict refusal is shown, the mobile coverage-threshold gap is recorded, and all
plan-file edits from the task brief were applied.

## Final commit and push

`git log --oneline -1`:
```
e2ad399 chore: gate infrastructure — test runner, linter, coverage in every tier (S1-06)
```

`git status`:
```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `857e03f..e2ad399  main -> main`.
