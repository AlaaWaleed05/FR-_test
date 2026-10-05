# 2026-09-04 — S3-02 (re-scoped): the real core-banking HTTP adapter

Task: EXECUTION_PLAN.md S3-02, re-scoped by AD-007 from "Oracle in Docker + JDBC" to the HTTPS/JSON
`CheckAccount` adapter; plus BL-031 (schema), BL-008's documented half (503 handler), and the port
reshaping OQ-024 implied. Plan approved in plan mode; researcher step run first and filed as
docs/sessions/2026-09-04-research-s3-02-corebanking-http.md. Fabricated account values only,
everywhere. No real account number appears in this session or this report.

## What was built

| Layer | Change |
|---|---|
| Port (`corebanking.domain`) | `CoreBankingClient.check(accountNumber)` → `CoreBankingCheckResult(code, message, RawExchange)`; branch dropped (OQ-024). `CoreBankingUnavailableException` carries the raw exchange for the failure-path audit. |
| Adapter (`corebanking.http`, new) | `HttpCoreBankingClient`: JSON POST `{"Account": …}`, `ResponseEntity<byte[]>` with the status handler disabled, explicit `isIntegralNumber()`/`isString()` parsing, fail-closed on absent/null/non-integer codes and non-JSON or empty bodies. `CoreBankingHttpProperties` (`fru.core-banking.http.*`): endpoint with no default, 5 s connect / 10 s read defaults. |
| Configuration | `fru.core-banking.client=http` beside `stub`, still no default. `restClient()` builds the JDK `HttpClient` with the connect timeout, HTTP/1.1 pinned, redirects never, and sets the read timeout on the `JdkClientHttpRequestFactory` — because the default `RestClient` on this classpath has no timeouts at all (research §3.2). |
| Stub | Keyed by account number alone; unknown → `0`; no invented exchange. |
| Outcomes | `AccountCheckOutcome`: `ACTIVE(1)`, `INVALID(0)`; `INACTIVE` removed. |
| Service | `-1` → audited `SYSTEM_ERROR` with the reply as artifact, then `CoreBankingUnavailableException`; unavailable → `CALL_FAILED` with whatever bytes exist; unrecognised parseable code → `UNMAPPED` (500, as before). Two events per real exchange: `account_check_requested` + `omni_check_request` artifact, then `account_check_attempted` + `omni_check_response` artifact, same `requestId`. Stub: one event, no artifacts. Payload gains `responseMessage`, `httpStatus`. |
| Web | `@ExceptionHandler(CoreBankingUnavailableException)` → 503 `ProblemDetail`. |
| Schema | `V0060__app_omni_check_corebanking_contract.sql`: `branch_code` nullable, CHECK `(1, 0, -1)`, three `COMMENT ON COLUMN`. Nothing writes the table (customer.md Stage 1a); the tension is an open item on the card. |
| Mobile | `DioEntryApi._mapAccountCheckError`: any 5xx → `BackendUnreachableException` (one branch), so a 503 at cold launch resumes offline. One new test. No package added, so no iOS support check arises. |
| Tests | New: `HttpCoreBankingClientTest` (23, `MockRestServiceServer`), `HttpCoreBankingClientTimeoutTest` (2, real socket), `HttpCoreBankingClientLiveTest` (3, `live` group, env-gated, never in a gate). Updated: configuration, stub, outcome, service (32), controller (503), integration (503 end to end; V0060 proof by rolled-back inserts). `pom.xml`: `live` added to the excluded groups in both the default and the integration profile. |

## Decisions settled (the five the plan named)

1. **Timeouts and retries:** 5 s connect, 10 s read, configurable; no retries; HTTP/1.1; no
   redirects. Reason: 1.2 s observed round-trips, a ~60-byte reply, and a customer-facing request
   thread that must never park on a wedged middleware.
2. **Result type:** `CoreBankingCheckResult(int code, String message, RawExchange exchange)` with
   `RawExchange(requestBody, responseBody, responseMediaType, httpStatus)`; null exchange from the stub.
3. **Fail closed:** `-1`, unreadable and no-response → 503 (audited `SYSTEM_ERROR` / `CALL_FAILED`);
   a parseable unknown code → 500 (`UNMAPPED`, a contract defect). Never a new wire value.
4. **Audit records:** two events per real exchange, one artifact each (the schema's 1:1), same
   `requestId`; payload fields listed above; stub writes none.
5. **Card:** docs/components/core-banking.md rewritten as the real card.

Also: customer.md Stage 1a rewritten (`0`/`-1`/`1`, no Oracle paragraph); EXECUTION_PLAN S3-02 ✅
with the re-scope in the task cell; BL-031 closed, BL-008 documented half closed; BL-032 filed
(profile identity is still `(branch, account)` while the check is by account — product-owner
question); PROJECT_PLAN OQ-024's consequence marked done; application.properties block rewritten.

## Gates (verbatim)

Backend, `./mvnw verify -Pdb-integration-test` (JAVA_HOME = JBR 21.0.8, Docker up):

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 45.15 s -- in sd.gov.bank.fruserupdate.accountcheck.AccountCheckIntegrationTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.207 s -- in sd.gov.bank.fruserupdate.accountcheck.domain.AccountCheckOutcomeTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.720 s -- in sd.gov.bank.fruserupdate.accountcheck.service.AccountCheckServiceTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.790 s -- in sd.gov.bank.fruserupdate.accountcheck.web.AccountCheckControllerTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.345 s -- in sd.gov.bank.fruserupdate.corebanking.config.CoreBankingClientConfigurationTest
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.303 s -- in sd.gov.bank.fruserupdate.corebanking.http.HttpCoreBankingClientTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.521 s -- in sd.gov.bank.fruserupdate.corebanking.http.HttpCoreBankingClientTimeoutTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in sd.gov.bank.fruserupdate.corebanking.stub.StubCoreBankingClientTest
[INFO] Tests run: 701, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 402 files clean - 0 needs changes to be clean, 0 were already clean, 402 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  02:25 min
exit=0
```

V0060 was applied by Flyway inside that run (the integration suite migrates from scratch), and
`v0060MakesOmniCheckAcceptTheMiddlewareContract` asserts the constraint name
`omni_check_result_code_check` by provoking it — so the constraint-name inference in the research
report is now observed.

Mobile (`mobile/`):

```
=== fvm flutter analyze ===
No issues found! (ran in 154.8s)
=== fvm flutter test ===
01:03 +226: All tests passed!
=== coverage ===
01:22 +226: All tests passed!
Line coverage: 85.05% (2184/2568 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

## Proofs beyond the gates

**Timeout wiring — revert-restore (indirect assertion, so a revert is required).** A green
`MockRestServiceServer` suite would coexist with a client that has no timeouts, because the mock
replaces the request factory. With `factory.setReadTimeout(properties.readTimeout())` removed from
`CoreBankingClientConfiguration.restClient`:

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 6.268 s <<< FAILURE! -- in sd.gov.bank.fruserupdate.corebanking.http.HttpCoreBankingClientTimeoutTest
[ERROR]   HttpCoreBankingClientTimeoutTest.aResponseSlowerThanTheReadTimeoutIsAnOutage:91 Expected sd.gov.bank.fruserupdate.corebanking.domain.CoreBankingUnavailableException to be thrown, but nothing was thrown.
```

— the client waited the server out and read the body. Restored:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.644 s -- in sd.gov.bank.fruserupdate.corebanking.http.HttpCoreBankingClientTimeoutTest
[INFO] BUILD SUCCESS
```

**Live proof against the real middleware — NOT achieved; blocked on the bank's side.** The
env-gated live test was run twice this session:

```
[ERROR]   HttpCoreBankingClientLiveTest.aRepeatedDigitAccountIsNotFound:47 » CoreBankingUnavailable no response from the core banking middleware: I/O error on POST request for "https://mb1.sfbank-sd.com:9191/OMNI_PH3/resources/bankRoutes/CheckAccount": HTTP connect timed out
[ERROR]   HttpCoreBankingClientLiveTest.aTwentyDigitAccountIsNotFound:57 » CoreBankingUnavailable ... HTTP connect timed out
[ERROR]   HttpCoreBankingClientLiveTest.anEmptyAccountIsTheMiddlewaresSystemError:67 » CoreBankingUnavailable ... HTTP connect timed out
[ERROR] Tests run: 3, Failures: 0, Errors: 3, Skipped: 0
```

Diagnosis, to rule out the adapter: the same host answered curl in 1.2 s this morning (S1-04). In
the afternoon curl (`--connect-timeout 20`, `OPTIONS`) timed out at the TCP connect on two separate
attempts, and a plain `java.net.http.HttpClient` probe timed out on both HTTP/1.1 and HTTP/2 with the
same DNS answer (`196.1.223.28`). The middleware host stopped accepting connections from this
machine; nothing in the adapter is implicated. What the failed run does show: the no-response path
raised `CoreBankingUnavailableException` at the configured 5 s connect timeout, wrapping the JDK's
`HttpConnectTimeoutException`, with no hang. The live test stays committed and inert; **rerun it at
the next session** (`FRU_CORE_BANKING_LIVE_ENDPOINT=<url> ./mvnw test -Dexcluded.test.groups=
-Dgroups=live -Dtest=HttpCoreBankingClientLiveTest`). The `1` path is mock-only by rule.

> **SUPERSEDED later the same day — done, and the diagnosis above was wrong.** The live test is
> **3/3 GREEN** against the real middleware once the product owner supplied the endpoint's real
> port, **9494**. The host had not gone down: the middleware moved off 9191, which now refuses TCP
> while 9494 and the registry's 5353 both connect in ~0.14 s. So this section's "the middleware
> host stopped accepting connections" is **not** an observed outage shape, and OQ-025's outage row
> still has none — the correction is recorded on docs/components/core-banking.md. Evidence, the
> re-measured round-trip (0.79–0.98 s on 9494) and the gate:
> docs/sessions/2026-09-04-civil-registry-http-adapter.md, "Addendum", §1 and §3.

## Reviewer findings and dispositions

`@agent-reviewer` on the full diff against the S3-02 row and CLAUDE.md's hard rules. No blockers.
Clean: fabricated values only; hostname only in reports and the live test's env var; `domain`
packages plain; adapter in `http`; V0060 app-schema only with the constraint name provoked live;
timeouts wired where the plan said; the read-timeout test proves the read timeout specifically
(local connect always succeeds, plus a released-immediately control); `INACTIVE` removal is
wire-safe; every Jackson 3 method used exists in `jackson-databind-3.1.4` (verified with `javap`).

| # | Finding | Disposition |
|---|---|---|
| 1 | SHOULD FIX — `AccountCheckController.java` had been rewritten with a literal U+0001 and U+0000 in its javadoc (the `\x01`/`\x00` escape text became the characters), so git treated the file as binary and the 503 handler was invisible in the diff | **Fixed**: escape text restored; the file diffs as text again |
| 2 | SHOULD FIX — a `Response_Code` beyond 64 bits is a `BigIntegerNode` whose `longValue()` throws before the int-range check, escaping as a generic exception: 500 instead of 503, and both artifacts lost | **Fixed**: `longValue()` wrapped, fails closed as unreadable with the bytes kept; the 20-digit value added to the parameterised test, as number and as string |
| 3 | SHOULD FIX — `mobile/bin/live_entry_flow_proof.dart` (outside every gate) still called the `…0002` account as "inactive"; it is now seeded to `-1`, so the script would die on the 503 before Stage 1b | **Fixed**: relabelled and wrapped, expecting `BackendUnreachableException` |
| 4 | SHOULD FIX — four places said a 503 shows the entry screen's "unexpected error, try again later"; with the new mapping it shows the "could not reach the server, try again" state instead | **Fixed** in customer.md, the card, the controller javadoc and the Dart comment; the screen test retitled to what it actually proves. The behaviour is kept: a middleware outage is the same situation as an unreachable backend |
| 5 | NOTE — `AccountCheckResponse` and `AccountCheckResult` javadoc still named `INACTIVE` and `ProcessOmniCheckAct` | **Fixed** |

## Gates after the review fixes (final code, verbatim)

Backend, `./mvnw verify -Pdb-integration-test`:

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 26.41 s -- in sd.gov.bank.fruserupdate.accountcheck.AccountCheckIntegrationTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.263 s -- in sd.gov.bank.fruserupdate.corebanking.http.HttpCoreBankingClientTest
[INFO] Tests run: 703, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 402 files clean - 0 needs changes to be clean, 0 were already clean, 402 were skipped because caching determined they were already clean
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  01:36 min
```

(701 → 703: the two BigInteger cases added to the adapter's parameterised test.)

Mobile, after the review fixes:

```
=== fvm flutter analyze ===
No issues found! (ran in 82.4s)
=== fvm flutter test ===
00:57 +226: All tests passed!
=== coverage ===
Line coverage: 85.05% (2184/2568 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

## What is not done, and why

- Live `0`/`-1` proof — see above. Not an implementation gap; a reachability outage.
- `app.omni_check` is still unwritten. Whether to write it, keep it, or drop it is a product-owner
  question recorded on the card and on BL-031's closure line.
- BL-032 (profile identity by account alone) — a schema question, not settled in passing.
- BL-021's mobile half (the `BLOCKED` continuation the app cannot decode) — pre-existing, noted by
  the research, out of this task's scope.

## Commit proof

```
$ git log --oneline -1
1616fd8 S3-02 (re-scoped, AD-007): real core-banking HTTP adapter for CheckAccount; port reshaped (account only, raw exchange); V0060 omni_check contract (BL-031); request/response audit artifacts; -1 and unusable answers -> 503 (BL-008 documented half); INACTIVE removed; mobile maps 5xx to BackendUnreachableException; BL-032 filed
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed straight to `origin/main`. 39 files, +2251/-444. This proof section was appended in a
follow-up commit, the same pattern as every report today.
