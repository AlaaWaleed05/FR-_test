# Component: Core banking (`CheckAccount` middleware)

Status: **contract observed live 2026-09-04 (S1-04); real adapter built 2026-09-04 (S3-02, re-scoped); adapter proven against the real middleware 2026-09-04 (S3-14 tail)**
Last verified: 2026-09-07 (S7-12) — **all three Response_Codes now proven live**, including `1`
"Account Found" against a real account for the first time; reachable from the AWS backend's own
network path. Previously 2026-09-04 — three live calls at discovery plus the adapter's own live test green
on **port 9494**, fabricated values only. The port changed during the day: see the contract table
and "Live proof" below.
Card rewritten 2026-09-04 by the S3-02 `@agent-researcher` step and the build that followed.
Supersedes the Oracle `ProcessOmniCheckAct` card entirely (AD-007); that card's content is
history, recoverable from git.

## The contract

| Item | Status | Source |
|---|---|---|
| `POST https://<host>:9494/OMNI_PH3/resources/bankRoutes/CheckAccount` | [OBSERVED] | S1-04 observed the same path on **port 9191**; the product owner supplied **9494** later the same day and the adapter's live test is green against it. Port 9191 now refuses TCP outright. The port is configuration — this row records which one answers, not a contract change |
| Request `{"Account": "<account number>"}`, `Content-Type: application/json` | [OBSERVED] | same |
| Branch is NOT sent | Settled by decision | OQ-024, product owner 2026-09-04 |
| Response `{"Response_Code": …, "Response_Message": …}` | [OBSERVED] | same |
| **Every outcome is HTTP 200**; the result is in the body, never the status | [OBSERVED] | same, all three calls |
| `Response_Code` arrives as a JSON **number**; the product owner's example was a **string** — accept both | [OBSERVED] | same |
| Code set closed at `1` found / `0` not found / `-1` system error | Settled by decision | OQ-025, product owner 2026-09-04. Anything else is fail-closed and logged |
| No authentication, no login, no cookie, no client certificate | [OBSERVED] | same |
| TLS: DigiCert-issued wildcard → `DigiCert Global Root G2`, already in JBR 21 `cacerts`; TLSv1.2 | [OBSERVED] | same |
| Server: Eclipse GlassFish 8.0.0, Servlet 6.0, Java 21; responses HTTP/1.1 | [OBSERVED] | same |
| Round-trip 1.21–1.22 s, three calls, from outside the bank's network | [OBSERVED] | same. **Re-measured on port 9494 (S3-14 tail): 0.79–0.98 s, three calls** — TLS handshake ~0.46–0.58 s of it, 58-byte reply. The 5 s connect / 10 s read defaults keep a wide margin |
| Behaviour on outage (timeout / error / body) | [UNVERIFIED] | never exercised — OQ-025. **Correction (S3-14 tail):** the "host stopped accepting TCP connections" recorded at S3-02 was almost certainly the move off port 9191, not an outage — 9494 and 5353 both connect in ~0.14 s while 9191 refuses. No outage shape has actually been observed |
| Behaviour for an inactive/dormant/closed account, or a non-numeric value | [UNVERIFIED] | never exercised — OQ-025. **S7-12 narrows this:** a real, active account now answers `1`, so only the inactive/dormant/closed and non-numeric shapes remain unobserved |
| `Response_Code: 1` / `"Account Found"` for a real, existing account | [OBSERVED] | **S7-12, 2026-09-07** — the path this card had recorded as unreachable, because reaching it needs an account that exists. The product owner supplied one; `HttpCoreBankingClientLiveTest.aRealAccountIsFound` (env-gated on `FRU_CORE_BANKING_LIVE_ACCOUNT`, value never printed, asserted on or committed) returned HTTP 200, `application/json`, `{"Response_Code":1,"Response_Message":"Account Found"}` |
| Reachable from AWS (`eu-central-1` Fargate, via NAT) | [OBSERVED] | **S7-12** — `infra/aws/11-egress-probe.sh` from the backend's own security group, subnet and NAT: `mb1…:9494 tcp=OPEN`. Note the admin host CANNOT test this: `fru-staging-admin-sg` has no 9494/5353 egress, so a probe from there times out and reads exactly like an unreachable bank endpoint |
| Whether a non-JSON (`text/html`) body is ever returned by THIS endpoint | [UNVERIFIED] | observed on the sibling Civil Registry service, OQ-023(a); assumed possible, handled defensively |
| Rate limit / concurrency limit | [UNVERIFIED] | never asked |

## How it is built (S3-02)

- **Port** `corebanking.domain.CoreBankingClient` — `CoreBankingCheckResult check(String accountNumber)`.
  Branch gone (OQ-024). The result record carries the raw `code`, the raw `message`, and a
  `RawExchange` (request bytes, response bytes, response media type, HTTP status) — null from the
  stub, so no invented artifact ever reaches the audit trail.
- **Adapter** `corebanking.http.HttpCoreBankingClient` — plumbing package, not `domain`/`service`.
  Takes a finished `RestClient` so tests can bind `MockRestServiceServer` to the builder.
- **Selection** by `fru.core-banking.client` = `stub` | `http`. **No default** — unchanged from S3-01.
  `http` without `fru.core-banking.http.endpoint` fails at startup naming the property.
- **Configuration** `fru.core-banking.http.{endpoint,connect-timeout,read-timeout}`; endpoint has no
  default, timeouts default to 5 s / 10 s. Host and path are configuration, never literals.
- **Timeouts, and why they are explicit.** [OBSERVED at research] On this classpath — no
  Apache/Jetty/Netty client, no Boot http-client autoconfiguration — `RestClient.builder().build()`
  yields a `JdkClientHttpRequestFactory` over a default `java.net.http.HttpClient` with **no connect
  and no read timeout**; `spring.http.client.*` does nothing here. `CoreBankingClientConfiguration.restClient`
  therefore builds the `HttpClient` with the connect timeout, HTTP/1.1 pinned and redirects never,
  and sets the read timeout on the factory. **No retries**: the call is on the customer's request
  thread and their own "try again" is the retry.
- **Retrieval** as `ResponseEntity<byte[]>` with `onStatus(s -> true, (req, res) -> {})`, so the body
  is byte-identical for the audit artifact and a non-200 is captured rather than thrown away.
- **Parsing** branches explicitly on `isIntegralNumber()` / `isString()`. [OBSERVED at research]
  Jackson 3's `asInt()` coerces JSON null and a missing node to `0` — now the real "Account not
  Found" code — so a bare `asInt()` would fail OPEN on a malformed response. Absent, null, boolean,
  object, array, fractional, out-of-int-range and non-numeric-string codes all fail closed.

## Journey mapping and what "fail closed" returns

| Middleware answer | Adapter | `AccountCheckService` audit outcome | Wire |
|---|---|---|---|
| `1` | code 1 | `ACTIVE` (+ the S3-07/S4-06 profile branch: PROCEED / TERMINAL / BLOCKED) | 200 |
| `0` | code 0 | `INVALID` / `RETRY` | 200 |
| `-1` | code -1, returned verbatim | `SYSTEM_ERROR`, response artifact stored, then `CoreBankingUnavailableException` | **503** |
| any other integer (e.g. `7`), as number or string | returned verbatim | `UNMAPPED`, artifact stored, `IllegalArgumentException` — a contract defect, not a transient | **500** |
| `Response_Code` absent / null / non-numeric; empty or non-JSON body | `CoreBankingUnavailableException` carrying whatever bytes arrived | `CALL_FAILED`, artifact if any bytes | 503 |
| no response (connect/read timeout, TLS, DNS) | `CoreBankingUnavailableException` carrying the request bytes only | `CALL_FAILED`, request artifact only | 503 |

`INACTIVE` (the Oracle contract's `2`) is removed from `AccountCheckOutcome`. No new wire value was
introduced: the app decodes outcomes by name with exhaustive switches. The one mobile change is
`DioEntryApi._mapAccountCheckError` mapping any 5xx to `BackendUnreachableException`, so a 503
shows the entry screen's "could not reach the server, try again" state and, at cold launch,
resumes offline instead of surfacing a raw error (`launchDecision()` catches only that type). `AccountOutcome.inactive` remains in the Dart enum, unreachable and harmless.

## The audit record

`audit.audit_event.artifact_id` is one FK, so the two raw artifacts need two events. Both go on the
existing `system`/`account_check` chain (V0034) under one `requestId`, written after the call in
this order — the same commit-or-throw posture S3-01 chose (no surrounding transaction):

| Event | Artifact | Payload |
|---|---|---|
| `account_check_requested` — only when request bytes exist (never against the stub) | `omni_check_request`, `application/json`, the exact bytes sent | `branch`, `accountNumber` |
| `account_check_attempted` (S3-01's event) | `omni_check_response`, the observed `Content-Type`, the exact bytes received — absent when nothing came back | `branch`, `accountNumber`, `resultCode`, `responseMessage`, `httpStatus`, `outcome`, `profileStatus`, `profileTerminal`, `blockedUntilIso` |

Against the stub there is no exchange: one event, no artifacts, exactly as before S3-02.

### Profile identity follows the check (BL-032, V0061)

Because the check carries no branch, the profile's identity carries none either: since V0061
(2026-09-04, product-owner decision) `app.profile`'s `profile_one_per_account` is
`UNIQUE (account_number)` — same constraint name as V0005, account-only column list — and
`ProfileRepository.findExisting(accountNumber)` is what Stage 1a's TERMINAL/PROCEED/BLOCKED branch
and Stage 1b's re-entry decision key on. The branch the customer selected is descriptive data on the
profile: written at creation, refreshed to the latest selection on a Stage 1b re-entry
(`updateBranchCode`, no `row_version` bump), and present in every audit payload above so the
previous value survives on the chain. A customer who completed under one branch and returns under
another is told the update is already complete; one who is mid-journey lands on the same profile.

### `app.omni_check` — an open item, not settled here

V0060 makes the table truthful (branch nullable, CHECK `(1, 0, -1)`, corrected column comments) and
nothing writes it. customer.md Stage 1a says "nothing is written to the profile database at this
stage", and `AccountCheckService` lives in a `service` package with no repository. Three readings
remain open: drop the table (dead by design, the audit trail is the record); keep it for a
post-Stage-1a re-check the journey has not defined; or correct customer.md and write it through a
new port. A product-owner call, filed on BL-031's closure.

## Live proof

- **All three paths are now covered live. `1` "Account Found" was closed at S7-12 (2026-09-07)** by
  `aRealAccountIsFound`, a sibling method carrying its own `FRU_CORE_BANKING_LIVE_ACCOUNT` gate so the
  other three still run without an account. It times TWO calls deliberately: the first reported
  **1970 ms** and the second **359 ms**, so the cold figure is JVM warm-up plus a cold TLS handshake,
  not the middleware — reporting only the first would have made the 5 s connect timeout look marginal
  when warm steady state is *faster* than the 0.79–0.98 s this card records. The account number reaches
  the test only as an environment variable and appears in no log, assertion, report or commit.
- `0` and `-1` paths: `HttpCoreBankingClientLiveTest` (`@Tag("live")`, enabled only by
  `FRU_CORE_BANKING_LIVE_ENDPOINT`, excluded from every gate) calls the real adapter with the
  fabricated values S1-04 already used. **GREEN 2026-09-04, 3/3 against port 9494** — the real
  middleware answered exactly the contract this card states, through the real adapter. Full
  evidence, including the port finding and the re-measured round-trip:
  **docs/sessions/2026-09-04-civil-registry-http-adapter.md, "Addendum", §1 and §3** (that report
  is named for the Civil Registry because the same tail closed both endpoints' live proofs):

  ```
  [live] input=00000000 http=200 content-type=application/json body={"Response_Code":0,"Response_Message":"Account not Found"}
  [live] input=<empty> http=200 content-type=application/json body={"Response_Code":-1,"Response_Message":"System Error"}
  [live] input=99999999999999999999 http=200 content-type=application/json body={"Response_Code":0,"Response_Message":"Account not Found"}
  ```

  So the two non-success paths are now proven end to end, not merely mocked: every outcome HTTP
  200, the code in the body, `Response_Code` a JSON number, `-1` reached the 503 path. The earlier
  "not yet run green" note is superseded — it was the port, not an outage.
- `1` path: **mock-only by rule** — the only known-good account is a real customer's.
- Timeouts: `HttpCoreBankingClientTimeoutTest` (a JDK `HttpServer` holding the response past the
  read timeout) plus revert-restore at S3-02 — with `setReadTimeout` removed, the test fails with
  "nothing was thrown" after waiting for the server.

## Rules that apply here

- Never key on HTTP status. Every observed outcome, including the error, was 200.
- Never assume `application/json` on the response.
- Never `asInt()` the code.
- Fabricated account values only, here and everywhere (CLAUDE.md hard rule).
- The middleware host and path are configuration; the tenant changes before production.
