# 2026-09-05 — Identity-scan defect sweep (investigation only)

One deliberate read of the whole identity-scan feature, so S5-07 builds against a settled
surface. **No code was changed, no plan file was edited, and no BL/R item was filed.** No
gates apply — nothing was touched that a gate measures.

**What was read:** `identityscan/{web,service,jdbc,domain}` in full (2,946 lines);
`uqudo/domain/UqudoClient` and `uqudo/http/HttpUqudoClient`'s image/purge paths as
identity-scan uses them; V0005, V0008, V0020, V0023, V0026, V0039, V0040, V0053, V0054,
V0055, V0062; `customer.md` stages 7–13 plus the "Resuming without local state" flow that
stage 13's no-local-state case points back to; the three cross-feature readers of
`app.identity_cycle` (`operator`, `liveness`, `submission`); BL-035, BL-036, BL-038,
BL-039, BL-040 and R-046/R-051/R-052.

Six real findings. None duplicates an open item; two are adjacent to closed ones and the
relationship is stated. The rest of the feature is sound, and §2 says exactly what that
covers.

---

## 1. Findings

| # | Defect | Harm | Triage | Existing item? |
|---|---|---|---|---|
| F-1 | A device-less re-entry inherits the previous device's accepted identity cycle, face result and signature. `customer.md` forbids it by name. | Someone re-entering an account on a new device can submit a profile carrying another person's scan, face match and signature. | **FIX BEFORE PRODUCTION** | New |
| F-2 | Stage 9's `accept` / `wrong-number` / `wrong-details` have no terminality guard. S5-11 gave one to `retry` and not to its three siblings. | An unauthenticated caller with a profile UUID supersedes a completed profile's identity cycle and moves its budget columns; the other two answer `500`. | **FIX BEFORE PRODUCTION** | New (BL-038 covered `retry` only) |
| F-3 | The operator profile view and list pick the profile's **latest** cycle by `seq`, ignoring `identity_cycle.state`. A failed attempt after a good scan inserts an `abandoned` cycle that wins. | The operator sees a blank identity/registry/face section for a profile that holds verified data — permanently, for a `blocked_scan` profile. | **FIX BEFORE PRODUCTION** | New |
| F-4 | `reportWrongNumber` does not re-check `blocked_scan`/`awaiting_registry`, which `recordFailedAttempt` explicitly does. | A live 24-hour block is extended by another 24, and a `profile_status_history` row is written for a transition that never happened. | **FIX BEFORE PRODUCTION** | New (distinct mechanism from BL-039) |
| F-5 | Two concurrent Stage 9 retries: the loser overwrites the winner's verified registry record, or hits `artifact_ref`'s `UNIQUE (cycle_id, kind)` as an unmapped `500`. | BL-038's defect (1) survives in its race form — a verified Civil Registry record blanked in the columns the back office reads. | **FIX BEFORE PRODUCTION** | New (BL-038 closed the sequential case) |
| F-6 | `customer.md` l.667-668 — "repeated verification failure ... must surface to operators" — is unimplemented. | A systemic verification breakage drives every customer to a 24-hour block with nothing on any operator screen. | **FIX BEFORE PRODUCTION** | New |

Nothing here blocks the demo. That is a real conclusion, not a hedge: every one of the six
needs either a second concurrent request, a client that walks off the app's own path, or an
operator looking at a screen the demo will not linger on. What they have in common is that
they are all writes or reads against *completed or blocked* profiles — the states a demo
passes through last and production lives in.

---

### F-1 — A device-less re-entry inherits the identity artifacts

**What customer.md requires.** "System-derived identity artifacts are not inherited"
(l.162-174, restated by stage 13's *no local state* resume case): the scan result, the
extracted document data, the Civil Registry data and the liveness result are **not** carried
into a device-less resume; "the resume point is capped at identity type, and the customer
rescans"; the prior artifacts "are retained in the backend, marked **superseded**". The
document states its own reasoning: OTP at 1b proves control of a handset the customer typed
in at that moment, and nothing more, so it "is not sufficient to hand over a half-finished
profile belonging to someone else."

**What the code does.** Nothing. `ContactChannelsService`'s re-entry transaction
(`ContactChannelsService.java:240-289` — verify the line, the method is the `reentry` branch
inside `executeWithoutResult`) refreshes contact details, channel states, the branch code and
the OTP challenges. It does not call `supersedeActiveCycleIfAny`, does not clear
`app.face_result`, does not clear the `signature` artifact, and does not reset the scan or
liveness budgets. `app.profile.resume_stage` — the column V0005 declares as "last completed
journey stage" — is **written by no code in this repository**; the only two hits outside
V0005 are a comment in `JdbcProfileRepository` and one in V0020. So there is no cap on the
resume point either.

**Consequence, followed through to the gate that matters.**
`JdbcSubmissionRepository.STATE_QUERY_BASE` (`:22-31`) gates submission on
`identity_cycle state='active' AND accepted_at IS NOT NULL` joined to `face_result.passed`,
plus an existing `signature` artifact. All three survive a re-entry untouched. So the
sequence is: person B enters account A's number at 1a, receives OTPs on a number B typed in,
reviews and edits the customer-entered data, and submits — against A's document scan, A's
face match and A's signature. That is precisely the case the journey's "why this flow exists"
section says the rule closes.

**Whose defect it is.** The *write* belongs in the re-entry path, not in `identityscan`. The
*invariant* is identity-scan's, which is why a read of this feature is where it surfaces, and
it cannot be left to the app: an anti-fraud rule enforced only client-side is the same
mistake `ScanAttemptBudget`'s server-side placement and AD-002a exist to prevent. Flagged
here rather than dropped for being one package over.

**Triage: FIX BEFORE PRODUCTION.** Not demo-blocking — the demo will not reinstall
mid-journey. It is the most serious thing in this report, and it is the only finding whose
fix is not a few lines: it needs a decision about *which* artifacts are superseded and
whether the budgets reset, which touches AD-territory. Raise it, do not bolt it on.

---

### F-2 — Stage 9's three actions have no terminality guard

**What customer.md requires.** Stage 13: "**Returning to a submitted, approved or rejected
profile** — stage 1a returns the current status. There is no re-entry in any of these
states." Every sibling path in this feature already refuses a terminal profile —
`issueToken` (`IdentityScanService.java:168`), `submitScan` (`:277`), `recordFailedAttempt`
(`:631`), `currentReviewPayload` (`:758`), and `retryRegistryLookup` since S5-11 (`:783`).

**What the code does.** `requireActiveReview` (`:996-1006`) reads the profile's `ScanState`
*and* its `ActiveRegistryContext` and returns both — S5-11 widened it precisely so the
terminality flag would stop being discarded. Only `retryRegistryLookup` reads `.state()`.
`acceptRegistryReview` (`:879`), `reportWrongNumber` (`:904`) and `reportWrongDetails`
(`:951`) each take `.context()` and drop the state on the floor. The precondition all three
require is still satisfied on a finished journey: `markCycleAccepted` does not change
`identity_cycle.state`, so a submitted or approved profile still has an `active` cycle whose
`registry_result.state` is `ok`.

**What V0020 does and does not stop.** `app.check_status_transition()` fires only on a status
*change* (`IF v_old IS NOT DISTINCT FROM NEW.status THEN RETURN NEW`). So:

- `reportWrongDetails` on a `submitted` profile: `submitted → terminated_registry_mismatch`
  is in no `app.status_transition` row, so the trigger raises `23514`, the transaction rolls
  back and the customer gets an **unmapped 500** where every sibling returns a
  `PROFILE_TERMINAL` 409. Nothing is corrupted; the error contract S5-07 is being built
  against is wrong.
- `acceptRegistryReview` on an approved profile: **commits**. It rewrites
  `identity_cycle.accepted_at` to now — on a cycle the operator approved earlier — and
  appends a `registry_review_accepted` event attributed to the customer on a finished
  journey. Evidence falsified, quietly.
- `reportWrongNumber` on a submitted or approved profile: **commits**.
  `supersedeActiveCycleIfAny` and `applyScanAttempt` change no status, so no trigger fires.
  The completed profile's identity cycle is marked `superseded` and its scan-attempt counters
  move. If the increment happens to reach 6, `applyScanBlock` then attempts
  `submitted → blocked_scan`, raises `23514`, and the whole thing rolls back as a 500
  instead — so the outcome depends on a counter, which is worse than either branch alone.

**Harm.** The endpoint surface is unauthenticated by design (R-051), so a profile UUID is the
only thing in front of this. Superseding is not cosmetic: `state='active' AND accepted_at IS
NOT NULL` is exactly what `JdbcLivenessRepository` and `JdbcSubmissionRepository` gate on, so
for a submitted-not-yet-approved profile the link between the profile and its verified scan
is gone.

**Triage: FIX BEFORE PRODUCTION.** The fix is three lines — `review.state().terminal()` in
the three siblings, the shape BL-038 already established next door — and there is no test for
any of the three cases (`IdentityScanServiceTest` has `retryOnATerminalProfileIsRejected...`
and no `accept`/`wrongNumber`/`wrongDetails` equivalent).

---

### F-3 — The operator's "latest cycle" is not the current cycle

**What customer.md requires.** Stage 9 and operator.md have the operator reviewing the
system-derived identity data. The schema models which cycle that is: `active` /`superseded`
/`abandoned`, with a partial unique index (`identity_one_active`, V0008) enforcing one active
row.

**What the code does.** `JdbcProfileViewRepository` (`:67-70`) and
`JdbcProfileListRepository` (`:71-74`) both resolve the cycle with
`SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ? ORDER BY seq DESC LIMIT 1` —
**no state filter** — and then LEFT JOIN `scan_result`, `face_result` and `registry_result`
onto it. Meanwhile `recordFailedAttempt` inserts an `abandoned` cycle for *every* countable
failure (`IdentityScanService.java:686`), at the next `seq`.

**The reachable case.** A cancel or a JWS rejection that happens *after* a good scan gives
the profile `seq N = active/accepted` and `seq N+1 = abandoned`. The operator queries then
resolve to `N+1`, which has no `scan_result`, no `registry_result` and no `face_result` row —
so every identity, registry and face column in both the list and the detail view comes back
null for a profile that demonstrably holds verified data. For a profile that ended in
`blocked_scan` after an earlier successful scan, that state is not transient: it persists
until the customer scans successfully again, which is exactly the profile an operator is most
likely to be looking at when a customer calls.

**Triage: FIX BEFORE PRODUCTION.** One `AND state <> 'abandoned'` (or `= 'active'`, if the
intent is strictly the current cycle) in two queries, plus a decision about whether a
superseded-only profile should show its last real scan. Worth settling deliberately rather
than by whoever edits the query first: "latest" and "current" have diverged, and it is the
back office reading it.

---

### F-4 — `reportWrongNumber` can extend a live block and falsify a history row

**What customer.md requires.** Stage 9's "the national number is wrong" "counts against the
stage 8 retry budget" — one attempt, from `in_progress`. Stage 12's governing rule for the
status model: "There are no silent state changes."

**What the code does.** `recordFailedAttempt` refuses to touch the budget columns from
`blocked_scan` or `awaiting_registry` under the lock (`:641-648`), with a comment recording
exactly why (an illegal V0020 pair, and a budget mutation on a blocked profile).
`reportWrongNumber` does not go through it — it calls `applyScanAttempt` and `applyScanBlock`
directly (`:939-942`) with no status re-check at all beyond what `requireActiveReview` read.

**The reachable case.** From Stage 9 (in_progress, active `ok` cycle) the customer goes back
to Stage 8, requests a token and cancels the SDK; repeat until the total hits 6 and the
profile becomes `blocked_scan`. None of that touches the active `ok` cycle, and
`currentReviewPayload` serves the Stage 9 payload to a `blocked_scan` profile happily — it
checks only `terminal()` (`:758`) — so the app can land back on Stage 9. "The national number
is wrong" from there increments the budget past its own limits and, because
`state.scanAttemptsTotal()` is still 6, `blockTriggered(7)` is true, so `applyScanBlock` runs
again: `status` is already `blocked_scan`, V0020's trigger sees no change and passes,
`scan_blocked_until` is pushed out **another 24 hours**, and `insertHistory`
(`JdbcIdentityScanRepository.java:305-309`, from-status hard-coded `'in_progress'`) writes a
`profile_status_history` row claiming an `in_progress → blocked_scan` transition that never
occurred. The deferred `profile_status_requires_history` trigger does not catch it either,
for the same reason: there was no status change to check.

**Harm.** A 48-hour block on a mandated update, and a falsified row in the very table the "no
silent state changes" rule exists to make trustworthy. Deterministic, not a race.

**Triage: FIX BEFORE PRODUCTION.** Distinct from BL-039 (which is `submitScan` never
consulting `canAttempt`); fixing BL-039 does not touch this path, which is why it is written
separately.

---

### F-5 — Concurrent Stage 9 retries overwrite a verified registry record

**What BL-038 closed, and what it left.** BL-038's defect (1) was that a retry against an
already-`ok` cycle re-queried the registry and wrote the outcome over the stored one, and
`updateRegistryResultOnRetry` writes *every* name/DOB/address column from `fields`, which
`queryRegistry` leaves null for `not_found`/`unreachable` — so one retry during an outage
blanked a verified record the back office reads. The fix is a short-circuit at
`IdentityScanService.java:798-800`.

**Why the race survives it.** That short-circuit reads the **pre-transaction** `context`,
taken before the unbounded registry call. Two retries that both start while the state is
not-`ok` both pass it and both call the registry. The row lock then serialises them, and the
second correctly re-reads `freshContext` under that lock (`:829-832`) — but
`updateRegistryResultOnRetry` at `:834` is called **unconditionally**. Only the status
transition at `:860` is guarded on the fresh state. So the loser writes its own outcome over
the winner's: if the registry flapped, the verified record is blanked exactly as BL-038
described. And if both come back `ok`, the second `insertArtifactRef('portrait_registry')`
(`:841-853`) violates `app.artifact_ref`'s `UNIQUE (cycle_id, kind)` (V0008) and raises a
`DuplicateKeyException` this method does not catch — an unmapped `500`, transaction rolled
back.

**Reachability.** The Stage 9 pause screen is the one place in the journey that invites
retrying. A double-tap, or a client retry after a lost acknowledgement, is enough.

**Triage: FIX BEFORE PRODUCTION.** The fix is to move the already-`ok` decision inside the
transaction, against `freshContext`, where the terminality guard already sits.

---

### F-6 — Repeated verification failure never reaches an operator

**What customer.md requires.** Stage 8, l.667-668: "Repeated verification failure is a system
fault, not a customer fault, and **must surface to operators rather than looping the
customer**."

**What the code does.** The customer-facing half is right: each rejection is audited as
`scan_jws_rejected` with the raw JWS as a purgeable artifact, and the customer gets
`SCAN_REJECTED` (BL-037). The operator half does not exist. `JdbcProfileViewRepository`
returns customer data, scan/face/registry results, artifacts and status history — and no
audit events; there is no read of `audit.audit_event` anywhere in `operator/`, no counter, no
alert. A systemic breakage — a JWKS or tenant change, R-034's remaining residual — would
drive every customer in the campaign into a 24-hour block, and the only thing visible to the
bank would be a rising count of `blocked_scan` statuses with no cause attached.

**Triage: FIX BEFORE PRODUCTION.** The weakest of the six as a *defect* and the most
consequential as an *operational* gap; it could be as small as surfacing the rejection count
on the profile view. Named here because it is a stated stage-8 requirement with nothing
behind it, and it is tracked nowhere.

---

## 2. What was checked and found sound

Stated so the reassurance has an explicit scope. Each of these was read against customer.md
and deliberately not filed.

- **The stage-8 ordering rule.** `verifyAndParse` → download every image → registry lookup →
  one transaction → `purgeSession` strictly after commit. Correct, and correct for the stated
  reason: a verified JWS whose images are gone writes nothing.
- **Budget exemptions.** `ARTIFACT_EXPIRED` and `IMAGES_UNAVAILABLE` are not counted; an
  image checksum mismatch is. Matches stage 8's own text ("a connectivity failure, not a scan
  failure") and uqudo-sdk.md's "a mismatch is a hard failure".
- **`ScanAttemptBudget`'s reconciliation** of stage 8's two policy statements (3-per-type
  forces a switch, 6-total triggers the block) is sound because `TOTAL_LIMIT == 2 ×
  PER_TYPE_LIMIT`, and `resumeFromScanBlock` resetting all three counters is what makes "try
  later" mean something (V0039's own reasoning).
- **PII placement.** No national number, name or field value reaches `payload_json` anywhere
  in this feature — checked event by event. The number lives in `app.scan_result` and in the
  purgeable `civil_registry_request` artifact body; `scan_accepted` carries `documentType` and
  `jti` only; the registry events carry classification and byte counts only.
- **The narrow `DuplicateKeyException` catch** in `submitScan`, deliberately not
  `DataIntegrityViolationException` — a rejected V0020 transition would otherwise be
  mis-reported as "this JWS was already accepted".
- **Lock ordering.** All four Stage 9 writers re-lock `app.profile` as the first statement of
  their own transaction, before any audit-chain write. Verified in each of the four.
- **The image endpoint.** Allowlist not denylist, one `404` for every kind of absence,
  `no-store`, bytes read only through `app.artifact_read()`'s checksum verification. The
  residual exposure is R-051's, unchanged, and not re-filed here.
- **The Uqudo boundary as identity-scan uses it.** `identityNumber` absent fails closed as a
  `JwsVerificationException`, so the `NOT NULL` on `scan_result.identity_number` cannot be
  reached; `purgeSession` never throws, so a purge failure cannot turn an accepted scan into a
  500; `downloadImage` funnels transport failures, 4xx and 5xx alike into
  `ImageUnavailableException`, which is a documented trade-off (a defined journey outcome and
  an audit event, rather than an opaque 500) and not a defect.
- **BL-034's short-circuit** — same `jti`, `ok` cycle, answer from storage, no second
  download, lookup, cycle, event, purge or attempt draw — is correctly scoped, and its
  registry-`ok` condition is asserted rather than implied.
- **`submitScan`'s unlocked pre-check.** Deliberate and documented; the real guards re-check
  under the lock, and the pre-check exists only to avoid spending a Uqudo operation.

Already-filed items were re-read and are **not** re-filed: BL-035 (the retry into a pause),
BL-036 (no idempotency key on the two budget-spending endpoints), BL-039 (`submitScan` never
consults `canAttempt`), BL-040 (a rejection that loses a race leaves no `scan_jws_rejected`),
R-046, R-051, R-052. F-4 sits next to BL-039 and F-5 next to BL-038; both relationships are
stated in place.

---

## 3. Recommended fix ordering

Nothing is marked FIX BEFORE DEMO, so this is the order for the pre-production queue rather
than a demo blocker list. It is ordered by cost-to-fix within severity, so the cheap
guard-shaped ones can land in one slice ahead of S5-07 and stop the screens being built
against the wrong error contract.

1. **F-2** (three-line terminality guard, plus the three missing tests). Cheapest, and it
   changes what S5-07's Stage 9 screens see on a finished profile — `PROFILE_TERMINAL`
   instead of a 500 or a silent success.
2. **F-4** (the same shape: re-check `blocked_scan`/`awaiting_registry` in
   `reportWrongNumber`). Same slice as F-2 — one guard-parity pass over Stage 9's four
   writers closes both, and both need a revert-restore proof since a wrong guard can pass a
   happy-path test.
3. **F-5** (move the already-`ok` decision inside the transaction). Same file, same slice, but
   listed after because it needs a concurrency test to prove, not just a state test.
4. **F-3** (two operator queries). Independent of the above and of S5-07; belongs with
   whoever next touches the back office, with the "latest vs current cycle" question settled
   deliberately.
5. **F-6** (surface repeated verification failures). Operational; sequence it with the next
   back-office slice, alongside F-3.
6. **F-1** (device-less re-entry). Last by ordering, first by severity — deliberately, because
   it is the only one that is not a guard. It needs a decision on which artifacts are
   superseded, whether the budgets reset, and where the resume-point cap lives, and CLAUDE.md
   forbids settling that in passing. Bring it as its own item.

Findings only. Nothing filed, nothing fixed, no plan file touched — for triage first, as
instructed.
