# S3-05 — The `MessageSender` port, its stub, and `app.notification_outbox`

**Session:** implementation · **Date:** 2026-08-29 · **Task ID:** S3-05

---

## 0. Commit check

```
$ git log --oneline -3
fd9bddf docs: record S3-04 commit/push proof in session report
fe30bed docs: S3-04 — file AD-002c, close the messaging port design
168f6a7 docs: record AD-002c commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```

`fe30bed` (S3-04) exists and `git log` shows it as an ancestor of the checked-out `main` at
session start.

---

## 1. Plan (approved before implementation)

The task was large enough to require plan mode. The approved plan, in summary:

1. Two corrections to prior filings — `RISKS.md` R-043 and `PROJECT_PLAN.md` OQ-021/OQ-022 —
   text supplied verbatim in the task.
2. `EXECUTION_PLAN.md`: add the S3-05 row.
3. Migration `V0035__app_notification_outbox.sql`, per AD-005 report §6 ("A. Status transition +
   audit record + notification dispatch"), with two hard constraints: the `channel` CHECK must be
   byte-identical to V0007/V0021, and the result columns must line up 1:1 with
   `MessageDispatchResult` so a dispatcher never needs a translation layer.
4. The port and stub, package `sd.gov.bank.fruserupdate.messaging` (`domain`/`service`/`config`/
   `stub`), implementing AD-002c research report §5 essentially verbatim.
5. A thin outbox-side package, `sd.gov.bank.fruserupdate.notification` (`domain`/`service`/`jdbc`),
   just enough to prove one manual claim → send → record → audit cycle — no scheduler.
6. Unit tests for every new class; one Testcontainers integration test proving the whole chain
   live against a real PostgreSQL 18.
7. Manual, live regression proofs (four-eyes, status guard, no-history-row-fails, append-only) via
   `psql` against a fresh `docker run postgres:18` container, matching S2-04/S2-05's own
   verification style, since no committed JUnit suite exists for those yet.
8. `@agent-reviewer` against the diff; this report; commit and push.

Full plan file: it was written to the harness's plan-mode scratch file and is not reproduced here
verbatim — the summary above is complete against what was actually built.

---

## 2. What changed after approval

Everything in the plan was built as scoped. Two additions surfaced only during implementation,
neither of which changes the plan's shape:

- **`MessagePayloadJson`** (`messaging.domain`) — a small pure-logic class converting a
  `MessagePayload` to/from the JSON text stored in `app.notification_outbox.payload`. Not in the
  original plan text; needed because the outbox's `payload` column has to round-trip a sealed
  interface with a genuinely nested shape (`WhatsAppPayload.bodyParameters`), which
  `audit.domain.CanonicalJson` cannot serialise (flat objects only) and was never meant to.
- **`OutboxState`** (`notification.domain`) — extracted mid-review (see §7) from a private method
  in the JDBC repository into its own pure-logic enum, because the outcome→state retry mapping is
  a chosen behaviour CLAUDE.md reserves for `domain`/`service`, not `jdbc`.

Everything else matches the plan: no adapter, no scheduler, no stage 1b/2 wiring, no message copy.

---

## 3. Migration `V0035__app_notification_outbox.sql`

One table, following AD-005 §6's dispatcher contract:

```sql
CREATE TABLE app.notification_outbox (
  outbox_id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id           uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  channel               text NOT NULL CHECK (channel IN ('sms','whatsapp','email')),
  destination           text NOT NULL,
  payload               jsonb NOT NULL,
  state                 text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','dispatched','failed')),
  attempt_count         int  NOT NULL DEFAULT 0,
  next_attempt_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
  outcome               text CHECK (outcome IN ('ACCEPTED','REJECTED','TRANSIENT_FAILURE','PERMANENT_FAILURE')),
  provider_id           text,
  provider_message_id   text,
  provider_status_code  text,
  provider_status_text  text,
  billed_segments       int,
  attempted_at          timestamptz,
  latency_millis        bigint
);
CREATE INDEX notification_outbox_pending_idx ON app.notification_outbox (state, next_attempt_at);
GRANT SELECT, INSERT, UPDATE ON app.notification_outbox TO fru_app;
```

`channel`'s value set is byte-identical to `app.profile_channel.channel` (V0007) and
`app.otp_challenge.channel` (V0021). The nine result columns are 1:1 with
`MessageDispatchResult`'s fields, so `JdbcNotificationOutboxRepository.recordResult` binds them
positionally with no mapper — the task's explicit shape requirement. `payload` is `jsonb`, not
flattened columns: `MessagePayload` is a sealed interface with three genuinely different shapes,
and this is `app`, not `audit` — `CanonicalJson`'s flat-object constraint never applied here.

---

## 4. The port — `sd.gov.bank.fruserupdate.messaging`

Built to AD-002c report §5:

- `domain/`: `MessageChannel`, `Urgency`, `MessagePayload` (sealed) + `SmsPayload`/
  `WhatsAppPayload`/`EmailPayload`, `WhatsAppTemplateCategory`, `OutboundMessage`,
  `MessageDispatchResult`, `DispatchOutcome`, `DeliveryState`, `DeliveryReceipt`, `MessageSender`
  (the port), `Ucs2Segmenter`, `MessagePayloadJson`.
- `service/`: `ChannelRoutingMessageSender` — the composite router.
- `config/`: `MessageSenderConfiguration` — one `fru.messaging.<channel>.provider` property per
  channel, no default, plus `fru.messaging.<channel>.enabled`.
- `stub/`: `StubMessageSender`, `StubMessagingProperties`.

No `http`/`smtp`/`whatsapp` adapter packages — deliberately out of scope; CLAUDE.md's
`@agent-researcher`-before-integration rule and "don't scaffold packages for features that don't
exist yet" both apply.

---

## 5. The outbox side — `sd.gov.bank.fruserupdate.notification`

- `domain/`: `NotificationOutboxRepository` (port), `ClaimedOutboxEntry`, `OutboxState`.
- `service/`: `OutboxDispatcher` — claims one row, sends it, records the result and the audit
  event **in one transaction** opened after `send()` returns (see §7 for why this needed a
  post-review fix).
- `jdbc/`: `JdbcNotificationOutboxRepository` — the one class that knows
  `app.notification_outbox`'s columns.

Deliberately thin: `OutboxDispatcher.dispatchOnePending()` does exactly one claim-send-record
cycle. No `@Scheduled`, no batch loop.

---

## 6. Proofs (step 5 of the task)

### 6.1 End to end, live — enqueue → dispatch → audit → outbox update

Proven by `NotificationOutboxIntegrationTest.endToEndDispatchThroughTheStubUpdatesTheOutboxRowAndWritesTheAuditEvent`
against a real Testcontainers PostgreSQL 18 with all 35 migrations applied:

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- NotificationOutboxIntegrationTest
```

Asserts: the outbox row's `state='dispatched'`, `outcome='ACCEPTED'`, `provider_id='stub'`,
`billed_segments=1`; exactly one `audit.audit_event` row with `event_type='notification_dispatched'`
and `profile_id` matching; and (`@Order(2)`) that `audit.verify_chain()` still reports `ok=true`
over a chain that actually contains that event (not a vacuous pass over an empty chain — see §7
for why this was tightened).

### 6.2 `MessageDispatchResult` round-trips through `CanonicalJson` unchanged

`MessageDispatchResultCanonicalJsonTest` — a fully-populated result and one carrying the nulls a
real REJECTED/TRANSIENT_FAILURE attempt produces both serialise via
`CanonicalJson.object(result.canonicalPayload())` and parse back field-for-field identical:

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- MessageDispatchResultCanonicalJsonTest
```

### 6.3 `MessageChannel` against the outbox CHECK constraint, all three values plus a rejection

`NotificationOutboxIntegrationTest.theChannelCheckConstraintAcceptsAllThreeLowercaseValuesAndRejectsWrongCase`
— `whatsapp` and `email` (`sms` already proven by §6.1) insert successfully; `'SMS'` (wrong case)
is rejected with `DataIntegrityViolationException`, live against the real CHECK constraint.

### 6.4 A disabled channel produces a returned result, not an exception

`ChannelRoutingMessageSenderTest.aChannelWithNoMappedSenderReturnsPermanentFailureRatherThanThrowing`
and `MessageSenderConfigurationTest.aDisabledChannelIsExcludedFromTheBuiltRouterEvenWithAValidProvider`:
a disabled/unconfigured channel returns `MessageDispatchResult` with
`outcome=PERMANENT_FAILURE`, `providerStatusCode=CHANNEL_NOT_CONFIGURED` — never throws.

### 6.5 Startup failing on an unset `fru.messaging.*` property

`MessageSenderConfigurationTest.anythingOtherThanARecognisedValueFailsAtStartupNamingWhatItFound`
(parameterised over null/empty/whitespace/typo/wrong-case) and
`anEnabledChannelWithAnUnsetProviderStillFailsStartup`: each throws `IllegalStateException` naming
the property, what was found, and what's accepted — mirrors `CoreBankingClientConfiguration`
exactly.

### 6.6 `billedSegments` for a realistic Arabic OTP body

`StubMessageSenderTest.billedSegmentsIsComputedHonestlyForARealisticArabicOtpBody`, body:

> بنك السودان: رمز التحقق عبر الرسائل القصيرة الخاص بحسابك هو 482913. صالح لمدة 5 دقائق. لا
> تشاركه مع أحد.

(bank name, own channel named, code carried, validity stated — the shape customer.md's stage 2
requires). **104 characters, 2 segments** — matching the research's own 2-segment estimate and its
FIB anchor (also 104 characters) exactly. `Ucs2SegmenterTest` proves the boundary arithmetic
(70/67-char limits) independently.

### 6.7 Regression, unchanged

No committed JUnit suite exists yet for these (S2-04/S2-05 proved them via `psql` transcripts in
their own session reports, not committed tests) — reproduced the same way, live, against a fresh
`docker run postgres:18` container with all 35 migrations applied and Layer 3 installed. Full
transcript in §8.4. Summary:

- **Four-eyes still blocks**: the AD-005 §4.3 approve statement, run as `fru_app`, for the
  operator who manually completed the profile → `UPDATE 0`. The same statement for a different
  operator → `UPDATE 1` (rolled back — a control showing the predicate actually discriminates).
- **Status guard still rejects an illegal transition**: `in_progress → approved` directly →
  `ERROR 23514`.
- **No-history-row-fails, unchanged**: a legal transition (`in_progress → submitted`) with no
  matching `profile_status_history` row inserted in the same transaction → the `UPDATE` succeeds
  but `COMMIT` fails with `23514` (the deferred constraint trigger), and the profile row is left
  unchanged.
- **Audit trail still append-only against the owner**: `fru_app` `UPDATE`/`DELETE` against both
  `audit.audit_event` and `app.profile_status_history` → `permission denied` (`42501`).

---

## 7. `@agent-reviewer` findings and disposition

The reviewer was run against the diff and this task (full prompt included the task's proof
requirements and out-of-scope list). Findings and what was done about each:

| Finding | Severity | Disposition |
|---|---|---|
| `recordResult` and the `notification_dispatched` audit write were two separate autocommit transactions — AD-005 §6 requires them to commit together; a failure between the two would leave a terminal outbox row with no audit record | SHOULD FIX | **Fixed.** `OutboxDispatcher` now takes a `PlatformTransactionManager` and wraps `recordResult`+`audit` in one `TransactionTemplate` transaction, opened after `send()` returns (send stays outside any transaction — unbounded external I/O, same reasoning AD-005 gives for the enqueue-time transaction). `TransactionTemplate` rather than `@Transactional`: `dispatchOnePending()` calls `dispatch()` internally, so a proxy-based annotation there would silently not apply (AOP self-invocation). New tests prove the ordering (`send → getTransaction → recordResult → audit → commit`) and that a `recordResult` failure rolls back and never reaches the audit write. `BackendApplicationTests` needed a mocked `PlatformTransactionManager` bean added (DataSource autoconfiguration is excluded there). |
| The outcome→state retry mapping (a chosen behaviour) lived in `jdbc` (plumbing) and was untested for REJECTED/PERMANENT_FAILURE/TRANSIENT_FAILURE | SHOULD FIX | **Fixed.** Extracted to `notification.domain.OutboxState.forOutcome(DispatchOutcome)`, pure logic, plain-JUnit tested (`OutboxStateTest`, all four outcomes). |
| The claim/lease mechanism — the thing the SQL's own Javadoc argues for — had no test; also `recordResult`'s `rowsUpdated != 1` path | SHOULD FIX | **Fixed.** Added `aSecondClaimImmediatelyAfterTheFirstFindsNothingDueToTheLease` and `recordingAResultForAnUnknownOutboxIdFailsRatherThanSilentlyDoingNothing` to `NotificationOutboxIntegrationTest`. `channelForWireValue`'s throw path left untested — accepted, unreachable in practice since the CHECK constraint prevents any other value ever landing in the column. |
| `theProfileChainStillVerifiesAfterTheNotificationEventLandsOnIt` could pass vacuously if it ran before the end-to-end test (JUnit doesn't order methods by default) | SHOULD FIX | **Fixed.** Added `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` + `@Order`, and the test now first asserts the `notification_dispatched` event actually exists before calling `verify_chain()`. |
| This session report did not exist yet at review time | SHOULD FIX | **Fixed** — this file. |
| `docs/components/messaging.md` still said two questions were "not yet assigned an OQ number" after this same diff numbered them OQ-021/OQ-022 in `PROJECT_PLAN.md`; `PROJECT_PLAN.md`'s AD-002c entry still said "OQ-016 through OQ-020" | NOTE | **Fixed.** Both bullets now cite OQ-021/OQ-022 by number; `PROJECT_PLAN.md` now says "OQ-016 through OQ-022". |
| The stub omits AD-002c §5.9's "records the attempt where a test can read it" | NOTE | **Fixed.** `StubMessageSender.recordedSends()` (backed by a `CopyOnWriteArrayList`), new test `recordsEveryAttemptWhereATestCanReadIt`. |
| `MessageSenderConfigurationTest`'s "still fails startup" test set `enabled=true`, not the disabled-channel case the class's own Javadoc specifically claims | NOTE | **Fixed.** Added `aDisabledChannelWithAnInvalidProviderStillFailsStartup`. |
| `MessageDispatchResult.accepted()` had no main-code caller (only a test used it) | NOTE | **Fixed.** Removed; the one test usage now builds the record via its canonical constructor. `permanentFailure()` left as-is — it is used by `ChannelRoutingMessageSender`. |
| `attempt_count` is incremented but never read or capped | NOTE | **No action** — reviewer's own assessment: not a gap against S3-05's scope, since scheduling/backoff is explicitly out of scope. |

All fixes were re-verified: `./mvnw test` (123 tests), `./mvnw test -Pdb-integration-test` (137
tests), `./mvnw verify` (Spotless clean, JaCoCo 80% gate met) — all green after every fix. The
reviewer was then asked to re-check the updated diff and confirmed 9 of the 10 findings resolved
as described, with one exception noted below.

### 7.1 Second review pass — one new issue found in the fix itself

The reviewer's re-check surfaced a genuine new problem introduced by the stub-recorder fix
(§7 row 7, "NOTE — stub omits §5.9's attempt recorder"):

**SHOULD FIX — `StubMessageSender`'s attempt recorder was an unbounded singleton retaining
destinations and rendered OTP bodies.** `recordedSends` was appended on every `send()` with no
bound, and `stub` is not test-only — it is the only value `fru.messaging.<channel>.provider`
accepts today, so this ran in every deployment, not just tests. Two consequences: unbounded heap
growth proportional to total messages ever sent across a whole-campaign process, and — more
seriously — each retained `OutboundMessage` carried its `payload`, which for an SMS/WhatsApp OTP
*is the rendered code*. AD-002c report §5.3 states the port's whole point is that there is "no
way for a caller to ask the port what code was sent" (the structural fix for the FIB defect in
§3.1), and a public `recordedSends()` accessor answered exactly that question. It also stood
against `app.otp_challenge`'s own rule that the code is never stored.

**Fixed.** `StubMessagingProperties` gained a `recordSends` flag (default `false`) — recording is
now opt-in, matching AD-002c §5.9's actual wording ("records the attempt where a test can read
it": a test-time convenience, not a standing production log). When enabled, `StubMessageSender`
records a new `SentMessage(messageId, channel, destination)` — deliberately not the full
`OutboundMessage`, so the payload (and therefore any OTP code) is never retained even when
recording is on. Tests: `recordingIsOffByDefaultSoNoAttemptIsKept` and
`recordsEveryAttemptWhereATestCanReadItWhenExplicitlyEnabled` (`StubMessageSenderTest`).

Re-verified: `./mvnw test` (124 tests), `./mvnw test -Pdb-integration-test` (138 tests), `./mvnw
verify` (Spotless clean, JaCoCo 80% gate met, 35 classes) — all green, pasted in §8.

Residual points from the second review pass, not requiring action:
- The rollback path in `OutboxDispatcherTest` is proven against a mocked
  `PlatformTransactionManager` (proves `rollback()` is *called*), not a database-level rollback —
  correct by construction since `JdbcAuditEventWriter` and `JdbcNotificationOutboxRepository`
  share the same injected `JdbcTemplate`/`DataSource`, so `JdbcTransactionManager` binds one
  connection to both; `NotificationOutboxIntegrationTest` exercises the real thing.
  `AccountCheckService`'s Javadoc argues a `service` class should stay "free of any claim about a
  database"; `OutboxDispatcher` now holds a `PlatformTransactionManager`. Checked against
  CLAUDE.md's actual rule (testability, not zero-database-awareness) and found consistent:
  `OutboxDispatcherTest` is plain JUnit with mocks, no container, no real database.
- Two stale test comments (`NotificationOutboxIntegrationTest`'s Javadoc says "three tests", now
  five; a comment still argues from "JUnit does not guarantee order", which `@TestMethodOrder` has
  since settled) — cosmetic, not defects, left as found.

---

## 8. Verify and report — full transcripts

### 8.1 Flyway from scratch

```
$ docker run -d --name fru-s305-proof -e POSTGRES_PASSWORD=bootstrap_pw -e POSTGRES_DB=fru \
    -e FRU_MIGRATOR_PASSWORD=migrator_pw -e POSTGRES_INITDB_ARGS="--locale-provider=icu --icu-locale=ar" \
    -p 55432:5432 -v ".../db/init/01-create-migrator.sh:/docker-entrypoint-initdb.d/01-create-migrator.sh:ro" \
    postgres:18

$ DB_PORT=55432 DB_NAME=fru DB_MIGRATOR_PASSWORD=migrator_pw FRU_APP_PASSWORD=app_pw \
  FRU_SEALER_PASSWORD=sealer_pw ./mvnw flyway:migrate
...
[INFO] Database: jdbc:postgresql://localhost:55432/fru (PostgreSQL 18.6)
[INFO] Schema history table "public"."flyway_schema_history" does not exist yet
[INFO] Successfully validated 35 migrations (execution time 00:00.118s)
[INFO] Creating Schema History table "public"."flyway_schema_history" ...
[INFO] Current version of schema "public": << Empty Schema >>
[INFO] Migrating schema "public" to version "0001 - roles and schemas"
...
[INFO] Migrating schema "public" to version "0034 - audit system account check chain"
[INFO] Migrating schema "public" to version "0035 - app notification outbox"
[INFO] Successfully applied 35 migrations to schema "public", now at version v0035 (execution time 00:01.276s)
[INFO] BUILD SUCCESS
```

### 8.2 Idempotent re-run

```
$ ./mvnw flyway:migrate
[INFO] Database: jdbc:postgresql://localhost:55432/fru (PostgreSQL 18.6)
[INFO] Successfully validated 35 migrations (execution time 00:00.299s)
[INFO] Current version of schema "public": 0035
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

### 8.3 Layer 3 installation (needed for the append-only regression proof)

```
$ docker exec -i -e PGPASSWORD=bootstrap_pw fru-s305-proof psql -U postgres -d fru \
    < db/post-migrate/01-audit-event-trigger.sql
DROP EVENT TRIGGER
CREATE EVENT TRIGGER
DROP EVENT TRIGGER
CREATE EVENT TRIGGER

$ docker exec -e PGPASSWORD=bootstrap_pw fru-s305-proof psql -U postgres -d fru \
    -c "SELECT evtname, evtevent, evtenabled FROM pg_event_trigger WHERE evtname LIKE '%audit%';"
       evtname        |    evtevent     | evtenabled
----------------------+-----------------+------------
 fru_audit_ddl_guard  | ddl_command_end | O
 fru_audit_drop_guard | sql_drop        | O
(2 rows)
```

### 8.4 Regression proofs — full live transcript

Fixture: profile A (submitted, manually completed by operator `opA`) and profile B
(`in_progress`), both built by hand as `fru_migrator` (see `NotificationOutboxIntegrationTest`'s
Javadoc for why this technique is needed — no application code creates profiles yet, BL-006).

```
########## APP PROOFS (as fru_app) ##########
=== Proof: four-eyes still blocks (AD-005 §4.3 approve statement, operator = manual completer) ===
UPDATE 0
=== Confirm: same statement with a DIFFERENT operator is not blocked by four-eyes (rolled back) ===
BEGIN
UPDATE 1
ROLLBACK
=== Proof: append-only -- fru_app cannot UPDATE audit.audit_event ===
ERROR:  permission denied for table audit_event
=== Proof: append-only -- fru_app cannot DELETE audit.audit_event ===
ERROR:  permission denied for table audit_event
=== Proof: append-only -- fru_app cannot UPDATE app.profile_status_history ===
ERROR:  permission denied for table profile_status_history
=== Proof: append-only -- fru_app cannot DELETE app.profile_status_history ===
ERROR:  permission denied for table profile_status_history

########## MIGRATOR PROOFS (as fru_migrator) ##########
=== Proof: status guard still rejects an illegal transition (in_progress -> approved, skipping submitted) ===
ERROR:  illegal status transition on app.profile 22222222-2222-2222-2222-222222222222: in_progress -> approved
CONTEXT:  PL/pgSQL function app.check_status_transition() line 19 at RAISE
=== Confirm: profile B is still in_progress after the rejected attempt ===
              profile_id              |   status
--------------------------------------+-------------
 22222222-2222-2222-2222-222222222222 | in_progress
(1 row)

=== Proof: a legal transition with NO matching history row fails at commit ===
BEGIN
UPDATE 1
ERROR:  status change on app.profile 22222222-2222-2222-2222-222222222222 (in_progress -> submitted) has no matching (most recent) app.profile_status_history row
CONTEXT:  PL/pgSQL function app.require_status_history() line 22 at RAISE
=== Confirm: profile B is still in_progress -- the failed commit rolled the UPDATE back too ===
              profile_id              |   status
--------------------------------------+-------------
 22222222-2222-2222-2222-222222222222 | in_progress
(1 row)
```

### 8.5 `./mvnw test -Pdb-integration-test` (closing run, after all review fixes including §7.1)

```
[INFO] Running sd.gov.bank.fruserupdate.accountcheck.AccountCheckIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 38.87 s
[INFO] Running sd.gov.bank.fruserupdate.AppSchemaConnectivityIntegrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running sd.gov.bank.fruserupdate.notification.NotificationOutboxIntegrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.04 s
[INFO] Running sd.gov.bank.fruserupdate.notification.service.OutboxDispatcherTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running sd.gov.bank.fruserupdate.notification.domain.OutboxStateTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running sd.gov.bank.fruserupdate.messaging.stub.StubMessageSenderTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] Results:
[INFO] Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 8.6 `./mvnw verify` (closing run — Spotless + JaCoCo)

```
[INFO] Tests run: 124, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 35 classes
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 61 files clean - 0 needs changes to be clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

JaCoCo bundle: **35 classes**, **95.0% line coverage** (2087/2197 lines), **90.3% branch
coverage** (140/155 branches) — both well above the enforced 80% line-ratio gate.

### 8.7 backoffice / mobile — untouched

This session touched only `backend/`. Per CLAUDE.md, "run the test and analyze gates for every
tier you touched" — `backoffice/` and `mobile/` were not touched, so their gates were not run.

---

## 9. Files touched

- `RISKS.md`, `PROJECT_PLAN.md`, `EXECUTION_PLAN.md` — plan-file corrections and the S3-05 row.
- `backend/src/main/resources/db/migration/V0035__app_notification_outbox.sql` — new.
- `backend/src/main/java/sd/gov/bank/fruserupdate/messaging/**` — new (14 domain classes,
  1 service, 1 config, 2 stub).
- `backend/src/main/java/sd/gov/bank/fruserupdate/notification/**` — new (3 domain, 1 service,
  1 jdbc).
- `backend/src/test/java/.../messaging/**`, `.../notification/**` — new tests (12 test classes).
- `backend/src/main/resources/application.properties` — new `fru.messaging.*` block.
- `backend/src/test/java/sd/gov/bank/fruserupdate/{BackendApplicationTests,
  AppSchemaConnectivityIntegrationTest, accountcheck/AccountCheckIntegrationTest}.java` — new
  `fru.messaging.*` properties/beans so existing Spring contexts still start (the new
  `MessageSenderConfiguration`/`OutboxDispatcher` beans have no default and need a
  `PlatformTransactionManager`).
- `docs/components/messaging.md`, `docs/components/persistence.md` — status updated to built.
- `docs/sessions/2026-08-29-s3-05-messaging-port-and-outbox.md` — this file.

---

## 10. What failed / open items

Nothing failed outright. Everything the reviewer flagged as SHOULD FIX or worth fixing was fixed
and re-verified. Recorded, not fixed (out of scope by the task's own text or by the reviewer's own
assessment):

- No concrete adapter, no scheduled dispatcher loop — explicitly OUT OF SCOPE.
- `attempt_count` written but never capped/read — scheduling's problem, not this task's.
- The stub's Javadoc-documented lease duration (30s) is a placeholder for the one-at-a-time
  dispatch this task proves; a real scheduler needs a considered value.

---

## 11. Commit/push proof

```
$ git log --oneline -1
2aa6ef9 feat: S3-05 — the MessageSender port, its stub, and app.notification_outbox

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```

Push output:
```
To https://github.com/Osmantou/Fr_user_update
   fd9bddf..2aa6ef9  main -> main
```
