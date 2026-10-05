# BL-033 — the identity-scan 409 error contract

**Date:** 2026-09-04 · **Tier:** backend only · **Closes:** BL-033 · **Unblocks:** S5-07

`IdentityScanController.run()` collapsed eight distinct domain exceptions into one
`ResponseStatusException(CONFLICT, message)` whose reason never reached the wire. All eight
arrived byte-identical, and customer.md stages 8/9/13 need five different screens off that
one signal. This slice makes them distinguishable. Backend contract only — no mobile
consumer exists yet (S5-07 not started).

---

## Step 1 — the required live proof, both halves

The premise — that Spring Boot 4.1 discards the `ResponseStatusException` reason — rested on
evidence ~a week old on a framework version past the researcher's cutoff, so it was re-proven
first. A pre-implementation review then found a **second** mechanism the fix depends on had
never been proven here at all, so the check was widened to cover both.

Setup: packaged jar against the compose Postgres, `./mvnw flyway:migrate` first as
`fru_migrator` (the shipped jar has no DDL rights), all clients stubbed. Spring Boot 4.1.0.

### (a) The reason IS discarded — real path, real domain exception

`POST /api/v1/identity-scan/token` against a profile in `submitted` (terminal). The service
throws `ProfileNotEditableException("profile … has already reached a terminal status")`,
which passed through `run()`'s eight-way clause:

```
HTTP/1.1 409
Content-Type: application/json

{"timestamp":"2026-09-04T21:31:37.222Z","status":409,"error":"Conflict","path":"/api/v1/identity-scan/token"}
```

No `message`, no `detail`. Content-Type is `application/json`, so Spring Boot 4.1 is **not**
rendering a `ProblemDetail` by default either. A throwaway probe using the identical
`ResponseStatusException(CONFLICT, "PROBE-REASON-TEXT-9f2a41")` construction returned the same
shape with the probe string absent. **Assumption confirmed; the fix shape stood.**

### (b) `setProperty` DOES render — the half that was never proven here

The task cited `AccountCheckController.java:74-81` as "the one place in this backend proven
to get a field into an error body." It is not: that handler calls
`ProblemDetail.forStatusAndDetail(...)` and nothing else, and no `ProblemDetail.setProperty`
call existed anywhere in `backend/src` (the only `setProperty` hits were
`MockEnvironment.setProperty` in unrelated tests). The whole `code` field rested on an unexercised
mechanism, so a throwaway probe handler was added, captured, and reverted:

```
HTTP/1.1 409
Content-Type: application/problem+json

{"detail":"PROBE-DETAIL-9f2a41","instance":"/api/v1/identity-scan/__probe/problem","status":409,"title":"Conflict","code":"PROBE_CODE_9f2a41","blockedUntil":"2026-09-05T12:00:00Z"}
```

Extension properties serialise as top-level members and the content type switches to
`application/problem+json`. Probe reverted, `git status` clean before planning; no fixture
rows left behind (the terminal profile used was created 2026-09-01 by an earlier session and
was not modified).

**Consequence S5-07 must carry:** the body shape for these eight changes from
`{timestamp,status,error,path}` to `{type,title,status,detail,instance,code[,blockedUntil]}`.
The status stays `409`, so a status-only mapper keeps working and the detail is opt-in.

---

## Pre-implementation review — three findings that changed the work

A reviewer pass ran against the task spec and current source *before* coding, since there was
no diff yet. Five findings altered the plan; the first three changed its shape:

| Finding | Disposition |
|---|---|
| The cited `setProperty` precedent does not exist; mechanism unproven in this backend | **Accepted** — step 1 widened to prove it live (above); BACKLOG.md's claim corrected |
| The new handlers would be **dead code**: `run()` catches all eight before they can escape, and the spec only said what to *add* | **Accepted** — the `catch` clause removed. Not the whole method: its `404` and four-way `400` clauses stay, or five mapped exceptions become `500`s |
| `otherDocumentTypeAvailable` is invariantly `true` and its `false` branch is untestable | **Accepted, field dropped** — see below |
| Task's exception list named three non-409s and omitted `ArtifactExpiredException` | **Accepted** — handler set built from the verified eight; `ARTIFACT_EXPIRED` ships |
| `blockedUntil` nullable → unguarded `toString()` would make the 409 a 500 | **Accepted** — null-guarded handler-local |

### Why `otherDocumentTypeAvailable` was dropped (product-owner decision)

`total == national + passport` is a single-writer invariant, and `ScanTypeExhaustedException`
fires only at `attemptsFor(requested) >= 3` — so `other >= 3` implies `total >= 6`, which
`blockTriggered` has already turned into `blocked_scan`, returned at
`IdentityScanService.java:186-191` **before** exhaustion is reachable. The field could only
ever be `true`: dead signal in a contract the screens get built against, with no constructible
test for its `false` case. This also moots the investigation report's own Part 2 / Part 4
contradiction over the field's name and type. Add it if the budget logic ever makes it real.

## What shipped

### The closed code set

| `code` | Exception | Extra field |
|---|---|---|
| `PROFILE_TERMINAL` | `ProfileNotEditableException` | — |
| `SCAN_BLOCKED` | `ScanTemporarilyBlockedException` | `blockedUntil` (null-guarded) |
| `SCAN_TYPE_EXHAUSTED` | `ScanTypeExhaustedException` | — |
| `IMAGES_UNAVAILABLE` | `ImagesUnavailableForAcceptanceException` | — |
| `ARTIFACT_EXPIRED` | `ArtifactExpiredException` (`uqudo/domain/`) | — |
| `REGISTRY_PENDING` | `RegistryReviewPendingException` | — |
| `REGISTRY_NOT_READY` | `NoActiveRegistryReviewException` — pause variant | — |
| `STATE_CONFLICT` | `NoActiveRegistryReviewException` — no-cycle variant; `JwsAlreadyAcceptedException` | — |

Status stays `409` for all eight. `detail` is a fixed generic string per code, never
`e.getMessage()` — those messages embed the profile id, and AD-002a's rule is that the wire
carries a code, never a message.

### Design decisions

**The `NoActiveRegistryReviewException` split.** One class, five throw sites, two genuinely
different meanings. Implemented as two static factories over a private constructor rather
than two classes: `registryNotReady(UUID)` for the three sites where the cycle exists but
`registryState != "ok"` (`:787` accept, `:817` wrong-number, `:863` wrong-details — the
customer is legitimately paused and Stage 9 gives that its own screen), and
`noActiveCycle(UUID)` for the two where there is no active cycle at all (`:741` post-lock
re-read, `:898` `requireActiveContext` — a correct client cannot reach this; it re-syncs via
Stage 13). One class keeps the handler count and the controller import unchanged, and the
class stays plain-JUnit testable with no Spring, DB or clock, so the `domain` package rule
holds.

**`blockedUntil` null-guard.** The field is nullable at construction and the service branches
on a null `scanBlockedUntil` at two other places, so the property is omitted rather than
emitted as null. The underlying null fall-through is out of scope, as filed.

---

## Files

| File | Change |
|---|---|
| `identityscan/web/IdentityScanController.java` | eight-way `catch` removed from `run()`; eight `@ExceptionHandler`s + two private helpers |
| `identityscan/domain/NoActiveRegistryReviewException.java` | private ctor, two factories, `registryNotReady()` |
| `identityscan/service/IdentityScanService.java` | five throw sites → factories |
| `identityscan/web/IdentityScanControllerTest.java` | body assertions on all eight codes + null case + both split variants |
| `identityscan/IdentityScanIntegrationTest.java` | body assertions at its three real 409 sites |
| `BACKLOG.md`, `EXECUTION_PLAN.md`, `RISKS.md` | BL-033 closed; S5-07 preconditions clear; R-052 narrowed |

## Tests

Every new code and field is asserted **on the response body**, not the status. The existing
tests asserted `status().isConflict()` only and would have passed unchanged against the fully
collapsed behaviour, which is exactly why none were left that way.

- `IdentityScanControllerTest` — all eight codes, the content type, the null-`blockedUntil`
  case, and one test per split variant. It uses `@WebMvcTest` (not standalone MockMvc), so
  the real `ExceptionHandlerExceptionResolver` runs and the standard gate covers these.
- `IdentityScanIntegrationTest` — `PROFILE_TERMINAL`, `SCAN_TYPE_EXHAUSTED` and `SCAN_BLOCKED`
  end to end against real Postgres, the last asserting `blockedUntil` matches the stored
  `scan_blocked_until` column.

**No revert-restore, and why.** These are direct wrong-value assertions: a test asserting
`$.code == "SCAN_BLOCKED"` cannot pass against a body that has no `code` member at all. Per
CLAUDE.md, revert-restore is reserved for indirect assertions and identical-happy-path
ordering defects; neither applies here.

## Out of scope, as specified

No status moved off `409`; BL-035's `awaiting_registry` path, the `/registry-review/*`
terminality gap and the `issueToken` defensive branch are untouched; no mobile consumer; the
200-vs-409 redesign stays deferred to S5-07.

## R-052 — narrowed, deliberately NOT closed

Both blockers (BL-034, BL-033) are closed and the app no longer needs a local counter. But
the backend only *emits* a distinguishable contract; nothing yet proves the eight codes map
onto the five screens stages 8/9/13 specify, and R-052 is the only item tracking that.
Reasoning and the closing condition are recorded in RISKS.md.

## Gates

### Before / after, same request

`POST /api/v1/identity-scan/token` against the same terminal profile that produced the step-1
capture:

```
BEFORE  Content-Type: application/json
{"timestamp":"2026-09-04T21:31:37.222Z","status":409,"error":"Conflict","path":"/api/v1/identity-scan/token"}

AFTER   Content-Type: application/problem+json
{"detail":"this profile can no longer be edited","instance":"/api/v1/identity-scan/token","status":409,"title":"Conflict","code":"PROFILE_TERMINAL"}
```

The exception's message is `"profile 4756b56f-… has already reached a terminal status"`; none
of it reaches the wire. `detail` is the fixed generic string, per AD-002a.

### One failure along the way

The first full gate run failed — a defect in the new test, not in the fix. The integration
test built its expected `blockedUntil` with `to_char(…, 'YYYY-MM-DD' || 'T' || 'HH24:MI:SS')`,
and PostgreSQL read the `TH` as its ordinal-suffix pattern, so `HH24` came through literally:

```
[ERROR]   IdentityScanIntegrationTest.exhaustingBothDocumentTypesTriggersTheTwentyFourHourBlock:582 JSON path "$.blockedUntil"
Expected: a string starting with "2026-09-05THH24:43:30"
     but: was "2026-09-05T21:43:30.196311Z"
[ERROR] Tests run: 891, Failures: 1, Errors: 0, Skipped: 0
```

Replaced with an `Instant`-to-`Instant` equality check against the stored column, which is
stricter than the prefix match it replaced — a wrong offset can no longer pass.
`IdentityScanIntegrationTest` then ran 12/12 (intermediate run:
`./mvnw test -Pdb-integration-test -Dtest=IdentityScanIntegrationTest`, 12 tests, pass).

### Final gate — `./mvnw verify -Pdb-integration-test`

Run after the reviewer fixes; 893 rather than 891, the two extra being the new
split-variant tests.

```
[INFO] Running sd.gov.bank.fruserupdate.uqudo.stub.StubUqudoClientTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.099 s -- in sd.gov.bank.fruserupdate.uqudo.stub.StubUqudoClientTest
2026-09-04T23:56:30.398+02:00  INFO 10164 --- [backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown initiated...
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 893, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 317 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 431 files clean - 0 needs changes to be clean, 0 were already clean, 431 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 317 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:53 min
[INFO] Finished at: 2026-09-04T23:56:39+02:00
[INFO] ------------------------------------------------------------------------
```

Intermediate runs after the review pass, one line each per CLAUDE.md:
`./mvnw test -Dtest=IdentityScanServiceTest` 41/41 pass;
`./mvnw test -Dtest=IdentityScanControllerTest` 16/16 pass.

Mobile and backoffice untouched, so their gates were not run.

## Review findings and dispositions

`@agent-reviewer` against the diff and BL-033.

| Finding | Disposition |
|---|---|
| **No test pinned which *variant* each throw site produces.** `IdentityScanServiceTest` asserted only `assertThrows(NoActiveRegistryReviewException.class, …)`. Flipping `acceptRegistryReview` or `reportWrongNumber` to `noActiveCycle` would put `STATE_CONFLICT` on the wire where the customer should get the pause screen — and the whole suite would stay green, because the controller tests only prove the handler *reads* the flag, not that the service *sets* it | **FIXED.** The two existing tests now capture the exception and assert `registryNotReady()`; two new tests cover the third pause site (`reportWrongDetails`) and the no-cycle site. `IdentityScanServiceTest` 41/41 |
| Every throw-site line number cited in this report was stale (`:789/:819/:865/:743/:902`; actual `:787/:817/:863/:741/:898`), as was `:182-187` for the blocked-scan early return (actual `:186-191`) | **FIXED** — renumbered above |
| BACKLOG.md's "`setProperty` … (grep: zero hits)" is literally false: `grep -rn setProperty backend/src` returns 14, all `MockEnvironment.setProperty` in unrelated tests. The substantive claim — no `ProblemDetail.setProperty` anywhere, so the mechanism was unproven — holds | **FIXED** — reworded to `ProblemDetail.setProperty` in both BACKLOG.md and this report |
| "Gate output not pasted — `<!-- GATE_OUTPUT -->` still present" | **OBSOLETE.** The reviewer read the file while the gate was still running and the output was being written into it. Verified present before commit |

The reviewer separately confirmed, checking the surrounding guards rather than the naming:
both surviving `run()` clauses intact (404 and the four-way 400 — no exception lost its
mapping); all eight handled and all still 409; the split correctly assigned at all five
sites; no PII in any body; the null guard genuinely reachable rather than defensive theatre;
no cross-feature blast radius (each other feature owns its own `ProfileNotEditableException`,
and `LivenessController` catches the shared `ArtifactExpiredException` in its own try/catch);
and scope clean.

**Why the new variant assertions need no revert-restore:** `assertTrue(thrown.registryNotReady())`
is a direct wrong-value assertion — flipping a throw site to the other factory returns
`false` and the assertion fails outright. CLAUDE.md reserves revert-restore for indirect
assertions and identical-happy-path ordering defects; this is neither.




## Commit

```
$ git log --oneline -1
f46aeee BL-033: give each identity-scan 409 a distinguishable code

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git push
To https://github.com/Osmantou/Fr_user_update
   34b956a..f46aeee  main -> main
```

Committed straight to `main`, this project's norm. Nine files: three main, three test, three
plan files, plus this report.

