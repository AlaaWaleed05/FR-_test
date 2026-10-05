# BL-037 — Stage 8's budget-spending rejections get a distinguishable wire code

**Date:** 2026-09-05
**Scope:** backend only. No mobile, no back office, no migration.
**Closes:** BL-037. **Files:** BL-039, BL-040. **Narrows (does not close):** R-052.

## What was wrong

`POST /api/v1/identity-scan/scan-result` answers `400` on ten distinct paths. Two of them
mean *the backend examined the scan and refused it, and one of the customer's three
per-type attempts is gone*; the other eight mean *the request was malformed* and spend
nothing. Every one of the ten was byte-identical on the wire —
`{timestamp,status,error,path}` at `application/json`, no discriminator of any kind.

An app cannot build Stage 8's error screens against that. It either shows the wrong screen
or misreports the attempt count, and the only way it could tell the two groups apart is a
client-side counter — precisely what `ScanAttemptBudget`'s server-side placement and
AD-002a exist to prevent. This is the 400-side analogue of BL-033.

## Source verification, before building

The task named `JwsVerificationException` as *the* budget-spending rejection and asked for
the actual path to be read rather than assumed. Reading it found **two**, not one.
`recordFailedAttempt` has a single spend site — `applyScanAttempt`,
`IdentityScanService:682` — and three callers, two of which are 4xx:

| Branch | Site | Spends? | Code before |
|---|---|---|---|
| `JwsVerificationException` | `IdentityScanService:327-330` | **yes** | none |
| `ImageIntegrityException` (image checksum mismatch) | `IdentityScanService:404-411` | **yes** | none |
| `IdentityScanRejectedException` (bad documentType) | `:263-265` | no | none |
| `InvalidScanSessionException` (sessionId/nonce mismatch) | `:304-309` | no | none |
| 5 pre-service validations (`parseProfileId`, `clean`) | `IdentityScanController:325-346` | no | none |
| unparseable request body | Spring, no handler | no | none |
| `ArtifactExpiredException` | `:316-326` | no, deliberately | `ARTIFACT_EXPIRED` (409) |

Both spending branches are inside `submitScan`, both call `recordFailedAttempt` before
rethrowing, and both landed in the same four-way `catch` at `IdentityScanController:315-319`.
`ImageIntegrityException`'s own comment at `IdentityScanService:405-407` says it is
"counted against the budget like a JWS rejection". The third caller, `cancelScan:608`,
returns `200` and is not a wire-visibility question.

**This widened the slice, with approval before any code was written.** Coding only the JWS
case would have left BL-037's own criterion (c) false: a checksum mismatch would still
spend an attempt and still arrive as an undistinguished `400`.

Also confirmed: neither exception is thrown anywhere else this controller can reach, so
narrowing `run()`'s catch cannot affect `/token`, `/cancel`, `/registry-review/*` or
S5-11's `/registry-review/current`.

## Decisions

### One code, not two — `SCAN_REJECTED`

customer.md l.666-667 settles it: *"Backend rejects the JWS … counted as a failed attempt.
**The customer sees a generic failure** and may retry."* One screen, one copy, one remedy —
rescan — and identical attempt accounting. BL-033's own precedent is that a code names the
client's recovery, not the exception type; it deliberately shares `STATE_CONFLICT` between
two unrelated exceptions for exactly that reason.

The counter-argument was real and worth stating: BL-033 gave `ARTIFACT_EXPIRED` and
`IMAGES_UNAVAILABLE` separate codes while noting they share a screen. The difference is
that those two differ in *cause the customer can act on* (time passed vs. the capture is
gone) and, decisively, in whether an attempt was spent. Our two differ in neither.

Where the distinction does matter — diagnosis — it is preserved server-side, in the audit
events that already existed: `scan_jws_rejected` and `scan_image_integrity_failed`. Nothing
was collapsed; only the wire is shared. If S5-07 finds it needs to split them, adding a
second code is additive. Merging two codes an app has already branched on is not.

### Status stays 400

Checked rather than assumed: `grep` over `mobile/lib` and `backoffice/src` finds no
reference to this endpoint, so BL-033's reason for freezing status ("the status is what the
app's existing mapper switches on") does not apply — there is no mapper yet. Nothing forces
a change, and nothing forces keeping it either, so the argument had to be made on merit:

Moving the spending rejections to `422` would split them from the client-side `400`s **by
status**. That is a redesign of the Stage 8 contract, which BL-037 explicitly forbids, and
it would introduce a second wire convention beside BL-033's status-stable one for no
consumer benefit. `400` + a code says the same thing and says it the same way the eight
409s already do.

### `attemptsRemaining` deliberately not added

It would help S5-07 and it changes no behaviour, but it is a new wire field carrying budget
state out through an exception — not "make the existing outcome legible". Left out, noted
in R-052 as available on request, so the next slice makes that call with the cost visible.

## The change

**`IdentityScanController`.**

- `problemDetail(HttpStatus, code, detail)` added as an **overload**; the existing 2-arg
  `problemDetail(code, detail)` now delegates to it with `HttpStatus.CONFLICT`. This was
  the one real trap in the slice — that helper hardcoded `CONFLICT`, and all eight 409
  codes route through it, so parameterising its existing signature could have moved them.
  A `badRequest(code, detail)` sibling sits beside `conflict(code, detail)`.
- One handler for both spending causes:
  `@ExceptionHandler({JwsVerificationException.class, ImageIntegrityException.class})` →
  `badRequest("SCAN_REJECTED", "this scan could not be verified")`.
- `run()`'s four-way catch narrowed to `IdentityScanRejectedException |
  InvalidScanSessionException`. Both removals and the handler that catches them are in the
  same edit — removing either without its handler would have made it an unmapped `500`,
  which is the failure mode BL-033's commit message calls out.
- `detail` is a fixed generic string, never `rejected.getMessage()`. Those messages embed
  the profile id (AD-002a). The old code passed them to `ResponseStatusException`, where
  they were discarded; a naive port to `detail` would have put them on the wire.

**The contract, stated in the code so the next reader does not have to re-derive it:** on a
Stage 8 `400`, a `code` means an attempt was spent; a bare `400` means the request was bad.

## Tests

**Direct assertions, no revert-restore proof.** Every new assertion is a wrong-value
assertion against a value that does not exist in the old code: no `400` carried a `code`
member at all, and the content type was `application/json`, not `application/problem+json`.
Each matcher fails outright against the unfixed version — it cannot pass against the bug, so
the revert-restore rule's condition (indirect assertions, or identical-happy-path ordering)
does not apply.

The one exception is stated rather than dressed up: the client-side half of
`aSpentAttemptCarriesACodeWhileAClientSide400DoesNot` is green **before and after** the fix.
It is a regression guard against an over-broad implementation that codes every `400`, not a
proof of the defect. Reverting the fix would leave it green, so no revert proof is claimed
for it. Its value is that it pins the *discrimination* — the pair — in one test.

| Test | What it pins |
|---|---|
| `scanResultJwsVerificationFailureMapsToScanRejected` | was status-only; now problem+json + `SCAN_REJECTED` |
| `scanResultImageIntegrityFailureMapsToScanRejected` | **new** — this path had zero controller-test coverage |
| `aSpentAttemptCarriesACodeWhileAClientSide400DoesNot` | the contract itself: coded vs. bare |
| `aBackendRejectedScanCarriesScanRejectedAndHasSpentAnAttempt` | **new integration test** — see below |

The old `scanResultJwsVerificationFailureMapsTo400` asserted `status().isBadRequest()` and
nothing else. That is how the ambiguity shipped: it passes just as happily against a bare
`400` that a malformed request also produces. It is renamed and given a body assertion.

**Why an integration test as well.** A code on a `400` asserts something about *state* —
"this attempt was spent" — not just about the response. Asserting `$.code` alone proves the
string is emitted, not that it is true. So the integration test reads
`app.profile.scan_attempts_passport` either side of the same request against real Postgres:
`0` before, `1` after, plus the `scan_jws_rejected` audit event, then fires a client-side
`400` and asserts the counter is still `1`. That is the claim and its truth in one test.

**Honest coverage limit.** The enrolment `ImageIntegrityException` is *not* reachable in the
integration test: `StubUqudoClient.fabricateJws` always emits matching checksums and exposes
no bad-checksum knob (only the face path takes an explicit checksum). Adding one would be
scope creep. That branch has controller-test coverage only.

**Untouched and still green:** all fifteen existing `$.code` 409 assertions in the tier
(twelve in `IdentityScanControllerTest`, three in `IdentityScanIntegrationTest`), and
`IdentityScanServiceTest.jwsRejectionCountsAgainstBudgetAndDoesNotAcceptTheScan`, which pins
the budget behaviour this slice must not change.

## Gate

`./mvnw verify -Pdb-integration-test` from `backend/`, `JAVA_HOME` = the JBR OpenJDK 21.0.8,
Docker 29.7.2 running for Testcontainers PostgreSQL 18. Four runs; the two failures were
both Spotless rejecting a javadoc line I wrote over the 100-column limit.

**Run 1 — FAILED.** All 911 tests passed, then:

```
[INFO] Tests run: 911, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 431 files clean - 1 needs changes to be clean, 2 were already clean, 428 were skipped because caching determined they were already clean
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal com.diffplug.spotless:spotless-maven-plugin:3.10.0:check (spotless-check) on project backend: The following files had format violations:
[ERROR]     src\main\java\sd\gov\bank\fruserupdate\identityscan\web\IdentityScanController.java
[ERROR]         -   * is a hard failure"). Both reach here having already been counted by {@code recordFailedAttempt}.
[ERROR]         +   * is a hard failure"). Both reach here having already been counted by {@code
[ERROR]         +   * recordFailedAttempt}.
[ERROR] Run 'mvn spotless:apply' to fix these violations.
```

**Run 2 — passed.** `./mvnw verify -Pdb-integration-test`, 911 tests, 0 failures, coverage
met, BUILD SUCCESS. Superseded by the reviewer's blocker fix, so not pasted.

**Run 3 — FAILED**, after moving the integration test off the colliding account number.
Both integration classes ran green and disjoint — `IdentityScanIntegrationTest` 15 tests,
`LivenessIntegrationTest` 10, which is the fix working — and Spotless caught my new javadoc
line the same way:

```
[INFO] Tests run: 911, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 431 files clean - 1 needs changes to be clean, 1 were already clean, 429 were skipped because caching determined they were already clean
[INFO] BUILD FAILURE
[ERROR]     src\test\java\sd\gov\bank\fruserupdate\identityscan\IdentityScanIntegrationTest.java
[ERROR]         - * above ends at 410 and {@code LivenessIntegrationTest} owns 411-420, which is why BL-037's test
```

**Run 4 — final, verbatim.** `./mvnw spotless:apply` first (line wrapping only, no source
semantics touched), then:

```
[INFO] Running sd.gov.bank.fruserupdate.uqudo.stub.StubUqudoClientTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.080 s -- in sd.gov.bank.fruserupdate.uqudo.stub.StubUqudoClientTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 911, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] 
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 318 classes
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
[INFO] Analyzed bundle 'backend' with 318 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:48 min
[INFO] Finished at: 2026-09-05T02:00:18+02:00
[INFO] ------------------------------------------------------------------------
```

911 tests, three of them new here (`git diff -U0 -- backend/src/test | grep -c "^+  @Test"`
= 3, none removed). Coverage met with the integration suite included, which is the real
backend gate — plain `./mvnw verify` proves Spotless and the non-integration tests only.

## Reviewer

`@agent-reviewer` against the diff and BL-037, run after the gate had already passed. It
found one blocker and two documentation errors, and cleared everything else. Dispositions:

**BLOCKER — fixed. The new integration test used an account number another class owns.**
I extended this class's range to `0000000411` and wrote in its javadoc that the range "is
disjoint from every other integration class's". It was not: `AbstractPostgresIntegrationTest`'s
master list gives `LivenessIntegrationTest` `0000000411–0000000420`, and
`LivenessIntegrationTest:49` uses that exact account **and the identical phone number**.
Both classes share one JVM-scoped container with no truncation between them, and
`POST /api/v1/contact-channels` re-enters an existing profile rather than creating a second
one — so the two classes would share one `app.profile` row, and my test's
`scan_attempts_passport == 0` precondition would hold only because `identityscan` happens to
sort before `liveness` under Surefire's filesystem order. That is exactly the order-dependence
the abstract class's own rule forbids. **The gate had passed; the test was still wrong.**
Fixed by moving to `0000000534` (verified unused; this class already holds 531–533),
reverting the javadoc's range claim, and recording 534 in both the class javadoc and the
master list — which the first version had left contradicting each other.

**NOTE — fixed. BL-040 as first drafted described a mechanism that does not exist.** See
"Filed, not fixed" below for the corrected wording and what was wrong.

**NOTE — fixed. Miscount.** I wrote "all twelve existing `$.code` 409 assertions"; there are
fifteen in the tier (twelve controller, three integration). Corrected above.

**Cleared, with the evidence the reviewer cited:**

- *Handler wiring.* Both exceptions are unchecked, propagate out of the `Supplier` lambda
  and the controller method, and resolve to the controller-local handler. No
  `@ControllerAdvice` exists in `src/main` to contend with. The only `uqudoClient` call sites
  reachable from this controller are `issueAccessToken` (`IllegalStateException`, a 500
  before and after), `verifyAndParse`, `downloadImage` and `purgeSession` (documented never
  to throw); the two that can raise the new handler's types are both inside `submitScan`
  behind `recordFailedAttempt`. No non-spending path can receive `SCAN_REJECTED`.
- *The eight 409s.* Unmoved: `problemDetail(String, String)` delegates with
  `HttpStatus.CONFLICT` hardcoded, `conflict(...)` still sets `CONFLICT` on the entity,
  `scanBlocked` is byte-for-byte unchanged including its null-guarded `blockedUntil`. Every
  identity-scan domain exception extends `RuntimeException` directly, so the new two-type
  handler cannot widen onto a 409.
- *Budget behaviour.* `IdentityScanService`, `ScanAttemptBudget`, `JdbcIdentityScanRepository`
  and `IdentityScanServiceTest` are absent from the diff. Nothing deleted or weakened.
- *Test quality.* The integration test genuinely reaches the JWS branch (real issued
  session/nonce, valid document type, `"not.a.jws"` fails `JWSObject.parse`); the counter
  column is the one `APPLY_SCAN_ATTEMPT_PASSPORT` increments; `not(containsString("code"))`
  is sound for its stated purpose and its comment describes it correctly as a regression
  guard, not a defect proof.
- *PII.* `detail` is a fixed literal; the exception message is never read; nothing logged.
- *Package rule.* All new code in `identityscan.web`.
- *The "ten paths, exactly two spending" count in the controller comment* checks out against
  source: 2 (`parseProfileId`) + 3 (`clean`) + 1 (unparseable body) + 2 (`run()`'s remaining
  clause) = 8 non-spending, plus the 2 handled. All three `IdentityScanRejectedException`
  throw sites and the `InvalidScanSessionException` site precede any `applyScanAttempt`.
- *BL-039's claim* that `canAttempt` is consulted only at token issuance is confirmed by
  the only three `ScanAttemptBudget.*` call sites in `src/main`.

## Filed, not fixed

Both were found by the pre-implementation source review and are out of BL-037's scope,
whose terms forbid changing attempt-budget behaviour.

**BL-039 — `submitScan` never consults `ScanAttemptBudget.canAttempt`.** The per-type limit
of 3 is enforced only at token issuance (`IdentityScanService:190-194`).
`recordFailedAttempt` re-checks terminality, `blocked_scan` and `awaiting_registry` under
lock, but never the budget, before `applyScanAttempt`. The pending session is deliberately
not purged on failure (`:284-286`), so one issued token's `sessionId`/`nonce` clears the
`:304-309` check indefinitely: `scan_attempts_national_id` can reach 6 against a limit of 3,
bounded only by the total-based 24-hour block.

This is worth more than a register line. It does not merely let a counter drift — it
**removes a fallback the customer is entitled to**. `SCAN_TYPE_EXHAUSTED` is never raised on
that route, so customer.md l.677's "switch to your other document type with a fresh budget"
never happens; the customer goes straight to a 24-hour lockout on a mandated update, with no
route to a branch in between. That wants attention before the demo reaches real users, not
Phase 2 deferral.

**BL-040 — a rejection that loses a race inside `recordFailedAttempt` is never recorded as
a rejection.** At `IdentityScanService:328-329` the service calls `recordFailedAttempt` and
rethrows. Inside that method's transaction, the three race checks (terminal, `blocked_scan`,
`awaiting_registry`) each `return` from the callback *before* the `scan_jws_rejected` append
and the `uqudo_scan_jws` artifact write, so on a lost race neither is written — nothing
rolls back; the transaction commits having done nothing. `auditRejection(...)` then writes
an event for the race itself, and a `409` replaces the original exception. The attempt is
correctly *not* spent, so BL-037's wire semantics are unaffected. What is lost is that a JWS
failed verification at all — the trail records the race outcome, not the rejection.

My first draft of this item said the append was "rolled back with the transaction". The
reviewer read the method and showed that is wrong — the early `return` precedes the append,
so there is nothing to roll back — and that the trail is not empty, just missing the
rejection. Both BACKLOG.md and this report carry the corrected mechanism; the wrong version
would have sent a future fixer hunting a rollback bug that does not exist. Filed separately
from BL-039 because it is a different mechanism and would otherwise disappear when BL-039 is
fixed.

## R-052 — narrowed a third time, still open

What this closes: on a Stage 8 `400`, S5-07 can now tell a spent attempt from a bad request
without a local counter, for **both** spending causes.

What stays open, and why this is still the only item tracking it:

1. The consumption half is unproven, exactly as it is for BL-033's 409s. Nothing yet shows
   `SCAN_REJECTED` plus the eight conflict codes map cleanly onto the five screens
   customer.md stages 8/9/13 specify. That becomes knowable only when S5-07 builds them.
2. S5-11's Stage 8 `STATE_CONFLICT` gap is untouched. There is still no read that answers
   "what is this profile's scan state, and how many attempts remain", so a `STATE_CONFLICT`
   from `/token` or `/scan-result` still names a re-sync the client cannot perform — and
   with `attemptsRemaining` deliberately absent, a screen that wants to show "1 attempt
   left" has no server-supplied number and must not invent one.

Close R-052 when S5-07 has built the Stage 8/9 error screens against the codes without
reintroducing a client-side counter.

## Files changed

- `backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/web/IdentityScanController.java`
- `backend/src/test/java/sd/gov/bank/fruserupdate/identityscan/web/IdentityScanControllerTest.java`
- `backend/src/test/java/sd/gov/bank/fruserupdate/identityscan/IdentityScanIntegrationTest.java`
- `backend/src/test/java/sd/gov/bank/fruserupdate/AbstractPostgresIntegrationTest.java` (javadoc
  registry of account ranges only)
- `BACKLOG.md`, `EXECUTION_PLAN.md`, `RISKS.md`

The new integration test uses account `0000000534` (phone `+249900005341`), recorded in both
the class javadoc and the master range list in `AbstractPostgresIntegrationTest`. See the
reviewer section for why it is 534 and not the 411 I first used.

## Commit

```
$ git log --oneline -1
32a4b22 BL-037: give Stage 8's budget-spending rejections a distinguishable code

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git push
To https://github.com/Osmantou/Fr_user_update
   ee7b976..32a4b22  main -> main
```

Straight to `main`, as this project's norm requires — no branch.
