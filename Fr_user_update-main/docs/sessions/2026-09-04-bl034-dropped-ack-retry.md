# BL-034 — the identity-scan dropped-acknowledgement retry

**Date:** 2026-09-04
**Scope:** backend only. BL-033 (the 409 error contract) deliberately untouched.
**Branch:** `main` (this project's norm — no feature branch).

---

## What was wrong, and what BL-034 got wrong about it

customer.md stage 13 (l.1149-1151) has the app retain a returned enrolment JWS and retry the
**upload** on reconnect rather than repeating the capture. The backend punished that retry:
nothing read the active cycle's stored `uqudo_jti`, so it could not tell "my own retry" from
"a replay". The customer was told the scan failed and rescanned — spending a real Uqudo
operation and one of their limited per-type attempts on a scan that had already landed.

**Correction 1 — the failure path.** BL-034 said the retry collides with
`scan_result.uqudo_jti`'s UNIQUE constraint in `insertAcceptedCycle`. Verified against
source, it usually fails *earlier*: `IdentityScanService.java:505` (pre-fix) calls
`uqudoClient.purgeSession(parsed.jti())` as soon as the first attempt commits, **regardless
of whether the client received the response**. So by the time the retry arrives Uqudo no
longer holds the images, and it dies in the download loop with `ImageUnavailableException`
→ `ImagesUnavailableForAcceptanceException`, **before the transaction at `:373` ever
opens**. The `DuplicateKeyException` path is only reachable when the purge did not take
effect. Both surface as a bare `409`, which is why the investigation read them as one.

Consequence for the prescribed fix: stage 10's `currentFaceResultJti` check sits *inside*
its transaction (`LivenessService.java:405-409`). A literal transplant would have been
**dead code on the real retry path** — the tests would have gone green and production would
have been unchanged. The check had to go above the image download instead.

**Correction 2 — the invariant.** BL-034 required "a different jti on an already-populated
cycle stays an error". That is not current behaviour and was **not** implemented: a customer
may legitimately re-issue a token, scan a *different* document and supersede the active
cycle. Implementing it as written would have regressed the re-scan flow. Settled by the
product owner this session: equality short-circuits, inequality falls through unchanged, and
V0008's UNIQUE constraint still catches a genuine global-duplicate replay.

**Scope decision.** Where the first attempt's registry lookup returned `not_found` or
`unreachable`, the profile is `awaiting_registry` and `submitScan`'s pre-check
(`IdentityScanService.java:294-298`) throws `RegistryReviewPendingException` **above** any
possible jti comparison. Scoped out by product-owner decision and filed as **BL-035**.

---

## The schema question, answered

BL-034's brief asked for this to be stated before writing code. **No migration was needed.**

| Needed | Where it already is |
|---|---|
| The active cycle's jti | `app.scan_result.uqudo_jti` (V0008:20) |
| One active cycle per profile | `identity_one_active` partial unique index (V0008:15-16) |
| The stored registry fields | `app.registry_result` after V0023 — `name_ar_given` … `raw_address_ar`, `sex_registry`, `date_of_birth`; `identity_number_returned` from V0062 |
| Read permission as `fru_app` | V0010:35-38 — `GRANT SELECT` on `identity_cycle`, `scan_result`, `registry_result` |

What was missing was **port surface, not schema**. `currentActiveRegistryContext` selects no
jti and no name/DOB/address columns, and nothing anywhere in `identityscan` read
`app.registry_result`'s name fields back — `buildDisplayPayload` only ever saw a **live**
`RegistryOutcomeInternal`. So returning the existing cycle's payload needed **two** new reads,
both satisfied by one statement; without the second the "fix" would have silently degraded into
re-running the Civil Registry lookup on every retry.

---

## What was built

| File | Change |
|---|---|
| `identityscan/domain/AcceptedScanSnapshot.java` | **New** record. Photograph deliberately never carried — those bytes live in `app.artifact_ref` and Stage 9 fetches them through the S5-10 image endpoint. |
| `identityscan/domain/IdentityScanRepository.java` | New port method `currentAcceptedScanSnapshot(UUID profileId)`. Interface in `domain`, never in the adapter. |
| `identityscan/jdbc/JdbcIdentityScanRepository.java` | `CURRENT_ACCEPTED_SCAN_SNAPSHOT` — the three-table join `CURRENT_ACTIVE_REGISTRY_CONTEXT` already uses, **scoped by `profile_id` + `state='active'`, never keyed on the jti** (a jti-keyed read would match another profile's row and turn the global replay guard into a success path). Inner joins make a cycle with no `scan_result` return empty — the first-submission fall-through. |
| `identityscan/service/IdentityScanService.java` | The short-circuit, and `buildDisplayPayload` retaking `(registryState, fields)` instead of a `RegistryOutcomeInternal`. |

### Placement, and what the short-circuit does not do

The check sits **after `verifyAndParse` and before the image-download loop** — the first point
where `parsed.jti()` exists and no irreversible external call has been made. On an equal jti
against an `ok` cycle it returns immediately, with no second Uqudo image download (which is what
makes the purged-session failure disappear), no second Civil Registry call (otherwise every retry
would cost a real lookup whose result is never persisted, so the payload could disagree with
`app.registry_result`), no transaction and therefore no new cycle / `scan_result` /
`registry_result` / hash-chained `scan_accepted`, no `purgeSession`, and no attempt-budget draw.

`buildDisplayPayload` is reused rather than duplicated, so the retry response is built by the
same code as the first attempt's. `availableImageKinds` needed nothing new —
`activeCycleArtifactKinds` already reads the first attempt's committed rows.

### Two things deliberately not changed

- **The `DuplicateKeyException` catch stays.** The new read runs unlocked
  (`lockAndGetScanState` is autocommit and releases immediately), so two simultaneous retries
  can both miss it. The catch remains the backstop and the genuine-replay guard; it was not
  replaced.
- **No audit event on the recognised retry.** The retry changes no state and evidences
  network conditions rather than identity, while `payload_json` is permanently hash-chained —
  the same reasoning the Stage 9 review-image endpoint already records for itself. The first
  attempt's `scan_accepted` remains the record of acceptance.

---

## Proof

### Revert-restore, as required

The defect-guarding test asserts a success path that looks much like the ordinary happy path
from the outside, so it was proven by reverting. Only the short-circuit block was removed
from `IdentityScanService` — the port, the SQL and all tests stayed — and the suite re-run:

```
[ERROR] Tests run: 39, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 5.776 s <<< FAILURE! -- in sd.gov.bank.fruserupdate.identityscan.service.IdentityScanServiceTest
[ERROR] sd.gov.bank.fruserupdate.identityscan.service.IdentityScanServiceTest.identicalJtiRetryOfAcceptedCycleReturnsExistingPayloadInsteadOfFailing -- Time elapsed: 0.017 s <<< ERROR!
sd.gov.bank.fruserupdate.identityscan.domain.ImagesUnavailableForAcceptanceException: scan verified but images are no longer available: image id-1 is no longer available
	at sd.gov.bank.fruserupdate.identityscan.service.IdentityScanService.submitScan(IdentityScanService.java:358)
	at sd.gov.bank.fruserupdate.identityscan.service.IdentityScanServiceTest.identicalJtiRetryOfAcceptedCycleReturnsExistingPayloadInsteadOfFailing(IdentityScanServiceTest.java:397)
```

That failure is the production symptom itself — the 409 the customer was shown — not a
generic assertion miss. The fix was then restored. Note the other three new tests **passed**
against the reverted code, which is correct: they assert behaviour this change was required
to leave alone.

### Why the test stubs `downloadImage` to throw

`StubUqudoClient.purgeSession` is a documented no-op and its `downloadImage` fails only for
`expired-` ids, so an integration test against the stub re-downloads happily and lands on the
insert — it exercises the branch production does *not* take, and would have gone green over an
in-transaction guard while the real `HttpUqudoClient` still returned 409. CLAUDE.md's "a green
test could coexist with a broken guard" case exactly, so the unit test forces the post-purge
ordering instead.

### The tests

| Test | Asserts |
|---|---|
| `IdentityScanServiceTest#identicalJtiRetryOfAcceptedCycleReturnsExistingPayloadInsteadOfFailing` | Same `cycleId`, stored registry fields, `registryReady`; and `never()` on `downloadImage`, `lookup`, `purgeSession`, `insertAcceptedCycle`, `insertScanResult`, `insertRegistryResult`, `applyScanAttempt`, `appendWithArtifact` |
| `IdentityScanServiceTest#differentJtiOnPopulatedCycleFallsThroughAndOpensANewCycle` | Decision 1 — a different jti opens a **new** cycle rather than erroring |
| `IdentityScanServiceTest#differentJtiThatIsAGlobalDuplicateStillThrowsJwsAlreadyAccepted` | The replay guard was not disabled |
| `IdentityScanServiceTest#identicalJtiDoesNotShortCircuitWhenStoredRegistryStateIsNotOk` | The BL-035 scope limit, explicit in tests rather than implied |
| `IdentityScanIntegrationTest#identicalJwsReuploadReturnsTheSameCycleInsteadOfCollidingOnTheJti` | Against real PostgreSQL: 200 not 409, same `cycleId`, one cycle, one `scan_result`, budget columns still 0, exactly one `scan_accepted` |
| `IdentityScanServiceTest#duplicateJtiIsMappedToJwsAlreadyAcceptedException` (pre-existing) | Unchanged and still passing |

---

## Gate

`./mvnw verify -Pdb-integration-test`, per CLAUDE.md's Coverage note — plain `./mvnw verify`
excludes the `@Tag("integration")` suite and is not the real gate. Final run, verbatim:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 885, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 317 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
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
[INFO] Total time:  02:11 min
[INFO] Finished at: 2026-09-04T22:58:40+02:00
[INFO] ------------------------------------------------------------------------
```

885 tests, zero failures; Spotless clean; JaCoCo's 80% check met with the integration suite
included. Docker was running locally, so the Testcontainers PostgreSQL 18 suite executed rather
than being skipped. Exit code checked explicitly (0) rather than inferred from the log tail —
see below for why that mattered.

### Two earlier gate runs that did NOT pass, recorded because they are the evidence

1. **Run 1 reported BUILD SUCCESS but was order-dependent.** It predates the account-number fix
   in review finding 1, so it passed only because Surefire happened to order
   `IdentityScanIntegrationTest` before `LivenessIntegrationTest` on this machine. Superseded.
2. **Run 2 failed on Spotless, and was briefly misread as passing.** The scripted edits applying
   the review fixes rewrote three files' CRLF line endings as LF, which `spotless:check`
   rejected. The run was piped through `tail`, so the shell reported the pipeline's exit code
   (0), not Maven's — the failure was visible only in the log body. Fixed with
   `./mvnw spotless:apply` (reformatted by the tool, never hand-edited) and re-run with the exit
   code captured directly, which is the run above.

Intermediate runs, one line each per CLAUDE.md: `./mvnw compile` — success; `./mvnw
spotless:apply` twice — success.

---

## Review

`@agent-reviewer` against the diff and BL-034. Three findings, all dispositioned.

### 1. BLOCKER, fixed — the integration fixture sat in another class's claimed account range

`IdentityScanIntegrationTest`'s new test used account `0000000412` / `+249900004121`, which is
byte-identical to `LivenessIntegrationTest.java:211` and inside **that** class's claimed block
(`AbstractPostgresIntegrationTest`: IdentityScan owns 0000000401–0000000410, Liveness owns
0000000411–0000000420). The Testcontainers PostgreSQL is JVM-scoped and shared across every
`@Tag("integration")` class, and `createProfile` goes through Stage 1b, which **re-enters** an
existing profile rather than failing — so the collision is silent, not a constraint error.

Concrete failure the reviewer traced: if `LivenessIntegrationTest` runs first, its
`acceptedProfile("0000000412", …)` has already left an accepted cycle and a `scan_accepted`
event on that profile. The new test then re-enters it, submits with a *different* jti, and
supersede-and-insert fires — so `assertEquals(1L, cycles)` and the single-`scan_accepted`
assertion would both fail. Surefire pins no `runOrder`, so the first green gate run reflected
only the order that machine happened to produce. **That gate run was order-dependent, not
genuinely passing** — which is why the gate was re-run after the fix; see the Gate section.

Fixed by claiming a genuinely free block: `0000000531` (the next free number after
`SalaryCertificateIntegrationTest`'s 0000000530; all ten of IdentityScan's original range are
in use). Both Javadocs — `AbstractPostgresIntegrationTest`'s allocation list and this class's
own — were extended, as that file's rule requires.

### 2. NOTE, decision recorded in code — the retry does not refresh `last_activity_at`

The first attempt's `ok` path ends with `profileRepository.touchLastActivity`; the
short-circuit returns before any transaction and so does not. `last_activity_at` feeds the
90-day abandonment sweep (`app.purge_abandoned_artifacts()`, V0055). Judged safe and left as
is: the accepted scan this retry is acknowledging refreshed the clock moments earlier when it
committed, so the window is not at risk, and adding a write to a path whose whole point is
that it changes nothing would be the larger surprise. The reviewer's real point was that the
asymmetry was undocumented — it is now stated in the short-circuit's own comment, with the
condition under which it should be revisited (a retry arriving long after its first attempt).

### 3. NOTE, fixed — BL-035's wording overstated its test coverage

BL-035 claimed it was "guarded meanwhile by"
`identicalJtiDoesNotShortCircuitWhenStoredRegistryStateIsNotOk`. That test uses `in_progress`
plus a `not_found` registry result — a combination the real state machine cannot produce, since
a non-`ok` `insertRegistryResult` always fires `transitionToAwaitingRegistry`. The test is
sound, but it guards the short-circuit's *condition*, not the `awaiting_registry` path BL-035
is about. BACKLOG reworded to say exactly that, and that the path itself is unguarded.

### Checked and clean

Confirmed from source with no finding: the short-circuit condition (both compared columns are
`NOT NULL` in V0008, so no NPE is reachable; a `documentType` disagreement is impossible because
`UqudoJwsParser:137` rejects a type mismatch, so an equal jti implies an equal type); every
claimed absent side effect, traced line by line; cross-profile safety of the new SQL and the
untouched session/nonce check; both pre-existing `buildDisplayPayload` callers passing exactly
what the old body derived; every new column against V0008/V0023/V0062 and the V0010 grants; that
no pre-existing test was weakened (unstubbed `currentAcceptedScanSnapshot` returns
`Optional.empty()` by Mockito default, so every other test falls through unchanged); and the
CLAUDE.md rules on package placement, generated files, secrets and synthetic fixtures.

---

## Documents updated

- **BACKLOG.md** — BL-034 → CLOSED, with both corrections recorded and its stale citation
  fixed (`insertAcceptedCycle` is at `JdbcIdentityScanRepository.java:318-329`, not
  `:295-305`). New **BL-035** for the `awaiting_registry` variant.
- **EXECUTION_PLAN.md** — S5-07's precondition text: BL-034 closed, BL-033 still blocking.
- **RISKS.md** — R-052 narrowed, still 🟡 while BL-033 is open.
- **PROJECT_PLAN.md** — carries an unrelated pre-existing working-tree edit (the "Phase 2
  entry gates" section, R-051), committed alongside.

## Commit

```
a78e275 BL-034: answer the customer's own re-upload instead of failing it
```

12 files changed, 772 insertions(+), 11 deletions(-); two new files
(`AcceptedScanSnapshot.java`, this report). Committed straight to `main`, this
project's norm. `PROJECT_PLAN.md`'s unrelated pre-existing edit rode along, as noted
above. Report is 276 lines — over the 150-250 target, carried by the two verbatim
blocks the rules require (the final gate output and the revert-restore failure).
