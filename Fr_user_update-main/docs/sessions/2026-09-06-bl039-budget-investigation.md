# BL-039 — what the scan-attempt budget actually counts

**Date:** 2026-09-06. **Type:** investigation only. **Code changed:** none. **Backlog filed:** none.

**Gates: not run, and not applicable — this session changed no code.** Nothing was compiled, no
test was executed, and the only file added is this report.

Scope: establish the real model behind the Stage 8 retry budget before any fix is designed. BL-039
as filed assumes the budget should count SUBMISSIONS; the product owner's model is that it should
count TOKENS (a token = a session, retries inside one free). Both readings were held open and
checked against source. Uqudo's own token/session semantics were consulted at the user's request
and turn out to decide part of the question.

---

## F-1 — What the per-type budget counts today

**One increment point, and only one.** `JdbcIdentityScanRepository.applyScanAttempt`
([JdbcIdentityScanRepository.java:296-302](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L296-L302))
picks one of two statements
([:61-75](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L61-L75)),
each of which raises the per-type column **and** `scan_attempts_total` in the same UPDATE. There is
no other writer of those three columns except the two resets (F-6, and AD-008 below).

**Two callers:**

| Caller | Line | Reached from |
|---|---|---|
| `IdentityScanService.recordFailedAttempt` | [:682](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L682) | `submitScan`'s `JwsVerificationException` ([:328](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L328)), `submitScan`'s `ImageIntegrityException` ([:408](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L408)), `cancelScan` ([:608](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L608)) |
| `IdentityScanService.reportWrongNumber` | [:1073](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1073) | Stage 9 "the national number is wrong" |

**Where it is NOT incremented:**

- **Token issuance.** `issueToken`
  ([:146-257](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L146-L257))
  writes an audit event, `recordPendingSession` and `touchLastActivity`. No counter.
- **Successful submission.** The accept transaction
  ([:419-517](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L419-L517))
  never touches the budget columns. A scan that lands is free.
- **The two deliberate exemptions.** `ArtifactExpiredException`
  ([:316-326](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L316-L326))
  and `ImageUnavailableException`
  ([:384-403](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L384-L403))
  are audited without a counter increment, each with a comment citing customer.md.

**So the unit is a countable FAILURE** — a cancelled SDK session, a rejected JWS, a document-image
checksum mismatch, or a Stage 9 wrong-number report. Not a token, not a submission, not a session.

## F-2 — What one token is allowed to do

`issueToken` mints `sessionId`/`nonce`
([:196-197](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L196-L197))
and persists them with `recordPendingSession`
([:220](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L220)).
The SQL is a plain overwrite
([JdbcIdentityScanRepository.java:55-59](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L55-L59)),
so only the most recently issued pair is live.

**The pending session is reusable, not one-shot.** There is no `clearPendingSession` anywhere on the
identity-scan path — the only statement that nulls those columns is AD-008's reset. V0040 says so on
purpose: *"deliberately never cleared after use"*
([V0040__app_scan_pending_session.sql:12-15](backend/src/main/resources/db/migration/V0040__app_scan_pending_session.sql#L12-L15)).
`submitScan`'s only session gate is an equality test
([:304-309](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L304-L309)),
which the same pair satisfies indefinitely.

**One token can therefore back unlimited submissions.** There is a bound on *accepted* ones and none
on *failed* ones:

- Accepted: the parser requires enrolment `jti == sessionId` (uqudo-sdk.md
  [:381-384](docs/components/uqudo-sdk.md#L381-L384), OBSERVED S1-02) and `app.scan_result.uqudo_jti`
  is UNIQUE (V0008), caught as `DuplicateKeyException`
  ([:518-531](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L518-L531)).
  **At most one accepted scan per session.**
- Failed: a JWS that fails verification never reaches the jti check. Each distinct bad JWS posted
  under the same session costs one budget unit. **No bound.**

For a well-behaved client the ratio is nevertheless 1:1, but **only by client convention**: `runScan`
requests a fresh token on every launch
([identity_scan_repository.dart:90-129](mobile/lib/core/identityscan/identity_scan_repository.dart#L90-L129))
and `_upload` clears the retained capture on `ScanRejectedException`
([:160-164](mobile/lib/core/identityscan/identity_scan_repository.dart#L160-L164)), so the app never
re-posts a rejected JWS. Nothing server-side enforces either habit.

The counter is not even coupled to tokens in the tests:
`exhaustingBothDocumentTypesTriggersTheTwentyFourHourBlock`
([IdentityScanIntegrationTest.java:718-756](backend/src/test/java/sd/gov/bank/fruserupdate/identityscan/IdentityScanIntegrationTest.java#L718-L756))
drives six full attempts and a 24-hour block through `/cancel` alone, **with no token ever issued**.

## F-3 — Where the per-type check is applied, and where it is not

**Present, in exactly one place:** `issueToken`
([:190-194](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L190-L194)).
BL-039 cites "line ~191" — **confirmed**, the block is 190-194.

**Absent everywhere else:**

- `submitScan`
  ([:261-560](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L261-L560))
  never names `canAttempt`. Its four guards are terminal, `blocked_scan`, `awaiting_registry` and
  session equality
  ([:277](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L277),
  [:287](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L287),
  [:295](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L295),
  [:304](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L304)).
  **BL-039 confirmed as filed.**
- `recordFailedAttempt`
  ([:618-711](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L618-L711))
  computes `newForType` at
  [:650](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L650)
  and writes it into the audit payload, but tests only `blockTriggered(newTotal)`
  ([:652](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L652)).
  The per-type limit is never consulted under the lock.
- **`reportWrongNumber`**
  ([:1017-1080](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1017-L1080))
  spends an attempt at
  [:1073](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1073)
  with no per-type check either. **This extends BL-039** — a second budget-spending path the ticket
  does not name. Whatever unit is chosen, this call site has to come under it.
- `cancelScan` inherits the gap through `recordFailedAttempt`, which is how the integration test
  above reaches six attempts without a token.

**The structural fault, stated once:** `canAttempt` gates the operation that does *not* count, and no
gate stands in front of the operations that *do*.

## F-4 — How the document-switch fallback fires

`ScanTypeExhaustedException` is thrown from **one line**:
[:249](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L249),
on the flag set at
[:192](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L192).
The chain is: `state.attemptsFor(appDocumentType)`
([ScanState.java:20-24](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanState.java#L20-L24))
≥ `PER_TYPE_LIMIT` = 3
([ScanAttemptBudget.java:17-25](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L17-L25))
→ 409 `SCAN_TYPE_EXHAUSTED`
([IdentityScanController.java:248-251](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/web/IdentityScanController.java#L248-L251))
→ [identity_scan_models.dart:42](mobile/lib/core/identityscan/identity_scan_models.dart#L42)
→ [stage8_screen.dart:263](mobile/lib/features/identityscan/stage8_screen.dart#L263)
(`_Stage8View.typeExhausted`), also
[stage9_screen.dart:273](mobile/lib/features/identityscan/stage9_screen.dart#L273).

**So the fallback fires only on a token request.** `resuming` forces the count to 0 after a block
expires
([:190](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L190)).

BL-039's route never reaches line 249: a customer holding a live session posts to `/scan-result`,
which has no path to that exception at all. `scan_attempts_national_id` climbs past 3 until
`scan_attempts_total` reaches 6 and `blockTriggered` fires
([:652](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L652)/[:688](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L688)),
with `scan_attempts_passport` still 0. **The customer is entitled to a passport attempt and is given
a 24-hour block instead. Confirmed as filed.**

## F-5 — What bounds real cost and abuse today

**Uqudo's own model** (docs/components/uqudo-sdk.md, the researched component card):

- **The access token is not a session.** `POST auth.uqudo.io/api/oauth/token`, `client_credentials`,
  `expires_in` 1800 (1859 observed at S1-02, [:575](docs/components/uqudo-sdk.md#L575)), and
  explicitly *"TENANT-scoped, not per-customer. No documented per-user or per-session scope"*
  ([:225-233](docs/components/uqudo-sdk.md#L225-L233)). It is a bearer credential. One token could
  back many enrolments.
- **The session is the enrolment.** Our backend-minted `setSessionId(uuid)` *is* the session id, and
  on an enrolment JWS `jti == sessionId` ([:241-243](docs/components/uqudo-sdk.md#L241-L243),
  [:381-384](docs/components/uqudo-sdk.md#L381-L384), OBSERVED S1-02
  [:547](docs/components/uqudo-sdk.md#L547)). Uqudo caches the session's data and images for the
  session's life and `DELETE /api/v1/info/{jti}` purges it. **A sessionId that never reaches the SDK
  creates nothing at Uqudo** — the session comes into being when the SDK actually enrols.
- **The device-facing token is minted fresh every call**, never cached
  ([:493-494](docs/components/uqudo-sdk.md#L493-L494)); confirmed in code at
  [HttpUqudoClient.java:93-95](backend/src/main/java/sd/gov/bank/fruserupdate/uqudo/http/HttpUqudoClient.java#L93-L95),
  which calls `mintToken()` unconditionally while the adapter's own server-to-server calls reuse a
  cached one
  ([:97-102](backend/src/main/java/sd/gov/bank/fruserupdate/uqudo/http/HttpUqudoClient.java#L97-L102)).

**Mapping the counters to that:**

- `POST /api/v1/identity-scan/token` → one real outbound `client_credentials` call per request, and a
  tenant-scoped bearer handed to a handset for ~1800 s, on FIB's borrowed tenant (R-001).
- The identity-bearing operation is the SDK enrolment session, which the backend cannot observe. Its
  only server-side proxy is a token issuance — the app requests exactly one immediately before each
  launch
  ([identity_scan_repository.dart:96-114](mobile/lib/core/identityscan/identity_scan_repository.dart#L96-L114)).
- `scan_attempts_total` counts **the same events** as the per-type counters — same UPDATE
  ([JdbcIdentityScanRepository.java:61-75](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L61-L75)) —
  so it is a total of self-reported failures, not of tokens or submissions.

**The finding BL-039 does not make, and the strongest support for the product owner's model:**
`issueToken` is gated by a counter it never increments. On a fresh profile `attemptsFor(type)` is 0
and stays 0 until a failure is *reported*, so a caller may request tokens **without limit**, mint
unlimited Uqudo tokens and overwrite the pending session each time, spending nothing. There is no
HTTP rate limit on this surface (the only rate limiting in the backend is the JWKS fetch limiter in
`UqudoClientConfiguration`/`UqudoJwksSignatureVerifier`), and `/api/v1/**` is `permitAll` by AD-002e
(R-051). **Nothing bounds real Uqudo token operations today.** The retry budget bounds only
self-reported failures.

## F-6 — What customer.md actually promises

The UX contract states its unit twice, and it is neither "token" nor "submission":

> **Customer cancels out of the SDK** → returns to preparation, and **counts as a failed attempt**. A
> launched SDK session consumes a real Uqudo operation whether or not a document was captured, so a
> cancel is not free. — [customer.md l.657-659](docs/journeys/customer.md#L657-L659)

> This screen carries real weight, because cancelling out of the scan counts as an attempt. It is the
> customer's only free opportunity to get set up before the retry budget starts being consumed
> — [l.621-624](docs/journeys/customer.md#L621-L624)

The exemptions are phrased in the same unit:

> **Token request fails, or the JWS upload fails** → a connectivity failure, not a scan failure, and
> **not** counted against the retry budget. — [l.661-664](docs/journeys/customer.md#L661-L664)

> **Backend rejects the JWS** — signature invalid or verification fails → counted as a failed
> attempt. — [l.666-667](docs/journeys/customer.md#L666-L667)

And the switch promise:

> **3** — once exhausted, the customer may still switch to the other document type with a fresh
> per-type budget. A passport is a genuinely different attempt, not a fourth try at a failing
> national ID. / **6** — bounds the whole stage regardless of switching.
> — [l.675-678](docs/journeys/customer.md#L675-L678); restated at
> [l.1198](docs/journeys/customer.md#L1198): *"Cancels count, so three is fewer real attempts than it
> appears — the preparation screen exists to make the first one land."*

**The unit customer.md requires is a launched SDK session.** Since a launch requires a token and the
app requests exactly one token per launch, "count launched sessions" is much closer to the product
owner's model than to BL-039's.

---

## The model source implements today

**Neither reading. Source counts self-reported failed outcomes, and enforces the limit only at token
issuance.** The unit of counting and the unit of enforcement are different objects, and that mismatch
is the whole defect. For a well-behaved client the two coincide — one token, one launch, at most one
counted failure — so the observable behaviour matches customer.md. Nothing on the server makes that
so; it is a property of the Flutter client (F-2).

Against the product owner's intent — partly right, partly not:

- **Right:** the check really is per-token-issuance, and non-counting retries inside one session
  really are free. The current shape is closer to "a token is a session" than BL-039 implies.
- **Wrong:** counting retries inside one session are **not** free. Each rejected JWS posted under the
  same session spends a unit
  ([:328](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L328)
  → [:682](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L682)).
  That is exactly BL-039's mechanism, and it is real.
- **Also wrong, in the other direction:** "issuing a token is a real Uqudo operation" holds for the
  OAuth call but that is not the KYC operation. The operation worth bounding is the enrolment session,
  which begins in the SDK. And today token issuance is bounded by nothing at all (F-5), so the cost
  the product owner wants protected is currently unprotected.

Against BL-039 as filed: **confirmed on its facts** (F-1, F-3, F-4), and **incomplete** — it misses
`reportWrongNumber` as a second unchecked spending path, and it misses that the endpoint making the
real Uqudo call is itself unbounded.

## Options

### (a) Count tokens — increment at `issueToken`, where `canAttempt` already sits

- **+** Aligns the budget with the real cost proxy and with customer.md's "launched session" unit.
- **+** Bounds `/token`, the only endpoint that makes an outbound Uqudo call — unbounded today.
- **+** Delivers the product owner's intent literally: retries within a session become free.
- **−** Charges for launches customer.md explicitly exempts. Three concrete regressions:
  camera-permission denied (mobile deliberately does not call `/cancel` —
  [stage8_screen.dart:141-146](mobile/lib/features/identityscan/stage8_screen.dart#L141-L146):
  *"Charging the budget for a device permission the customer can fix in Settings would burn a
  three-attempt budget on something that is not a scan"*), `IMAGES_UNAVAILABLE` and
  `ARTIFACT_EXPIRED`. Each forces a rescan → a new token → a charge l.661-664 forbids. A refund path
  is possible but reintroduces a decrement and its own races.
- **−** A token issued and never used is charged: a double-tap on "ابدأ المسح", or an app killed
  between token and launch, costs an attempt. That makes BL-036 (no idempotency key) load-bearing
  rather than merely desirable.

### (b) Count submissions — BL-039 as filed

Add `canAttempt` to `submitScan`, and under the lock to `recordFailedAttempt` and `reportWrongNumber`.

- **+** Smallest diff. Closes the fallback gap directly: the 4th bad post per type answers
  `SCAN_TYPE_EXHAUSTED` instead of silently spending toward the 24-hour block.
- **+** Preserves every exemption exactly as written.
- **−** Leaves the unit as "failure". One token can still absorb 3 charged posts per type, so a client
  that reuses a session gets 3 charges from 1 real Uqudo session — the over-charging the product owner
  objects to, capped rather than removed.
- **−** Does nothing about unbounded `/token`.

### (c) One-token-one-attempt — make the pending session single-use

- **+** Makes the implemented model coherent: 1 token = 1 session = at most 1 charge, enforced
  server-side instead of by client convention.
- **+** Preserves every exemption unchanged — the counter still increments only on countable failures,
  so camera-denied, `IMAGES_UNAVAILABLE`, `ARTIFACT_EXPIRED` and connectivity stay free.
- **+** Restores the fallback: a 4th attempt per type must come through `/token`, which raises
  `SCAN_TYPE_EXHAUSTED` at
  [:249](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L249).
- **−** Must not break BL-034. `retryUpload` re-posts the same sessionId/nonce/jws
  ([identity_scan_repository.dart:135-141](mobile/lib/core/identityscan/identity_scan_repository.dart#L135-L141)),
  and the accepted-snapshot short-circuit
  ([:350-375](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L350-L375))
  sits **after** the session-equality check at
  [:304-309](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L304-L309).
  So "single-use" must mean **consumed at the moment an attempt is spent**, not "consumed on first
  `submitScan`" — otherwise a lost acknowledgement on an accepted scan becomes `INVALID_SCAN_SESSION`.
- **−** Does not bound `/token` on its own.

## Recommendation

**(c), with (b)'s guard as a belt, and a separate decision on bounding `/token`.**

(c) is the only option that satisfies both halves of the question. It bounds the thing that costs — a
session can be charged once, and any further attempt must pass the gate at
[:190-194](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L190-L194) —
and it keeps the document-switch promise, because the 4th attempt per type necessarily goes through
`issueToken`. It is also the closest fit to what customer.md actually says: the document charges for
*a launched SDK session*, and (c) is what makes one token equal one session on the server rather than
by the client behaving well. Adding (b)'s `canAttempt` re-check inside `recordFailedAttempt` under the
lock costs a few lines and holds the invariant even if a session is somehow re-presented.

**What customer.md's wording actually requires:**

1. The unit is the launched session (l.658), so charging per post — (b) alone — contradicts it.
2. The exemptions at l.661-664 are explicit, so charging at issuance — (a) — contradicts them unless
   refunds are added.
3. l.675-677 requires that exhausting one type *offers the other*, so whatever the unit, it must be
   checked **before** the 4th one is allowed to run. All three options satisfy this; today's code does
   not.

(a) is not wrong about cost — it is right about cost and wrong about the exemptions. If `/token`
should be bounded, and F-5 argues it should, the cleaner route is a **separate** bound on token
issuance (a per-profile issued-token counter, or a rate limit) rather than repurposing the retry
budget, so the customer-facing "3 attempts" keeps meaning what customer.md says it means.

## Interaction with BL-041 / AD-008's budget reset

`JdbcDeviceLessReentrySuperseder.RESET_PER_TYPE_SCAN_BUDGET`
([:81-92](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcDeviceLessReentrySuperseder.java#L81-L92))
zeroes `scan_attempts_national_id`/`scan_attempts_passport` and nulls
`pending_scan_session_id`/`pending_scan_nonce`/`pending_face_session_id`, deliberately **not**
`scan_attempts_total` or `scan_blocked_until`
([:64-80](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcDeviceLessReentrySuperseder.java#L64-L80)) —
because R-051's unauthenticated `POST /api/v1/contact-channels` would otherwise clear the 24-hour
block on demand.

- **Under (c): already consistent, and mutually reinforcing.** Nulling the pending session *is* the
  consume semantics (c) generalises, and that statement's own comment already calls the handles
  *"single-use challenge material"*
  ([:74-76](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcDeviceLessReentrySuperseder.java#L74-L76)).
  (c) makes that description true everywhere rather than only here.
- **Under (b): consistent.** The counters keep meaning "failures" and the reset keeps meaning "a fresh
  per-type failure budget".
- **Under (a): needs restating.** Per-type counters would mean "tokens issued for this type", so the
  reset hands a re-entrant 3 fresh tokens per type per re-entry, and AD-008's justification — *"the
  total-based 24-hour block still keys on cumulative attempts across sessions, so abuse stays bounded
  despite the reset"* — must be re-expressed in the new unit or the abuse bound silently changes
  meaning on an unauthenticated endpoint.

## Also flagged, not filed

Per the session's terms no backlog item was created. Two things a fix session must pick up:

- **`reportWrongNumber` spends an attempt with no per-type check**
  ([:1073](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1073)).
  Same class of gap as BL-039 on a path BL-039 does not name. It is not a duplicate of BL-043, which
  fixed that method's *status* guards, not its budget guard.
- **BL-036 (no idempotency key) is coupled to the choice.** Under (a) a retried `/token` costs an
  attempt, which promotes BL-036 from desirable to required; under (b)/(c) it stays as filed.

## Decision needed before any code

Which unit the budget counts: (a) tokens, (b) submissions, or (c) one-token-one-attempt. This report
recommends (c) plus (b)'s guard. **No code was changed and no fix was filed** — the model comes back
for a decision first, as the session's terms require.

---

# Addendum — 2026-09-06: decision recorded, open facts verified, fix shape proposed

**Still investigation. No code changed, no fix filed. Gates not run — nothing was compiled.**

The model decision was taken on this report's recommendation: **option (c), one-token-one-attempt,
server-enforced, PLUS a separate bound on `/token`** — not pure token-counting (a), because (a)
would charge for the exempt cases F-5/F-6 identified. A product change rides along: **the per-type
limit moves 3 → 5.** This addendum verifies the facts that decision rests on, states the blast
radius of 3 → 5, and proposes a fix shape to approve.

Every claim below was checked against source. Two beliefs carried into this session turned out to
be wrong, and one turned out to be right; all three are marked.

## V-1 — Is there a timed reset of the per-type counters? **No. None. Never has been.**

The product owner believes attempts reset after 1 hour. **They do not, on any timer.**

- The whole application has exactly one `@Scheduled` method: `OutboxDispatchScheduler:70`
  ([OutboxDispatchScheduler.java:70](backend/src/main/java/sd/gov/bank/fruserupdate/notification/scheduler/OutboxDispatchScheduler.java#L70)),
  which drains the notification outbox. `@EnableScheduling`
  ([BackendApplication.java:13](backend/src/main/java/sd/gov/bank/fruserupdate/BackendApplication.java#L13))
  was added by S6-04 for that outbox and nothing else.
- No clock-based reset of `scan_attempts_*` exists anywhere. V-2 enumerates every writer of those
  columns; none is time-driven.

**Where the "1 hour" almost certainly comes from — a different budget entirely.** customer.md's
phone-channel lock is *"**15 minutes, escalating to 1 hour** on a repeat in the same session"*
([l.264](docs/journeys/customer.md#L264), [l.1192](docs/journeys/customer.md#L1192)), implemented as
`phone_lock_until`/`phone_lock_escalated` (V0037) in
[OtpVerificationService.java:211-212](backend/src/main/java/sd/gov/bank/fruserupdate/otpverification/service/OtpVerificationService.java#L211-L212)
and [OtpVerificationPolicy.java:34](backend/src/main/java/sd/gov/bank/fruserupdate/otpverification/domain/OtpVerificationPolicy.java#L34).
That is Stage 2's OTP wrong-code lock. The Stage 8 scan budget has no equivalent.

**Why this matters to the decision.** With no timed reset, a customer who burns a per-type budget
has exactly two exits: switch document type, or exhaust the total and wait out the 24-hour block.
Raising per-type 3 → 5 is therefore the only lever that gives a struggling customer more room.
**If the product owner genuinely wants a 1-hour per-type reset, that is a new product decision, not
part of this fix** — and it would need its own customer.md line and its own abuse assessment,
because a 1-hour reset on an unauthenticated endpoint (R-051) makes the per-type budget nearly
unbounded over a day.

## V-2 — What actually resets the per-type counters. **Exactly two paths.**

| Path | Statement | Resets | Fires when |
|---|---|---|---|
| 24-hour block expiry, **lazily** | `RESUME_FROM_SCAN_BLOCK` ([JdbcIdentityScanRepository.java:85-92](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L85-L92)) | **all three** counters, `scan_blocked_until` → NULL, status `blocked_scan` → `in_progress` | only inside `issueToken`'s `resuming` branch ([:182-188](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L182-L188), [:222-223](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L222-L223)) — the next token request after the deadline |
| AD-008 device-less re-entry | `RESET_PER_TYPE_SCAN_BUDGET` ([JdbcDeviceLessReentrySuperseder.java:81-92](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcDeviceLessReentrySuperseder.java#L81-L92)) | the **two per-type** counters + the pending scan/face handles. **Not** `scan_attempts_total`, **not** `scan_blocked_until` | inside the Stage 1b re-entry transaction, when an active identity cycle exists |

The only other writer is the increment itself (`APPLY_SCAN_ATTEMPT_*`,
[:61-75](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L61-L75)).
No database default, trigger or job touches them — V0039 declares them `smallint NOT NULL DEFAULT 0`
and nothing more. **A grep of all three column names across `backend/src/main` returns these three
writers and nothing else.**

## V-3 — 24-hour block mechanics. **Confirmed, including the lazy clear.**

- **Trigger:** `blockTriggered(newTotal)` = `newTotal >= TOTAL_LIMIT` (6) —
  ([ScanAttemptBudget.java:31-33](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L31-L33)).
  It keys on the **total**, never on a per-type counter. Evaluated at
  [:652](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L652)
  and [:1064](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1064).
- **Effect:** `applyScanBlock` → `APPLY_SCAN_BLOCK`
  ([:77-83](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java#L77-L83))
  sets `status='blocked_scan'` and `scan_blocked_until = now + SCAN_BLOCK_DURATION`, where that is
  `Duration.ofHours(24)`
  ([:113](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L113)),
  plus a `profile_status_history` row.
- **Clearing is lazy, and on one path only.** `issueToken:183` is the **only** line in the codebase
  that compares `scanBlockedUntil()` to `now`. Every other `blocked_scan` guard keys on the *status
  alone*: `submitScan`
  ([:287-293](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L287-L293)),
  `recordFailedAttempt`
  ([:641-643](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L641-L643)),
  `reportWrongNumber`
  ([:1047-1050](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1047-L1050)).
  So once the deadline passes, `/scan-result` and `/cancel` **still refuse** until a `/token`
  request performs the resume.
- **The planner's note is right, and this is already on the record as accepted:**
  [2026-09-05-stage9-guard-parity.md:209](docs/sessions/2026-09-05-stage9-guard-parity.md#L209) —
  *"the new `blocked_scan` refusal keys on status, not on the block still being live, so
  `blockedUntil` can be in the PAST ... ACCEPTED as correct-by-parity, not fixed. The reset is lazy
  — `issueToken` is the only path that clears it"*, carried into S5-07's row as "the Stage 9 screen
  must not assume `blockedUntil` is in the future".
- **Interaction with (c), and it is favourable:** (c) routes *more* traffic through `issueToken`
  (a fresh token per retry), so the lazy resume fires sooner and more reliably after a block
  expires. (c) mildly improves this behaviour; it does not worsen it.

## V-4 — Blast radius of per-type 3 → 5

**The constants themselves are clean.** `PER_TYPE_LIMIT` / `TOTAL_LIMIT`
([ScanAttemptBudget.java:17-18](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L17-L18))
are referenced in exactly two places, both inside that same class —
[canAttempt:24](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L24)
and [blockTriggered:32](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L32).

- **Is the document-switch fallback wired to the same constant? Yes — it fires at 5 automatically.**
  `SCAN_TYPE_EXHAUSTED` originates solely from `canAttempt` at
  [:191](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L191).
  **There is no second hard-coded 3 anywhere.**
- **No database constraint pins the values.** V0039 declares `smallint NOT NULL DEFAULT 0` only;
  `smallint` holds 5 and 10 comfortably.
- **No client states a number.** The only Arabic attempt-related strings in either client
  ([channel_verification_screen.dart:532](mobile/lib/features/entry/channel_verification_screen.dart#L532),
  [ProfileDetailPage.tsx:496](backoffice/src/profiles/ProfileDetailPage.tsx#L496)) belong to the
  phone-channel lock and the liveness budget and name no count. The Stage 8 exhausted screen is
  driven by the server's `SCAN_TYPE_EXHAUSTED` code, not a client-side tally — which is exactly what
  AD-002a's server-side placement was for.

**But the total is not independent — 3 → 5 forces 6 → 10.** The `TOTAL == 2 × PER_TYPE`
relationship is load-bearing in three places:

1. **The reasoning.** `ScanAttemptBudget`'s Javadoc
   ([:7-13](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L7-L13))
   reconciles customer.md's two apparently contradictory promises *by* that arithmetic: *"Because
   TOTAL_LIMIT equals exactly 2 * PER_TYPE_LIMIT, the total can only reach 6 once both per-type
   budgets are separately exhausted — so exhausting the first type alone only forces a switch"*.
2. **The test.** `exhaustingOneTypeAloneDoesNotTriggerTheBlock`
   ([ScanAttemptBudgetTest.java:37-44](backend/src/test/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudgetTest.java#L37-L44))
   asserts precisely that, restating the same reasoning in a comment.
3. **The customer's actual experience.** With per-type 5 and total still 6, a customer who exhausts
   `national_id` gets **one** passport attempt before the 24-hour block — which breaks customer.md
   l.675-677's promise of *"a fresh per-type budget"* on the second type. The document switch would
   become a token gesture.

**So `TOTAL_LIMIT` must move 6 → 10.** That is not a nice-to-have; it is what keeps l.675-678's two
sentences from contradicting each other.

**Also to change, easy to miss, and it goes first:** customer.md
[l.675](docs/journeys/customer.md#L675), [l.678](docs/journeys/customer.md#L678),
[l.1198](docs/journeys/customer.md#L1198) and [l.1199](docs/journeys/customer.md#L1199) all state
the numbers, and the code cites those lines as its source of truth.

**One judgement to surface, not to decide:** 5 per type across two types means **10** cancels or
failures before the 24-hour block, on an unauthenticated endpoint (R-051), each one costing a
hash-chained audit event and an abandoned `identity_cycle` row. That is a materially weaker abuse
bound than 6. The number is the product owner's to set — but it makes the separate `/token` bound
(P-4) more important, not less.

## V-5 — The cost tradeoff (surfaced, not decided)

**Is token issuance the billable Uqudo operation? UNKNOWN — and not assumable.**

- The repository holds **no Uqudo pricing of any kind**. uqudo-sdk.md
  [:13](docs/components/uqudo-sdk.md#L13) says only *"Requires a commercial licence and a tenant"*.
  Neither OpenAPI spec, the component card, nor PROJECT_PLAN states a per-operation price or names
  the metered unit. **This needs the tenant's billing terms** — and note we currently run on FIB's
  borrowed tenant (R-001), so the terms in force today are FIB's, not ours. The natural home for the
  question is OQ-010 (provisioning of our own tenant).
- **What source does establish** (F-5, restated): `/token` costs one `client_credentials` call
  ([HttpUqudoClient.java:93-95](backend/src/main/java/sd/gov/bank/fruserupdate/uqudo/http/HttpUqudoClient.java#L93-L95),
  minted fresh every time on the device path). That is an OAuth mint of a **tenant-scoped**
  credential (uqudo-sdk.md [:225-233](docs/components/uqudo-sdk.md#L225-L233)) — the same credential
  the adapter happily caches for its own server-to-server calls. **A tenant-scoped OAuth mint is not
  obviously a metered KYC operation**; the metered thing is far more likely the enrolment session
  Uqudo actually processes and stores. If that holds, **(c) costs nothing extra** — it adds token
  mints, not SDK launches, and the launches were happening anyway.
- **The real tension, plainly:** "reuse one token across retries" and "bound cost/abuse per token"
  are one dial turned opposite ways. The more submissions a token backs, the less a token bounds
  anything — that *is* the BL-039 defect. The product owner cannot have both a reusable token and a
  token-shaped bound.
- **The number that would decide it:** the price of one `POST /api/oauth/token`, if any, against the
  price of one enrolment session. If token mints are free or negligible — the expected answer for an
  auth call — the objection closes. If they are separately metered at a non-trivial rate, the cost
  of (c) is (extra retries per customer) × (token price), bounded above by **10** mints per customer
  at the proposed 5-per-type against a current floor of 1. **Ask Uqudo/FIB two questions: is `POST
  /api/oauth/token` metered, and what is the metered unit for an enrolment?** Until answered, treat
  the cost objection as *unquantified*, not refuted.

## Proposed fix shape — a PLAN to approve, not built code

Five parts, in this order. The sequencing is deliberate: the contract changes before the code that
cites it, and the model changes before the numbers.

**P-1 — customer.md first.** Update l.675, l.678, l.1198, l.1199 to **5** and **10**, and state the
unit explicitly ("a launched SDK session; one token per launch") so the code has an unambiguous
contract to cite. Everything below follows from this.

**P-2 — Make the pending session single-use. This is (c)'s core.** Consume it at exactly the moment
an attempt is spent: inside `recordFailedAttempt`'s transaction beside `applyScanAttempt`
([:682](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L682))
and inside `reportWrongNumber`'s
([:1073](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1073)) —
nulling `pending_scan_session_id`/`pending_scan_nonce`, either via a new repository method or by
extending the existing UPDATE.

- **Deliberately NOT on the accept path, and not on the non-counting rejections.** This is the trap
  flagged in the main report: `retryUpload` re-posts the same triple
  ([identity_scan_repository.dart:135-141](mobile/lib/core/identityscan/identity_scan_repository.dart#L135-L141))
  and the accepted-snapshot short-circuit
  ([:350-375](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L350-L375))
  sits **after** the session check
  ([:304-309](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L304-L309)).
  Clearing on success turns a lost acknowledgement into `INVALID_SCAN_SESSION` and regresses BL-034.
- Exemptions survive by construction: `ARTIFACT_EXPIRED` and `IMAGES_UNAVAILABLE` neither count nor
  clear, so the retained JWS stays replayable exactly as today.
- **V0040's comment must be superseded.** It currently documents the opposite rule — *"deliberately
  never cleared after use"* — and would otherwise read as a contradiction of the new behaviour.

**P-3 — The `canAttempt` belt, and `reportWrongNumber` brought under the model.** Re-check
`canAttempt(state.attemptsFor(type))` **under the lock** in `recordFailedAttempt` (after the three
existing status checks, before
[:650](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L650))
and in `reportWrongNumber` (same position, before
[:1063](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java#L1063)),
throwing `ScanTypeExhaustedException` so the customer reaches the document-switch screen instead of
silently over-spending. This is what closes BL-039 even if a session is somehow re-presented.

- **Wire consequence to confirm:** `POST /registry-review/wrong-number` gains a 409
  `SCAN_TYPE_EXHAUSTED` it cannot return today.
  [stage9_screen.dart:273](mobile/lib/features/identityscan/stage9_screen.dart#L273) already maps
  that code, so the client is ready — but the screen's copy should be reviewed for that route.

**P-4 — The separate `/token` bound. This is the only genuinely NEW policy here.** Do **not** charge
the retry budget. Add an issued-token counter (a new `scan_tokens_issued` column, incremented inside
`issueToken`'s existing transaction) with its own ceiling, sized well above the retry budget so an
honest customer never meets it — at 5-per-type the honest ceiling is 10 launches, so something like
20 leaves real headroom while ending unbounded minting. Exceeding it is an abuse signal, not a
customer-facing budget: a distinct refusal code, audited.

- Needs the product owner's number and its own customer.md line, and should reset on the same paths
  that reset the total (V-2).
- Alternative worth pricing separately: an account/IP rate limit instead of a counter. Out of scope
  here — R-048 already tracks the general absence of rate limiting, and AD-002d owns the auth
  surface.

**P-5 — The numbers.** `PER_TYPE_LIMIT` 3 → 5 and `TOTAL_LIMIT` 6 → 10
([ScanAttemptBudget.java:17-18](backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudget.java#L17-L18)),
the class Javadoc's arithmetic restated, and `ScanAttemptBudgetTest` updated throughout
([:15-44](backend/src/test/java/sd/gov/bank/fruserupdate/identityscan/domain/ScanAttemptBudgetTest.java#L15-L44)) —
including `exhaustingOneTypeAloneDoesNotTriggerTheBlock`, whose entire point is the 2× relationship.
**Consider deriving `TOTAL_LIMIT = 2 * PER_TYPE_LIMIT` in code** so the invariant cannot drift; the
Javadoc already treats the two as one fact.

### What the fix must NOT do — regression list for the reviewer

Charge for a successful scan · charge for camera-permission-denied · charge for `ARTIFACT_EXPIRED`
or `IMAGES_UNAVAILABLE` · break BL-034's upload retry · disturb AD-008's deliberate exclusion of
`scan_attempts_total`/`scan_blocked_until` from its reset · re-introduce the hard-coded
`insertHistory` from-status defect BL-043 fixed.

### Still open, and needs the product owner

1. **The `/token` ceiling number** (P-4).
2. **Whether a timed per-type reset is actually wanted** (V-1) — the "1 hour" belief traces to the
   phone-channel lock, so there may be nothing to do; if one *is* wanted, it is a new decision with
   its own abuse assessment.
3. **The Uqudo billing answer** (V-5) — the one input that would settle the cost objection to (c).

**No code was changed and no backlog item was filed.** This addendum is the plan to approve.

---

# Addendum 2 — 2026-09-06: Uqudo bills per token mint

**Still investigation. No code changed, no fix filed. Gates not run — nothing was compiled.**

**Input, product owner, 2026-09-06: Uqudo's cost is per new token.** This answers V-5's open
question. Recorded as product-owner-supplied — it is not derivable from the repository, and the exact
metered unit should still be confirmed against the tenant contract before anything is built on it.

It does **not** change the recommendation. It answers the cost objection to (c), re-prioritises the
fix shape, and turns one earlier finding from a hardening item into a live financial exposure.

## C-1 — The cost objection to (c) is answered by source: **(c) is cost-neutral**

The concern was that (c) mints a fresh token per retry rather than reusing one token across tries.
**The app already mints a fresh token per retry today.**

- `runScan` calls `issueToken` on **every** invocation
  ([identity_scan_repository.dart:90-96](mobile/lib/core/identityscan/identity_scan_repository.dart#L90-L96)),
  and every "try again" path on the Stage 8 screen goes through `_startScan` → `runScan`
  ([stage8_screen.dart:349,375,403,415,447](mobile/lib/features/identityscan/stage8_screen.dart#L349)).
- `retryUpload` mints **nothing** — it re-posts the retained JWS
  ([identity_scan_repository.dart:135-141](mobile/lib/core/identityscan/identity_scan_repository.dart#L135-L141)).

So the token count per customer under (c) is **identical to today's**. "Reuse one token across
tries" describes a behaviour the client does not currently have; there is nothing to lose by
forbidding it server-side.

**A related clarification worth having on the record:** under per-token pricing, the BL-039 abuse
never cost *us* money. An attacker posting six bad JWS under one token paid for one token and
consumed six of the customer's budget units. The damage is to the customer's budget and to the
document-switch promise — not to the bill. Per-token pricing therefore does not weaken the case for
fixing BL-039; it clarifies that BL-039 is a UX and integrity defect, and that the *cost* problem is
somewhere else entirely.

## C-2 — Where the money actually leaks, ranked

### 1. Unbounded `/token` — now a direct financial exposure, and it outranks BL-039 on urgency

F-5 established that `issueToken` is gated by a counter it never increments, on an endpoint that is
unauthenticated by design (R-051), with no rate limit anywhere. Under per-token pricing that is
**unmetered spend**: request a token, never use it, repeat. Nothing increments, nothing blocks, no
budget is touched, no status changes. On FIB's borrowed tenant (R-001) the bill lands on FIB.

The same hole exists in liveness — its issuance transaction
([LivenessService.java:204-243](backend/src/main/java/sd/gov/bank/fruserupdate/liveness/service/LivenessService.java#L204-L243))
increments nothing either.

**This promotes P-4 from hardening to the highest-value item in the whole investigation.**

### 2. Liveness never checks its budget at all, and each request costs **two** Uqudo operations

`LivenessAttemptBudget.canAttempt`
([:20-22](backend/src/main/java/sd/gov/bank/fruserupdate/liveness/domain/LivenessAttemptBudget.java#L20-L22))
is called from **no production code**. A grep across `backend/src/main` returns only its own
definition; the only callers are its unit test. Its Javadoc states the intent that was never wired
up: *"Whether a new attempt (token issuance) may start"*.

A liveness token request costs `createFaceSession` — a real `POST /api/v1/face` image upload
([:200](backend/src/main/java/sd/gov/bank/fruserupdate/liveness/service/LivenessService.java#L200)) —
**plus** `issueAccessToken`
([:256](backend/src/main/java/sd/gov/bank/fruserupdate/liveness/service/LivenessService.java#L256)).

To be precise rather than alarming: liveness is not unbounded *after* failures — `blockTriggered` at
5 still drives `blocked_liveness`, and the status guard
([:221-223](backend/src/main/java/sd/gov/bank/fruserupdate/liveness/service/LivenessService.java#L221-L223))
refuses from then on. The hole is *before* any failure: unlimited minting of unused tokens, exactly
as in scan.

**This is a new finding, outside BL-039's scope. Flagged, not filed, per this session's terms — it
warrants its own backlog item.**

### 3. Fresh mint per device call — a per-launch multiplier that may be avoidable

`HttpUqudoClient` deliberately never serves the device-facing path from cache
([:82-95](backend/src/main/java/sd/gov/bank/fruserupdate/uqudo/http/HttpUqudoClient.java#L82-L95)),
for a real reason: *"a device could receive a token with seconds left and fail the scan in a way
that looks like the app breaking."*

But the token is **tenant-scoped** and lives ~1800 s (uqudo-sdk.md
[:225-233](docs/components/uqudo-sdk.md#L225-L233)), and the adapter already owns the machinery that
answers the staleness objection — `AccessToken.isDueForRenewal()` with `INTERNAL_TOKEN_HEADROOM`
([:134-138](backend/src/main/java/sd/gov/bank/fruserupdate/uqudo/http/HttpUqudoClient.java#L134-L138)) —
which is precisely a "never hand out a token with less than N remaining" guard. A headroom-guarded
shared token would keep the staleness property *and* collapse the mint count from one-per-launch to
one-per-window across all customers.

**Do not act on this yet.** Two things must be confirmed, and neither can be confirmed from the
repository:

1. **What Uqudo actually meters** — the product owner's statement says new tokens, which is enough
   to make this worth pricing, but the exact metered unit should come from the contract.
2. **Whether one token may serve multiple end customers at all.** Our own component card instructs
   the opposite — *"Mint at point of use, never persist to disk, drop after the call"*
   (uqudo-sdk.md [:230-233](docs/components/uqudo-sdk.md#L230-L233)) — and CLAUDE.md forbids changing
   a third-party integration on documentation alone, so this needs `@agent-researcher` plus the
   tenant terms before it becomes a plan.

Stated as an argument rather than a finding: sharing does not widen what a leaked token can do (it is
tenant-scoped either way), and arguably narrows exposure by keeping fewer distinct live credentials
in circulation. That reasoning needs testing, not adopting.

### 4. The exemptions are free to the customer and **not** free to the bank

Camera-permission-denied, double-tap, app-killed, `ARTIFACT_EXPIRED`, `IMAGES_UNAVAILABLE` — in every
one of these a token was already minted and billed *before* the exemption applied.

That remains correct product behaviour: a customer must not lose an attempt to a permission dialog.
But it means **the customer-facing budget can never be the spend control**. Which is exactly why P-4
has to be a separate counter — the same reason customer.md already keeps two counters for two
different questions.

## C-3 — Does per-token pricing revive option (a)?

No, and now for a better reason than before. (a) counts what we pay for, which is economically
attractive, but the product owner has ruled it out to protect the exemptions and that judgement
stands on its own: charging a customer for a camera-permission dialog is a UX defect regardless of
what it costs the bank. The right structure is **two counters answering two questions** — the
customer's fairness budget (per-type, 5) and the bank's spend cap (per-profile token mints, P-4).
(c) + P-4 is exactly that split. Per-token pricing strengthens the split rather than collapsing it.

## Revised priority of the fix shape

| New order | Part | Change from Addendum 1 |
|---|---|---|
| **1** | **P-4 — the `/token` bound** | **Promoted from last to first.** Was abuse hardening; is now the spend cap on unmetered billable minting over an unauthenticated endpoint |
| 2 | P-1 — customer.md first | unchanged; still precedes the code that cites it |
| 3 | P-2 — single-use pending session ((c)'s core) | unchanged, and now known to be **cost-neutral** (C-1) |
| 4 | P-3 — `canAttempt` belt + `reportWrongNumber` | unchanged |
| 5 | P-5 — the numbers, 5 and 10 | unchanged in shape; see the cost note below |
| **new** | **P-6 — liveness parity** | Wire `LivenessAttemptBudget.canAttempt`, and apply the same token bound to `/liveness/token`. Its own item, not part of BL-039 |
| **new** | **P-7 — device-token caching** | Potentially the largest single saving. **Blocked** on the two confirmations in C-2.3 |

### The number the product owner should now decide with eyes open

At 5-per-type the honest worst case is **10 scan launches + up to 5 liveness launches = 15 billable
token mints per customer**, against a best case of **2** (one scan, one liveness). At 3-per-type the
scan half caps at 6, for a worst case of 11.

**So 3 → 5 raises the worst-case per-customer token bill by roughly 36% (11 → 15).** That is the
direct, quantified cost of the product change — expressible in tokens even without knowing the
price, and it should be weighed against the UX gain of two extra tries per document type.

## Still open

1. **The `/token` ceiling number** (P-4) — now the priority decision.
2. **The metered unit and whether a token may be shared** (P-7) — needs the tenant contract plus a
   researcher pass; do not build on the repository's current guidance, which says the opposite.
3. **Whether a timed per-type reset is wanted** (V-1) — unchanged; the "1 hour" traced to the
   phone-channel lock.
4. **Liveness parity** (P-6) — needs filing as its own backlog item.

**No code was changed and no backlog item was filed.**

---

# DECISION — 2026-09-06. Investigation CLOSED.

**Settled by the product owner on this investigation's findings. No feature code was written in
this session and no fix was built. Gates not run — nothing was compiled.** The build is a separate
session; this section is its specification.

## The model

**Option (c): one-token-one-attempt, enforced server-side, PLUS a separate lifetime token cap.**

Explicitly **not** pure token-counting (option a). The exempt cases stay free to the customer:
a **successful scan**, **camera-permission-denied**, **`ARTIFACT_EXPIRED`** and
**`IMAGES_UNAVAILABLE`**. Those exemptions are the reason (a) was rejected, and preserving them is a
hard requirement on the implementation — see the regression list.

## The numbers

| Setting | Value | Note |
|---|---|---|
| Per-type scan limit | **5** (was 3) | Product-owner decision |
| Total scan limit | **10** (was 6) | **Forced, not chosen** — must stay 2 × per-type or the document-switch fallback breaks (V-4) |
| Relationship | `TOTAL_LIMIT = 2 * PER_TYPE_LIMIT` **derived in code** | So the two cannot drift apart; the Javadoc already treats them as one fact |
| Lifetime scan-token cap, per profile | **20** | The literal maximum token mints a profile may ever spend. Product-owner decision, accepted as the deliberate cost/fairness point |
| Cap reset | **Never** | Same class of bound as the 24-hour block. AD-008's device-less reset deliberately leaves that class alone, so **the cap is NOT reset there** either |
| Timed reset of scan attempts | **None** | The "resets after 1 hour" belief was the Stage 2 phone lock, a different feature (V-1). Not wanted here |

**Consequence of the cap at 20, stated so it can be monitored rather than discovered.** One full
budget cycle is 10 launches (5 + 5). A customer who exhausts both document types, waits out the
24-hour block and exhausts both again reaches 20 — about two full cycles — after which only a branch
visit remains. Exempt mints (camera-denied, double-taps, dropped uploads) come out of the same 20.
That is the accepted trade-off, not an oversight; it is recorded here so that if real customers start
hitting the cap, the number and not the design is what gets revisited.

## Cap behaviour

Crossing 20 applies the **existing 24-hour block**. Reuse the response the app already renders:
**no new error code, no new screen, no new copy, no Flutter change.**

**Ordering requirement:** check the cap **before** the code that lifts an expired block. Otherwise a
block is lifted and immediately reapplied, writing two pointless `profile_status_history` rows. In
practice: if the profile is already `blocked_scan` and over the cap, refuse with the existing
`scan_blocked_until` and write nothing; if it is `in_progress` and over the cap, apply the block
(legal — `applyScanBlock`'s history row assumes an `in_progress` from-status).

## Two couplings to carry forward

**BL-036 (no idempotency key) rises from desirable to worth-doing.** If token generation is billed,
a double-tap on "start scan", or an app killed between the mint and the SDK launch, spends a real
billable token for nothing. BL-036 was filed as a correctness/fairness item; the billing position
adds a cost justification on top of the one it already had. Cross-reference it when the fix is built.

**The Uqudo billing position: token generation is metered per generation — product-owner statement,
2026-09-06, NOT corroborated by any public source.** Stated precisely, because the distinction
governs how much weight the reasoning above can bear:

- **What we were told.** The product owner states that Uqudo bills per new token generated. It is on
  that basis that the lifetime cap was sized and that BL-036 was re-prioritised.
- **What research established** (`@agent-researcher`, 2026-09-06, recorded in
  `docs/components/uqudo-sdk.md`): **Uqudo publishes no billing, pricing, metering, quota or credit
  documentation at all** — not a page, not a section. The only unit language anywhere public is
  *"discounts for larger **verification counts**"* (uqudo.com/faq) and *"keep track of your license
  usage"* (customer portal docs). Neither names the token as a unit, and "verification" points
  elsewhere. Nothing public contradicts the product owner either; a vendor marketing page is not a
  contract.
- **So V-5 is NOT closed.** V-5 asked for the tenant billing terms, and a product-owner statement is
  not those. Research has since established that this question is *unanswerable from documentation
  in principle* — only the contract, or a written answer from Uqudo or FIB, can settle it. That
  narrows the route to an answer; it does not supply one.
- **Tenant caveat.** We run on FIB's borrowed tenant (R-001), so the terms in force today are FIB's,
  and any saving accrues to FIB until our own tenant exists. Our own terms may differ — OQ-010 for
  provisioning, and the new **OQ-026** for the commercial terms themselves.

**What this does and does not change.** The decision stands under either metered unit. If the unit
turns out to be verifications rather than tokens, the lifetime cap still bounds abuse — the
unbounded-minting hole (F-5) is real regardless of price — and BL-036 still holds its original
correctness justification. What would evaporate is only the *cost* rationale layered on top of both.
**Nothing in Slice A or Slice B needs rebuilding if the billing answer comes back different; only
this paragraph does.**

## Implementation plan — for the next session. NOT built here.

Two slices, two commits, an `@agent-reviewer` pass on each.

### Slice A — the defect (BL-039)

1. `docs/journeys/customer.md` → 5 and 10 (l.675, l.678, l.1198, l.1199), and state the unit
   explicitly: a launched SDK session, one token per launch.
2. Make the pending scan session **single-use**, consumed at the moment a try is spent — inside
   `recordFailedAttempt`'s transaction and `reportWrongNumber`'s. **Not on success**, which would
   break BL-034's upload retry (the accepted-snapshot short-circuit sits *after* the session check).
3. Add `canAttempt` **under the lock** to the two spend paths that lack it: `recordFailedAttempt` and
   `reportWrongNumber`. This is what brings `reportWrongNumber` — the second spending path BL-039
   never named — under the model.
4. `PER_TYPE_LIMIT` 3 → 5; `TOTAL_LIMIT` derived as 2 ×; update `ScanAttemptBudget`'s Javadoc and
   `ScanAttemptBudgetTest` (including `exhaustingOneTypeAloneDoesNotTriggerTheBlock`, whose whole
   point is that relationship).
5. Supersede V0040's *"deliberately never cleared after use"* comment — it documents the opposite of
   the new rule and would otherwise read as a contradiction.

### Slice B — the cost cap

1. One migration adding a **scan-token counter** and a **face-token counter** to `app.profile`.
2. Cap at 20 wired into both issuance paths, each reusing its own side's existing block
   (`blocked_scan` / `blocked_liveness`), with the cap-check-before-block-lift ordering above.
3. **Connect the orphaned face-scan budget check** — `LivenessAttemptBudget.canAttempt` is called
   from no production code today; roughly three lines, and its own Javadoc states where it belongs.

### Regression list for the reviewer

Never charge for a **successful scan**, **camera-denied**, **`ARTIFACT_EXPIRED`** or
**`IMAGES_UNAVAILABLE`** · don't break **BL-034**'s upload retry · don't disturb **AD-008**'s
deliberate exclusion of `scan_attempts_total`/`scan_blocked_until` from its reset · don't reintroduce
**BL-043**'s hard-coded `insertHistory` from-status defect.

## CLAUDE.md — noted, deliberately NOT edited in this session

The planner makes this edit against the committed implementation, not against this plan. What will
need updating once the fix lands:

- The per-type and total scan-attempt numbers (3/6 → 5/10) wherever CLAUDE.md or the plan files
  restate them.
- The new **lifetime token cap** (20 per profile, never reset) — a bound that does not exist in any
  current description.
- The **single-use token model** — the statement that a pending scan session is reusable, and V0040's
  rationale, both become wrong.

## What this investigation produced

Six source findings (F-1…F-6), six verified facts (V-1…V-5 plus the addendum-2 cost findings), the
settled decision above, and one new backlog item — **BL-063**, the deferred token-reuse cost lever.
No feature code, no fix, and no other plan file's substance changed: **the numbers change when the
fix is built, not now.**

---

# Post-close note — 2026-09-06: the BL-063 researcher pass ran

**Still no feature code. Gates not run.** Recorded here because it changes two things this report
asserted, and a reader must not act on the superseded version.

A `@agent-researcher` pass on the token-reuse question completed after the close-out above was
committed. Its findings are folded into `docs/components/uqudo-sdk.md` (new *Token reuse, metering
and limits* subsection under *Backend surface*); only what changes THIS report is repeated here.

**1. The billing claim lost its corroboration, and the DECISION section was corrected above.**
Uqudo publishes **no** billing, pricing, metering, quota or credit documentation anywhere. The only
public unit language is *"discounts for larger **verification counts**"* (uqudo.com/faq) and *"keep
track of your license usage"* — neither names the token. Nothing public contradicts the product
owner either. **V-5 is therefore not closed**, and the *Two couplings* section now says so; the
metered unit is a contract question, filed as **OQ-026**. The decision itself is unaffected — the
cap and BL-036 stand on abuse and correctness grounds whatever the unit turns out to be.

**2. BL-063's blocker halved, and its documentation obstacle was weaker than filed.**
- **Reuse is already live in production on the tenant we borrow.** FIB's `UqudoServiceImpl.getToken()`
  serves a Redis-cached tenant-wide token (TTL `expires_in − configured margin`) from `GET /token`,
  and their production web app calls it per SDK launch. **Verified directly against `../FIB` rather
  than taken on the agent's word.** One token, many launches, many customers, same tenant.
- **Our card's *"Mint at point of use, never persist to disk, drop after the call"* was OUR guidance
  mislabelled `[DOC]`.** No Uqudo page states it. So BL-063 overturns a habit of ours, not a vendor
  rule — a materially lower bar than the row originally claimed. Marker corrected in the card.
- **The commercial half cannot be discharged by research at all.** That is now established, not
  assumed. BL-063's remaining blocker is the contract alone.

**3. Two properties of FIB's backend, verified and filed to R-001.** Their `GET /token` carries no
authentication annotation (a global filter chain was not checked, so: no annotation observed, not a
proven open endpoint), and `getImageById` logs the base64 identity-document image at INFO. Neither is
ours to fix; both bear on OQ-011 and on how far FIB's design should be treated as a pattern.

**4. A tooling correction for future research.** `llms-full.txt` returned "NOT PRESENT" for a string
that had just been read verbatim off an individual page — it is truncated before the fetch model sees
it, so it is **not** a reliable absence oracle. The card previously recommended it as the fastest way
to settle absence questions; that advice is withdrawn there.

Files touched by this note, none of them feature code: this report, `BACKLOG.md` (BL-063),
`docs/components/uqudo-sdk.md`, `PROJECT_PLAN.md` (OQ-026), `RISKS.md` (R-001).
