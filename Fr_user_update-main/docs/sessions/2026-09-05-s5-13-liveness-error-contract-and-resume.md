# S5-13 — Stages 10-12: error contract and resume pointer

Backend only. Unblocks S5-08 by settling the wire contract its screens map.

## Why this is S5-13 and not S5-12

The task arrived labelled S5-12. That ID is taken: EXECUTION_PLAN.md:87 is the Stage 9
guard-parity slice (BL-042/043/044), ✅ 2026-09-05, and RISKS.md:57 already cites it as
closed. Building over it would have corrupted the plan's ID space and R-052's audit trail.
Renumbered to S5-13 before any code was written.

## Claims in the brief that were wrong, and what source actually said

The brief was derived from an S5-08 pre-build review. Every claim was checked against source
first. Nine did not survive.

| Brief's claim | Source |
|---|---|
| "S5-12" | Taken and ✅ done (EXECUTION_PLAN.md:87) |
| `AccountCheckController` is "the one proven place a field reaches an error body" | It sets **no property at all** (AccountCheckController.java:74-81). The real precedent is `IdentityScanController.scanBlocked` (:234-245) — and specifically its **null-guard**, which is what "blockedUntil may be null" needs and AccountCheck cannot demonstrate |
| "Reuse the code helper additively (status param / badRequest sibling)" | Already built and closed as BL-037 (IdentityScanController.java:331-350). Nothing to do; that file is untouched by this slice |
| "11 classes under liveness/domain", five error categories | 14 files, 10 exception types. Real surface: 8 collapsed 409s + 4 collapsed 400s + 404 for liveness alone. The brief omitted `FaceJwsAlreadyAccepted`, `InvalidFaceSession`, `ArtifactExpired`, `AuditTrailImageUnavailable` and the entire 400 side |
| BL-037's rule applies only to 409s | It is a **400-side** rule. Two liveness 400s spend an attempt (`JwsVerificationException` LivenessService.java:320, `ImageIntegrityException` :356); two do not |
| A "rescan needed" code | **Not derivable** — `NoAcceptedIdentityCycleException` collapsed two remedies with no discriminator (:188-200). Added one |
| Part 2 scoped like S5-11 **and** Part 3 returning channels from the read | **Contradictory as written.** `submitted` is terminal (V0005:23) and S5-11's read refuses terminal profiles (IdentityScanService.java:758-761). Resolved by the reframe below |
| "AD-002a … is FALSE against source" | **Partially wrong.** True and enforced for `enroll()`; false only for face sessions, and already documented as deliberate at UqudoClient.java:107-109. Narrowed, not overturned |
| "Verify on a running instance that `code` renders" | Stale. Proven at BL-033 on this Boot version (4.1.0), ~15 tests assert `$.code`. CLAUDE.md: a passing re-run repeating an earlier passing run does not earn its place. **Not repeated** |

Two further findings the review had not made:

- **`LivenessRejectedException` is never thrown.** Declared, imported, caught at
  LivenessController.java:106 — no throw site anywhere in the codebase. It gets no code:
  minting one would document a screen that can never be shown. Left in place; removing the
  type is a cleanup this slice was not scoped to make. Recorded here so nobody later mints
  a code for it.
- **`ProfileNotEligibleException` had the same two-causes-one-type defect** as
  `NoAcceptedIdentityCycleException`. Its second cause is a lost race whose landing status
  **need not be terminal** — a concurrent `reportLivenessTerminated` moves the profile to
  `blocked_liveness`, which is temporary. Mapping both to `PROFILE_TERMINAL` would have told
  that customer their journey was over when it was not. Discriminator added.

### A correction I made mid-session

I stated during planning that no customer-facing status endpoint existed. That was wrong:
`POST /api/v1/account-check` is Stage 1a and already answers `ACTIVE`+`TERMINAL` for a
complete profile (AccountCheckResponse.java:14-22). It carries no reference number or
channels by design, being unauthenticated and keyed by account number. The decision below
holds for a better reason than the one I first gave: account-check serves the **reinstall**
path, the new read serves the **local-state resume** path, and they do not overlap.

## The design decision: a pointer, not a guard

customer.md l.1112-1114 settles it in its own words:

> The backend alone decides whether the session is still open. Every resume begins by
> asking. The answer can be *proceed*, *already complete*, or *blocked until X*.

`already complete` is an **answer**, not a refusal. So the Stage 10-12 read deliberately
diverges from its S5-11 sibling, which refuses terminal profiles: `SUBMITTED` is this read's
single most important answer. A customer whose app died between submitting and rendering the
confirmation screen has no other route to their reference number, and terminal-refusal would
dead-end exactly the person the read exists to rescue. Terminal-refusal stays where it
belongs — on the action endpoints, where it is what stops a submitted customer signing again.

## Part 1 — the error contract

Statuses are held exactly as they were; bodies now carry a machine-readable `code`. `detail`
is a fixed generic string per code, never `getMessage()` — those embed the profile id
(AD-002a). Helpers are duplicated per controller rather than extracted, matching how
`parseProfileId`/`clean` are already duplicated across all four customer controllers;
CLAUDE.md makes features the top-level cut and a shared `web` package would be neither a
feature nor an outbound integration.

### Liveness — 409 (8 exception types, 9 handler outcomes)

| Exception | Code | Screen |
|---|---|---|
| `ProfileNotEditableException` | `PROFILE_TERMINAL` *(reused)* | journey over → re-run launch check |
| `LivenessTemporarilyBlockedException` | `LIVENESS_BLOCKED` **+ `blockedUntil`** | blocked-until screen |
| `FaceMatchAlreadyPassedException` | `LIVENESS_ALREADY_PASSED` | skip to Stage 11 |
| `NoAcceptedIdentityCycleException` — portrait purged | `RESCAN_REQUIRED` | back to Stage 7/8, rescan |
| `NoAcceptedIdentityCycleException` — no cycle | `STATE_CONFLICT` *(reused)* | re-sync via Stage 13 |
| `FaceJwsAlreadyAcceptedException` | `STATE_CONFLICT` *(reused)* | re-sync |
| `RegistryReviewPendingException` | `REGISTRY_PENDING` *(reused)* | Stage 9 pause |
| `ArtifactExpiredException` | `ARTIFACT_EXPIRED` *(reused)* | capture unusable, no attempt spent |
| `AuditTrailImageUnavailableException` | `AUDIT_TRAIL_UNAVAILABLE` | same screen as `ARTIFACT_EXPIRED`, distinct code — the shape BL-033 used for `ARTIFACT_EXPIRED` vs `IMAGES_UNAVAILABLE` |

`blockedUntil` is null-guarded. The field is nullable at construction; an unguarded
`toString()` turns the 409 into a 500. This is the one thing the brief got exactly right —
the controller genuinely was discarding a typed field through `getMessage()`.

### Liveness — 400, extending BL-037's rule to Stage 10

**A `code` on a Stage 10 400 means an attempt was spent; a bare 400 means the request was bad.**

| Exception | Code | Attempt spent |
|---|---|---|
| `JwsVerificationException` | `LIVENESS_REJECTED` | yes |
| `ImageIntegrityException` | `LIVENESS_REJECTED` (same code) | yes |
| `InvalidFaceSessionException` | *bare 400* | no |
| `LivenessRejectedException` | *unreachable, no code* | n/a |

One code for two causes, matching `SCAN_REJECTED`: same screen, same copy, same remedy;
the causes stay distinguishable server-side through separate audit events.

### Signature and submission

| Endpoint | Exception | Status | Code |
|---|---|---|---|
| signature | `ProfileNotEditableException` | 409 | `PROFILE_TERMINAL` |
| signature | `LivenessNotCompleteException` | 409 | `LIVENESS_REQUIRED` |
| signature | `SignatureRejectedException` | 400 | `SIGNATURE_REJECTED` |
| submission | `ProfileNotEligibleException` — terminal | 409 | `PROFILE_TERMINAL` |
| submission | `ProfileNotEligibleException` — lost race | 409 | `STATE_CONFLICT` |
| submission | `LivenessNotCompleteException` | 409 | `LIVENESS_REQUIRED` |
| submission | `SignatureMissingException` | 409 | `SIGNATURE_REQUIRED` |
| submission | `NotInFinalStagesException` (read only) | 409 | `STATE_CONFLICT` |

`SIGNATURE_REJECTED` is a deliberate stretch of BL-037's wording, recorded as such in the
controller: that rule is scoped to stages that **have** an attempt budget (8 and 10). Stage
11 has none, so there is no counter for a client to misread, and one of the four causes —
content over `SignatureService.MAX_BYTES` — is a screen a customer reaches by drawing a
large signature.

## Part 2 — `POST /api/v1/submission/current`

POST for a read, for S5-11's stated reason: a GET puts the profile id in the request line
and so into every access log and proxy, on a surface unauthenticated by design (R-051).

| Profile state | Answer |
|---|---|
| unknown | 404 |
| no accepted cycle, or `blocked_scan`/`awaiting_registry`/`abandoned` | 409 `STATE_CONFLICT` |
| `blocked_liveness` | 200 `LIVENESS_BLOCKED` + `blockedUntil` |
| liveness not passed | 200 `LIVENESS` |
| passed, no signature | 200 `SIGNATURE` |
| passed + signature | 200 `SUBMIT` |
| `submitted`/`approved`/`rejected` | 200 that stage + `referenceNumber` + `verifiedChannels` |
| `terminated_registry_mismatch` | 409 `PROFILE_TERMINAL` |

Stage selection fails closed: `in_progress` and `blocked_liveness` are the only non-terminal
statuses that can be inside Stages 10-12, so nothing is invented for the others.

### Data sourcing — a change from the approved brief

The brief specified a new non-locking `LivenessRepository` read for `blockedUntil`. Built
instead as two additional fields on `SubmissionState` (`livenessBlockedUntil`,
`hasAcceptedCycle`), read by the existing non-locking `checkState`. `SubmissionState`
already carries `facePassed`, which submission's own SQL gets by joining into
`app.face_result` — it already crosses that boundary — and `liveness_blocked_until` is a
column on `app.profile`, the table `checkState` already reads. No new port, no cross-feature
repository injection, less coupling. Approved by the product owner mid-session.

The binding constraint holds either way: **`lockAndGetLivenessState` is `SELECT … FOR UPDATE`
and is not used by the read.**

Logic sits in `submission/service` (`currentJourneyPointer` + the `JourneyPointer` payload);
the controller only maps it to a `web` record. No stage-selection logic in `web`.

## Part 3 — G-9, answered by giving the screen a source rather than changing a field

`SubmissionResponse.verifiedChannels` is **untouched**. Empty on the idempotent path is
*correct* for its documented meaning — "channels this call enqueued a notification for"
(SubmissionOutcome.java:9-10) — because a re-call enqueues nothing, and
`SubmissionIntegrationTest`'s no-duplicate-notification assertion guards exactly that. The
read supplies the channels instead, recomputed from `currentChannelStates` with the same
VERIFIED filter `submit` applies, so the two can never disagree.

### The entry-path check, and the one gap it found

| Path | Reaches the read? |
|---|---|
| Fresh submit | **No, not by default** |
| Lost-acknowledgement retry | Yes — needs it |
| Resume with local state | Yes — customer.md:1112 |
| Reinstall / no local state | No, correctly — Stage 1a answers `TERMINAL`; customer.md:1144 "there is no re-entry" |

**The fresh-submit path never calls the read on its own.** For the screen to have one source
on all three reachable paths, S5-08's confirmation screen must call `/submission/current`
after a successful submit too — one extra POST against a non-locking read. That cost is
accepted and is now written into three places so it cannot be quietly optimised away:
`SubmissionController.current`'s Javadoc, the EXECUTION_PLAN S5-08 row, and BL-058.
Branching on "did the submit response carry channels" reintroduces the bug.

## Part 4 — AD-002a narrowed, not overturned

PROJECT_PLAN.md:327 read "the backend mints `sessionId` and `nonce` per attempt and validates
them back out of the JWS". Dated correction appended in the AD-007 style, scoping that
sentence to the **enrolment** invocation, where it is real and enforced
(`IdentityScanController` returns `issuance.nonce()`; `UqudoJwsParser` rejects a mismatched
`data.nonce`). For face sessions the backend mints no nonce: `FaceSessionIssuance` and
`FaceTokenResponse` carry `accessToken` + `faceSessionId`, V0044 adds only
`pending_face_session_id`, and binding is on `data.sessionId` alone. Already deliberate and
documented at `UqudoClient.java:107-109`. The two-invocation decision is unaffected.

## Tests

Every assertion names the exact `code` or field value on the wire — **no test asserts status
alone**, which is the whole BL-033/BL-037 lesson: before this slice a status-only test passed
against the collapsed behaviour and proved nothing.

**All direct wrong-value assertions; no revert-restore anywhere in this slice.** A body
carrying no `code` at all cannot satisfy `jsonPath("$.code").value("LIVENESS_ALREADY_PASSED")`,
and a body carrying no `blockedUntil` cannot satisfy an equality against a real instant — so
each test fails against the pre-slice code by construction. CLAUDE.md's revert-restore rule
applies to indirect assertions and identical-happy-path ordering defects; neither is present
here.

| Test | What it proves |
|---|---|
| `LivenessControllerTest` (16) | Every liveness code, both `blockedUntil` branches (real instant; `doesNotExist()` when null), and that `InvalidFaceSessionException` and a malformed profile id stay **bare** 400s — the other half of BL-037's rule |
| `SignatureControllerTest` (6) | Stage 11's three codes plus the bare-400 case |
| `SubmissionControllerTest` (10) | Stage 12's four codes including both `ProfileNotEligibleException` arms, and the read's wire shape for `SUBMITTED`, `LIVENESS_BLOCKED` and a mid-journey stage |
| `SubmissionServiceJourneyPointerTest` (15) | Stage selection including all three `blocked_liveness` deadline cases (future, null, elapsed) and the strict-`isAfter` boundary; that `approved`/`rejected` answer rather than refuse; that `blocked_scan`/`awaiting_registry`/`abandoned` and a missing cycle fail closed; and `readingThePointerMutatesNothing` — Mockito `never()` named individually on `lockAndCheckState`, `nextReferenceNumber`, `submit`, `recordReferenceVersions`, `usedReferenceListVersions`, plus `verifyNoInteractions` on the outbox and the audit writer |
| `SubmissionIntegrationTest.stage10To12ResumeReadReturnsTheStoredStateAndChangesNoRow` | The S5-11 proof shape against real PostgreSQL: `status`, `reference_number`, `submitted_at`, `row_version`, `liveness_attempts`, `liveness_blocked_until` and **`pending_face_session_id`** unchanged, five row counts unchanged, and a second read byte-identical. `pending_face_session_id` is in the snapshot on purpose — the obvious wrong way to build this probe was to call `/api/v1/liveness/token`, which mints a Face Session and writes that column, so asserting it is untouched is what proves the read is not that |
| `SubmissionIntegrationTest.resumeReadCarriesTheChannelsAnIdempotentResubmitCannot` | G-9 end to end: the re-submit returns empty `verifiedChannels` (asserted, unchanged) while the read returns the real channel and the same reference number |
| `SubmissionIntegrationTest.resumeReadDistinguishesTheStagesBeforeSubmission` | `SIGNATURE` and `SUBMIT` against real profiles |

No live curl was taken for the `code` mechanism. A green test asserting `$.code` cannot
coexist with a missing `code`, so CLAUDE.md's live-proof rule is satisfied by the named tests;
BL-033's original capture stands as the Boot-version evidence.

## Review findings and dispositions

`@agent-reviewer` against the diff and this task. No BLOCKER. Two SHOULD FIX, both real,
both fixed; two NOTEs, one acted on.

**SHOULD FIX 1 — an elapsed liveness block still answered `LIVENESS_BLOCKED`. Fixed.** A
genuine correctness bug the tests as first written could not catch. `LivenessService` treats
a lapsed block as resumable — `issueFaceSessionToken` refuses only when
`livenessBlockedUntil == null || isAfter(now)` (LivenessService.java:132-138, again at
:221-226), and clears the block on the next token request rather than on a timer, so `status`
stays `blocked_liveness` long after the deadline passes. The pointer branched on status alone.
A customer returning after the block lifted would have been shown a block screen carrying an
already-past deadline while `/liveness/token` would have succeeded — leaving the app to
re-derive the expiry from its own clock, which is exactly the client-side state inference
R-052 and AD-002a exist to prevent. `currentJourneyPointer` now mirrors `LivenessService`'s
comparison exactly, **null included**: a null deadline still counts as blocked on both sides.

The original unit test could not have caught it: the fixed clock is `2026-09-05T12:00:00Z`
and its only deadline was `2026-09-06T09:15:00Z`, always in the future. Three tests added —
an elapsed deadline falling through to `LIVENESS`; the strict-`isAfter` boundary, where a
deadline exactly at `now` has lapsed, pinned so the two comparisons cannot silently drift
apart; and `resumeReadTreatsALapsedLivenessBlockAsResumable` against real PostgreSQL, which
also closes NOTE 2 below.

**SHOULD FIX 2 — neither no-mutation proof covered `touchLastActivity`. Fixed.** The unit
test named five `SubmissionRepository` writers but asserted nothing on `profileRepository`,
which cannot take `verifyNoInteractions` because the read legitimately calls
`currentChannelStates`. The integration snapshot missed it too:
`JdbcProfileRepository.TOUCH_LAST_ACTIVITY` updates only `last_activity_at` and deliberately
does not bump `row_version`, so it was invisible to every column being compared. Adding that
call is the easiest write to introduce here by accident, since every other Stage 10-12 path
makes it, and it would have reset the 30-day abandonment and 90-day purge clocks on every
poll. Now `verify(profileRepository, never()).touchLastActivity(any(), any())` plus
`last_activity_at` in the snapshot.

**NOTE 1 — `JourneyStage.valueOf(status.toUpperCase())` has no compile-time link to
`ALREADY_SUBMITTED_FAMILY`. Accepted as-is.** Not reachable today: the family is exactly
`{submitted, approved, rejected}` and the enum declares all three. A fourth status added to
the family without a matching constant would be a runtime `IllegalArgumentException`.
Recorded rather than defended against, because the alternative — a hand-written switch — has
the same failure mode with more code.

**NOTE 2 — `LIVENESS_BLOCKED` was never exercised against the real database. Fixed**, by the
integration test above. It is now the only test that reads a non-null `liveness_blocked_until`
out of PostgreSQL, so it proves the new column's `Timestamp -> Instant` conversion as well as
the clock comparison, in both directions from one profile.

The reviewer separately confirmed, with evidence: no `getMessage()` reaches any
`ProblemDetail`; no exception newly escapes as a 500 from any of the three narrowed catch
blocks (all throw sites enumerated against handlers, no competing `@ControllerAdvice`); the
shared `STATE_QUERY_BASE` change is safe under `FOR UPDATE OF p` and the `rs.wasNull()`
ordering invariant for `face_passed` still holds; `IdentityScanController`,
`SubmissionResponse` and `SubmissionOutcome` are untouched; and account numbers
`0000000433`-`0000000437` sit inside this class's declared disjoint range.

## Gate

```
[INFO] Results:
[INFO]
[INFO] Tests run: 974, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 322 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 439 files clean - 0 needs changes to be clean, 0 were already clean, 439 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 322 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  03:31 min
[INFO] Finished at: 2026-09-05T20:27:34+02:00
[INFO] ------------------------------------------------------------------------
```

`./mvnw verify -Pdb-integration-test` from `backend/`, `JAVA_HOME` set to the JBR OpenJDK 21.
974 tests, up from S5-12's 922 — 52 added by this slice. Spotless clean; JaCoCo's bundle-wide
check met with the integration suite running, which is the real backend coverage gate.

Intermediate runs, one line each as the rules allow:

- `./mvnw verify -Pdb-integration-test` — 971 tests green, then FAILED `spotless:check` on the
  new files' formatting. `./mvnw spotless:apply` (exit 0), re-run green.
- `./mvnw verify -Pdb-integration-test` after the review fixes — **974 tests, 1 error.** Failure
  output, since that is evidence:

```
[ERROR] SubmissionIntegrationTest.resumeReadTreatsALapsedLivenessBlockAsResumable:304->blockLiveness:327
  DataIntegrityViolation PreparedStatementCallback; SQL [UPDATE app.profile SET status = 'blocked_liveness',
  liveness_blocked_until = ?::timestamptz WHERE profile_id = ?::uuid];
  ERROR: status change on app.profile 87e63ae3-... (in_progress -> blocked_liveness)
  has no matching (most recent) app.profile_status_history row
```

  My new helper set the status by hand without the history row V0020's deferred
  `profile_status_requires_history` trigger demands. Fixed by wrapping the status change and its
  history row in one transaction — the same shape `AccountCheckIntegrationTest` already uses to
  manufacture a status. Worth recording rather than quietly fixing: the trigger did exactly its job,
  and it is the reason a test cannot fabricate a profile status with a bare `UPDATE`.

## Environment note

The first gate run failed at `spring-boot:repackage` with `Unable to rename … .jar` — not a
code failure. A backend jar left running from the **S5-07 device-run session** (PID 14832,
started against an old scratchpad config) still held the file. Stopped it and re-ran. Worth
recording because the same stale process will block any future packaging run on this machine.

## Files

**New** — `submission/domain/JourneyStage.java`, `submission/domain/NotInFinalStagesException.java`,
`submission/service/JourneyPointer.java`, `submission/web/JourneyPointerResponse.java`, and four
test classes.

**Changed** — `liveness/domain/NoAcceptedIdentityCycleException.java` (discriminator),
`liveness/service/LivenessService.java` (factory call sites),
`liveness/web/LivenessController.java`, `signature/web/SignatureController.java`,
`submission/web/SubmissionController.java` (handlers + the read),
`submission/domain/ProfileNotEligibleException.java` (discriminator),
`submission/domain/SubmissionState.java` + `submission/jdbc/JdbcSubmissionRepository.java`
(two fields), `submission/service/SubmissionService.java` (`currentJourneyPointer`),
`submission/SubmissionIntegrationTest.java`.

**Not changed, deliberately** — `identityscan/web/IdentityScanController.java` (BL-037 already
did the additive helper work), `submission/web/SubmissionResponse.java` and
`submission/service/SubmissionOutcome.java` (Part 3), every happy path, every attempt budget,
the face-session flow.

## Plan files

- EXECUTION_PLAN — S5-13 row added; S5-08 marked unblocked and carrying the fresh-submit
  binding note.
- BACKLOG — BL-056 (error contract), BL-057 (resume read), BL-058 (idempotency surfacing)
  filed and closed as this slice's own. **BL-051 explicitly NOT closed**: BL-057 closes the
  liveness half of that resume gap and leaves the scan half open, which
  `NotInFinalStagesException`'s Javadoc also records at the code boundary.
- RISKS — R-052 extended to stages 10-12 and narrowed a second time; stays 🟡 open. Its
  residual (ii) is discharged for the liveness block, still standing for the scan block.
- PROJECT_PLAN — AD-002a narrowed per Part 4.

## Out of scope, untouched

No mobile changes (S5-08). No attempt-budget, face-session-flow or happy-path changes. Not
AD-008/BL-041 device-less supersession. Not R-016. BL-051's scan half stays open.

## Commit

```
$ git log --oneline -1
9c72db1 S5-13: stages 10-12 error contract and read-only resume pointer

$ git push
To https://github.com/Osmantou/Fr_user_update
   03947ec..9c72db1  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing added to commit but untracked files present
```

Straight to `main`, this project's norm. 23 files: 4 new main classes, 4 new test classes,
9 modified backend files, 4 plan files, this report. The untracked file left behind is
`this.md`, a scratch file that predates this session and is not part of the change.
