# S5-11 — Stage 9 read-only resume endpoint, and `retryRegistryLookup`'s two missing guards

**2026-09-05 · backend only · branch `main`**

Unblocks S5-07, which was waiting on this and on S5-09/S5-10. Found by the 2026-09-04 S5-07
precondition review.

## What shipped

1. `POST /api/v1/identity-scan/registry-review/current` — Stage 9's display payload, rebuilt from
   storage. No Civil Registry call, no write, no status transition, no budget draw, no row lock, no
   audit event.
2. **BL-038**, found and fixed here: `retryRegistryLookup` had neither an already-`ok` guard nor
   any terminality check. Both added, plus a third guard added under review (below).
3. Three deferred items filed, not built: **BL-036** (idempotency), **BL-037** (the Stage 8
   `400`), and R-052's S5-11 notes.

## The task statement was wrong about the guard, and was corrected before coding

The task said "add the same `registryState == ok` guard its three siblings have". That guard means
*require* ok. Retry exists **for** the non-ok case (customer.md Stage 9, "Civil Registry unreachable
or returning nothing", l.747-757), so transplanting it would have disabled retry and failed two
existing tests — `IdentityScanServiceTest#retrySucceedsAndTransitionsBackToInProgressWithoutTouchingUqudo`
and `#retryStillUnreachableStaysPaused`. Raised in plan mode; the user corrected the task and the
build followed source, not the prompt.

The **inverse** was missing, and that is the real defect. Also confirmed: the "false status-history
row" half of the reported defect **cannot occur** — it was already guarded at
`IdentityScanService.java:855-863` on the freshly re-read state. No test was written for it.

## Design decisions and why

**No schema change.** Every `ScanDisplayPayload` field is already persisted across
`app.identity_cycle`, `app.scan_result` (V0008), `app.registry_result` (V0008 + V0023 + V0062) and
`app.artifact_ref` (V0008 + V0053), and `JdbcIdentityScanRepository.currentAcceptedScanSnapshot`
already rebuilds the whole payload — BL-034 does exactly this in production. The endpoint is a pure
query. The task's "stop and say so if this needs a schema change" branch was checked and not taken.

**Not-ready is a `200` with `registryReady=false`, never a `409`.** `registryReady` already exists
for this and is derived from the same `registryState == "ok"` predicate `REGISTRY_NOT_READY` keys
on; both mutating producers already report a pause that way. Answering a *read* with a conflict
would make Stage 9's pause screen reachable only through an error path. `REGISTRY_PENDING` (profile
status `awaiting_registry`) and `REGISTRY_NOT_READY` (`registry_result.state`) are **not** synonyms
and both stay on the mutating paths; the read endpoint throws neither.

**POST, not GET.** Consistent with every other endpoint in the controller, and it keeps the profile
UUID out of the request line — and so out of access logs and proxies — on a surface that is
unauthenticated by design (R-051). `GET /image/{kind}` carries that cost because an image URL has
no other shape; a JSON read does.

**One new port method, `readScanState`** — `LOCK_AND_GET_SCAN_STATE` minus `FOR UPDATE OF p`, same
mapper, shared through one private `scanState(sql, profileId)` so the two cannot drift. The only
existing profile-existence read takes a write lock, which a path whose contract is that it changes
nothing must not do. Interface in `domain`, implementation in `jdbc`. Javadoc says explicitly: never
use it to decide a write.

**Short-circuit, not a throw, for the already-`ok` retry** (product-owner decision A). Mirrors
BL-034's treatment of the same shape of redundant call and reuses the read path rather than
duplicating it, so a redundant retry becomes a harmless re-read.

**No audit event on the read**, following `reviewImage`'s recorded reasoning and BL-034's
short-circuit: a customer re-reading their own scan evidences nothing about identity, the screen
re-reads freely, and `payload_json` is permanently hash-chained.

**BL-033 reuse without refactoring.** The eight codes are inline string literals in
`IdentityScanController`, not an enum. The new endpoint throws the same exception types and gets its
codes from the existing `@ExceptionHandler` methods; no enum was introduced (explicitly out of
scope). `UnknownProfileException` → bare `404` with no code, left as-is.

**Guard-order asymmetry, deliberate and documented in the Javadoc.** The read checks terminality
first and the active cycle second; the four Stage 9 *actions* resolve the active cycle first
(`requireActiveReview`, unchanged since S3-12). So a terminal profile whose active cycle carries no
`scan_result` answers `PROFILE_TERMINAL` on the read and `STATE_CONFLICT` on retry. Both are correct
advice — a read should say "your journey is over" before "re-sync"; an action should refuse on the
same grounds its siblings do. Flagged because S5-07 consumes both.

## The `requireActiveContext` → `requireActiveReview` refactor

It now returns a record carrying both the `ScanState` and the `ActiveRegistryContext`; the three
siblings take `.context()`. The `app.profile` read was always made there and its `terminal` flag
simply discarded — which is *how* `retryRegistryLookup` came to have no terminality guard at all.

**Confirmation asked for: the siblings' tests pass unchanged, and none needed editing.** The six
tests covering the three siblings are `acceptRequiresRegistryOk`, `acceptMarksTheCycleAccepted`,
`wrongNumberSupersedesAndCountsAgainstBudget`,
`wrongDetailsRequiresRegistryOkAndReportsThePauseVariant`,
`wrongDetailsTransitionsToTerminatedMismatch` and
`noActiveCycleReportsTheStateConflictVariantRatherThanThePause`. The evidence that the "mechanical,
no behaviour change" claim holds is stronger than "they pass": the diff for that file is **pure
insertion**.

```
$ git diff --stat backend/src/test/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanServiceTest.java
 .../service/IdentityScanServiceTest.java | 167 +++++++++++++++++++
 1 file changed, 167 insertions(+)
```

(That capture is from before the review pass added the race test; the file is still insertion-only.)
No sibling test was touched, so nothing about them was adjusted to fit.

## Review findings and dispositions

`@agent-reviewer` ran twice — once before implementation (which is what corrected the task) and once
against the diff. Second-pass findings:

| Finding | Disposition |
|---|---|
| **SHOULD FIX** — the new terminality guard is evaluated pre-transaction, from a lock already released, and never re-checked under the write lock. A profile that turns terminal *during* the unbounded registry call still has `updateRegistryResultOnRetry` applied — BL-038's defect (2) in its race form. | **Fixed.** The in-transaction `lockAndGetScanState` result was being discarded; it is now captured and `terminal()` re-checked under the lock, using the `boolean[] becameTerminal` + throw-after-commit shape `recordFailedAttempt` already establishes. New test below. |
| **NOTE** — the pre-existing six-arg `buildDisplayPayload` Javadoc was left stranded above the new two-arg overload, documenting the wrong method and leaving the six-arg one undocumented. | **Fixed.** Moved back; the overload has its own doc. |
| **NOTE** — BL-036 and BL-037 cite line numbers this same diff invalidated. | **Fixed.** Re-resolved against the committed file (`:608`/`:682`/`:939`, and `:315-319`/`:328`). |
| **NOTE** — guard-order asymmetry between the read and retry. | **Kept, documented.** See above; recorded in `currentReviewPayload`'s Javadoc for S5-07. |

Confirmed by the reviewer and worth recording: `CURRENT_ACTIVE_REGISTRY_CONTEXT` and
`CURRENT_ACCEPTED_SCAN_SNAPSHOT` have byte-identical `FROM`/`JOIN`/`WHERE` clauses, so
context-present implies snapshot-present and the short-circuit **cannot** answer `STATE_CONFLICT`
where the old code succeeded — except under a concurrent `supersedeActiveCycleIfAny` between the two
reads, where the old code would have written to a superseded cycle and throwing is the better answer.

## Proof

**Revert-restore: not required for any of the three defect tests, and here is why.** All three are
direct assertions that cannot pass against the pre-fix code.

- `retryOnAnAlreadyOkResultReturnsTheStoredPayloadAndNeverRequeriesTheRegistry` — the stubbed live
  lookup returns `sampleRegistryResult()` (`"Given"`, `1990-01-01`); the stored snapshot holds
  `storedRegistryFields` (محمد, `1990-01-02`). The assertions name the **stored** values. Pre-fix
  the method returns the freshly-queried ones, so it fails. A **wrong-value** assertion, not an
  absence-of-call one — the `verify(never())` calls corroborate but are not what it rests on.
- `retryOnATerminalProfileIsRejectedBeforeAnyRegistryCall` — `assertThrows`; pre-fix returns
  normally.
- `retryAbortsWithoutWritingIfTheProfileTurnsTerminalDuringTheRegistryCall` — `assertThrows` with
  the first `lockAndGetScanState` healthy and the second terminal; pre-fix *and the first draft of
  the fix* both return normally and write the row.

Per CLAUDE.md's rule, a revert proof is required only for indirect assertions or identical-happy-path
ordering defects. None of these are either.

**"No live lookup fires and no row changes" is proven at both tiers, because neither alone suffices.**
`StubCivilRegistryClient` records nothing by design (its own Javadoc: no call log, no counter,
deliberately), so "the registry was never called" is **not** assertable at integration level:

- Service tier, `currentReviewPayloadReturnsTheStoredPayloadWithoutCallingTheRegistry` — the direct
  `verify(civilRegistryClient, never()).lookup(any())`, plus `never()` on `lockAndGetScanState`,
  `updateRegistryResultOnRetry`, `insertRegistryResult`, `applyScanAttempt`, `markCycleAccepted`,
  `supersedeActiveCycleIfAny`, `transitionBackToInProgressFromRegistry`, `touchLastActivity`,
  `append` and `appendWithArtifact`.
- Integration tier, `stage9ResumeReadReturnsTheStoredPayloadAndChangesNoRow` (real PostgreSQL 18) —
  the persisted consequence, which mocks cannot give: `app.registry_result`'s whole row (including
  `attempts` and `queried_at`, both of which a live lookup would move) is equal before and after,
  and row counts for `identity_cycle`, `scan_result`, `artifact_ref`, `profile_status_history` and
  `audit.audit_event` are unchanged. Asserted twice, since a resume read must be repeatable.
- `retryOnAnAlreadyOkCycleReturnsTheStoredResultInsteadOfOverwritingIt` (integration) switches the
  stub to `NOT_FOUND` before retrying, so pre-fix the stored Arabic name would come back null and
  the row would be rewritten. It is not.

New integration accounts `0000000532`–`0000000533`, registered in `AbstractPostgresIntegrationTest`'s
range list. `overrideOutcome` is keyed by identity number and `IDN-0000000533` is used nowhere else,
so nothing leaks into another test.

## Gate — `./mvnw verify -Pdb-integration-test`, verbatim

Per CLAUDE.md's Coverage section this is the real backend gate; plain `verify` proves Spotless and
the non-integration tests only.

```
[INFO] Results:
[INFO]
[INFO] Tests run: 908, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
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
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:43 min
[INFO] Finished at: 2026-09-05T01:16:07+02:00
[INFO] ------------------------------------------------------------------------
```

The three touched classes, from the same run:

```
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.187 s -- in sd.gov.bank.fruserupdate.identityscan.IdentityScanIntegrationTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.711 s -- in sd.gov.bank.fruserupdate.identityscan.service.IdentityScanServiceTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.911 s -- in sd.gov.bank.fruserupdate.identityscan.web.IdentityScanControllerTest
```

An earlier run of the same command, before the review pass, was green at 908 − 1 = 907 tests.

## Files

| File | Change |
|---|---|
| `identityscan/domain/IdentityScanRepository.java` | + `readScanState` (no lock) |
| `identityscan/jdbc/JdbcIdentityScanRepository.java` | + `READ_SCAN_STATE`; both reads share one mapper |
| `identityscan/service/IdentityScanService.java` | + `currentReviewPayload`; `requireActiveContext` → `requireActiveReview`; three guards in `retryRegistryLookup`; `buildDisplayPayload` overload |
| `identityscan/web/IdentityScanController.java` | + `POST /registry-review/current` |
| the three identity-scan test classes + `AbstractPostgresIntegrationTest` | tests and the account-range registry |
| `EXECUTION_PLAN.md`, `BACKLOG.md`, `RISKS.md` | S5-11 row + S5-07 unblocked; BL-036/037/038; R-052 notes |

No migration, no security config change (chain 2's `/api/v1/**` catch-all already covers it, and its
own comment says new customer endpoints join by falling under it, not by being listed), no new wire
record, no mobile or backoffice change.

## Out of scope, and left that way

- No mobile consumer — that is S5-07.
- BL-036 and BL-037 filed, not fixed.
- **BL-037 should be sequenced before or with S5-07's Stage 8 error screens**, or they get built
  against a `400` that cannot distinguish a backend JWS rejection (which spends an attempt) from a
  client bug or a pre-service validation failure (which do not).
- **R-052 stays open and only half of its resume gap is closed.** The endpoint answers a
  `STATE_CONFLICT` raised by a Stage 9 *action*. A `STATE_CONFLICT` from `/token` or `/scan-result`
  still names a re-sync the client cannot perform, because nothing returns Stage 8 scan state or
  remaining attempts. Recorded on R-052; it is tracked nowhere else.

## Separate commit

The `CLAUDE.md` edit already in the working tree at session start (the session-report length rule)
is unrelated to S5-11 and outside its backend-only scope. Committed on its own, ahead of the S5-11
commit, rather than folded in.

## Commit proof

Two commits, deliberately separate — the `CLAUDE.md` edit is not part of this backend-only slice.

```
$ git log --oneline -2
69a1224 S5-11: let a customer re-read Stage 9 without re-running the registry lookup
5c4542b docs: exclude required proof output from the session-report length target

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git push
To https://github.com/Osmantou/Fr_user_update
   9453be3..69a1224  main -> main
```

Straight to `main`, per CLAUDE.md — no feature branch.
