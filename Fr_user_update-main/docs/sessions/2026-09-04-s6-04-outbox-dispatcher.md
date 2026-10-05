# S6-04 — a scheduled runner that drains `app.notification_outbox`

Date: 2026-09-04 · Backend only · Windows (DELL) · Branch `main`

## The finding

`OutboxDispatcher.dispatchOnePending()` has existed and worked against a real PostgreSQL since
S3-05. Nothing called it. `grep -rn "Scheduled\|EnableScheduling" backend/src/main` returned zero
hits before this session: there was no scheduler anywhere in the codebase. Every `DEFERRED` message
the journey enqueues — the submission notification, and every approve/reject/manual-completion
status transition — was written to `app.notification_outbox` and never sent.

OTP was never affected: `ContactChannelsService` sends `Urgency.INTERACTIVE` inline from the request
thread, never through the outbox (messaging.md §S3-06).

## What was built

| File | What |
| --- | --- |
| `notification/domain/OutboxDrainSummary.java` | New record `(attempted, failed, reachedCap)`. Counts only — a summary is logged every tick, and `destination`/`payload` are PII. |
| `notification/service/OutboxDispatcher.java` | New `drainPending()`: the loop, per-attempt failure isolation, `MAX_ATTEMPTS_PER_TICK = 50`. Class Javadoc's "deliberately not a scheduler" paragraph rewritten. |
| `notification/scheduler/OutboxDispatchScheduler.java` | New `@Component`, `@Scheduled(fixedDelayString = "${fru.notification.outbox.poll-interval:30s}", initialDelayString = "${…poll-initial-delay:30s}")`. Calls `drainPending()`, logs the summary. |
| `BackendApplication.java` | `@EnableScheduling`. |
| `application.properties` | Both poll properties documented (commented out — they have defaults, so the class stays the single source of truth for the values). |

### Why the loop is in `service` and the timer is not

CLAUDE.md reserves `domain`/`service` for chosen behaviour a plain JUnit test can exercise with no
Spring context. The drain policy — how many rows one pass takes, what a failing row does to the rest
of the queue — is exactly that, so it is `OutboxDispatcher.drainPending()`. What is left in
`notification.scheduler` (a new adapter package) is a schedule and a log line.

### Why 30 seconds

1. Nothing in this queue is time-critical. It carries `DEFERRED` messages only — "we received your
   submission", "your profile was approved". The one message a customer actively waits on never
   enters the outbox.
2. **30 s is already the claim lease.** `JdbcNotificationOutboxRepository.CLAIM_ONE_PENDING` advances
   a claimed row's `next_attempt_at` by `interval '30 seconds'`. A row left `pending` by a
   TRANSIENT_FAILURE cannot become due sooner than that, so polling faster cannot deliver anything
   sooner — it only runs queries that find nothing. The lease is the natural floor for the poll
   period, so the two are tied rather than picked independently.
3. Idle cost is one indexed query per 30 s against a normally-empty pending set.

`fixedDelay`, not `fixedRate`: the delay runs from the *end* of the previous pass, so a slow pass
never accumulates catch-up ticks behind it. Spring's default single-thread scheduler already
serialises runs; `fixedDelay` states that intent rather than depending on the pool size staying at 1.

### Failure isolation, and the cap

Each attempt is wrapped: a `RuntimeException` is caught, counted, logged at WARN, and the pass moves
to the next pending row. No bookkeeping is added for the failed row — `claimOnePending()` already
advanced its lease, so it drops out of the pass and is due again later. **No retry-backoff schedule
and no dead-letter state**, per the task: the messaging tier is still `StubMessageSender`.

`MAX_ATTEMPTS_PER_TICK = 50` is AD-005 §6's own `LIMIT 50` figure. What makes it load-bearing is not
a backlog but a broken dependency: with the database unreachable, `claimOnePending()` throws on every
iteration, there is no "next row" to move to, and the empty-claim exit is never reached — the cap is
the loop's only termination. Cost of that path: ~50 WARN stack traces per tick while the database is
down.

## Test safety — the one real design constraint

Every `@Tag("integration")` class shares **one JVM-lifetime Postgres container** (S3-09), and
`AbstractPostgresIntegrationTest`'s own Javadoc is explicit that `claimOnePending()` is *unscoped*:
each enqueuing class pushes `next_attempt_at` an hour out so no other class's poll steals its rows.
A background timer firing inside the test JVM would break that contract for eight-plus classes, from
a thread no test controls.

`AbstractPostgresIntegrationTest` therefore registers both poll properties as `1h`, and
`BackendApplicationTests` (which does not extend it) sets them inline. Postponed rather than switched
off with a conditional bean, deliberately: the `@Scheduled` task stays **registered**, so the wiring
proof below is real, and every cached context in the run is covered rather than only the one class
that exercises the drain. The `@WebMvcTest` slices need nothing — their type filter excludes
`@Component`, so the scheduler is not in those contexts at all (confirmed by the reviewer).

## Proof

`NotificationOutboxIntegrationTest` `@Order(6)` — against real PostgreSQL 18, calling
`scheduler.pollOutbox()` (the method the timer calls), **not** `drainPending()` or
`dispatchOnePending()`: hides every already-pending row in the shared table an hour out to make the
tick deterministic, enqueues four rows — three to an accepted destination, one to `+249900000001`
which `application.properties` seeds the stub to REJECT, placed in the middle — then one tick, and
asserts three rows `dispatched`, the rejected one `failed`, and exactly four
`notification_dispatched` audit events. That the rows enqueued *after* the rejected one are
dispatched in the same tick is the "a bad message does not block the queue" claim.

`@Order(7)` — asserts via the container's `ScheduledTaskHolder` that a task is registered for
`OutboxDispatchScheduler.pollOutbox`. This is an indirect assertion about wiring, so per CLAUDE.md it
was proven by revert-restore: `@EnableScheduling` removed from `BackendApplication`, suite re-run.

```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 26.01 s <<< FAILURE! -- in
sd.gov.bank.fruserupdate.notification.NotificationOutboxIntegrationTest
[ERROR] sd.gov.bank.fruserupdate.notification.NotificationOutboxIntegrationTest.theScheduledTaskIsActuallyRegistered
-- Time elapsed: 0.019 s <<< FAILURE!
org.opentest4j.AssertionFailedError: no ScheduledTaskHolder in the context — @EnableScheduling is
missing, so no @Scheduled method anywhere in this application is ever called ==> expected: not <null>
```

Exactly one failure, and it is the wiring test: the `@Order(6)` drain test still passes without
`@EnableScheduling` (it calls the method directly), which is precisely why `@Order(7)` has to exist.
`@EnableScheduling` was restored immediately after.

The `ScheduledTaskHolder` is injected `@Autowired(required = false)`. The first revert run used a
required injection and failed all 7 tests at context startup on `NoSuchBeanDefinitionException` —
true, but blunt; optional injection puts the failure in one named assertion with a message that says
what is actually wrong.

## Review

`@agent-reviewer` against the diff and the S6-04 task text. Two findings, both real, both fixed:

| Severity | Finding | Disposition |
| --- | --- | --- |
| BLOCKER | `BackendApplication.java` written with LF line endings while the tree is CRLF; Spotless's GIT_ATTRIBUTES policy rejected it. Caused by rewriting the file during the revert-restore. | Fixed — `./mvnw spotless:apply`. Confirmed by the final gate's `spotless:check`. |
| NOTE | `drainPending()`'s Javadoc claimed a failed row is "invisible for the rest of this pass" unconditionally. That holds only while a pass runs shorter than the 30 s lease — true against the stub (a full 50-attempt pass is milliseconds), not necessarily true once a real adapter makes each send network I/O, where a long pass could re-claim and re-send a row it already handled. | Fixed — the Javadoc now states the pass-duration/lease coupling and names the lease sizing plus AD-005's batch claim as belonging to the real-adapter work. No code change: with the stub there is no latency to size against, and inventing a lease policy here would be exactly the speculative complexity the task rules out. |

The reviewer independently confirmed: no test context starts a live poller; `@Order(6)` is
deterministic (no JUnit parallelism configured, so classes never interleave); nothing the task ruled
out was built; no PII, no real account data, all phone values E.164; the `domain`/`service` additions
are context- and clock-free.

## Gates

`./mvnw verify -Pdb-integration-test` from `backend/` — the real backend gate per CLAUDE.md
(plain `verify` measures ~60% because the integration suite is excluded).

```
[INFO] Tests run: 803, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 308 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 416 files clean - 0 needs changes to be clean, 0 were already clean, 416 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 308 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:02 min
[INFO] Finished at: 2026-09-04T20:29:55+02:00
[INFO] ------------------------------------------------------------------------
```

Test-count reconciliation, 792 → 803 (+11). Only these three classes gained tests; no other test
file was touched:

| Class | Before | After |
| --- | --- | --- |
| `OutboxDispatcherTest` | 3 | 7 |
| `OutboxDispatchSchedulerTest` (new) | — | 5 |
| `NotificationOutboxIntegrationTest` | 5 | 7 |

Mobile and backoffice untouched — their gates were not run.

## Not done, deliberately

Retry backoff, dead-letter state, batch claiming (`LIMIT 50` in SQL), lease sizing for a real
provider's latency, multi-instance coordination, per-channel cadence. All ruled out by the task text
and all genuinely undecidable against a stub. The lease/pass-duration coupling above is the one to
carry into the real-adapter task.
