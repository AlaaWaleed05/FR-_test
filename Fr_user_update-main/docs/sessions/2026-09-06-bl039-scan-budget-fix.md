# BL-039 — the scan-budget fix, built

**Build session. Two slices, two commits, a reviewer pass on each.** Built from the closed
investigation's DECISION (`docs/sessions/2026-09-06-bl039-budget-investigation.md`, commits
`8f71459`→`0060b0b`); the model was not re-derived.

| | |
|---|---|
| Repo | Fr_user_update · branch `main` · pushed |
| Date | 2026-09-06 |
| Tickets | BL-039 (closed), BL-064/065/067/068 filed |

---

## What the two slices did

**Slice A — the defect.** The budget was consulted at token issuance and nowhere that spent a
try. Combined with a pending session that was never cleared (V0040, deliberately), one issued
token backed an unbounded number of failed posts: `scan_attempts_*` sailed past the per-type
limit and the customer landed on the 24-hour block with their other document type untouched,
so the document-switch fallback never fired.

- `consumePendingScanSession` — the session is single-use, consumed beside `applyScanAttempt`
  in the two spend transactions and nowhere else.
- `canAttempt` under the lock on both spend paths, throwing `SCAN_TYPE_EXHAUSTED`.
- `PER_TYPE_LIMIT` 3→5, `TOTAL_LIMIT` derived as `2 * PER_TYPE_LIMIT`.
- V0064 supersedes V0040's "deliberately never cleared" comment (`COMMENT ON COLUMN`; the
  applied migration is not hand-edited).

**Slice B — the lifetime cap.** F-5: token issuance was gated by a counter it never
incremented, on an endpoint unauthenticated by design (R-051). V0065 adds
`scan_tokens_minted`/`face_tokens_minted`; both cap at 20 and reuse their own side's existing
24-hour block. No new error code, no new screen, no new copy, no Flutter change. The orphaned
`LivenessAttemptBudget.canAttempt` — written at S3-13, called from no production code — is
wired into `issueFaceSessionToken`.

### Two extensions beyond the ticket as filed

`reportWrongNumber` was a **second unchecked spending path** the ticket never named. It now
carries the same guard, so `POST /registry-review/wrong-number` gains a `409
SCAN_TYPE_EXHAUSTED` — a code the controller already mapped and Stage 9 already routes to
`_goToScan()`. No wire or mobile change was needed; verified against
`stage9_screen.dart:273`.

Its refusal sits **before** `supersedeActiveCycleIfAny`: a call that declines to spend
anything must not take the customer's verified identity cycle with it.

---

## Design decisions worth the record

**The consume point is attempt-spend, not success.** This was the load-bearing trap.
`submitScan`'s session-equality check sits *above* the accepted-snapshot short-circuit, so
BL-034's upload retry re-presents the same `(sessionId, nonce, jws)` triple. Consuming on
acceptance turns a lost acknowledgement into `INVALID_SCAN_SESSION` — the exact failure BL-034
was filed to remove.

**Cap before the block-lift.** Checked after the lift, a capped profile whose block had just
expired would be resumed (`blocked_scan -> in_progress`, one history row, counters zeroed) and
immediately re-blocked (a second row) — two rows for a round trip the customer never made.

**Two copies of `LIFETIME_TOKEN_CAP = 20`, not one shared constant.** The two features are
separate quarantine boundaries (CLAUDE.md, Architecture), they block independently, and either
could be retuned alone. Each Javadoc cross-references the other. The duplication is the cost;
the alternative is a shared class that belongs to neither feature.

**`TOTAL_LIMIT` derived, not written.** A total left at 6 against a per-type of 5 gives the
customer one try on their second document, making the promised switch a token gesture.
`ScanAttemptBudgetTest.totalLimitIsExactlyTwiceThePerTypeLimit` asserts the relationship, so a
future edit that re-writes it as a literal fails.

**The mint increments before the external Uqudo call, deliberately.** It over-counts on a
Uqudo outage and never under-counts, which for an abuse bound is the right direction to fail.
Moving it after the call would lose the count on a crash and partly reopen the hole. The
customer-facing edge is filed as BL-068 rather than papered over.

---

## Reviewer findings and dispositions

### Slice A — 2 SHOULD-FIX, 2 NOTE

| # | Finding | Disposition |
|---|---|---|
| 1 | **A lost ack after a REJECTED scan now loops the app on the upload-retry screen.** The session is consumed, so the retry gets a bare 400 `INVALID_SCAN_SESSION`, which `_retryUpload`'s `catch (_)` maps back to upload-retry. Before, it got a coded `SCAN_REJECTED` and moved to rescan. | **Confirmed against mobile source, filed as BL-064.** Not fixed: the server behaviour is correct (the attempt was spent), and the obvious shortcut — giving the exception a code — would violate BL-037's deliberate rule that *a code on a Stage 8 400 means an attempt was spent*. Needs a wire + mobile change, out of this session's scope. |
| 2 | **An existing AD-008 assertion was silently defanged.** `spendOneScanAttempt` issues a token then cancels; the cancel now consumes the session, so `budgetBefore` captured NULL handles and "clears no pending handle" compared NULL to NULL. | **Fixed.** Extracted `issueScanToken`, left a live session standing, and added `assertNotNull` preconditions so the assertion can fail again. |
| 3 | The new `typeExhausted` branch is a fourth instance of BL-040 (early return precedes the `scan_jws_rejected` append). | **BL-040 row updated** to name the fourth branch. Race-only, no state escapes. |
| 4 | customer.md's new "the exemptions below" pointed at nothing. | **Fixed** — the four exempt outcomes are now listed inline. |

One consequence of finding 1 was a **falsified Javadoc**: `IdentityScanController.run()` claimed
its bare 400s are "what a correct client cannot provoke against a backend it is in step with".
BL-039 made that false. Corrected in place, with the reason and the BL-064 pointer.

### Slice B — 2 BLOCKER, 1 SHOULD-FIX, 2 CONSIDER

| # | Finding | Disposition |
|---|---|---|
| 1 | **BLOCKER — the cap applied a block from non-`in_progress` statuses.** V0020 defines no transition into `blocked_scan` from `blocked_liveness`/`abandoned`, nor into `blocked_liveness` from `blocked_scan`/`abandoned`; `issueFaceSessionToken` guarded only terminality. `applyScanBlock`/`applyLivenessBlock` hard-code `'in_progress'` as their from-status, so either would raise 23514 — a 500 where the customer should see a 409. BL-043's defect through a new door. | **Fixed.** Both block-applying branches now gate on `"in_progress".equals(state.status())` and otherwise refuse without writing. Verified the claim against V0005's status set and V0020's transition table before acting. |
| 2 | **BLOCKER — a profile that had already PASSED could be blocked by the cap, permanently.** The cap branch ran above the `facePassed` guard; the resulting block never lifts, because the already-blocked arm returns the stored deadline before any expiry check, while `SubmissionService.submit` requires `in_progress` and `journeyPointer` routes the customer to SUBMIT. | **Fixed.** `facePassed` moved above both new branches. |
| 3 | **SHOULD-FIX — no cap re-check in the liveness second transaction.** Phase 1 commits and releases the lock before `createFaceSession`, so N concurrent callers at `cap - 1` all pass and all increment. The scan side has no equivalent hole: its check, mint and pending-session write share one transaction under `FOR UPDATE OF p`. | **Fixed.** Cap re-checked in phase 2, carried by a boolean rather than its instant — the caller that crossed the cap may have taken the refuse-without-writing arm, leaving the deadline null, and a null-checked instant would fall through to a *successful* issuance. |
| 4 | **CONSIDER — a comment claimed `in_progress` with a spent liveness budget was unreachable.** `REACTIVATE_FROM_ABANDONED` moves `abandoned -> in_progress` without zeroing `liveness_attempts`. | **Comment corrected** (it was wrong), and the behaviour change it implies filed as **BL-067**. The block is the way *out* for that customer — `resumeFromLivenessBlock` zeroes the counter — whereas refusing without writing would strand them permanently, which is why the branch still writes. |
| 5 | **CONSIDER — the mint is counted before `issueAccessToken` runs.** | **Filed as BL-068** with the reasoning above for why the placement stands. |
| 6 | **NOTE — no plan-file update or session report in the staged commit.** | **Fixed** — this report, the BL-039 closure and the four new rows all land with Slice B. |

### One correction of my own

My first fix for BLOCKER 2 moved `noAcceptedCycle` above the block handling as well, which
changed two unrelated error precedences and broke
`issueFaceSessionTokenStillBlockedIsRejected` (a blocked, cycle-less profile started answering
`NO_ACCEPTED_CYCLE` instead of `LIVENESS_BLOCKED`). Corrected by splitting the block handling
instead: the **live-block refusal keeps its original position**, and only the **lift** moves
below the cap — which is all the cap-before-lift rule actually requires. `noAcceptedCycle`
went back where it was.

---

## Proofs

**Revert-restore — the BL-034 consume point.** Required: identical happy path, so a
consume-on-success defect is invisible without proving the test fails against it. Probe:
`consumePendingScanSession` added to the accept transaction beside `insertAcceptedCycle`.

```
[ERROR] IdentityScanIntegrationTest.identicalJwsReuploadReturnsTheSameCycleInsteadOfCollidingOnTheJti:191
        the customer's own retry must not 409 ==> expected: <200> but was: <400>
```

Probe removed; `grep -c "REVERT-RESTORE PROBE"` → 0. Note the mocked sibling test passed
against the probe — it short-circuits before `insertAcceptedCycle` — so the **integration test
is the real guard**, which is why the column assertion was added there too.

**Revert-restore — the `!resuming` guard.** Required: `liveness_attempts` is zeroed only in
the second transaction, so without the guard every legitimate post-block resume is refused.
Probe: `!resuming &&` dropped.

```
[ERROR] LivenessServiceTest.issueFaceSessionTokenResumesAfterBlockExpires:120
        LivenessTemporarilyBlocked liveness checking is temporarily blocked until 2026-09-01T12:00:00Z
```

That deadline is `NOW + 24h` — the customer is re-blocked on the request that should have
resumed them. Restored.

**Direct assertions, no revert needed**, for everything else: the 5/10 constants and the 2×
relationship (wrong-value assertions that cannot pass against the old numbers); the single-use
consume (`INVALID_SCAN_SESSION` where a 200 stood); the refused wrong-number (cycle still
`active`, counters unmoved); the cap-before-lift ordering (history-row count and deadline both
asserted against their pre-call values — the wrong ordering moves both); the two
non-`in_progress` BLOCKER guards (`applyScanBlock`/`applyLivenessBlock` never called).

---

## Gates

Backend only — no mobile or backoffice file was touched, so neither tier's gate applies.

### Slice A — `./mvnw clean verify -Pdb-integration-test`

```
[INFO] Results:
[INFO]
[INFO] Tests run: 994, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 443 files clean - 0 needs changes to be clean, 443 were already clean, 0 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 324 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  03:28 min
```

Intermediate runs: one failure at first pass (`wrongNumberDuringALiveBlock…` drove the block
with six cancels; retuned to ten), one Spotless failure (Javadoc rewrap), both fixed.

### Slice B — `./mvnw clean verify -Pdb-integration-test`

```
[INFO] Results:
[INFO]
[INFO] Tests run: 1016, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 443 files clean - 0 needs changes to be clean, 443 were already clean, 0 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 324 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:20 min
```

Intermediate run after the reviewer fixes: 1 failure
(`issueFaceSessionTokenStillBlockedIsRejected`, my own over-reordering — see the correction
above), fixed.

**Environment note.** `mvnw clean` first failed because a leftover packaged backend from an
earlier session (PID 7412, started 10:35) held `target/backend-0.0.1-SNAPSHOT.jar`. Stopped
with the user's approval; it is a live-proof artefact, not part of this work.

---

## What this session did NOT do

- **CLAUDE.md untouched**, as instructed. The edits it now needs are listed below.
- **BL-063** (token reuse) — untouched; still blocked on OQ-026 and the contract alone.
- **BL-036** (idempotency) — noted, not built. The billing position gives it a cost
  justification on top of the correctness one it already had.
- **No mobile changes.** The `SCAN_TYPE_EXHAUSTED` route was confirmed to map already; the one
  stale comment this creates is BL-065.
- A concurrent session committed `a7faba2` (AD-002d hosting) mid-run. Its `PROJECT_PLAN.md`
  and `BACKLOG.md` (BL-066) changes were deliberately kept out of both of these commits.

## CLAUDE.md — for the planner, not applied here

1. **Scan attempt numbers**: 3 per type / 6 total → **5 / 10**, wherever CLAUDE.md or the plan
   files restate them.
2. **The lifetime token cap** — 20 scan tokens and 20 face tokens per profile, never reset, a
   bound no current description mentions.
3. **The single-use token model** — any statement that a pending scan session is reusable, and
   V0040's rationale, are now wrong; V0064 carries the current truth.

## Commits

| SHA | What |
|---|---|
| `a041a19` | Slice A — one token, one attempt |
| `959fa07` | Slice B — the lifetime token cap |

(`959fa07` carries this report, so its own SHA is recorded by the fixup commit that follows
it — the only third commit in this session, and it changes nothing but these two lines.)
