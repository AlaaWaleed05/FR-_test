# The eight-way 409 collapse in `IdentityScanController`

**Status:** investigation only — informs a decision, does not settle one.
**Date:** 2026-09-04
**Scope:** `IdentityScanController.java:135-143`; `docs/journeys/customer.md` stages 8, 9, 13.
**Not done:** no source file edited, no plan file edited, nothing committed.

---

## Part 1 — Plain English, for the decision

### What's going on

When the phone asks the backend to start a document scan or upload a scan result, eight
different things can go wrong. The backend knows exactly which one happened — it throws
eight different, well-named exceptions. But the controller catches all eight and answers
with a bare `409 Conflict`. The explanatory text it attaches is **thrown away before the
response leaves the server** (Spring's default). So the phone receives a status number and
nothing else.

Meanwhile `customer.md` says the customer should see five *different* screens depending on
which one it was: a 24-hour countdown, an offer to try their other document, a "scan again"
prompt, a "we're paused, your progress is safe" screen, or an exit out of the journey
entirely.

The phone cannot tell these apart. Not "with difficulty" — it has literally zero
information to work with.

### The good news, and the one piece of bad news

**Good news:** no mobile screen consumes this yet. S5-07 hasn't started. Nothing is broken
for a real customer *because of the missing codes*, and fixing it now is cheap and additive.

**Bad news:** one of the eight is a genuine bug today, independent of any UI work. If a
customer's scan upload succeeds but the confirmation gets lost on a bad connection, the app
is supposed to retry the same upload. The backend currently treats that retry as a duplicate
and rejects it. The customer is told their scan failed and rescans — burning a real Uqudo
operation and one of their limited attempts, for a scan that already worked. Stage 10
(liveness) already fixed this exact problem; stage 8 never got the same fix.

### The work, in three separable pieces

1. **Put a code on the wire** — one `@ExceptionHandler` per case, returning `409` with a
   `code` field. Small, additive, nothing to migrate.
2. **Two cases need more than a code.** "You've used up your ID card attempts" is useless to
   the UI unless it also says whether the passport still has attempts left — that number is
   readable at the throw site but currently discarded. And "no active review" currently means
   three different things, one of which is the pause screen.
3. **Fix the dropped-ack retry** — a real defect, worth doing whether or not you do 1 and 2.

### Options

| | What it means | Cost |
|---|---|---|
| **A — Do nothing now** | S5-07 discovers this on day one and either stalls or works around it by guessing state on the phone | The workaround is the dangerous part: the app inferring attempt counts locally is exactly what the server-side budget exists to prevent, and it'd be spread across five screens before anyone notices |
| **B — Small slice before S5-07** | Pieces 1 + 2 as one backend task; fix piece 3 as a separate bug | Small. No consumer to migrate |
| **C — Fold it into S5-07** | Backend and mobile in one slice | Bigger slice, and the wire contract gets designed under UI deadline pressure |
| **D — Redesign the endpoint** | Make `/scan-result` return `200` with an outcome field instead of errors, matching how OTP already works | Arguably the better long-term shape, but it turns the response into a union type and drags the idempotency fix in with it |

### Recommendation — Option B, in this order

1. **Fix the dropped-ack retry first, as a bug.** It doesn't depend on any of this, it's a
   defect today, and the fix pattern already exists in `LivenessService` — copy it across.
2. **Then a small backend slice for the error contract**: `409` + a `code` field via
   `ProblemDetail`, plus `blockedUntil` and the other-document-type availability. Follow
   `AccountCheckController` — the one place in this backend already proven to get a field
   into an error body.
3. **Don't settle the 200-vs-409 question now.** It's a real design question, but larger than
   the problem in front of you, and it should be decided with S5-07's screens on the table.

**Verify first:** a single `curl` against a running instance to confirm what Spring Boot 4.1
actually puts in a `409` body. The evidence below is a live capture from this backend on this
version, which is solid — but it's a week old and it costs one command to be certain.

---

## Part 2 — The evidence

### Headline finding

**Over the wire, today, all eight are indistinguishable — not "hard to tell apart", literally
identical.** `ResponseStatusException`'s `reason` string never reaches the client: this
backend sets no `server.error.include-message` and has no `@ControllerAdvice` anywhere, so the
reason is discarded by `DefaultErrorAttributes`. The client sees `409` and the path, nothing
else.

Proof is a live capture from this backend, same construction
(`ContactChannelsController.java:71`), recorded at
`docs/sessions/2026-08-30-s3-07-profile-existence.md:270-273`:

```
HTTP/1.1 409
{"timestamp":"2026-08-29T22:11:00.374Z","status":409,"error":"Conflict","path":"/api/v1/contact-channels"}
```

Server-side at the throw sites the picture is better but not complete. Six of the eight carry
enough to build their screen once a code reaches the wire. **Two do not, and need a code
change beyond adding an error code.**

### Per-exception table

| # | Exception (throw sites) | customer.md requires | Distinguishable today? | Recommendation |
|---|---|---|---|---|
| 1 | `ProfileNotEditableException` — `IdentityScanService.java:231,276,487,552,645`; class carries message only | Stage 13 l.1140-1145: *"the backend answers **complete**. The journey terminates and local state is cleared."* — leave this flow | **No.** Class invisible on wire. Server-side `state.status()` is in scope at `:276`/`:552` but at `:231`/`:487`/`:645` lives inside a transaction lambda; only a `boolean[]` escapes | Own signal `PROFILE_TERMINAL`. Status value not needed — stage 13 routes this through `/account-check`, which already answers `continuation=TERMINAL` |
| 2 | `ScanTemporarilyBlockedException` — `:242,290,651`; **carries typed `Instant blockedUntil`** (`ScanTemporarilyBlockedException.java:12,19`) | Stage 8 l.680-685: *"**24 hours**"*; stage 13 l.1137-1138: *"the backend answers blocked until X and the customer sees that"* — countdown | **No on wire. Yes at throw site** — the only one of the eight with a typed payload field. Only the controller discards it | Own signal **plus a data field**: `SCAN_BLOCKED` + `blockedUntil`. Precedent exists twice: `WrongNumberResponse.blockedUntil`, `AccountCheckResponse.blockedUntil` |
| 3 | `ScanTypeExhaustedException` — `:246` (`issueToken` only); constructor takes the type but **retains no field**, interpolates into the message and loses it | Stage 8 l.675-677: *"once exhausted, the customer may still switch to the other document type with a fresh per-type budget"* | **No on wire. Partially at throw site.** The requested type is re-derivable — but **whether the *other* type still has budget is nowhere.** `state.attemptsFor(otherType)` readable at `:187-190`, confined to the lambda | Own signal **+ a code change**: exception needs `exhaustedType` / `otherTypeAttemptsRemaining` fields, `issueToken` must capture them out of the lambda. Without it, "try your passport instead" sends the customer into a second 409 |
| 4 | `ImagesUnavailableForAcceptanceException` — `:354` | Stage 13 l.1160-1162: *"the backend … reports **ARTIFACT_EXPIRED or IMAGES_UNAVAILABLE**"* — rescan prompt | **No on wire.** Nothing more needed server-side — the class fully determines the outcome | Own code, **shared screen with #5** |
| 5 | `ArtifactExpiredException` — rethrown `:326`, originates `UqudoJwsParser.java:300` | Same sentence — it is the *other* named code | **No on wire. Yes server-side by class** — the class exists precisely so callers can tell it from `JwsVerificationException` (its Javadoc records that collapsing them was a prior reviewer finding) | Own code, shared screen with #4 |
| 6 | `NoActiveRegistryReviewException` — `:684,730,761,808,846` | **Nothing** — customer.md prescribes no screen; reaching it means the client called an action the journey never offers | **Uniquely distinguishable — by endpoint, not class** (only 409 on the four `/registry-review/*`). But **three conditions share the class**: (a) no cycle, (b) cycle with `registryState != "ok"` — the *pending* case, (c) post-lock re-read. (a) and (b) not distinguishable today | `NO_ACTIVE_REVIEW`, grouped with #8 — **but (b) must be split out** as `REGISTRY_NOT_READY`: it is the pause case, not a defect |
| 7 | `RegistryReviewPendingException` — `:236,294,655` | Stage 9 l.747-757: *"**The session pauses. It does not fail.** … not an error and not a failure … no rescan, no new token"* | **No on wire.** Class alone suffices server-side — the pause screen carries no variable data | Own signal `REGISTRY_PENDING`. Note the same state is already reachable as a 200 via `ScanDisplayResponse.registryReady == false` |
| 8 | `JwsAlreadyAcceptedException` — `:481`, from `DuplicateKeyException` on `scan_result.uqudo_jti` UNIQUE (V0008) | Stage 13 l.1149-1151: *"The app retains it and retries the upload on reconnect rather than making the customer repeat the capture."* If the first upload landed and only the ack was lost, correct behaviour is **success — stage 9**, not an error | **No on wire, and not server-side either.** `insertAcceptedCycle` (`JdbcIdentityScanRepository.java:295-305`) always opens a **new** cycle over a plain INSERT, so an identical re-upload **always** collides. No read tells "my retry" from "a replay" | **Behaviour change, not an error code.** `LivenessService.java:405-409` + `LivenessRepository.currentFaceResultJti` already solved exactly this at stage 10; `IdentityScanService` has no equivalent |

### Collapse by endpoint

| Endpoint | Merged onto one indistinguishable 409 |
|---|---|
| `POST /identity-scan/token` | #1, #7, #2, #3 — **4** |
| `POST /identity-scan/scan-result` | #1, #2, #7, #5, #4, #8 — **6** |
| `POST /identity-scan/cancel` | #1, #2, #7 — **3** |
| `POST /identity-scan/registry-review/*` | #6 only — **1** (three internal conditions still merged) |

---

## Part 3 — Which groupings are safe

Test applied: two cases may merge only if customer.md would have the customer **see and do
the same thing** either way — not merely because the code throws them from a similar place.

### SAFE

**#4 + #5 — same screen.** Both mean "this capture can no longer be used; scan again", both
exempt from the retry budget (`:352-353`, `:313-316`). **But emit two codes** — customer.md
l.1162 names both explicitly. One screen, two codes; the second costs nothing and the journey
document asks for it.

**#6(a),(c) + #8's genuine-replay half.** customer.md prescribes no screen for either; both
mean the client's view of state disagrees with the backend's, and the right action for both is
re-running stage 13 resume. Safe **only after** the two splits below.

### NOT SAFE — each needs its own signal

**#1 terminal**, **#2 24h block**, **#3 type-exhausted**, **#7 registry-pending.** Four
screens, four next actions (leave the journey / wait out a countdown / switch document type /
wait with progress intact). Two tempting-but-wrong merges:

- **#2 and #3 are not the same**, despite both coming from the retry budget a few lines apart.
  l.675-677 gives the exhausted-type customer a way forward *right now*; l.680-685 gives the
  blocked customer a 24-hour wait. `ScanTypeExhaustedException`'s own Javadoc says so.
- **#1 and #7 are not the same.** Both are "you cannot scan now", but one ends the journey and
  the other explicitly does not: *"The session pauses. It does not fail."*

### Cannot be distinguished without a code change

**Pair A — `NoActiveRegistryReviewException`'s "no cycle" vs "registry not ready".**
Same class at `:846` vs `:730`/`:761`/`:808`; only the message differs, and messages do not
reach the client. The second is the pause case; the first is a client defect.
**Fix:** a field carrying `context.registryState()`, already a local at all three sites.
One field, three constructor call sites.

**Pair B — `JwsAlreadyAcceptedException`'s own-retry vs replay.**
Not distinguishable at all today, in either direction, because `submitScan` never reads what
jti the profile's current cycle already holds.
**Fix:** read the active cycle's `uqudo_jti` before insert; on equality return the existing
cycle's display payload as a 200 instead of throwing. The `currentFaceResultJti` pattern,
transplanted. This is a behaviour change, not an error code, and it should be settled before
S5-07 builds a client that retries uploads.

**Third, smaller:** #3 needs fields, not just a code — a wire code alone cannot tell the screen
whether the other document type has budget.

---

## Part 4 — Recommended shape

Follow `AccountCheckController.java:74-81` — a per-feature `@ExceptionHandler` returning an
explicitly constructed `ProblemDetail` with a `code` extension property.

Why this and not the alternatives:

1. **It is the only shape in this backend demonstrated to put a field on the wire.**
   `ResponseStatusException`'s reason demonstrably does not. Anything relying on the reason
   string would be a contract that looks right in a MockMvc test — `status().reason(...)`
   reads the servlet error message, which *is* set — and is empty in production.
2. **It keeps the status stable at 409**, so the existing mobile mapper's `case 409:` shape
   still works and the app opts into detail rather than being broken by it.
3. **It is additive.** No consumer exists yet (S5-07 is not started).

```java
// identityscan/web/IdentityScanController.java — replaces the eight-way catch
@ExceptionHandler(ScanTemporarilyBlockedException.class)
ResponseEntity<ProblemDetail> blocked(ScanTemporarilyBlockedException e) {
  ProblemDetail p = ProblemDetail.forStatusAndDetail(
      HttpStatus.CONFLICT, "scanning is temporarily unavailable");
  p.setProperty("code", "SCAN_BLOCKED");
  p.setProperty("blockedUntil", e.blockedUntil().toString());   // already a typed field
  return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
}
```

### The closed code set

Arabic copy stays in the app; the wire carries a code, never a message.

| `code` | From | Screen |
|---|---|---|
| `PROFILE_TERMINAL` | #1 | Journey over — re-run the launch check |
| `SCAN_BLOCKED` + `blockedUntil` | #2 | 24h countdown, "try later or visit a branch" |
| `SCAN_TYPE_EXHAUSTED` + `otherDocumentTypeAvailable` | #3 | Offer the other document type (back to stage 7) |
| `ARTIFACT_EXPIRED` | #5 | "This capture can no longer be used — scan again" |
| `IMAGES_UNAVAILABLE` | #4 | Same screen as `ARTIFACT_EXPIRED` |
| `REGISTRY_PENDING` | #7 | Pause screen, progress intact |
| `REGISTRY_NOT_READY` | #6(b) | Pause screen (client raced its own retry) |
| `STATE_CONFLICT` | #6(a),(c) + #8-replay | Re-sync via stage 13 resume |

**Hard rule the sketch must carry:** the response body never contains `jti`, a Uqudo image id,
an access token, or the national number (AD-002a). Only the code and the two scalar fields.

### Alternatives considered

- **Distinct HTTP statuses** (e.g. 429 for the block, mirroring
  `ContactChannelsController.java:72-75`, which the mobile mapper already consumes). Viable and
  precedented; worth taking for `SCAN_BLOCKED` alone if a code field were rejected. Not
  recommended as the whole answer: seven codes do not map onto seven meaningful statuses
  without inventing semantics, and it still gives the client no `blockedUntil`.
- **200-with-outcome**, matching `OtpVerificationController.java:36-42` and the AD-002a sketch.
  Arguably the better long-term fit for `/scan-result` — "the capture failed" is a journey
  outcome, not a transport error, and the OTP endpoint's documented reasoning applies almost
  verbatim. Not recommended *here* because it turns `ScanDisplayResponse` into a union type and
  would have to be decided alongside #8's idempotency, not before it.

**Condition under which this flips:** if the product owner decides `/scan-result` should report
all non-acceptance as a 200 outcome, the codes above become `reason` values inside a 200 body
for that endpoint, and `ProblemDetail` survives only on `/token` and `/cancel`. That is a
wire-contract decision for whoever scopes S5-07. **This report does not settle it.**

---

## Part 5 — Supporting evidence

- **[OBSERVED] The 409 body carries no message.** Live capture, same construction, quoted above:
  `docs/sessions/2026-08-30-s3-07-profile-existence.md:270-273`. Matching 400 capture at
  `docs/sessions/2026-08-29-s3-06-stage-1b.md:307`.
- **[OBSERVED]** No `server.error.include-message` anywhere in `backend/` (grep, zero hits);
  `application.properties` does not set it. Spring Boot's default is `never`.
- **[OBSERVED]** No `@ControllerAdvice` / `ResponseEntityExceptionHandler` anywhere in
  `backend/src` (main or test). Nothing converts `ResponseStatusException` into a `ProblemDetail`.
- **[OBSERVED]** Spring Boot **4.1.0** (`backend/pom.xml:7-8`), unchanged since S1-01 — so the
  2026-08-29 capture was taken on the *current* framework version.
- **[OBSERVED] The mobile client maps by status code only, never by body:**
  `mobile/lib/core/entry/dio_entry_api.dart:185-199`
  (`case 409: return const ProfileAlreadyCompleteException();`) and
  `mobile/lib/core/dataentry/dio_data_entry_api.dart:162-165`.
- **[OBSERVED] No mobile identity-scan client exists yet** (grep for `identity-scan|IdentityScan`
  across `mobile/` returns nothing). The consumer is `EXECUTION_PLAN.md:83`, S5-07, not started.
  Nothing has to be un-shipped.
- **[OBSERVED] The tests cannot catch this.** `IdentityScanControllerTest.java:80,94,155,185` and
  `IdentityScanIntegrationTest.java:425,456,476` assert `status().isConflict()` only. No test
  asserts any error body field, so every one passes against the fully-collapsed behaviour.
- **[OBSERVED] The project already uses a distinct status for a distinct customer state:**
  `ContactChannelsController.java:72-75` returns 429 for `SessionTemporarilyBlockedException`
  while returning 409 for `ProfileAlreadyCompleteException`; the mobile mapper consumes exactly
  that distinction.
- **[OBSERVED] The only proven way to get a field into an error body here** is
  `AccountCheckController.java:74-81`.
- **[OBSERVED] The 200-with-outcome precedent, with its rationale written down:**
  `OtpVerificationController.java:36-42` — *"200 always, carrying the outcome as a response field
  — WRONG_CODE/EXPIRED/CHANNEL_LOCKED are expected states Stage 2's UI renders inline, not HTTP
  errors"*; and `AccountCheckController.java:36-41` — *"Not-found is a business outcome of the
  journey, not a transport error."*
- **[DOC] AD-002a already sketched this contract and it was never built:**
  `docs/sessions/2026-08-21-research-ad-002a-uqudo-integration.md:265` (409 with
  `{"reason":"BLOCKED","blockedUntil":"…"}`), `:289-297` (200 with
  `{"outcome":"rejected","reason":"ARTIFACT_EXPIRED","countsAgainstBudget":false,...}`),
  `:313` (`state — ready | pending_registry | unavailable`), `:562` (*"Both failure reasons
  produce the same customer-facing message"*). All marked `[Proposed]`; none reached the code.
- **[OBSERVED] Nothing in the plan files tracks this gap.** `BACKLOG.md` has no row (grep for
  `409`, `errorCode`, `IdentityScanController`, `ARTIFACT_EXPIRED`, `IMAGES_UNAVAILABLE`: zero
  hits). `RISKS.md` has none. `EXECUTION_PLAN.md:83` describes S5-07 as *"backed by the existing
  S3-12 backend endpoints"* with no note that the error contract is missing. **Untracked.**

---

## Part 6 — Risks

| If the recommendation is not taken | Cost |
|---|---|
| S5-07 builds five customer.md screens against a signal that cannot select between them | The likely fallback is the app inferring state from a *local* counter — precisely what `ScanAttemptBudget`'s server-side placement exists to prevent, and what AD-002a called out (*"attemptsRemaining is informational. The backend, not the app, enforces the budgets"*). Expensive to unwind because the wrong behaviour would be spread across five screens |
| #8's legitimate dropped-ack retry keeps surfacing as a conflict | A customer whose upload ack was lost is told their scan failed, and rescans — burning a real Uqudo operation and a real budget draw for a scan that already succeeded. A **live behaviour defect today**, not a presentation gap, and the same defect S3-13 already fixed on the liveness side |
| The recommended shape is taken and later replaced by 200-with-outcome | Cheap to reverse *now* (no consumer exists), expensive after S5-07 ships. The strongest argument for settling the shape **before** S5-07 rather than during it |
| `blockedUntil` is exposed | Already assessed and accepted for the identical phone-lock case at S4-06: a timestamp only, reachable only by a customer who already drove the budget to exhaustion. The reasoning transfers; it should be restated, not assumed |

**Reversal cost of the recommended shape: low.** Additive, no consumer, and the eight `catch`
clauses it replaces are already enumerated in one place.

---

## Part 7 — Could not determine from source

- **Whether Spring Boot 4.1.0 renders `ResponseStatusException` as RFC 9457 `ProblemDetail` by
  default** rather than via the `/error` path. `spring.mvc.problemdetails.enabled` is not set,
  and 4.1.0 is past the researcher's knowledge cutoff. The claim rests on the **live capture
  from this backend on this Boot version**. If that behaviour has since changed, the "invisible
  on the wire" finding weakens for the `detail` field — **but not for the eight-way collapse**,
  because all eight would then share one generic `detail` derived from eight different free-text
  messages, still not machine-readable. Confirm with one `curl` before acting.
- **Whether `status='blocked_scan'` with `scan_blocked_until IS NULL` is reachable.**
  `applyScanBlock` always sets it and `resumeFromScanBlock` nulls it in the same `UPDATE`
  (`JdbcIdentityScanRepository.java:59-73`), so probably not — but not every writer of
  `app.profile.status` was audited. Matters because of the note in Part 8.
- **What Arabic copy each screen should carry.** Not in scope, and not in customer.md at that
  granularity.
- **Whether `SCAN_BLOCKED` should also surface at the launch check.** Stage 13 l.1137-1138 says
  the block should be answered at re-entry, but `AccountCheckService` reads only
  `currentPhoneLockUntil` (BL-021/S4-06), never `scan_blocked_until`. Flagged as adjacent, not
  resolved.

---

## Part 8 — Noticed in passing (not part of this question)

1. **The four `/registry-review/*` endpoints never check terminality.** `requireActiveContext`
   (`IdentityScanService.java:838-848`) reads `lockAndGetScanState` but discards the result
   except for existence; `acceptRegistryReview`, `reportWrongNumber`, `reportWrongDetails` and
   `retryRegistryLookup` have no `state.terminal()` guard, unlike `issueToken`, `submitScan`
   and `cancelScan`. Whether a terminal profile is otherwise fenced out (via
   `currentActiveRegistryContext` returning empty) was not traced to a conclusion.
2. **A defensive branch in `issueToken` does not do what it reads like.** At `:180`,
   `state.scanBlockedUntil() == null` sets `stillBlockedUntil[0] = null` and returns from the
   lambda; the check at `:239` is `!= null`, so that path falls through every rejection test and
   reaches `uqudoClient.issueAccessToken()` at `:252`, returning a `TokenIssuance` with null
   `sessionId`/`nonce`. Believed unreachable (see Part 7), so latent not live — but the guard as
   written cannot enforce what its comment claims.

---

## Part 9 — Component card

**No `docs/components/identity-scan.md` exists** (`docs/components/` holds ten cards; none
covers identity scan). Not an SDK investigation, so no full card drafted — but the wire-error
contract for stages 8-9 has no home today, which is part of why the gap went untracked. If a
card is created it needs at minimum the endpoint-collapse table and the code table above.

`docs/components/uqudo-sdk.md` is the nearest existing card and does own the
`ARTIFACT_EXPIRED` / `IMAGES_UNAVAILABLE` design rule. Two lines belong there:

> - [OBSERVED 2026-09-04] The `ARTIFACT_EXPIRED` / `IMAGES_UNAVAILABLE` distinction that
>   customer.md stage 13 requires the backend to *report* (l.1162) exists server-side as two
>   exception types but **does not reach the client**: `IdentityScanController:135-143` maps
>   both onto a bare 409, and this backend's `ResponseStatusException` reason is not rendered in
>   the response body. The design rule is enforced for the *budget* (neither counts) but not for
>   the *customer message*.
> - [OBSERVED 2026-09-04] At stage 8, unlike stage 10, a re-uploaded identical enrolment JWS
>   always produces `JwsAlreadyAcceptedException`: `insertAcceptedCycle` opens a new cycle per
>   call and `INSERT_SCAN_RESULT` is a plain insert, so the `scan_result.uqudo_jti` UNIQUE always
>   fires. `LivenessService` avoids this with `currentFaceResultJti`; `IdentityScanService` has
>   no equivalent.
