# S3-07 — Stage 1a's profile-existence branch; safe re-entry to Stage 1b

**Session:** implementation · **Date:** 2026-08-30 (started 2026-08-29, spans a wall-clock day
change mid-session) · **Task ID:** S3-07

---

## 0. Commit check

```
$ git log --oneline -3
fa0e48f docs: record S3-06 commit/push proof in session report
7223117 feat: S3-06 — Stage 1b, session/profile creation, channel selection, OTP issuance
8307957 docs: record S3-05 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean

$ git merge-base --is-ancestor 7223117 HEAD && echo "7223117 is ancestor of HEAD"
7223117 is ancestor of HEAD
```

S3-06's stated hash `7223117` confirmed as an ancestor of `main` at session start.

---

## 1. EXECUTION_PLAN.md

S3-07 added, marked ✅ (see §6 for what closed it).

---

## 2. Design

Full reasoning is in the plan file used for this session and in
`docs/components/persistence.md`'s new "Stage 1a/1b profile existence and re-entry (S3-07)"
section. Summary:

**No Flyway migration was needed.** Every table Stage 1b touches already granted `fru_app`
`SELECT`/`INSERT`/`UPDATE` (never `DELETE`) — V0010 — and `app.status_transition` (V0020) already
had the `abandoned → in_progress` edge pre-seeded, its own comment citing this exact case.

**Terminal-status set, derived from operator.md, not re-derived in Java:**
operator.md, "Profile statuses" → "Terminal for the customer" table lists exactly:

| Status | Source line |
|---|---|
| `submitted` | "Journey complete. Awaiting operator review." |
| `approved` | "Reviewed and accepted. Final." |
| `rejected` | "Reviewed and refused, with a recorded reason. Resolution is at a branch." |
| `terminated_registry_mismatch` | "Customer confirmed the national number was right but registry details wrong. Directed to a branch." |

This is exactly what `app.status_code.is_terminal` was seeded `true` for at V0005 (S2-02). The
implementation reads `is_terminal` from the database (`ProfileRepository.findExisting`) rather than
re-deriving the set in Java, so there is exactly one place it can drift.

**Stage 1a (`AccountCheckService`):** for the `ACTIVE` outcome only, calls `findExisting`. A
complete profile overrides the continuation from `PROCEED` to `TERMINAL` — no wire-shape change,
`AccountCheckResult` now carries `continuation` computed by the service rather than derived by the
controller from `outcome.continuation()`. The `account_check_attempted` audit payload gained
`profileStatus`/`profileTerminal` (populated only on the ACTIVE path).

**Stage 1b (`ContactChannelsService`):** the profile-existence check runs before anything is
generated or sent. Three outcomes:

- **No existing profile** — unchanged from S3-06.
- **Existing, terminal** — one audit event (`contact_channels_rejected`, reason
  `profile_already_complete`), then `ProfileAlreadyCompleteException` → HTTP 409. Nothing else
  runs.
- **Existing, not terminal** — a re-entry: sends fresh OTPs (unchanged mechanics), then inside one
  transaction: re-checks eligibility under `SELECT ... FOR UPDATE` (closing the gap between the
  pre-send-loop read and this write — see the TOCTOU fix in §7), appends `session_reentered`
  (previous phone/email/per-channel-states alongside what replaced them), either
  `reactivateFromAbandoned` or `touchLastActivity`, `updateContactDetails`,
  `invalidateOtpChallenges` (`expires_at = LEAST(expires_at, now)` — **expires, never deletes**:
  `app.otp_challenge` grants `fru_app` no `DELETE`, and the row is harmless, useful evidence that a
  code was issued), `upsertChannel` per decision (`INSERT ... ON CONFLICT (profile_id, channel) DO
  UPDATE`), `declineChannelsNotIn` for any channel present before but absent now (only ever email).

**New `ProfileRepository` methods:** `findExisting`, `lockAndCheckStillEligibleForReentry`,
`currentChannelStates`, `currentContactDetails`, `updateContactDetails`, `touchLastActivity`,
`reactivateFromAbandoned`, `declineChannelsNotIn`, `invalidateOtpChallenges`; `insertChannel`
became `upsertChannel`. New types: `ExistingProfile`, `ContactSnapshot` (`profile.domain`),
`ProfileAlreadyCompleteException` (`contactchannels.domain`). `MessageChannel`/`ChannelState` each
gained `fromWireValue(String)`.

**Out of scope, as specified:** Stage 2's verification/resend/lockout; the device-less review flow
(BL-006's remainder — restoring customer-entered data, discard-and-start-over); rate limiting
(BL-007); R-042; any concrete messaging adapter; the scheduled dispatcher; AD-002b/d/e/f; AD-003;
AD-004; S3-02.

---

## 3. Flyway

No migration added. From-scratch and idempotent re-run, against a fresh `postgres:18` container
(same shape as S3-06's proof):

```
$ docker run -d --name fru-s307-proof -e POSTGRES_PASSWORD=bootstrap_pw -e POSTGRES_DB=fru \
    -e FRU_MIGRATOR_PASSWORD=migrator_pw -e POSTGRES_INITDB_ARGS="--locale-provider=icu --icu-locale=ar" \
    -p 55434:5432 -v ".../db/init/01-create-migrator.sh:/docker-entrypoint-initdb.d/01-create-migrator.sh:ro" \
    postgres:18

$ DB_PORT=55434 DB_NAME=fru DB_MIGRATOR_PASSWORD=migrator_pw FRU_APP_PASSWORD=app_pw \
  FRU_SEALER_PASSWORD=sealer_pw ./mvnw flyway:migrate
...
[INFO] Migrating schema "public" to version "0035 - app notification outbox"
[INFO] Migrating schema "public" to version "0036 - audit profile chain creation"
[INFO] Successfully applied 36 migrations to schema "public", now at version v0036 (execution time 00:01.270s)
[INFO] BUILD SUCCESS

$ ./mvnw flyway:migrate   # same env
[INFO] Successfully validated 36 migrations (execution time 00:00.120s)
[INFO] Current version of schema "public": 0036
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

Still 36 migrations, unchanged from S3-06 — confirms no schema change was needed for this task.

---

## 4. Proofs — live, against the same real PostgreSQL 18

Real Spring Boot app on port 8199, real `curl`, real `psql` (via `docker exec`). All timestamps
below are as captured live during the session.

### 4.1 1a — ACTIVE account, no profile exists → continue

```
$ curl -s -X POST http://localhost:8199/api/v1/account-check -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000301"}'
{"outcome":"ACTIVE","continuation":"PROCEED","requestId":"dad1bc06-4f5c-49c4-986b-b60be1fa90ab"}
```

### 4.2 1a — ACTIVE account, a TERMINAL profile exists → already-complete, no mutation, no message

Fixture: created a profile for `0000000302` via 1b, then transitioned it to `submitted` via a
legal single-hop `in_progress → submitted` DB transition (V0020).

```
$ curl -s -X POST http://localhost:8199/api/v1/contact-channels -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000302","phoneNumber":"+249900003020","sms":true,"whatsapp":true}'
{"profileId":"18c628d6-47c2-4b0f-a041-85898a806e8e","channels":[{"channel":"sms","state":"unverified","maskedDestination":"•••• 3020"},{"channel":"whatsapp","state":"unverified","maskedDestination":"•••• 3020"}]}

$ psql (fru_migrator) -- transition to submitted
BEGIN
UPDATE 1
INSERT 0 1
COMMIT

$ psql -- profile row BEFORE the 1a call
  status   |       status_changed_at       | row_version |       last_activity_at
-----------+-------------------------------+-------------+-------------------------------
 submitted | 2026-08-29 22:09:07.299283+00 |           1 | 2026-08-29 22:06:12.130501+00

$ curl -s -X POST http://localhost:8199/api/v1/account-check -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000302"}'
{"outcome":"ACTIVE","continuation":"TERMINAL","requestId":"a940e7e2-a648-4965-b8a8-02e10d47015b"}

$ psql -- profile row AFTER the 1a call -- byte-identical
  status   |       status_changed_at       | row_version |       last_activity_at
-----------+-------------------------------+-------------+-------------------------------
 submitted | 2026-08-29 22:09:07.299283+00 |           1 | 2026-08-29 22:06:12.130501+00

$ psql -- profile_channel / otp_challenge counts -- unchanged
 channels | challenges
----------+------------
        2 |          2
```

### 4.3 1a — ACTIVE account, an INCOMPLETE profile exists → continue (re-entry outcome)

```
$ curl -s -X POST http://localhost:8199/api/v1/contact-channels -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000301","phoneNumber":"+249900003011","sms":true,"whatsapp":true,"emailAddress":"customer301@example.invalid"}'
{"profileId":"d80bf983-f65c-4579-9a97-ee0be54a223a", ...}

$ curl -s -X POST http://localhost:8199/api/v1/account-check -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000301"}'
{"outcome":"ACTIVE","continuation":"PROCEED","requestId":"5fcb554a-9405-4f91-8997-a3161272f4b4"}
```

### 4.4 The full BL-009 path, end to end

Same account (`0000000301`) as 4.3, profile `d80bf983-f65c-4579-9a97-ee0be54a223a`.

```
$ psql -- first submission's otp_challenge rows (3: email/sms/whatsapp)
             challenge_id             | channel  |           issued_at           |          expires_at
--------------------------------------+----------+-------------------------------+-------------------------------
 50aa38f1-... | email    | 2026-08-29 22:09:54.299196+00 | 2026-08-29 22:14:54.299196+00
 18f5217b-... | sms      | 2026-08-29 22:09:54.299196+00 | 2026-08-29 22:14:54.299196+00
 c9d13c48-... | whatsapp | 2026-08-29 22:09:54.299196+00 | 2026-08-29 22:14:54.299196+00

$ curl -s -i -X POST http://localhost:8199/api/v1/contact-channels -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000301","phoneNumber":"+249900009999","sms":true,"whatsapp":true}'
HTTP/1.1 200
{"profileId":"d80bf983-f65c-4579-9a97-ee0be54a223a","channels":[{"channel":"sms",...},{"channel":"whatsapp",...}]}
```

Second call succeeded (200), same `profileId` returned.

```
$ psql -- new phone number, one profile row for the account
 phone_number  | email_address
---------------+---------------
 +249900009999 |
(1 row)
 count
-------
     1

$ psql -- profile_channel: email declined (dropped, not absent), sms/whatsapp reset unverified
 channel  |   state
----------+------------
 email    | declined
 sms      | unverified
 whatsapp | unverified

$ psql -- otp_challenge: first 3 now expired (invalidated), 2 fresh sms/whatsapp still valid
             challenge_id             | channel  |           issued_at           |          expires_at           | invalidated
--------------------------------------+----------+-------------------------------+-------------------------------+-------------
 18f5217b-... | sms      | 22:09:54 | 22:10:14 | t
 c9d13c48-... | whatsapp | 22:09:54 | 22:10:14 | t
 50aa38f1-... | email    | 22:09:54 | 22:10:14 | t
 cb7901ab-... | sms      | 22:10:14 | 22:15:14 | f
 df312153-... | whatsapp | 22:10:14 | 22:15:14 | f

$ psql -- audit trail, both submissions, all 12 events survive
 seq |       event_type        | payload_json (abbreviated)
-----+--------------------------+-------------------------------------------------
   1 | session_created          | {..."emailChallenged":true,"smsChallenged":true,"whatsappChallenged":true...}
   2 | otp_issued               | {"channel":"sms",...}
   3 | notification_dispatched  | {..."channel":"sms","outcome":"ACCEPTED"...}
   4 | otp_issued               | {"channel":"whatsapp",...}
   5 | notification_dispatched  | {..."channel":"whatsapp","outcome":"ACCEPTED"...}
   6 | otp_issued               | {"channel":"email",...}
   7 | notification_dispatched  | {..."channel":"email","outcome":"ACCEPTED"...}
   8 | session_reentered        | {"emailSelected":false,"previousEmailAddress":"customer301@example.invalid","previousEmailState":"unverified","previousPhoneNumber":"+249900003011","previousSmsState":"unverified","previousWhatsappState":"unverified",...}
   9 | otp_issued               | {"channel":"sms",...}
  10 | notification_dispatched  | {..."channel":"sms","outcome":"ACCEPTED"...}
  11 | otp_issued               | {"channel":"whatsapp",...}
  12 | notification_dispatched  | {..."channel":"whatsapp","outcome":"ACCEPTED"...}
(12 rows)

$ psql -- the chain still verifies
 ok | checked | reason
----+---------+--------
 t  |      12 | ok
```

Every element the task asked for is present: second call succeeds, profile carries the new number,
fresh challenges exist, old challenges cannot be used (expired), and the audit record of **both**
submissions survives, including the first submission's sends.

### 4.5 A terminal profile submitted to 1b directly, bypassing 1a → rejected, no message sent

Same fixture as 4.2 (`0000000302`, `submitted`).

```
$ psql -- BEFORE: channel/challenge/audit-event counts
 channels | challenges | audit_events
----------+------------+--------------
        2 |          2 |            5

$ curl -s -i -X POST http://localhost:8199/api/v1/contact-channels -H "Content-Type: application/json" \
  -d '{"branch":"16","accountNumber":"0000000302","phoneNumber":"+249900099999","sms":true,"whatsapp":true,"emailAddress":"attacker@example.invalid"}'
HTTP/1.1 409
{"timestamp":"2026-08-29T22:11:00.374Z","status":409,"error":"Conflict","path":"/api/v1/contact-channels"}

$ psql -- AFTER: unchanged except one new audit event
 channels | challenges | audit_events
----------+------------+--------------
        2 |          2 |            6

$ psql -- the new event
        event_type         |                                                 payload_json
---------------------------+--------------------------------------------------------------------------------------------------------------
 contact_channels_rejected | {"accountNumber":"0000000302","branch":"16","profileStatus":"submitted","reason":"profile_already_complete"}
```

Server-side, not an app-only courtesy: the endpoint was called directly, with no 1a call at all.

### 4.6 Regression — unchanged

Fixture: profile A (`0000000303`, `submitted`, manually completed by operator `opA`), profile B
(`0000000301`'s profile, `in_progress`).

```
########## APP PROOFS (as fru_app) ##########
=== four-eyes still blocks (operator = manual completer opA) ===
UPDATE 0
=== same statement, a DIFFERENT operator, not blocked (rolled back) ===
BEGIN
UPDATE 1
INSERT 0 1
ROLLBACK
=== append-only: fru_app cannot UPDATE audit.audit_event ===
ERROR:  permission denied for table audit_event
=== append-only: fru_app cannot DELETE audit.audit_event ===
ERROR:  permission denied for table audit_event
=== append-only: fru_app cannot UPDATE app.profile_status_history ===
ERROR:  permission denied for table profile_status_history
=== append-only: fru_app cannot DELETE app.profile_status_history ===
ERROR:  permission denied for table profile_status_history

########## MIGRATOR PROOFS (as fru_migrator) ##########
=== status guard rejects in_progress -> approved (skipping submitted) ===
ERROR:  illegal status transition on app.profile d80bf983-...: in_progress -> approved
=== profile B still in_progress after the rejected attempt ===
              profile_id              |   status
--------------------------------------+-------------
 d80bf983-f65c-4579-9a97-ee0be54a223a | in_progress
=== a legal transition with NO matching history row fails at commit ===
BEGIN
UPDATE 1
ERROR:  status change on app.profile d80bf983-... (in_progress -> submitted) has no matching (most recent) app.profile_status_history row
=== profile B still in_progress -- the failed commit rolled the UPDATE back too ===
              profile_id              |   status
--------------------------------------+-------------
 d80bf983-f65c-4579-9a97-ee0be54a223a | in_progress
```

Four-eyes still blocks; the status guard still rejects `in_progress → approved`; a status change
with no history row still fails; the audit trail is still append-only against `fru_app`. S3-06's
three-channel and deselection proofs still hold — re-run unchanged, both as automated tests (see
§6) and originally in this same live session before the reviewer round.

Environment torn down after §4: `pkill -f spring-boot:run`, `docker rm -f fru-s307-proof`.

---

## 5. `@agent-reviewer` — first pass

| # | Finding | Severity | Disposition |
|---|---|---|---|
| 1 | The re-entry existence/terminal check is TOCTOU: `findExisting` runs once, outside any transaction, before the OTP send loop — nothing re-asserts it inside the transaction. Two concrete sequences: (a) a double-tap on a brand-new account races `insertProfile` against `profile_one_per_account`, reproducing BL-009's original symptom; (b) an operator manually completes the profile during the send loop, letting a stale-read re-entry overwrite a now-terminal profile's data. | SHOULD FIX | **Fixed** for sequence (b): `ProfileRepository.lockAndCheckStillEligibleForReentry`, `SELECT ... FOR UPDATE` as the transaction's first statement on the re-entry branch; aborts the write if the profile turned terminal in between. Sequence (a) is narrower (requires no existing profile at all) and is tracked separately as **BL-010** rather than bolted on — closing it needs an advisory lock or catch-and-retry, an architecture-adjacent decision. |
| 2 | `reactivateFromAbandoned` and the decline-not-absent behaviour of `declineChannelsNotIn` were only asserted as SQL substrings against a mocked `JdbcTemplate` — never proven against a real database. | SHOULD FIX | **Fixed.** Two new `@Tag("integration")` tests: `reEntryToAnAbandonedProfileReactivatesItLive`, `emailDroppedOnReEntryIsDeclinedNotDeletedLive`. |
| 3 | Re-entry's `upsertChannel` resets `wrong_code_attempts`/`resend_count`/`locked_at` to defaults on every conflict — Stage 2 (unbuilt) is what actually writes those columns, and its eventual lockout design may assume they persist across a same-session correction. | SHOULD FIX | **Documented, not code-changed.** Judged defensible: customer.md scopes both counters explicitly per session, and the "Back to 1b" correction reuses the same `profile_id`/session. New `RISKS.md` R-044 records the ambiguity and the resend/billing consequence for whoever scopes Stage 2. |
| 4 | `AccountCheckService`'s new profile-existence check had no audit fallback on failure, unlike its two sibling failure paths (call-failed, unmapped result code), which audit-then-rethrow with `addSuppressed`. | NOTE | **Fixed.** Same try/catch/`addSuppressed` shape, new `OUTCOME_PROFILE_CHECK_FAILED` outcome, real `resultCode` still recorded. |
| 5 | The 409 response body disclosed the internal profile status (`submitted`/`approved`/`rejected`) to an unauthenticated caller via the exception message. | NOTE | **Fixed.** Status stays only in the audit payload; the exception message the controller turns into an HTTP response no longer includes it. |
| 6 | Plan files cite a session report that didn't exist yet. | NOTE | Resolved by this file. |
| 7 | `findExistingReturnsEmptyWhenTheQueryHasNoRows` passed vacuously — Mockito's default answer for an unstubbed `List`-returning method is already an empty list. | NOTE | **Fixed.** Renamed and strengthened to capture and assert the SQL text and both bound arguments. |

All fixes re-verified: `./mvnw test -Pdb-integration-test` (220 tests), `./mvnw verify` (Spotless
clean, JaCoCo gate met) — all green.

## 5.1 Second review pass

Run against the fixed diff, per this task's own instruction and CLAUDE.md.

| Verdict | Item |
|---|---|
| RESOLVED | TOCTOU fix (`lockAndCheckStillEligibleForReentry`) — correct lock syntax, correct scope, no write commits after a negative check; BL-010's characterization of the remaining gap confirmed accurate. |
| RESOLVED | Live-DB proof for `reactivateFromAbandoned`/`declineChannelsNotIn` — both new tests genuinely exercise the claimed behaviour. |
| PARTIALLY RESOLVED | Counter-reset documentation (R-044) — see NOTE-2 below, now fully addressed. |
| RESOLVED | `AccountCheckService` audit fallback. |
| RESOLVED | 409 status disclosure. |
| RESOLVED | Vacuous `findExisting` test. |
| **NEW-1 — SHOULD FIX** | The mid-transaction rejection's audit event was written **inside** the same `transactionTemplate.executeWithoutResult` callback that then threw — `TransactionTemplate` rolls back on any escaping `RuntimeException`, and `JdbcAuditEventWriter` shares the same transaction-bound connection, so the `contact_channels_rejected` event for the *new* TOCTOU path was silently rolled back with everything else. The exact class of evidence loss BL-009 itself was filed about, reintroduced on the new path. The existing unit test could not catch it (mocked transaction manager doesn't roll back). |
| NOTE-2 | R-044 presented the counter-reset question as more evenly balanced than the evidence supports, and omitted the resend/billing consequence. |
| NOTE-3 | No test pinned `verified_at = NULL` on the channel-state overwrite customer.md requires. |
| NOTE-4 | Dangling session-report reference (expected, resolved by this file). |

### NEW-1 — fixed and independently verified

Restructured: the callback now sets a local flag and returns normally on ineligibility (the
transaction commits, having written nothing); the audit write and throw happen **after**
`executeWithoutResult` returns, outside any transaction — the same shape `rejectTerminalReentry`
already used correctly for the pre-send-loop case.

**Verified two ways, not just re-reviewed:**

1. A new deterministic live-DB test, `aRejectionMidReEntryPersistsItsAuditEventDespiteTheTransactionDoingNothing`
   (`ContactChannelsIntegrationTest`), uses a `@MockitoSpyBean` on the real `ProfileRepository` to
   simulate the race without genuine concurrency: the spy lies only on the first `findExisting`
   read (as if it still saw the pre-race state), while `lockAndCheckStillEligibleForReentry` is
   untouched and hits the real, already-`submitted` row. Asserts the audit event count goes from
   5 to 6 across the real HTTP call.
2. **Sanity-checked the test itself against the bug**: temporarily reverted the fix (wrote the
   audit event back inside the transactional callback, restoring the exact defect the second
   review pass found), re-ran just this one test:

   ```
   org.opentest4j.AssertionFailedError: [... 5 events ...] ==> expected: <6> but was: <5>
   [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
   [INFO] BUILD FAILURE
   ```

   Restored the fix, re-ran the same test:

   ```
   [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 239.9 s
   [INFO] BUILD SUCCESS
   ```

   Confirms the test is not vacuous — it fails against the exact defect it exists to catch, and
   passes against the fix.

NOTE-2 and NOTE-3 addressed in the same pass:

- `RISKS.md` R-044 extended with the per-session textual evidence from customer.md
  ("locks... for the session", "3 per channel **per session**") and the resend/billing-abuse
  consequence (unlimited billed resends by cycling through 1b).
- `JdbcProfileRepositoryTest.upsertChannelWritesTheWireValuesNotTheEnumNames` now asserts
  `verified_at = NULL`/`locked_at = NULL`/`wrong_code_attempts = 0`/`resend_count = 0` are in the
  `ON CONFLICT` SQL; a new live test, `aPreviouslyVerifiedChannelComesBackUnverifiedWithNoVerifiedAtOnReEntry`,
  marks a channel `verified` by hand, re-enters, and asserts it comes back `unverified` with
  `verified_at IS NULL`.

---

## 6. Verify and report

### 6.1 `./mvnw test -Pdb-integration-test`

Run three times over the course of this session (once after the first review pass, once after the
second, once as the final gate) — all green. The one transient failure seen along the way
(`AppSchemaConnectivityIntegrationTest`'s Testcontainers container timing out) was reproduced in
isolation, traced to Docker Desktop/registry slowness after many container starts in one long
session (not a code defect — that test file is untouched by this diff), and resolved by warming
the `postgres:18` image cache with a plain `docker pull`; the retry and every subsequent full run
passed clean. Final run:

```
[INFO] Tests run: 222, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**222 vs S3-06's 189** — 33 new tests across: `AccountCheckServiceTest` (+9: profile-existence
branches, audit payload, the new failure-path fallback), `AccountCheckIntegrationTest` (+2:
terminal profile, incomplete profile), `AccountCheckControllerTest` (+1: ACTIVE+TERMINAL),
`JdbcProfileRepositoryTest` (+13: every new/changed repository method, including
`lockAndCheckStillEligibleForReentry` and the strengthened `verified_at`/`locked_at` assertions),
`ContactChannelsServiceTest` (+5: re-entry, abandoned reactivation, dropped-email decline, terminal
rejection, mid-transaction rejection), `ContactChannelsControllerTest` (+1: 409 mapping),
`ContactChannelsIntegrationTest` (+6: BL-009 round trip, terminal-bypass, abandoned reactivation,
email-decline, mid-transaction rejection audit-persistence (with the sanity check described in
§5.1), `verified_at` reset — plus the 4 unchanged S3-06 tests, 10 total in that class).

### 6.2 `./mvnw test`

```
[INFO] Tests run: 196, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(222 minus the 26 `@Tag("integration")` tests.)

### 6.3 `./mvnw verify`

```
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 89 files clean - 0 needs changes to be clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

JaCoCo bundle: **56 classes** (up from S3-06's 53), **95.5% line coverage**, **91.5% branch
coverage** (both well above the enforced 80% line-ratio gate; the modest dip from S3-06's
96.2%/91.9% reflects the new TOCTOU-guard and failure-fallback branches, still comfortably above
gate).

### 6.4 backoffice / mobile — untouched

Per CLAUDE.md, "run the test and analyze gates for every tier you touched" — this session touched
only `backend/`.

### 6.5 Derived terminal-status set — restated with source

See §2 above: `submitted`, `approved`, `rejected`, `terminated_registry_mismatch`, from
operator.md's "Terminal for the customer" table, matching `app.status_code.is_terminal` seeded
`true` at V0005.

---

## 7. BL-006 / BL-009 / BL-010 / R-044 — final state

- **BL-006** — narrowed. The profile-existence branch and safe re-entry to Stage 1b are closed.
  What remains: the device-less review flow (restoring customer-entered data for explicit review,
  discard-and-start-over), which depends on data-entry stages (Stage 3+) that don't exist yet.
- **BL-009** — closed for the sequential case the original report described (a legitimate,
  non-concurrent resubmission). The narrower, genuinely concurrent residual — two near-simultaneous
  submissions for an account with no existing profile yet — is tracked separately as new item
  **BL-010**, not silently left inside BL-009's "closed" claim.
- **BL-010** — new. Two concurrent Stage 1b submissions for an account with no existing profile can
  both pass the pre-write check and race `profile_one_per_account`. Needs an advisory lock or
  catch-and-retry; left open as an architecture-adjacent decision, not bolted on under review
  pressure.
- **R-044** — new. Re-entry resets Stage 2's not-yet-built lockout/resend counters; documented with
  the textual evidence leaning toward this being intended, plus the resend/billing consequence, for
  whoever scopes Stage 2.

---

## 8. Files touched

- `EXECUTION_PLAN.md`, `BACKLOG.md`, `RISKS.md` — S3-07 row; BL-006 narrowed; BL-009 closed
  (qualified) and BL-010 added; R-044 added.
- `docs/components/persistence.md` — new "Stage 1a/1b profile existence and re-entry (S3-07)"
  section; `Last verified` bumped.
- `backend/src/main/java/sd/gov/bank/fruserupdate/accountcheck/service/AccountCheckResult.java`,
  `AccountCheckService.java` — profile-existence branch, continuation computed by the service,
  audit fallback on the new failure path.
- `backend/src/main/java/sd/gov/bank/fruserupdate/accountcheck/web/AccountCheckController.java`,
  `AccountCheckResponse.java` — use `result.continuation()`; javadoc updated.
- `backend/src/main/java/sd/gov/bank/fruserupdate/contactchannels/service/ContactChannelsService.java`
  — the three-way branch, the TOCTOU lock-and-recheck (audit write moved outside the transaction
  after the second review pass), the shared rejection-event builder.
- `backend/src/main/java/sd/gov/bank/fruserupdate/contactchannels/web/ContactChannelsController.java`
  — 409 mapping.
- `backend/src/main/java/sd/gov/bank/fruserupdate/contactchannels/domain/ProfileAlreadyCompleteException.java`
  — new.
- `backend/src/main/java/sd/gov/bank/fruserupdate/profile/domain/ProfileRepository.java`,
  `ExistingProfile.java` (new), `ContactSnapshot.java` (new) — the new/changed port methods.
- `backend/src/main/java/sd/gov/bank/fruserupdate/profile/jdbc/JdbcProfileRepository.java` — SQL
  implementations, including `LOCK_AND_CHECK_STILL_ELIGIBLE`'s `FOR UPDATE OF p`.
- `backend/src/main/java/sd/gov/bank/fruserupdate/messaging/domain/MessageChannel.java`,
  `backend/src/main/java/sd/gov/bank/fruserupdate/profile/domain/ChannelState.java` —
  `fromWireValue`.
- Tests: `AccountCheckServiceTest`, `AccountCheckControllerTest`, `AccountCheckIntegrationTest`,
  `JdbcProfileRepositoryTest`, `ContactChannelsServiceTest`, `ContactChannelsControllerTest`,
  `ContactChannelsIntegrationTest` — all extended per §6.1.
- `docs/sessions/2026-08-30-s3-07-profile-existence.md` — this file.

---

## 9. What failed / open items

Nothing failed outright once the reviewer's findings (both passes) were fixed. Recorded, not
fixed, by design:

- **BL-010** — the narrower concurrent-fresh-insert race (see §7).
- **R-044** — the counter-reset-on-re-entry question, properly Stage 2's to settle.
- **BL-006's remainder** — the device-less review flow.
- Everything in the OUT OF SCOPE list (§2) — untouched, as instructed.

---

## 10. Commit/push proof

```
$ git log --oneline -1
752a6e0 feat: S3-07 — Stage 1a profile-existence branch; safe re-entry to Stage 1b

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   .claude/settings.json

no changes added to commit (use "git add" and/or "git commit -a")
```

`.claude/settings.json`'s change is an auto-recorded permission-allowlist entry from a `find`
command run mid-session (harness tooling config), unrelated to this feature — deliberately left
out of this commit.

Push output:
```
$ git push
To https://github.com/Osmantou/Fr_user_update
   fa0e48f..752a6e0  main -> main
```
