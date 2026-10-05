# Session: S1-12 — file the Uqudo API specification findings; correct R-012

2026-08-22. Documentation session: no feature code, no scaffold changes, no dependencies.

## Step 0 — commit check

`git log --oneline -3` at session start:

```
130eda2 docs: record S1-11 commit/push proof in session report
79f8496 docs: close AD-002a and reconcile journey/risk register (S1-11)
b576ee8 docs: record AD-002a commit/push proof in session report
```

HEAD was `130eda2` as expected — S1-11's substantive commit `79f8496` with its proof-recording
commit `130eda2` on top.

`git status` showed a clean tree except three **untracked** files:

```
Untracked files:
	docs/components/uqudo-api-findings.md
	docs/components/uqudo-face-api.openapi.yaml
	docs/components/uqudo-info-api.openapi.yaml
```

As the S1-11 report anticipated, the product owner renamed the untracked `docs/components/face`
file and saved it alongside the Info spec and the findings document. All three were `git add`ed
in this session (confirmed staged before the final commit).

## Edits made

1. **EXECUTION_PLAN.md** — added the S1-12 row (🔵) after S1-11, and replaced S1-02 Notes
   measurement item (5) — the `POST /api/v1/face` request body, now answered — with the
   `faceImage` base64-vs-id check (R-024).
2. **RISKS.md**:
   - R-012 — Risk and Impact columns replaced entirely. The risk is no longer "access tokens
     expire after 1800s"; it is now "a resumed session may hold a JWS whose images Uqudo has
     already deleted", with the 30-minute Info API image-retention window as the actual
     mechanism and Mitigation restated around the verify→download→checksum design rule. Status
     unchanged, 🟡 Watching.
   - R-023 → ✅ Retired, with the Face API OpenAPI closure appended to Mitigation.
   - R-021 → kept 🔴 Live but narrowed to only the undocumented `exp` value; the
     image-retention half is marked CLOSED inline.
   - R-024 added: the `faceImage` (base64) vs `faceImageId` (reference) discrepancy between
     the scan field pages and `scan-object`, 🟡 Watching, to check at S1-02.
3. **PROJECT_PLAN.md** — Architecture section: added a new "Settled facts from the Uqudo
   OpenAPI specifications and Sudan document field pages" block covering the `identityNumber`
   vs `documentNumber` Civil Registry lookup-key trap, both document types carrying
   `identityNumber`, the per-document-type OCR field set and the two SDN_ID card versions, the
   30-minute image window and `image/jpeg` content type, the Face API's bytes-only request body
   and limits, `jti` = session id, the deliberate `DELETE /api/v1/info/{jti}` privacy purge, the
   default match-level-3 threshold, `mrzVerified`, and the QR-code-only scope of the rest of the
   Info API.
4. **docs/journeys/customer.md** — Stage 12, "The stale-artifact cases": replaced the paragraph
   that attributed the retry-window limit to "the Uqudo token lives 1800 seconds" with the
   corrected mechanism — Uqudo deletes session images after 30 minutes; the JWS itself still
   verifies; the practical retry window is 30 minutes, not hours. No other line in customer.md
   was touched.
5. **docs/components/uqudo-sdk.md** — folded in the findings, preserving existing `[DOC]` /
   `[OBSERVED]` / `[UNVERIFIED]` markers and adding new ones:
   - Sources line and a new "Docs" bullet point at the two local OpenAPI files by path.
   - Backend surface section: image endpoint now notes the 30-minute 404 window and
     `image/jpeg` content type; the info/jti section now confirms `jti` = session id and states
     the deliberate purge rationale and the QR-code-only scope of the rest of the API; the face
     session line now states the resolved request body (bytes only, multipart or base64, 5 MB /
     JPEG-PNG limits, `sessionId` response valid 10 minutes) and the closure of R-023; the
     `minimumMatchLevel` default of 3 was added next to the existing face-matching endpoints.
   - "Result validity and the stale-artifact case" section retitled to reference R-012 and
     R-021, with the image-retention clock marked CLOSED at 30 minutes and the retry-window
     conclusion restated.
   - New "Sudan document field pages — the parser contract" subsection under Sudan: the
     `identityNumber`/`documentNumber` trap, the two-version SDN_ID field tables, the SDN
     passport field set, the per-document-type parser point, `mrzVerified`, and the
     address/occupation cross-check note.
   - Open items: removed the now-closed items (Face API request body, `jti`=session-id,
     locate the OpenAPI spec, image retention window) and added the S1-02 check for
     `faceImage` base64 vs `faceImageId` (R-024).

## Verification — grep for surviving 1800-second causal claim

```
$ grep -n "1800" RISKS.md docs/journeys/customer.md
docs/journeys/customer.md:598:The reason is the token's 1800-second lifetime. A customer who reaches this stage and then
docs/journeys/customer.md:815:   deleted by Uqudo after **600 seconds** — a tighter clock than the 1800-second
```

Both surviving mentions are unrelated facts, not the corrected causal claim: line 598 is stage
7's reason for requesting the enrolment token at point-of-use rather than earlier in the flow;
line 815 is stage 10's comparison of the 600-second face-session window against the 1800-second
enrolment token. Neither claims that an expired access token causes the backend to reject a JWS
on resume — that claim has been removed from RISKS.md and from Stage 12 of customer.md.

R-023 confirmed `✅ Retired` and R-024 confirmed present in RISKS.md (both greppable, see body
above).

All three new files (`docs/components/uqudo-api-findings.md`,
`docs/components/uqudo-face-api.openapi.yaml`, `docs/components/uqudo-info-api.openapi.yaml`)
were tracked via `git add` before the final commit.

## Gate output — all three tiers (verbatim)

### backend — `./mvnw verify` (JAVA_HOME set to the JBR OpenJDK 21)

```
[INFO] Scanning for projects...
[INFO]
[INFO] ------------------< sd.gov.bank.fruserupdate:backend >------------------
[INFO] Building backend 0.0.1-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- enforcer:3.6.3:enforce (enforce-java-21) @ backend ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO]
[INFO] --- jacoco:0.8.15:prepare-agent (jacoco-prepare-agent) @ backend ---
[INFO] argLine set to -javaagent:...org.jacoco.agent-0.8.15-runtime.jar=destfile=...\target\jacoco.exec,excludes=sd/gov/bank/fruserupdate/BackendApplication.class
[INFO]
[INFO] --- resources:3.5.0:resources (default-resources) @ backend ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO] Copying 0 resource from src\main\resources to target\classes
[INFO]
[INFO] --- compiler:3.15.0:compile (default-compile) @ backend ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- resources:3.5.0:testResources (default-testResources) @ backend ---
[INFO] skip non existing resourceDirectory ...\backend\src\test\resources
[INFO]
[INFO] --- compiler:3.15.0:testCompile (default-testCompile) @ backend ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- surefire:3.5.6:test (default-test) @ backend ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO]
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running sd.gov.bank.fruserupdate.BackendApplicationTests
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.89 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact ...\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to ...\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 2 files clean - 0 needs changes to be clean, 0 were already clean, 2 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file ...\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  34.079 s
```

### backoffice — `npm run test:coverage` (Node v24.19.0 from the isolated install)

```
> backoffice@0.0.0 test:coverage
> vitest run --coverage

 RUN  v4.1.11 C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/backoffice
      Coverage enabled with v8

 Test Files  1 passed (1)
      Tests  1 passed (1)
   Start at  11:33:51
   Duration  55.69s (transform 327ms, setup 7.86s, import 915ms, tests 55ms, environment 44.69s)

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

### backoffice — `npm run lint`

```
> backoffice@0.0.0 lint
> oxlint

EXIT: 0
```

### mobile — `fvm flutter test`

```
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```

### mobile — `fvm flutter analyze`

```
Analyzing mobile...
No issues found! (ran in 29.2s)
```

### mobile — `fvm dart run tool/check_coverage.dart`

```
Running "flutter.bat test --coverage"...
00:00 +0: loading C:/Users/DELL/Documents/Osman/Waleed/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:02 +1: All tests passed!
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

Nothing changed in any gate — this was a documentation-only session, as expected.

## S1-12 status

Set to ✅ in EXECUTION_PLAN.md (all steps above completed successfully).

## Commit/push proof

```
$ git log --oneline -1
7bbcb3a docs: file Uqudo API findings and correct R-012 (S1-12)

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
