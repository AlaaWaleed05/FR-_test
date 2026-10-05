# 2026-09-04 — Research: Civil Registry `GetCRSData` not-found shape and adapter-relevant behaviours

`@agent-researcher` report, filed verbatim apart from formatting. Scope: OQ-023 (a) and (b),
AD-002b, R-031. **No live calls were made** (the agent has no shell, and the product-owner stop
recorded in the same-day discovery addendum forbids further probes). Sources: the FIB clone on
disk, the local plan files and code, GitHub/web search, vendor documentation. No personal data
from any registry record appears here.

Ordered by the researcher: reading of the task → direct answer → other behaviours → FIB →
external sources → recommended adapter behaviour → unknowns → risks → card edits → bank
questions → noticed in passing.

## Reading of the task

"What does the service return for a well-formed but unknown national number" cannot be answered
by observation without a live probe, which is forbidden. The defensible reading taken: establish
what the not-found shape can and cannot be, from the server stack's documented framework
behaviour and from the three responses already observed, rank the candidates with mechanisms, and
design the adapter so it is correct under every candidate. Not pursued: any further probe; asking
the bank (not the agent's to do); evaluating Uqudo's Government DB Lookup as a replacement route
(AD-002b parks that for the bank).

## 1. Direct answer — the not-found shape

**No well-formed-but-unknown response has ever been observed, by us or by anyone whose work could
be found. It remains unknown.** What can be established with high confidence is what it is *not*,
and a ranked, mechanism-backed set of what it can be.

**High confidence — the observed HTTP 400 is not the not-found response.** The two 400s (20-digit
value, empty string) are container-level rejections that happen before the lookup runs, so a
well-formed 11-digit unknown number will not produce one.

- [OBSERVED] Both 400s returned `Content-Type: text/html`, the GlassFish page "The request sent by
  the client was syntactically incorrect", with no JSON body — discovery addendum in
  `docs/sessions/2026-09-04-plan-reconciliation-and-corebanking-discovery.md`;
  `docs/components/civil-registry.md` error-contract table.
- [OBSERVED] Stack: GlassFish Server Open Source Edition 4.1 / Java 1.8 / Jersey 2.10.4; `OPTIONS`
  returns a WADL declaring exactly one `POST`, `application/json` in and out.
- [INFERRED] A body-less 400 rendered as the container's own HTML page is what a Jersey 2 resource
  produces when entity binding fails (Jackson cannot coerce the value into the DTO's declared type)
  or when Bean Validation rejects the parameter — Jersey 2 defaults
  `jersey.config.beanValidation.enableOutputValidationErrorEntity.server` to `false`, so a
  validation failure yields a 400 with no entity and the container substitutes its page. Both fire
  before the resource method body executes. Which of the two it is could not be told, and it does
  not matter for the adapter.
- [INFERRED] The discriminator is length/type, not existence: eleven characters passed, twenty and
  zero did not. Either the DTO field is a numeric type too narrow for 20 digits (`long` tops out at
  19) or there is an exact-length/pattern constraint. Both fit all three observations.

**Ranked candidates for the real not-found response** — all [INFERRED], none observed:

| # | Candidate | Mechanism | Why ranked here |
|---|---|---|---|
| 1 | HTTP 204, empty body | JAX-RS maps a `null` return from a non-`void` method to 204 No Content [DOC — Jersey/JAX-RS overview, Eclipse EE4J; Oracle Java EE tutorial ch. 13] | Requires the developer to write nothing. `return dao.find(nid);` returning null is the default path in this style of code. |
| 2 | HTTP 200, JSON object with all fifteen fields empty/null | The method constructs and returns an empty DTO | [OBSERVED] The one real record already carried an empty `FIRST_NAMES`, proving the service emits empty strings for absent values — an all-empty record is a shape it can already produce, and it is indistinguishable from success by HTTP status alone. |
| 3 | HTTP 500, GlassFish HTML page | The DAO throws (NPE, `list.get(0)` on an empty result, `EmptyResultDataAccessException`) and nothing catches it | Very common in 2014-era JAX-RS/DAO code with no exception mappers. The WADL declares no error representations. |
| 4 | HTTP 200, body `{}` or the literal `null` | Jackson serialising a null/empty map | Plausible; less likely than 1 because JAX-RS intercepts null before serialisation. |
| 5 | HTTP 404 | Deliberate `Response.status(404)` | Least likely: a single-`POST` RPC endpoint on a `/Services/` path with no resource model; a 404 would be a deliberate REST-shaped choice by someone who made no other REST-shaped choices. |

The adapter must be correct under candidates 1–5 simultaneously. Section 5 sets out how.

## 2. Other behaviours the adapter must know about

### 2a. The all-zeros record, and the sharper problem it exposes

[OBSERVED] `{"NID":"00000000000"}` returned HTTP 200 with fourteen of fifteen fields populated and a
genuine photograph. Content not recorded, capture deleted.

Four hypotheses, all [INFERRED], none distinguishable from outside:

- H1 — a seeded test/demo record deliberately keyed at all zeros. Benign.
- H2 — numeric coercion: if `NID` binds to a numeric type, leading zeros are stripped and the query
  runs against `0`; the registry has a row at key `0`. Benign for us, but it also means leading
  zeros in a real national number would be silently destroyed — a correctness bug we would inherit.
- H3 — the lookup is not an exact match, or the filter does not bind and the DAO returns the first
  row. **This is the dangerous one: a well-formed unknown number could return an arbitrary real
  person's record with HTTP 200 and no error at all.**
- H4 — a real citizen's entry genuinely keyed under zeros.

H3 is why "check the echoed `IDENTITY_NUMBER`" is mandatory. But it is not sufficient on its own:

> [UNVERIFIED — and this contradicts a line the component card stated as fact.] The card described
> `IDENTITY_NUMBER` as "Echo of the request NID". If it is literally echoed from the request rather
> than read from the matched row, comparing it against the submitted number verifies nothing — it
> matches by construction even when the returned record belongs to someone else. The only
> supporting observation is that in the all-zeros call the field was eleven characters, which is
> equally consistent with an echo of eleven zeros and with a stored value of eleven zeros. Not
> established either way; must not be relied on until it is.

Consequence: the accept decision needs a second, independent corroboration — cross-field agreement
between the registry record and the Uqudo OCR (name and date of birth), not just the identity-number
echo. Stage 9's existing `terminated_registry_mismatch` status is the right destination for a
failure.

### 2b. Authentication

- [OBSERVED] None. A bare `POST` with no header succeeded; the WADL says nothing about auth.
- [OBSERVED] CORS response headers list `Authorization` as an allowed header — a browser preflight
  hint, not a requirement.
- [OBSERVED] Reachable from the public internet, on a hostname, with a publicly-trusted DigiCert
  wildcard certificate.
- [INFERRED] Combined: an unauthenticated, internet-reachable endpoint that returns a citizen's
  full four-generation name chain, address, date of birth and photograph in exchange for an
  eleven-digit number. Whether an IP allowlist exists cannot be told from one successful source
  address — our call succeeded from outside the bank, so either there is no allowlist or our
  address was already on it. A finding to raise with the bank in its own right; bears on OQ-001
  and OQ-021.
- [UNVERIFIED] Whether authentication is required under other conditions (different source
  network, production tenant, after a hardening change).

### 2c. Rate limiting

- [OBSERVED] Nothing. Exactly three requests were ever made. No `429`, no `Retry-After`, no
  rate-limit headers — but three requests prove nothing.
- [INFERRED] GlassFish 4.1 ships no built-in HTTP rate limiting. The only server-side back-pressure
  levers on a default listener are the thread pool and `max-connections` (default 256 pipelined
  requests per connection [DOC — Oracle GlassFish `create-http` reference]). The realistic failure
  mode under load is queueing and timeouts, not a clean 429.
- [INFERRED] At our scale this barely matters: ~100,000 profiles over 12–18 months, about three
  submissions per hour. One registry call per profile is nothing. Rate limiting is a risk only if
  a retry loop misbehaves.

### 2d. Timeouts

- [DOC] GlassFish HTTP listener documented defaults: `request-timeout-seconds` 30, keep-alive
  `timeout-seconds` 30, `max-connection` 256 — Oracle GlassFish Server Reference Manual,
  `create-http`. Caveat: the 3.1 reference manual; no 4.1-specific page located, same Grizzly
  stack. [UNVERIFIED] whether `request-timeout-seconds` is actually enforced in 4.1.
- [OBSERVED] TLSv1.2 negotiated. GlassFish 4.1 on Java 1.8 cannot do TLS 1.3.
- [INFERRED] The response body carries a base64 JPEG — ~18 KB decoded in the observed sample, so
  ~24 KB base64 plus fifteen fields. A read timeout tuned for a 200-byte JSON reply will produce
  spurious failures on a poor link.
- [OBSERVED, negative] Nothing about the service's behaviour when it is down. Untestable from our
  side; still unknown.

### 2e. Field semantics — corrections and additions to the card

- [OBSERVED] `FIRST_NAMES` was empty in the one real record. The Latin name pair is not guaranteed.
  The adapter must not require `FIRST_NAMES` or `LAST_NAME`; a comparison against Uqudo's English
  `fullName` must tolerate absence rather than treating it as a mismatch.
- [OBSERVED] `BIRTH_DATE` is `DD/MM/YYYY`. Parse with an explicit `DateTimeFormatter`; the code
  already knows this (`RegistryLookupResult` javadoc).
- [UNVERIFIED] Whether the service can emit a partial or placeholder date (e.g. `01/01/YYYY`).
  R-034's precedent applies: S3-12's `LocalDate.parse` would have been an uncaught 500 against a
  real Uqudo MRZ date. Do not let a date parse failure throw.
- [OBSERVED] `GENDER` was lowercase `m`. The schema CHECK is
  `sex_registry char(1) CHECK (sex_registry IN ('m','f'))` (V0023 line 46). [UNVERIFIED] that the
  service only ever emits lowercase `m`/`f`. An uppercase `M` or an empty string would surface as
  a database error, not a clean domain failure. Normalise at the adapter boundary.
- [OBSERVED] `ADDRESS` is one comma-separated free-text Arabic string, stored raw for comparison.
- [DOC] Uqudo's own Sudan Government DB Lookup — the alternative route AD-002b parks for the bank —
  returns a materially smaller field set: image, first/middle/last name, ID number, DOB, date of
  issue, date of expiry (`docs.uqudo.com/docs/coverage/africa/sudan.md`, fetched 2026-09-04;
  Identity Card only, not passport). No address, no maternal chain, no four-generation paternal
  chain. Recorded so the bank's comparison is on facts.

### 2f. A schema gap the echo check exposes

[OBSERVED] `app.registry_result.state` is constrained to
`CHECK (state IN ('pending','ok','not_found','unreachable'))` — V0008 line 51.

[INFERRED] "The registry returned a fully populated record whose `IDENTITY_NUMBER` does not match
what we asked for" is none of those four. Not `not_found` (a record came back), not `unreachable`
(the service answered), emphatically not `ok`. Recording it as either of the first two puts a false
fact into a profile the operator then judges. This needs a fifth state — `mismatch` — and therefore a
migration, before the real adapter ships.

[OBSERVED] `RegistryLookupResult` (`civilregistry/domain/RegistryLookupResult.java` lines 16–30) has
no `identityNumber` component at all. The echoed value has nowhere to live, so the echo check cannot
currently be expressed in the domain type.

## 3. What FIB's production code does

**FIB does not integrate the Civil Registry. Anywhere. A clean negative finding, not a gap in the
search.**

Searches against the read-only clone at `../FIB`:

- [OBSERVED] `CRSAPI` / `GetCRSData` / `CRSData` / `IDENTITY_NUMBER` / `GRE_GRA_FATHER` /
  `PHOTOGRAPH`, case-insensitive — zero matches across `Web_Code/` (the Vite bundle and its `.map`
  source maps), `backend server fib/utility/src` (Spring Boot), `backend server fib/wwwroot/` (the
  deployed .NET `FIB_API` and the older CRA front end `FIB_FRONT`).
- [OBSERVED] `civil` / `السجل المدني` — zero matches in `Web_Code/`. Seven hits in
  `wwwroot/FIB_FRONT/static/js` are minifier identifiers, confirmed by `-o` extraction.
- [OBSERVED] `civil|registry|CRS` — zero matches in `mobile/lib`.
- [OBSERVED] The deployed .NET API's external endpoints are exactly three, none a registry —
  `backend server fib\wwwroot\FIB_API\appsettings.json` lines 26–30 (`fib_url`, `email_url`,
  `my_email_url`).
- [OBSERVED] `196.1.223.27`, `:5353`, `Services/Get` — zero matches in `backend server fib/utility`.

FIB observed nothing about this service and assumed nothing about it. What FIB does have is
precedent for calling other external services — precedent to reject, not copy:

[OBSERVED] `FIB/backend server fib/utility/src/main/java/com/aztech/utility/config/AppConfiguration.java`
lines 10–13:

```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

A bare `RestTemplate` — no connect timeout, no read timeout. Every external call in FIB's Spring
tier can hang indefinitely.

[OBSERVED] `.../service/ApiClientService.java` lines 37–41:

```java
@Retryable(
        value = {Exception.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2)
)
public <T> ResponseEntity<T> post(String url, Map<String, String> headers, Object requestBody, Class<T> responseType) {
```

Retry on `Exception.class`, three attempts. With no timeout, a slow upstream becomes three unbounded
hangs. The `post_header` variant actually used for the bank call (lines 51–58) carries no
`@Retryable` at all — the retry policy is incidental, not designed.

[OBSERVED] `.../service/Impl/BankServiceImpl.java` lines 151–189 — the closest analogue to a registry
lookup, and the shape it collapses everything into:

```java
if (openCiFAccountResponse.getStatusCode().is2xxSuccessful() && openCiFAccountResponse.getBody() != null) {
    ...
} else {
    ...
    mobileOpenCIFResponse.setResponseCode(-1);
    mobileOpenCIFResponse.setResponseStatus("Failed");
    ...
}
} catch (Exception e) {
    ...
    log.error("exception {}", e.getMessage());
    mobileOpenCIFResponse.setResponseCode(-1);
    mobileOpenCIFResponse.setResponseStatus("Failed");
```

[INFERRED] Three properties our adapter must not inherit: any 2xx with a non-null body is treated as
success with no content validation (under candidate 2, an all-empty record would be accepted as a
real person); a not-found, a fault and a timeout collapse into one indistinguishable `-1`; and
`catch (Exception e)` logs only `e.getMessage()`, discarding the type and stack — the information
needed to classify the failure.

## 4. External sources

**None exist. Nothing about this service or its vendor was found.**

- [OBSERVED — search results] `"GetCRSData"` + `CRSAPI`: no result relates to Sudan or a civil
  registry (hits: Cognite's geospatial `CrsAPI`, OWASP Core Rule Set, Croatian Register of
  Shipping, DAC/CRS aid codelists).
- [OBSERVED — search restricted to github.com / gist.github.com / raw.githubusercontent.com /
  documenter.getpostman.com] `"GetCRSData"`: no repository, gist or Postman collection.
- [OBSERVED] `"CRSAPI" Sudan civil registry API national number`: civil-registration background
  material only (Landinfo, IRB Canada, the 2011 Civil Registration Act, embassy pages). No technical
  documentation of any API.
- [OBSERVED] `"OMNI_PH3" OR "sfbank-sd" OR "CRSAPI" postman collection sudan bank api`: nothing.
  openbankingtracker.com lists no public API for the peer Sudanese bank.
- [OBSERVED] Arabic: `"السجل المدني" السودان API الرقم الوطني خدمة استعلام` returns embassy service
  pages and news, no API documentation.
- [OBSERVED] `"Al Hawafiz Computer Devices"` (the TLS certificate subject): a UAE computer-hardware
  company in business directories. No product, case study or technical material connecting it to
  Sudanese banking or civil-registry integration. [INFERRED] The certificate subject identifies who
  bought the wildcard certificate, not who wrote `GetCRSData`.

On the national-number format itself:

- [DOC, weak] Multiple embassy and secondary sources describe the national number as 11 digits,
  unique, never reissued. No clean primary quote: `sudanembassy.org/national-number/` states only
  that it is "a unique reference number assigned to Sudanese citizens" and nothing about digit
  count; `sudanembassy.nl` redirects; `sudanembassy.org.sa` refused the connection. The 11-digit
  figure survives only as a search-engine summary.
- [OBSERVED — the strongest evidence, and it is local] The live discovery: an eleven-character
  value was accepted and produced a record; twenty characters and an empty value were rejected at
  the container with 400; the `IDENTITY_NUMBER` returned was eleven characters.
- [UNVERIFIED, flagged bad source] A WebFetch of the Landinfo 2023 report on Sudanese civil
  registration returned "9 digits", "the first digit represents the state" and "the last digit is
  a check digit" — while reporting the PDF as partially corrupted. This contradicts every other
  source on digit count; the PDF could not be read directly. **Treat those claims as unsourced. Do
  not build a check-digit validator on them.**
- [OBSERVED] Socure's national-ID validation documentation: Sudan is not listed.

**Conclusion on format: no authoritative public specification of the national number's internal
structure or check digit exists that can be stood behind. Validate on length and character class
only.**

## 5. Recommended adapter behaviour

Correct under every candidate in §1 and under H1–H4 in §2a.

**Validate before calling.**
1. Reject anything that is not exactly 11 ASCII digits, before the HTTP call. No check-digit
   validation. Mirror CLAUDE.md's phone rule and reject non-ASCII digits (Arabic-Indic `٠١٢…`)
   explicitly at the boundary; Uqudo OCR of an Arabic-script card is a realistic source of them.
2. Reject an all-zeros or all-same-digit value before the call, as an input-validation failure.
   Until the bank explains the all-zeros record, submitting it is the one input known to return a
   stranger's PII.

**Classify the response by an explicit whitelist, fail closed on everything else.**

| Response | Outcome |
|---|---|
| 2xx, `application/json`, parses, `IDENTITY_NUMBER` equals the submitted value, and at least one corroborating field agrees with the Uqudo OCR | `ok` |
| 2xx, JSON, parses, but the record is empty (all name fields blank) — **tested first**, before the echo test, so a candidate-2 not-found is never recorded as `mismatch` | `not_found` |
| 2xx, JSON, parses, record populated, but `IDENTITY_NUMBER` differs or is absent | `mismatch` — new state. Never `ok`. |
| 204, or any 2xx with an empty body | `not_found` |
| 404 | `not_found` |
| 400 with non-JSON body | Fail closed as a client-side defect, distinct from `unreachable` — our validation failed. Never parse the body. |
| 5xx, any non-JSON body on any status, or a parse failure | `unreachable` |
| Connect/read timeout, TLS failure, DNS failure | `unreachable` |

**Add the fifth state.** `app.registry_result.state`'s CHECK (V0008 line 51) needs `'mismatch'`.
Add `identity_number_returned` to `app.registry_result` and an `identityNumber` component to
`RegistryLookupResult` so the echoed value is stored and auditable — today it is discarded.

**Do not trust the echo alone.** Gate `ok` on the echo and on cross-field agreement with the Uqudo
OCR (date of birth is the cheapest strong signal; the Arabic name chain is stronger but needs
normalisation). A disagreement routes to `terminated_registry_mismatch`.

**Timeouts and retries — deliberately, not by default.**
- Explicit connect timeout (~5 s) and read timeout (~20–30 s, sized for a ~30 KB body with a base64
  JPEG). Never a bare `RestTemplate`/`RestClient` with framework defaults.
- At most one retry, only on connect failure or read timeout — never on any 4xx or 5xx. The
  service has no rate limiting and no observed back-pressure signal; retrying a 5xx buys nothing
  and risks amplifying an outage. At ~3 submissions/hour there is no throughput argument.
- Never `@Retryable(value = Exception.class)`.

**Store the raw response.** PROJECT_PLAN.md already requires the raw Civil Registry response as an
audit artifact. The first real not-found in production is the evidence that closes R-031 — only if
the raw body was captured, including on paths classified `unreachable`, subject to PII rules.

**Log the classification, not just the failure.** Status code, `Content-Type`, body length and the
chosen outcome — never the body, never a field value.

*Editorial correction on review (2026-09-04): the two rows above were reordered and the
`mismatch` row narrowed to a populated record. As the researcher first wrote them, an all-empty
record (candidate 2) satisfied both the `mismatch` and the `not_found` row, and the `mismatch`
row came first — so if not-found really is candidate 2, every unknown number would have been
recorded as `mismatch`, the false-fact outcome §2f warns against. Found by `@agent-reviewer`.*

## 6. What could not be determined

1. The well-formed-unknown response: status, content type, body.
2. Whether `IDENTITY_NUMBER` is echoed from the request or read from the matched row.
3. Whether the lookup is an exact match (H3).
4. What the all-zeros record is.
5. Outage behaviour — timeout, TCP reset, 502/503, or a GlassFish HTML page.
6. Whether authentication is required under any conditions other than the one exercised.
7. Rate limits.
8. The national number's internal structure and whether it has a check digit.
9. Whether the service preserves leading zeros in a submitted national number.
10. Whether `GENDER` is always lowercase `m`/`f`, and whether `BIRTH_DATE` can be partial.
11. The GlassFish listener's actual configured timeouts (documented defaults only).

## 7. Risks if the recommendation is wrong, and reversal cost

| Risk | If wrong | Cost to reverse |
|---|---|---|
| The lookup is not exact-match (H3) and `IDENTITY_NUMBER` is a literal echo | A customer is approved against another person's registry record; the operator sees a coherent, photographed record with no signal it is wrong; it lands in an append-only audit trail. The single worst outcome in this system. | Low code cost (cross-field checks); unbounded reputational and regulatory cost if shipped first. This is why corroboration is not optional. |
| Not-found is candidate 2 (200 + all-empty record) and any parsed 200 is treated as success | Empty profiles recorded as `ok`. Detected in UAT; corrupts data in production. | Low — one classification branch — provided the raw body was captured. |
| Not-found is candidate 3 (500 HTML) and 500 is classified `unreachable` | Every unknown number presents as "registry unavailable, try later". Annoying, fail-closed, safe. The deliberate direction to be wrong in. | Low. |
| The 400 is a business "not found" after all | A genuine not-found mis-reported as our own defect. | Low. Rejected: a body-less container HTML page is not how a Jersey resource returns a business outcome, and eleven zeros did not produce a 400. |
| The `mismatch` state is not added | The echo/corroboration failure is recorded as `not_found` or `unreachable` — a false fact, permanently. | Cheap now (one migration, zero rows). Uncorrectable in audit once rows exist. |
| No timeout is set (the FIB pattern) | Threads park on a hung registry; the backend degrades under a dependency that never returns. | Trivial to fix; silent until load. |
| Retrying 5xx | Amplifies a registry outage. | Trivial. |
| A check-digit validator built on the Landinfo-attributed claim | Rejects valid national numbers at the boundary; customer-visible. | Trivial to remove; embarrassing to have shipped. Do not build it. |

**Conditions under which the recommendation flips:** a real not-found sample from the bank
collapses the classification table to the observed shape; confirmation that `IDENTITY_NUMBER` is
read from the matched row and the lookup is exact-match makes corroboration belt-and-braces rather
than load-bearing (keep it anyway); a switch to Uqudo's Government DB Lookup makes most of this
moot except the validation rules and the `mismatch` state.

## 8. Card and risk updates

Applied this session to `docs/components/civil-registry.md` (status line, `IDENTITY_NUMBER` row
corrected to [UNVERIFIED], not-found candidates and the 400-is-not-not-found row added to the
error-contract table, adapter bullets extended, new "Authentication, transport and server
behaviour" and "FIB precedent" sections) and to RISKS.md R-031. BL-030 (registry `mismatch` state,
`identity_number_returned` column, `RegistryLookupResult.identityNumber`) and BL-031
(`app.omni_check` still models the Oracle contract — see §10) filed.

## 9. The exact questions to put to the bank

Ordered by how much each unblocks.

1. What does `GetCRSData` return for a well-formed 11-digit number that is not in the registry?
   Exact HTTP status, `Content-Type` and body — a synthetic or redacted example is fine.
2. Is `IDENTITY_NUMBER` in the response echoed back from the request, or read from the matched
   row? If echoed, it cannot verify that the record received is the record asked for.
3. What SQL does the lookup run? Exact `=` match on the stored national number, or
   `LIKE`/numeric-coercion/partial match? What does the DAO do on zero rows — null, empty object,
   or throw?
4. What is the record returned for `00000000000`? Seeded test record, data artifact, or a real
   citizen's entry? If real, that record is currently retrievable by anyone who can reach the
   endpoint.
5. Is `NID` typed as a string or a number in the service? Does a leading zero survive the round
   trip?
6. What does the service do during an outage or database failure? Timeout, connection reset,
   502/503, or a GlassFish HTML page?
7. Is this endpoint intended to be unauthenticated and reachable from the public internet? We
   reached it from outside the bank with no credential and received a citizen's full record and
   photograph. If an IP allowlist is intended, our hosting addresses need registering before
   AD-002d picks a host.
8. Is there a rate limit, a concurrency limit, or an agreed request budget? Expect roughly three
   lookups per hour at steady state, and a burst during any pilot.
9. May we have two or three synthetic test national numbers that resolve to seeded records? Failing
   that, written authorisation for a bounded set of probes, with the numbers supplied by the bank.
10. Is there a specification for the national number's structure — digit layout, check digit? We
    validate on length and character class only.
11. Can `BIRTH_DATE` be partial or a placeholder, and is `GENDER` always lowercase `m`/`f`? Our
    CHECK constraint is `('m','f')`.
12. Is a `PHOTOGRAPH` always present? Fourteen of fifteen fields were populated in the one record
    seen, with `FIRST_NAMES` empty — which fields are guaranteed?

## 10. Noticed in passing

- [OBSERVED] `app.omni_check` (V0008 lines 58–65) still models the superseded Oracle
  `ProcessOmniCheckAct` contract — `branch_code NOT NULL` and
  `result_code CHECK (result_code IN (1, 2, -1))`. AD-007 replaced that with the HTTPS middleware
  call, OQ-024 dropped branch from the check and OQ-025 closed the code set at `1`/`0`/`-1`. The
  `0` code cannot currently be stored. Filed as BL-031.
- [DOC] Uqudo publishes markdown variants of its documentation pages at
  `docs.uqudo.com/docs/<path>.md`, which fetch cleanly and are far cheaper to read than the HTML.
  The canonical route for future Uqudo research.
- [OBSERVED] The `RegistryLookupResult` javadoc already commits to storing `PHOTOGRAPH`
  byte-identical into `app.artifact_ref.body` per AD-004. Consistent; no change needed.

## Sources

- Jersey / JAX-RS overview — null return maps to 204 (Eclipse EE4J):
  https://eclipse-ee4j.github.io/jersey.github.io/documentation/1.19.1/jax-rs.html
- Oracle Java EE Tutorial ch. 13 — Building RESTful Web Services with JAX-RS:
  https://docs.oracle.com/cd/E19798-01/821-1841/6nmq2cp1v/index.html
- Oracle GlassFish Server Reference Manual — `create-http` defaults:
  https://docs.oracle.com/cd/E18930_01/html/821-2433/create-http-1.html
- Uqudo coverage — Sudan (markdown variant): https://docs.uqudo.com/docs/coverage/africa/sudan.md
- Embassy of Sudan — National Number: https://sudanembassy.org/national-number/
- Landinfo (2023) — Sudan: Civil Registration, Identity Documents and Passports —
  flagged unreliable as fetched, PDF not readable on this machine:
  https://landinfo.no/wp-content/uploads/2023/03/Report-Sudan-Civil-Registration-ID-documents-and-passports-03032023.pdf
- Socure — National ID validation (Sudan not listed): https://help.socure.com/riskos/docs/national-id-validation
- Open Banking Tracker — Faisal Islamic Bank (Sudan): https://www.openbankingtracker.com/provider/faisal-islamic-bank-sudan-sd/apis
- ClarifiedBy — Al Hawafiz Computer Devices: https://clarifiedby.diligenciagroup.com/company/summary/3420163-al-hawafiz-computer-devices/

## Review and dispositions (filing session)

`@agent-reviewer` on the filing diff. Clean: no personal data anywhere (only the permitted
fabricated pattern, a URL date and a citation id among long digit runs); the three FIB snippets
are short, path-cited, verbatim, and carry no config values; the three code claims (V0008 line 51,
V0008 lines 58–65, `RegistryLookupResult` without `identityNumber`) verified against the repo; the
FIB negative reproduced by the reviewer's own grep; labels present; placeholders kept; scope exact.

| # | Finding | Disposition |
|---|---|---|
| 1 | SHOULD FIX — the retracted "`IDENTITY_NUMBER` is an echo" claim still stood as fact in `docs/journeys/field-provenance.md` field 7 and in V0023's comment, and BL-030 did not say it reverses V0023's no-column decision | **Fixed**: field-provenance field 7 annotated [UNVERIFIED] pointing at BL-030; BL-030 now states the reversal. V0023 is applied and not edited. |
| 2 | SHOULD FIX — §5's classification table put the `mismatch` row (echo absent or differing) before the empty-record row, so a candidate-2 not-found would be recorded as `mismatch` | **Fixed** in §5 with an editorial note: empty-record test first, `mismatch` narrowed to a populated record. |
| 3 | NOTE — BL-031 called S3-02 "re-scoped" when EXECUTION_PLAN.md still carries the Oracle wording | **Fixed**: reworded as the re-scope AD-007 implies and the plan has not yet recorded. |
| 4 | NOTE — the card's FIB negative said "the whole clone" while §3 lists the trees searched and the reviewer could not reproduce the seven `civil` hits | **Fixed**: the card names the trees and notes the two root zip archives were not searched. The seven-hit count in §3 stands as the researcher's report; the conclusion is unaffected. |
| 5 | NOTE — two adapter bullets lacked labels, and the timeout bullet cited the smaller of the two observed photo sizes | **Fixed**: labelled; sized on the larger record (~25 KB decoded / ~33 KB base64). |
| 6 | NOTE — AD-002b's open list did not gain the echo-provenance question | **Fixed**: one clause added. |

Files changed by the filing: this report (new); `docs/components/civil-registry.md`; RISKS.md
R-031; PROJECT_PLAN.md OQ-023 and AD-002b; BACKLOG.md BL-030, BL-031;
`docs/journeys/field-provenance.md` field 7. No code; no gate run.

Key local paths: `docs/components/civil-registry.md`;
`backend/src/main/resources/db/migration/V0008__app_identity_artifacts.sql` line 51 (four-state
CHECK) and lines 58–65 (`omni_check`);
`backend/src/main/java/sd/gov/bank/fruserupdate/civilregistry/domain/RegistryLookupResult.java`;
`.../civilregistry/domain/CivilRegistryClient.java`; FIB's `AppConfiguration.java`,
`ApiClientService.java`, `BankServiceImpl.java` under `../FIB/backend server fib/utility/`.

## Commit proof

```
$ git log --oneline -1
3b8217f docs: Civil Registry not-found research filed (five candidates, echo-provenance risk, no FIB precedent); card, R-031, OQ-023, AD-002b, field-provenance updated; BL-030/BL-031 filed
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
