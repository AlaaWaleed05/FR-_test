# 2026-09-04 — Research: the core-banking `CheckAccount` HTTP adapter (S3-02 re-scoped)

`@agent-researcher` report, filed verbatim apart from formatting — the CLAUDE.md-mandated
researcher step before integrating the bank middleware. No live calls were made; no files were
edited by the agent. The only account values are fabricated patterns. Where the S3-02 build
deviated from a recommendation, the deviation is marked **[build note]**.

## 1. Question

How should `backend/` implement the real core-banking adapter for
`POST https://<host>:9191/OMNI_PH3/resources/bankRoutes/CheckAccount` on this project's exact
Spring Boot version, and how should it be tested — HTTP client choice and timeouts, network-free
testing, `Response_Code` number-or-string parsing, outcome/wire mapping, raw-artifact storage,
Jakarta/GlassFish gotchas, and the BL-031 migration.

## 2. Answer

Build `corebanking/http/HttpCoreBankingClient` on `RestClient` (spring-web 7.0.8, already on the
classpath) with an explicitly constructed `JdkClientHttpRequestFactory`, because the `RestClient`
default on this exact classpath has **no connect and no read timeout at all**. Retrieve the response
as `ResponseEntity<byte[]>` with the status handler disabled — that gives the byte-identical
artifact the audit trail requires and survives a non-JSON GlassFish error page — then parse with
`JsonMapper.readTree(bytes)` and branch explicitly on `isNumber()` / `isString()`. Test with
`MockRestServiceServer.bindTo(RestClient.Builder)` (spring-test 7.0.8 is on the test classpath;
WireMock is not and is unnecessary), plus one JDK `com.sun.net.httpserver.HttpServer` test for the
timeout path that `MockRestServiceServer` structurally cannot exercise.

Map `1 → ACTIVE/PROCEED`, `0 → INVALID/RETRY`, and do not give `-1` an outcome value: raise a
`CoreBankingUnavailableException` and return HTTP 503, which costs a one-line mobile change (map
5xx to `BackendUnreachableException` in `DioEntryApi._mapAccountCheckError`) versus three files and
two exhaustive-switch breakages for a new enum constant — and is the only option that does not
silently wipe an in-progress session on a transient middleware outage. Drop `INACTIVE` from the
backend enum; mobile's now-dead `inactive` constant is harmless.

Store request and response as two audit events on the existing `system`/`account_check` chain, each
with one `AuditArtifact` — the port supports exactly one artifact per event. Land migration
`V0060__app_omni_check_corebanking_contract.sql` before the adapter.

## 3. Evidence

### 3.1 Versions and the classpath

| Fact | Marker | Source |
|---|---|---|
| Spring Boot parent 4.1.0 | [OBSERVED] | `backend/pom.xml:8` |
| Managed spring-web 7.0.8, spring-test 7.0.8 | [OBSERVED] | local Maven repository |
| Managed Jackson: `tools.jackson.core:jackson-databind:3.1.4` (Jackson 3); annotations stay at the Jackson-2 group/package | [OBSERVED] | `spring-boot-dependencies-4.1.0.pom`, `jackson-databind-3.1.4.pom` |
| The codebase already uses `tools.jackson.databind.JsonNode` / `JsonMapper`; `@JsonProperty` appears nowhere | [OBSERVED] | grep over `backend/src` |
| `spring-boot-restclient`, `spring-boot-restclient-test`, `spring-boot-http-client` are NOT resolved — neither webmvc starter depends on them | [OBSERVED] | starter POMs, local repository listing |
| Consequence: `ClientHttpRequestFactoryBuilder` / `ClientHttpRequestFactorySettings` (Boot's `org.springframework.boot.http.client`) are unavailable, and `spring.http.client.connect-timeout`/`read-timeout` do nothing | [DOC] + [OBSERVED] | Boot 4.0.x API docs; Framework 7.0.x `org.springframework.http.client` package summary |
| No `RestClient.Builder` bean exists; the one existing HTTP client builds one by hand | [OBSERVED] | `spike/UqudoSpikeConfiguration.java:22-24` |
| No WireMock, Apache HttpClient, Jetty client or Reactor Netty in `backend/pom.xml` | [OBSERVED] | `backend/pom.xml`; corroborated by S1-02's `ClassNotFoundException: org.reactivestreams.Publisher` |

### 3.2 HTTP client and timeouts

Options: A `RestClient` + hand-built `JdkClientHttpRequestFactory` (recommended: zero new
dependencies, mirrors the spike, mock-testable); B `RestTemplate` (its own javadoc points new work
at `RestClient`); C raw `java.net.http.HttpClient` (no converters, no mock server); D add Boot's
restclient/http-client modules for autoconfig (two dependencies and a config surface to replace six
lines; revisit if a second HTTP integration makes shared config worthwhile).

**The load-bearing finding.** `DefaultRestClientBuilder.initRequestFactory()` selects Apache →
Jetty → Reactor Netty → `JdkClientHttpRequestFactory` → `SimpleClientHttpRequestFactory`. With none
of the first three present, `RestClient.builder().build()` produces a `JdkClientHttpRequestFactory`
from its no-arg constructor: a default `HttpClient` with no connect timeout, and no read timeout
set [OBSERVED in the 7.0.x source; DOC in the `JdkClientHttpRequestFactory` javadoc]. So the spike's
pattern copied verbatim would give a Stage 1a request thread that can hang indefinitely.

`JdkClientHttpRequestFactory` has `setReadTimeout(Duration)` but no connect-timeout setter; connect
timeout belongs on the `HttpClient`:

```java
HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(properties.connectTimeout())
    .followRedirects(HttpClient.Redirect.NEVER)
    .version(HttpClient.Version.HTTP_1_1)
    .build();
JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
factory.setReadTimeout(properties.readTimeout());
RestClient client = RestClient.builder().requestFactory(factory).build();
```

Timeout values: observed round-trip 1.21–1.22 s on all three live calls; `PT5S` connect / `PT10S`
read gives ~8× headroom and is [INFERRED], not measured under load. Both must be
`@ConfigurationProperties`, never literals.

Do not use `.retrieve()`'s default status handling — it throws on 4xx/5xx and destroys the body
before it reaches the audit artifact. Mirror the spike's `onStatus(s -> true, …)` +
`toEntity(byte[].class)`, proven live at S1-02. `byte[]` is required because `AuditArtifact.body`
is "byte-identical, never re-encoded". `RestClient.builder()` with no converter configuration
registers `JacksonJsonHttpMessageConverter` for Jackson 3 automatically, so serialising the request
map works with no wiring.

### 3.3 Testing without the network

`MockRestServiceServer.bindTo(RestClient.Builder)` exists since 6.1 and is not deprecated;
`spring-test-7.0.8.jar` is on the test classpath. Design constraint: binding replaces the builder's
request factory, so the adapter must accept an already-built `RestClient` and never call
`RestClient.builder()` internally. Cases the suite must cover: number code; string code; `0`; `-1`;
unrecognised code; absent/null code (the Jackson trap); `text/html` body on 200 or 400; empty body;
non-200 status with a JSON body. What the mock cannot test is the timeouts — close that with one
JDK `HttpServer` test on port 0 whose handler sleeps past the read timeout.

### 3.4 Parsing `Response_Code` as number or string

Jackson 3's `JsonNode.asInt()` coerces Number values, stringified numbers, **JSON null (to 0)** and
**a missing node (to 0)** [OBSERVED in the 3.1 source]. `0` is now a meaningful code, so a bare
`asInt()` would read a missing or null `Response_Code` as "Account not Found" — exactly the fail-open
OQ-025 forbids. Recommended, no custom deserializer: `readTree`, then branch on `isNumber()` →
`intValue()`, `isString()` → `Integer.parseInt(trim)`, else fail closed. `isString()` /
`stringValue()` are Jackson 3's spellings. **[build note]** The build uses `isIntegralNumber()` +
`longValue()` with an int-range check, so a fractional or overflowing number also fails closed.

### 3.5 Mapping onto `AccountCheckOutcome` and the wire

| Middleware | Outcome | Continuation | HTTP |
|---|---|---|---|
| `1` "Account Found" | `ACTIVE` | `PROCEED` (or `TERMINAL`/`BLOCKED` per the S3-07/S4-06 profile branch) | 200 |
| `0` "Account not Found" | `INVALID` | `RETRY` | 200 |
| `-1` "System Error" | none | none | 503 |
| anything else | none | none | 503 **[build note: 500 — a parseable but unrecognised code is a contract defect, kept as `IllegalArgumentException`/UNMAPPED as before]** |
| `2` | removed | — | — |

Why `-1` must not become an outcome value, measured against the code: mobile's `_handleNext` maps
`RETRY` to "check your account number" (`account_entry_screen.dart:76-77`) — wrong for an outage;
`EntryRepository.launchDecision()`'s `retry` branch clears the session (`entry_repository.dart:344-348`)
— a transient outage at cold launch would destroy an in-progress session; and both Dart switches are
exhaustive with no default, so a new continuation is a compile break in three files. Why 503 costs
almost nothing: `account_entry_screen.dart:93-98` already has a generic catch producing "unexpected
error, try again later", commented as being for exactly this case. The mobile change is one line in
`DioEntryApi._mapAccountCheckError` (map 5xx to `BackendUnreachableException`), which also makes
`launchDecision()` resume offline. Backend: an `@ExceptionHandler` on the controller mapping
`CoreBankingUnavailableException` to 503 — closing the documented half of BL-008; a distinct audit
constant so `-1` is not confused with a connection failure. Port reshaping: drop `branchCode`
(OQ-024) and return a small value record carrying the raw message and bytes; adapter in a new
`corebanking/http` package (plumbing); `select` gains `case "http"`, no default.

### 3.6 Where the raw request and response go

`AuditEventWriter.appendWithArtifact` writes one artifact linked by the single nullable FK
`audit_event.artifact_id`; V0002's CHECK already includes `omni_check_request` and
`omni_check_response`. `liveness` and `identityscan` show the pattern (one JWS → one artifact on one
event; `append`/`appendWithArtifact` ternary when the artifact may be absent). Recommendation: two
events, one artifact each, same chain, same `request_id` — `account_check_requested` with the
request bytes and the existing `account_check_attempted` with the response bytes. The schema is 1:1;
folding both into one artifact would misreport its kind; extending the port to N artifacts is an
audit-schema change. Cost: 2 rows per check on the serialised system chain (R-038), negligible at
~3 submissions/hour. **[build note]** The build writes the request event immediately before the
attempt event, after the call (the adapter builds the request bytes), and only when request bytes
exist — the stub produces one event and no artifacts, as before.

`app.omni_check` — flag, do not decide: nothing writes it (only V0008 creates it, V0010 grants
SELECT/INSERT); customer.md Stage 1a says nothing is written to the profile database at this stage
and `AccountCheckService` repeats it. Three readings — drop the table; keep it unwritten for an
undefined later re-check; correct customer.md and write it through a new port — none settled by
research. Recommendation: land the V0060 constraint correction, write nothing, record the tension.
`account_hash` is an unsalted SHA-256 over a small enumerable domain: schema tidiness, not a privacy
control.

### 3.7 Jakarta / GlassFish

Nothing Jakarta-specific is required. Observed: GlassFish 8.0.0, Servlet 6.0, Java 21,
`application/json`, HTTP/1.1, TLSv1.2, DigiCert chain in the JBR `cacerts`, no auth. Defend
against: HTTP/2 ALPN (pin 1.1 — every reply was 1.1); non-JSON error bodies (the sibling registry
service returned `text/html` 400s); `JSESSIONID` (no `CookieHandler` installed, so ignored); status
is never the outcome.

### 3.8 The BL-031 migration

Next free number `V0060`; naming `V00NN__<schema>_<subject>.sql`. App schema only — no R-035
`SET LOCAL fru.migration_in_progress`. PostgreSQL auto-names the inline column CHECK
`omni_check_result_code_check`; V0026 is the drop-and-re-add precedent (`artifact_ref_kind_check`)
[INFERRED by analogy — proven at the S3-02 integration run, which applied the migration]. V0008's
`profile_id` line comment ("NULL for -1 and 2 outcomes") names the superseded code set and must be
corrected by `COMMENT ON COLUMN` in the new migration, as V0050/V0053 did. No new grant needed.

## 4. What could not be determined

The actual constraint name (proven at build); the middleware's behaviour on a genuine outage
(later that day the host stopped accepting TCP connections — one outage shape now seen); whether
it ever returns non-200 or non-JSON; whether HTTP/2 negotiation would fail; the response
`Content-Type` variants; whether `app.omni_check` is meant to be written; any rate limit.

## 5. Risks

| If the recommendation is wrong | What breaks | Reversal cost |
|---|---|---|
| `-1 → 503` is the wrong customer semantics | customers see "try later" for a permanent account condition | low-moderate: add an outcome later, paying the mobile cost then |
| Timeouts too tight | legitimate slow responses become 503s | trivial, configuration |
| Timeouts omitted | a wedged middleware exhausts request threads; the whole backend stops answering | trivial to fix, expensive to discover — the failure this brief exists to prevent |
| Two audit events per check is the wrong shape | the append-only chain permanently carries it | irreversible — decide before the first real call |
| `app.omni_check` should have been dropped | a dead table survives into handover | low while it has zero rows |
| `MockRestServiceServer` masks a wire defect | TLS/redirect/chunking issues ship untested | moderate: the JDK-server test plus the live fabricated-value run |

**Live-proof note.** A green mock test could coexist with a client that has no timeouts — hence the
JDK-server test or pasted live output. The `0` and `-1` paths can be live-proven with fabricated
values; the `1` path cannot and must be declared mock-only.

## 6. Noticed in passing

- `mobile/lib/core/entry/entry_models.dart` `AccountContinuation` has only `{proceed, terminal, retry}`
  while the backend already returns `BLOCKED` (S4-06): `.byName('blocked')` throws `ArgumentError`,
  which escapes `_mapAccountCheckError` and, at cold launch, fails the launch decision outright. Live
  today; tracked under BL-021's mobile half. **[build note]** Not fixed here — it belongs with the
  "blocked until" launch screen that BL-021's mobile half builds.
- `entry_repository.dart:300-316` carries a stale comment about what `AccountCheckResponse` carries.
- `customer.md` Stage 1a still had the Oracle paragraph and the `-1/2/1` headings — rewritten at S3-02.
- `account_entry_screen.dart:79-81` and `entry_repository.dart:340-342` branch on
  `AccountOutcome.inactive`, now unreachable; dead but harmless.

Sources: Spring Framework 7.0.x `DefaultRestClientBuilder` and `DefaultHttpMessageConverters`
source; `JdkClientHttpRequestFactory`, `SimpleClientHttpRequestFactory`, `RestTemplate` and
`MockRestServiceServer` 7.0.9 javadoc; Spring Boot 4.0.x `ClientHttpRequestFactoryBuilder` API doc;
jackson-databind 3.1 `JsonNode` source; local repository POMs and jars as cited above.
