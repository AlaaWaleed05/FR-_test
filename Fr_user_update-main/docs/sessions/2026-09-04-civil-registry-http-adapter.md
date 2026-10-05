# 2026-09-04 — S3-14: the real Civil Registry `GetCRSData` HTTP adapter (AD-002b build; BL-030)

Task: replace `StubCivilRegistryClient` with the real HTTPS/JSON adapter per AD-002b's closed
decision, reusing S3-02's core-banking pattern; close BL-030. `@agent-researcher` ran first in this
session (findings below); plan approved in plan mode; built; reviewed; gates green. Fabricated
values only, everywhere. No real national number, no registry field value from a real record and
no hostname appears in the diff or in this report.

## Research findings (`@agent-researcher`, this session)

Scope: confirm S3-02's pattern transfers, and pin down timeouts, the `IDENTITY_NUMBER` guard, the
audit shape, and the `BIRTH_DATE`/`GENDER` degradation. No live call was made. Sources: the card,
the decisions file, S3-02's code and tests, `IdentityScanService`, V0002/V0008/V0023, the S3-02
and error-contract research reports. Each finding below was verified against the file it cites
before being built on.

| # | Finding | Evidence | Consequence built |
|---|---|---|---|
| 1 | S3-02's HTTP client and Jackson approach apply unchanged: same classpath, same JDK client, same host family and TLS chain; `RestClient.builder().build()` has no timeouts here | `CoreBankingClientConfiguration.restClient`, `HttpCoreBankingClient` [OBSERVED] | `restClient()` copied in shape; `ResponseEntity<byte[]>` with the status handler disabled; explicit node-type parsing |
| 2 | **No round-trip timing exists for this endpoint anywhere** — the discovery addendum and the five-test table record status, content type, bytes and field counts only; the only latency in the project is core banking's 1.2 s | plan-reconciliation report, decisions file [OBSERVED] | Read timeout is a reasoned budget, not a measurement |
| 3 | The binding ceiling is the mobile client, not GlassFish's documented 30 s request timeout: `dio_provider.dart` sets `receiveTimeout: 30 s` | `mobile/lib/core/network/dio_provider.dart:14-15` [OBSERVED] | **Read 15 s** (connect 5 s unchanged). A 30 s read would let the backend commit a result after the customer's socket had gone |
| 4 | No in-adapter retry: the journey already has an audited, customer-driven retry (`retryRegistryLookup`, `attempts`); a silent retry would double the worst case to the whole mobile budget; Q9 said one retry is *acceptable*, not required; the card's "at most one retry" was a session-derived line | `IdentityScanService.retryRegistryLookup`, decisions file Q9, card "derived" paragraph [OBSERVED] | None built; the card's derived paragraph corrected |
| 5 | `IDENTITY_NUMBER` must be a JSON **string** and is never coerced from a number: the five tests proved `NID` is string-bound and that `0` and eleven zeros are two records, so a numeric value would already have lost leading zeros | decisions file, five-test table [OBSERVED]; [INFERRED] | Rule 6 of the classification; a number is `identity_number_not_string` |
| 6 | Match = exact `equals` after `strip()` both sides; no leading-zero, Arabic-Indic, NFKC or case folding. `strip()` over `trim()` (Unicode space separators); neither removes NBSP/bidi marks, hence a log-only `differsOnlyByWhitespace` diagnostic | Java 21 `String.strip` [DOC]; [INFERRED] | Built as specified; WARN log carries lengths and the flag, never a value |
| 7 | `audit.audit_artifact.kind`'s CHECK lists `civil_registry_response` but no request kind — the first artifact kind the project has had to add; V0002's inline CHECK is named `audit_artifact_kind_check` by PostgreSQL | V0002 lines 18–26 [OBSERVED]; name [INFERRED, proven by V0062 running] | V0062 re-declares the CHECK under the same name (V0026 precedent), with `SET LOCAL fru.migration_in_progress` (R-035) |
| 8 | The completion event already exists on the profile chain and is written **inside** the transaction, unlike `AccountCheckService`; the request payload must not copy S3-02's `accountNumber` field — here it would be the national number in a hash-chained payload | `IdentityScanService` lines 440/681/879 (pre-change), S3-12 reviewer rule [OBSERVED] | Two events inside the transaction; request payload `{"requestBytes": n}` only |
| 9 | `yyyy` under `ResolverStyle.STRICT` requires an era and fails every input; `uuuu` is the proleptic year; `Locale.ROOT` per the record's own "never a locale default" | `DateTimeFormatter` [DOC] | `dd/MM/uuuu` STRICT `Locale.ROOT` |
| 10 | `IdentityScanService.decodePortrait` called `Base64.getDecoder().decode` unguarded — an undecodable `PHOTOGRAPH` would 500 after the Uqudo images were downloaded | `IdentityScanService` lines 503–508 (pre-change) [OBSERVED] | Decode moved into the adapter, MIME decoder, null on failure, lookup still `ok` |
| 11 | CLAUDE.md's quarantine rule forbids `civilregistry` → `corebanking` imports | CLAUDE.md Architecture | `RegistryExchange`, a four-field twin of `RawExchange` |
| 12 | A live test may use only values already exercised (Q8); every one of them returns either a real citizen's record or the 400 page | decisions file Q5/Q8 [OBSERVED] | Live test asserts the 400/not-found path only, with three zeros |

Unknowns the research left, all accepted: the endpoint's real latency (re-derive 15 s from the
first real timing); whether GlassFish 4.1 enforces its request timeout; whether `IDENTITY_NUMBER`
is ever padded, non-ASCII or numeric (the rules are chosen to be safe under all three, none was
observed); the outage shape.

## What was built

| Layer | Change |
|---|---|
| Port (`civilregistry.domain`) | `CivilRegistryClient.lookup(identityNumber)` → `RegistryLookup(record, identityNumberReturned, reason, exchange)` with `found()`; reason-code constants; `RegistryExchange`; `RegistryUnreachableException.exchange()` (request only); `RegistryLookupResult` gains `identityNumber`, carries `byte[] photograph`; `RegistryFieldNormaliser` (pure) for Q11. |
| Adapter (`civilregistry.http`, new) | `HttpCivilRegistryClient`: JSON POST `{"NID": "<string>"}`, no auth, byte-array retrieval whatever the status, the nine-rule classification in order, the guard, field extraction (every field optional, `strip()`, blank → null), MIME base64 decode. `CivilRegistryHttpProperties` (`fru.civil-registry.http.*`): endpoint no default, 5 s / 15 s. Logging: WARN on mismatch and undecodable/absent photograph, INFO on routine not-found; never a value. |
| Configuration | `fru.civil-registry.client=http` beside `stub`, still no default; `restClient()` builds the timeouts exactly as core banking's; stale "once AD-002b closes" wording gone. |
| Stub | Returns `RegistryLookup`; OK carries the requested number as `identityNumber` and the synthetic portrait bytes; no exchange, no reason. |
| Consumer (`identityscan`) | `queryRegistry` maps `found()`/not/exception to `ok`/`not_found`/`unreachable`; `auditRegistryLookup` writes `registry_lookup_requested` + `civil_registry_request` (when request bytes exist) then `registry_lookup_completed` + `civil_registry_response` (when response bytes exist), same `requestId`, inside the existing transaction; completed payload gains `reason`, `httpStatus`, `responseMediaType`, `responseBytes`. `insertRegistryResult`/`updateRegistryResultOnRetry` take `identityNumberReturned` explicitly. `decodePortrait` removed. |
| Schema | `V0062__registry_identity_number_returned_and_request_artifact_kind.sql`: `SET LOCAL fru.migration_in_progress`; `audit_artifact_kind_check` re-declared with `civil_registry_request`; `app.registry_result.identity_number_returned text` + comment. |
| Mobile | **No change.** Stage 9 returns HTTP 200 with `registryReady=false` for both non-success states; the adapter adds no status; `mobile/` has no identity-scan client (S5-07 ⬜). No package added, so no iOS check arises. |
| Tests | New: `HttpCivilRegistryClientTest` (32, `MockRestServiceServer`), `HttpCivilRegistryClientTimeoutTest` (2, real socket), `HttpCivilRegistryClientLiveTest` (1, `live` group, env-gated, never in a gate, not run), `CivilRegistryClientConfigurationTest` (16), `RegistryFieldNormaliserTest` (27). Updated: stub test (5), `IdentityScanServiceTest` (+5: two events with artifacts in order and payload contents; mismatch stores the returned number with null fields; unreachable audits the request artifact only; stub writes no artifact and null reason; retry with an exchange), `IdentityScanIntegrationTest` (+1 V0062 proof by rolled-back inserts and the constraint provoked by name; the `ok` path asserts `identity_number_returned` and the absence of a request event against the stub). |
| Docs | Card: status line, derived paragraph corrected, "How it is built", "Live proof", "Open items"; BL-030 CLOSED; AD-002b bullet; S3-14 row + S3-12 pointer; field-provenance field 7; `application.properties` block. |

### Decisions settled this session (engineering, none touching a product-owner answer)

1. Read timeout 15 s, connect 5 s, no in-adapter retry — finding 3/4 above. Corrects the card's
   earlier derived "~30 s, at most one retry".
2. Whitespace is the only tolerated difference in the guard; the stored `identity_number_returned`
   is the stripped value (the raw bytes are in the artifact).
3. `RegistryExchange` duplicated rather than a shared package — quarantine over DRY, four fields.
4. Both audit events inside the transaction — the completed event already lived there; the lookup
   is one leg of a multi-row state change.
5. The stub's `reason` is null; the unreachable path's reason is `no_response` even from the stub
   (it is literally the classification, not an invented artifact).
6. EXECUTION_PLAN gets a new permanent row S3-14 rather than an annotation on S3-12.

## Gates (verbatim)

Backend, `./mvnw spotless:apply` then `./mvnw verify -Pdb-integration-test` (JAVA_HOME = JBR
21.0.8, Docker up). V0062 applied by Flyway inside the run (the integration suite migrates from
scratch). Test count 792 = S3-02/BL-032's 708 + 84 new (16 + 27 + 32 + 2 + 1 stub + 5 service + 1
integration).

```
[INFO] --- spotless:3.10.0:apply (default-cli) @ backend ---
[INFO] Spotless.Java is keeping 413 files clean - 1 were changed to be clean, 0 were already clean, 412 were skipped because caching determined they were already clean
[INFO] BUILD SUCCESS
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.494 s -- in sd.gov.bank.fruserupdate.civilregistry.config.CivilRegistryClientConfigurationTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.162 s -- in sd.gov.bank.fruserupdate.civilregistry.domain.RegistryFieldNormaliserTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.781 s -- in sd.gov.bank.fruserupdate.civilregistry.http.HttpCivilRegistryClientTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.628 s -- in sd.gov.bank.fruserupdate.civilregistry.http.HttpCivilRegistryClientTimeoutTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in sd.gov.bank.fruserupdate.civilregistry.stub.StubCivilRegistryClientTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.584 s -- in sd.gov.bank.fruserupdate.identityscan.IdentityScanIntegrationTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.499 s -- in sd.gov.bank.fruserupdate.identityscan.service.IdentityScanServiceTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.052 s -- in sd.gov.bank.fruserupdate.identityscan.web.IdentityScanControllerTest
[INFO] Tests run: 792, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 413 files clean - 0 needs changes to be clean, 0 were already clean, 413 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  02:29 min
exit=0
```

Mobile: not run — no mobile file touched.

Post-review re-run (a deleted tautological assertion, a removed Javadoc line, docs):
`./mvnw spotless:apply && ./mvnw test -Dtest='HttpCivilRegistryClientTest,IdentityScanServiceTest'
&& ./mvnw spotless:check` — 67 tests (32 + 35), 0 failures, BUILD SUCCESS, Spotless clean. The
full gate above was run before those edits; nothing in them touches production behaviour, so it
was not repeated.

## Proofs beyond the gates

**Read-timeout wiring — revert-restore (indirect assertion, so a revert is required).** With
`factory.setReadTimeout(properties.readTimeout());` replaced by a comment in
`CivilRegistryClientConfiguration.restClient`:

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 6.737 s <<< FAILURE! -- in sd.gov.bank.fruserupdate.civilregistry.http.HttpCivilRegistryClientTimeoutTest
[ERROR]   HttpCivilRegistryClientTimeoutTest.aResponseSlowerThanTheReadTimeoutIsUnreachable:89 Expected sd.gov.bank.fruserupdate.civilregistry.domain.RegistryUnreachableException to be thrown, but nothing was thrown.
[INFO] BUILD FAILURE
```

— the client waited the server out and found the record. Restored:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.109 s -- in sd.gov.bank.fruserupdate.civilregistry.http.HttpCivilRegistryClientTimeoutTest
[INFO] BUILD SUCCESS
```

**V0062's constraint name.** `audit_artifact_kind_check` was inferred (PostgreSQL's
`<table>_<column>_check` for an inline CHECK). `IdentityScanIntegrationTest.v0062AdmitsTheRequestArtifactKindAndStillRejectsAnUnknownOne`
proves it two ways in the gate run: V0062's `DROP CONSTRAINT` by that name succeeded during
migration, and the re-declared constraint is provoked by name with an unknown kind.

**Guard assertions are direct wrong-value assertions** (`reason`, `identityNumberReturned`,
`found()`), so no revert is needed for them: a folded or coerced comparison cannot pass
`nearMissesThatAreNotWhitespaceAreMismatches` or `aNonStringIdentityNumberIsNotFoundNeverCoerced`.

**Live call — not made during the build, by scope.** `HttpCivilRegistryClientLiveTest` was
committed inert, to be run with `FRU_CIVIL_REGISTRY_LIVE_ENDPOINT=<url> ./mvnw test
-Dexcluded.test.groups= -Dgroups=live -Dtest=HttpCivilRegistryClientLiveTest`; it asserts the
400/not-found path only and prints status, content type and byte count, never a body.
**Superseded later the same day — it was run and is green: see the addendum.**

## Reviewer findings and dispositions

`@agent-reviewer` on the full working-tree diff against the plan, AD-002b, BL-030 and CLAUDE.md's
hard rules; read-only (the gate was running concurrently). No blockers. Checked and found clean:
the nine-rule classification order and first-rule-wins; the guard's no-folding rule pinned by
direct wrong-value assertions; timeouts on the right objects, no retry; the hash-chained-payload
rule in `auditRegistryLookup` (no national number, no field value, pinned by `assertFalse
(contains(...))`); V0062's flag, constraint re-declaration and column, and that `event_type` needs
no CHECK change; quarantine (no `corebanking` import under `civilregistry`; `domain` imports are
`java.time`/`Locale` only); the stub fabricates nothing; mobile untouched; fabricated values and
placeholder hosts only; the live test cannot print or persist a body; the test-count arithmetic.

| # | Finding | Disposition |
|---|---|---|
| 1 | SHOULD FIX — `HttpCivilRegistryClientTest` request-shape test ended with an `assertEquals` of two identical string literals, which no implementation could fail | **Fixed**: deleted. The request bytes are proven by `content().json(..., true)` + `server.verify()` in the same test and by `theRawExchangeIsByteIdenticalForTheAuditArtifacts` |
| 2 | NOTE — the adapter sends `Accept: application/json`, said to deviate from the precedent, which "sends no `Accept` header" | **Not changed — the premise is wrong**: `HttpCoreBankingClient` line 74 sends exactly the same `.accept(MediaType.APPLICATION_JSON)`; the pattern transferred unchanged, as the plan says. A 406, should the service ever send one, is `non_success_status` → `not_found` |
| 3 | NOTE — an orphaned one-line Javadoc left above `RegistryOutcomeInternal`'s new block | **Fixed**: removed |
| 4 | NOTE — customer.md's retained-artifact list still named only the raw Civil Registry response | **Fixed**: names request and response artifacts, mirroring the core-banking bullet beside it |
| 5 | NOTE — the card's research-trail block still said in the present tense that no code compares the returned `IDENTITY_NUMBER` | **Fixed**: past-tensed, pointing at "How it is built" |

Re-run after the fixes (one test class deleted an assertion, one Javadoc line removed, docs):
`./mvnw spotless:apply` then the two touched classes — see the one-line result below the gate.

## What was skipped, and why

- Live call against the real registry: out of scope for the build by instruction; the host was
  also believed unreachable (S3-02 report). **Both premises changed later the same day — the call
  was made and the adapter is proven against the real service; see the addendum, which also
  corrects what that "unreachable" actually was.**
- Mobile gates: mobile untouched.
- A `mismatch` schema state: rejected by AD-002b; a mismatch is `not_found` with reason
  `identity_number_mismatch` in the audit payload and the returned number on the row.

## Commit proof

The build commit, then this proof appended in a second commit (the S3-02 shape):

```
$ git log --oneline -1
f4d294e S3-14: real Civil Registry GetCRSData HTTP adapter (AD-002b build); BL-030 closed. civilregistry.http.HttpCivilRegistryClient behind the reshaped port (RegistryLookup with reason codes, RegistryExchange, request-only exchange on unreachable); IDENTITY_NUMBER guard (JSON string only, strip both sides, exact equals); 5 s / 15 s timeouts, no in-adapter retry; RegistryFieldNormaliser for BIRTH_DATE/GENDER; PHOTOGRAPH decoded in the adapter; V0062 adds civil_registry_request artifact kind and app.registry_result.identity_number_returned; two-events-one-chain audit inside the existing transaction; research report, card rewrite, plan files, session report
$ git status
On branch main
nothing to commit, working tree clean
```

---

## Addendum (same day, later) — live proofs on both endpoints, and a port correction

Trigger, from the product owner: the host is reachable; `CheckAccount` is on **port 9494**, not
9191; the Civil Registry should already be working. Both env-gated live tests were therefore run.
Fabricated and already-authorised values only. No response body was kept.

### 1. Core banking — S3-02's outstanding live proof, closed

`HttpCoreBankingClientLiveTest`, 3/3 green against the real middleware on 9494:

```
[live] input=00000000 http=200 content-type=application/json body={"Response_Code":0,"Response_Message":"Account not Found"}
[live] input=<empty> http=200 content-type=application/json body={"Response_Code":-1,"Response_Message":"System Error"}
[live] input=99999999999999999999 http=200 content-type=application/json body={"Response_Code":0,"Response_Message":"Account not Found"}
```

Every claim the adapter was built on holds against the real thing: all outcomes HTTP 200, the
result in the body, `Response_Code` a JSON number, `-1` reaching the 503 path. The `1` path stays
mock-only by rule — the only known-good account is a real customer's.

### 2. Civil Registry — the new adapter, against the real service

`HttpCivilRegistryClientLiveTest`, 1/1 green:

```
[live] input=000 http=400 content-type=text/html bytes=1105 reason=non_success_status
```

The service still answers an unknown number with the GlassFish 400 page, **byte-identical in size
to the 1,105-byte page the five authorised tests hashed on 2026-09-04**. The adapter classifies it
`non_success_status` → `not_found` against the real service, and the HTML never reaches the JSON
parser. The `matched` path remains mock-only: every value known to produce a record returns a real
citizen's data, and no synthetic number exists (Q8).

### 3. The port, and a correction to S3-02's "outage"

| Port | Result |
|---|---|
| 9191 (S1-04's `CheckAccount`) | **refuses TCP outright** |
| 9494 (`CheckAccount` today) | connects in ~0.14 s |
| 5353 (`GetCRSData`) | connects in ~0.13 s |

S3-02 recorded "the host stopped accepting TCP connections" as an outage shape, the one datapoint
it had for OQ-025's unverified outage row. That reading is now almost certainly wrong: the
middleware moved off 9191, and its neighbour on the same host never stopped answering. **No outage
shape has actually been observed**, and the card's row is corrected to say so. This is the second
time this project has recorded an inference as an observation and had to withdraw it — the first
was the `IDENTITY_NUMBER` echo.

### 4. First real timings [OBSERVED]

Three curl calls each, same authorised values, from outside the bank's network:

| Endpoint | Total | TLS handshake | Reply |
|---|---|---|---|
| `CheckAccount` (9494) | 0.79–0.98 s | ~0.46–0.58 s | 58 bytes |
| `GetCRSData` (5353), not-found path | 1.77–1.87 s | ~0.55–0.65 s | 1,105 bytes |

Core banking is faster on 9494 than the 1.21–1.22 s S1-04 measured on 9191. The registry's number
is the **first timing this endpoint has ever had**, and it is the reject path only: it exercises
neither the record lookup nor the ~25 KB base64 photograph, so a populated call is necessarily
slower by an unmeasured amount. The 15 s read default stands with a measured floor under it — the
reject path spends about an eighth of the budget — and the registry being roughly twice as slow as
its neighbour is exactly why it did not inherit core banking's 10 s.

### 5. Changed here

`docs/components/core-banking.md` (status, endpoint row → 9494 with the history, outage row
corrected, round-trip re-measured, live proof green); `docs/components/civil-registry.md` (status,
live proof green, first timing, open items narrowed); `application.properties` and
`CoreBankingHttpProperties` javadoc (the documented example port); the two core-banking test
constants' fake URL, for consistency; PROJECT_PLAN.md OQ-025 (re-confirmed live); EXECUTION_PLAN.md
S3-02 (live proof closed) and S3-14 (live test green).

Earlier session reports keep their original text — they record what was true when written — with
one deliberate exception, added in a follow-up pass:

- **The S3-02 report gains a superseded block.** Its live-proof section told the reader to rerun
  the test "at the next session" and diagnosed a host outage, with no way to learn either had been
  overtaken hours later. The original text stands; a quoted block after it records that the test
  is green on 9494, that the outage diagnosis was the port move, and where the evidence is. The
  core-banking card, which cited only "S3-14 tail", now names this report too — so the closure is
  reachable from the card, the plan files and the S3-02 report alike, not only from here.
- **The same pass repaired a one-byte defect in that report.** It carried a literal U+0001 and
  U+0000 in the very sentence describing how a literal U+0001 and U+0000 got into
  `AccountCheckController.java` — the escape text became the characters a second time, in the
  report about it. Git and grep therefore classified the report as binary and skipped its
  contents, which is why the stale "rerun at the next session" line never surfaced in any search.
  The two bytes are now the four-character escape text the sentence meant, and the file is
  greppable. Nothing else in it changed.

### 6. Gate after the port correction

`./mvnw spotless:apply` then `./mvnw verify -Pdb-integration-test`, after the javadoc, properties
and two test-constant edits (no production behaviour changed — the port is configuration):

```
[INFO] Tests run: 792, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 413 files clean - 0 needs changes to be clean, 0 were already clean, 413 were skipped because caching determined they were already clean
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  02:33 min
exit=0
```

Mobile: not run — no mobile file touched in this addendum either.
