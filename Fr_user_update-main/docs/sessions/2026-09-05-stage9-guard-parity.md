# 2026-09-05 — Stage 9 guard-parity slice (BL-042, BL-043, BL-044)

Backend only. Three missing-guard defects on Stage 9's four registry-review writers, found by the
2026-09-05 identity-scan sweep (F-2, F-4, F-5). Sequenced before S5-07 because BL-042 changes what
the Stage 9 screens see on a finished profile.

One file of production code — `identityscan/service/IdentityScanService.java`. No migration, no new
`app.status_transition` pair, no change to any happy path.

---

## 1. Diagnosis verified before any code was written

The session brief said to treat the sweep's line numbers as leads, not facts. Verified against
current source; the mechanisms all held, one line number did not.

| Claim | Verdict |
|---|---|
| `markCycleAccepted` leaves `identity_cycle.state` alone, so a submitted/approved profile still has an `active` cycle with `registry_result.state = 'ok'` | CONFIRMED — `MARK_CYCLE_ACCEPTED` is `UPDATE app.identity_cycle SET accepted_at = ? WHERE cycle_id = ?`; `CURRENT_ACTIVE_REGISTRY_CONTEXT` filters on `ic.state = 'active'` only |
| The three siblings take `.context()` and drop `.state()` | CONFIRMED at `:880`, `:905`, `:952` |
| `blocked_scan` is reachable at Stage 9 without a race | CONFIRMED — `blocked_scan.is_terminal = false` (V0005:20); `insertAbandonedCycle` does not supersede the active `ok` cycle; `currentReviewPayload` gates on `terminal()` alone |
| Retry's already-`ok` short-circuit is pre-transaction and `updateRegistryResultOnRetry` is unconditional under the lock | CONFIRMED — short-circuit outside the `executeWithoutResult` that opens at `:805`; the write is unconditional; only the status transition was guarded on `freshContext` |
| The short-circuit sits at `:796-798` | **REFUTED** — it is at `:798-800`; `:796-798` is the tail of the comment block. BACKLOG's own BL-038/BL-044 entries had it right |

---

## 2. What changed

### BL-042 — terminality parity on the three siblings

Each of `acceptRegistryReview`, `reportWrongNumber`, `reportWrongDetails` now binds the whole
`ActiveReview` and guards **twice**: pre-transaction (retryRegistryLookup's position, so the more
accurate of the two errors wins over the registry-ready guard) and again on the freshly locked
`ScanState` inside its own transaction.

The under-lock half is not belt-and-braces. `requireActiveReview`'s lock is released with its own
unwrapped statement, and `app.profile` has real concurrent writers — operator approve/reject,
S4-02 manual completion, submission. `recordFailedAttempt`'s own comment names the under-lock
re-check as the actual guard and the pre-check as an early exit. It costs no extra read: all three
were already issuing `lockAndGetScanState` as their transaction's first statement, and two of them
were discarding the result.

Pre-fix behaviour, all three now uniformly `PROFILE_TERMINAL` (409):

- `acceptRegistryReview` — **committed**, rewriting `accepted_at` on an already-approved cycle and
  appending a `registry_review_accepted` event attributed to the customer.
- `reportWrongNumber` — **committed**, marking a completed profile's cycle `superseded` and moving
  its budget columns. Not cosmetic: `state='active' AND accepted_at IS NOT NULL` is what
  `JdbcLivenessRepository` and `JdbcSubmissionRepository` gate on.
- `reportWrongDetails` — **unmapped 500**, because `submitted → terminated_registry_mismatch` is in
  no `app.status_transition` row and V0020 raised `23514`.

### BL-043 — `reportWrongNumber` re-checks `blocked_scan` / `awaiting_registry`

`recordFailedAttempt`'s two under-lock re-checks, in its flag-return-throw-after shape, placed
before `supersedeActiveCycleIfAny` / `applyScanAttempt` / `applyScanBlock`, each with its matching
`auditRejection` — this surface is unauthenticated by design (R-051), so a repeatedly refused
wrong-number has to leave a trace.

Two deliberate departures from the shape being copied, both stated because they are judgement
calls rather than transcription:

1. **A separate `scanBlockedNow` boolean** carries the blocked branch, rather than null-checking
   `activeBlockUntil` the way `recordFailedAttempt` does. That method has a latent hole: a
   `blocked_scan` row whose `scan_blocked_until` is somehow null falls through its
   `activeBlockUntil[0] != null` check to a silent success. Not copied. `recordFailedAttempt` was
   not fixed here — out of scope, and it needs its own decision about what a null expiry means.
2. **The `awaiting_registry` arm is parity-only and is believed unreachable today.** The
   pre-transaction registry-ready guard already refuses unless the active cycle is `ok`, and a
   profile only reaches `awaiting_registry` with a not-`ok` active cycle. Its passing test proves
   the guard holds; it is **not** evidence of a reachable defect. Kept because this method's whole
   defect was inheriting three sibling checks and getting none of them.

The terminal case is not separately audited, following Stage 9's own precedent
(`retryRegistryLookup` throws without auditing); the two BL-043 refusals are, following
`recordFailedAttempt`'s.

### BL-044 — the already-`ok` decision, re-made under the lock

BL-038's pre-transaction short-circuit is untouched — it is what avoids the registry call in the
sequential case. Added inside the transaction, against `freshContext`: a
`supersededByConcurrentRetry` flag gating both `updateRegistryResultOnRetry` and
`insertArtifactRef`, then the early return, then `currentReviewPayload(profileId)` after the
transaction so both short-circuit paths answer identically.

**The early return sits deliberately AFTER `auditRegistryLookup`.** The losing retry really did
send the national number to the Civil Registry and really did get an answer; returning before the
audit write would have left a real outbound exchange with no `registry_lookup_completed` event and
no `civil_registry_request` artifact — BL-040's class of evidence loss, introduced by this fix
rather than found by it. Caught in review before the code was written.

**The now-unreachable `!REGISTRY_STATE_OK.equals(freshContext.registryState())` half of the
transition condition is KEPT**, with a comment saying why. It is the only remaining guard against a
duplicate `awaiting_registry → in_progress` transition and its history row if BL-044's guard is
ever refactored away — which is precisely the failure mode BL-042 and BL-043 exist to record. A
future reader who tidies it away reintroduces the defect. Filed as BL-048 rather than fixed,
because the accurate test (`fresh.status()`, already held under the lock) is a behaviour change to
the status-transition path, not a race fix.

Not claimed: the race loser still spends one wasted Civil Registry call. It has already happened by
the time the lock is acquired.

---

## 3. The first BL-044 test was vacuous — caught before implementation

Worth recording in full, because the mistake is not obvious and the corrected design is the load-bearing
part of this slice.

The plan proposed proving BL-044 by having the test hold the `app.profile` row lock in its own
transaction, playing the winning retry itself, while a worker thread ran a real retry against the
HTTP endpoint. `@agent-reviewer` refuted it: `retryRegistryLookup`'s **first** statement is
`requireActiveReview`, whose query is `SELECT ... FOR UPDATE OF p`
(`JdbcIdentityScanRepository.java:29-37`). The worker would have blocked *there* — before the
pre-transaction already-`ok` short-circuit — and on release would have read `ok` and returned
through the **existing** short-circuit, never opening the transaction and never reaching the new
guard. Identical result pre-fix and post-fix: a green test proving nothing.

Replaced with a real two-thread race. A `@MockitoSpyBean(name = "civilRegistryClient")` wraps the
selected client and a latch holds the **first** caller inside `lookup` — past the short-circuit,
holding no database lock at all — while the winner runs a complete retry and commits. The loser
then takes the freed row lock and must notice, under it, that the record is already verified. The
spy delegates by default, so the other 15 tests in the class are unaffected, and the latch lives in
the test rather than in `civilregistry/stub`, which is main source.

---

## 4. Tests, and which kind of proof each is

Ten new service tests (plain JUnit, no Spring — CLAUDE.md's business-logic rule) and two new
integration tests (`@Tag("integration")`, real PostgreSQL 18).

| Test | Kind | Revert needed? |
|---|---|---|
| accept / wrongNumber / wrongDetails on a terminal profile | Direct wrong-value assertion — pre-fix accept and wrongNumber return normally and wrongDetails dies on a DB constraint, so `assertThrows` cannot pass | No, by CLAUDE.md's rule. Run anyway on the accept case, as the brief asked |
| accept / wrongNumber / wrongDetails turning terminal *under the lock* (consecutive-return stubs) | Direct, and the only thing covering the guard's second half — a single-state stub cannot tell the two halves apart | The wrongDetails one was added post-review and proven by deletion; see §5 |
| wrongNumber during a live block | **Ordering defect** — the guard must sit before the writes, and against mocked collaborators a guard placed after them would still throw | **Yes, performed** |
| wrongNumber while `awaiting_registry` | Direct. Proves the guard, not a reachable state | No |
| BL-044 already-`ok` under the lock | Direct — `updateRegistryResultOnRetry` is unconditional pre-fix, so `never()` cannot pass | No |
| BL-044 loser still audits its registry exchange | Direct, on the event type | No |
| BL-043 integration: block not extended, no history row | The half a mocked test cannot reach — the falsified `profile_status_history` row | Covered by the service-level revert |
| BL-044 integration: real two-thread race | Genuine concurrency proof | **Yes, performed** — see below |

### Revert-restore proofs

**BL-042 (accept, the silent-commit case).** Both guards removed:

```
[ERROR]   IdentityScanServiceTest.acceptAbortsWithoutWritingIfTheProfileTurnsTerminalUnderTheLock:1090 Expected
sd.gov.bank.fruserupdate.identityscan.domain.ProfileNotEditableException to be thrown, but nothing was thrown.
[ERROR]   IdentityScanServiceTest.acceptOnATerminalProfileIsRejectedAndWritesNothing:1070 Expected
sd.gov.bank.fruserupdate.identityscan.domain.ProfileNotEditableException to be thrown, but nothing was thrown.
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
```

"nothing was thrown" is the silent commit, exactly as BL-042 describes it.

**BL-043 (the ordering defect).** `blocked_scan` guard removed:

```
[ERROR]   IdentityScanServiceTest.wrongNumberDuringALiveBlockIsRefusedWithoutExtendingItOrWritingHistory:1176 Expected
sd.gov.bank.fruserupdate.identityscan.domain.ScanTemporarilyBlockedException to be thrown, but nothing was thrown.
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

**BL-044 (validating the replacement test, not just the fix).** `supersededByConcurrentRetry`
forced to `false`:

```
[ERROR]   IdentityScanIntegrationTest.aRetryThatLosesTheRaceOverwritesNothingAndDoesNotCollideOnTheArtifactUniqueKey:940
Execution jakarta.servlet.ServletException: Request processing failed: org.springframework.dao.DuplicateKeyException:
PreparedStatementCallback; SQL [INSERT INTO app.artifact_ref
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
```

That is BL-044's own predicted failure — `app.artifact_ref`'s `UNIQUE (cycle_id, kind)` (V0008)
raised as a `DuplicateKeyException` the method does not catch, surfacing unmapped. Run because the
first design of this test was vacuous; a test whose design has already been wrong once earns a
proof that it fails against the bug.

All three reverts restored before the gate.

---

## 5. Review findings and dispositions

`@agent-reviewer` ran twice: once against the PLAN before any code existed, once against the diff.

### Pre-implementation pass

| # | Finding | Disposition |
|---|---|---|
| 1 | **The BL-044 concurrency test as designed was vacuous** — see §3 | FIXED before implementation. Redesigned as a real two-thread latch race |
| 2 | BL-044's early return would drop the audit record of a registry exchange that really happened | FIXED. Return placed after `auditRegistryLookup` |
| 3 | The plan leaned toward simplifying the now-dead `:860` condition | REFUTED my position, accepted. Condition kept with a comment; filed as BL-048 |
| 4 | BL-043 copied `recordFailedAttempt`'s guard but dropped its audit half | FIXED. Both `auditRejection` calls added |
| 5 | The plan's `:796-798` line number was wrong | CORRECTED to `:798-800` |
| 6 | BL-043's `awaiting_registry` branch is probably unreachable and was presented as parity without saying so | FIXED. Labelled in the code, the test Javadoc and §2 |
| 7 | `insertHistory`'s hard-coded from-status survives the slice | Filed as BL-047, not fixed — out of scope |

### Post-implementation pass, against the diff

| # | Finding | Disposition |
|---|---|---|
| 1 | **SHOULD FIX — `reportWrongDetails`'s under-lock guard had no test.** The existing test stubs the terminal state on the FIRST `lockAndGetScanState` call, so the pre-transaction guard fires and the transaction body is never entered. That guard could be deleted with the suite green — the one of the three not actually held down | **FIXED.** Added `wrongDetailsAbortsWithoutWritingIfTheProfileTurnsTerminalUnderTheLock`. Proven by deleting the guard: 1 failure out of 59, and it is the new test — confirming both the reviewer's claim that nothing else covered it and that the new test covers it |
| 2 | SHOULD FIX — code cites BL-048 and a session report that do not exist | ALREADY RESOLVED, and the finding was stale: the reviewer read the tree before the plan-file and report edits landed. Re-confirmed present, and both are in this commit with the code, which is what the finding actually asks for |
| 3 | NOTE — the null-`scan_blocked_until` hole I filed for `recordFailedAttempt` also exists in `issueToken`, where it mints a real Uqudo token and returns a null `sessionId`/`nonce` | ACCEPTED. My filing was too narrow. Widened and filed as **BL-049** covering both sites. Still not fixed: unreachable today, and out of scope |
| 4 | NOTE — BL-044's stale-pre-transaction-context class still applies to the three siblings | ACCEPTED. Filed as **BL-050**. Not fixed: outside BL-042's terminality-only brief, and narrower than BL-044 (no unbounded external call between the two reads) |
| 5 | NOTE — the new `blocked_scan` refusal keys on status, not on the block still being live, so `blockedUntil` can be in the PAST | ACCEPTED as correct-by-parity, not fixed. The reset is lazy — `issueToken` is the only path that clears it — and this is exactly how `recordFailedAttempt`/`cancelScan` already behave, which is what parity required. The controller null-guards the property, so no 500. **Carried into S5-07's EXECUTION_PLAN row: the Stage 9 screen must not assume `blockedUntil` is in the future** |

Checked and clean, per the reviewer: all six stated constraints honoured; `reportWrongNumber`'s
four-branch flag plumbing has no path to a silent success; a single `auditRegistryLookup` call on
both BL-044 paths; `currentReviewPayload` cannot recurse; the race test is deterministic and cannot
hang the suite (both latches bounded, `countDown` in a `finally`); the spy shares
`StubCivilRegistryClient`'s `final ConcurrentHashMap`, so `overrideOutcome` still works and
`MockReset.AFTER` clears the `doAnswer` between methods; and none of the new tests is vacuous.

---

## 6. Gate

`./mvnw verify -Pdb-integration-test`, verbatim tail. This is the FINAL run, taken after the
review fix in §5 added a tenth service test — an earlier run at 922 is superseded and is not
quoted as covering it:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 923, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 318 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact ... with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to ...jar.original
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
[INFO] Total time:  02:13 min
[INFO] Finished at: 2026-09-05T03:52:39+02:00
[INFO] ------------------------------------------------------------------------
```

Mobile and backoffice untouched — no gate run for either, and none required.

---

## 7. Scope held

- **No attempt-budget change.** `ScanAttemptBudget.canAttempt` is still not consulted by
  `submitScan` or `reportWrongNumber`. BL-039 stays open and untouched — BL-043 is a status
  re-check, a different mechanism, which is why the sweep filed them separately.
- **BL-034's short-circuit** in `submitScan` untouched.
- **Sweep §2's found-sound list** undisturbed: the stage-8 ordering rule, the budget exemptions,
  PII placement, the narrow `DuplicateKeyException` catch, lock ordering, the image endpoint, the
  Uqudo boundary, `submitScan`'s deliberate unlocked pre-check.
- **Lock ordering** preserved — every new guard sits after the `app.profile` row lock and before
  any `auditEventWriter` call, so the order S4-01 deadlocked on (40P01) is not inverted.
- **No schema change and no new V0020 transition pair.** All three fixes only suppress writes.
- No mobile, no back-office. BL-045/BL-046 remain separate.

## 8. Filed, not fixed

- **BL-047** — `JdbcIdentityScanRepository.insertHistory` hard-codes `'in_progress'` as the
  from-status. This is the root cause behind BL-043's falsified row rather than the path BL-043
  closed. After this slice both callers refuse every non-`in_progress` status, so nothing reaches
  it today; the repository would still write the lie for the next caller that does.
- **BL-048** — retry's `:860` condition is a registry-result proxy for a status the method already
  holds under the lock. Kept deliberately (see §2); filed so nobody tidies it into a bug.
- **BL-049** — a `blocked_scan` profile with a null `scan_blocked_until` is answered with a silent
  success in `recordFailedAttempt` and, worse, in `issueToken`, which mints a real Uqudo token and
  returns a null `sessionId`/`nonce`. Unreachable today (both columns are written in one UPDATE and
  no CHECK ties them). This slice deliberately did not copy the shape — `reportWrongNumber` uses a
  separate boolean — but did not fix the two existing sites. **Filed too narrowly at first:** I
  scoped it to `recordFailedAttempt`; the reviewer found the worse `issueToken` case.
- **BL-050** — the three siblings still use `requireActiveReview`'s released-lock context inside
  their transactions. BL-044's defect class in the paths BL-044 did not cover; outside BL-042's
  terminality-only brief.

---

## 9. Commit

```
$ git log --oneline -1
b68237d BL-042/043/044: Stage 9 guard-parity slice

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   61ce23a..b68237d  main -> main
```

Straight to `main`, per CLAUDE.md — no branch, nothing to fast-forward.
