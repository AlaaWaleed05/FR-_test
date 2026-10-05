# Component: Civil Registry

Status: contract observed once; error handling **decided by the product owner 2026-09-04 (AD-002b, CLOSED)** — coarse classification, see "Decisions" below; **real adapter built 2026-09-04 (S3-14, BL-030 closed) and proven live on the not-found path the same day**; **the SUCCESS path was first proven at S7-12, 2026-09-07, against a real national record — and that walk found the adapter could never have succeeded for a passport holder (see "The national number must be canonicalised" below)** — see "How it is built" and "Live proof" · Last verified: 2026-09-07
Sources: one real Postman response, 2026-08-23; the product owner's 2026-09-04 endpoint; three live
discovery calls plus five authorised tests 2026-09-04
(docs/sessions/2026-09-04-plan-reconciliation-and-corebanking-discovery.md,
docs/sessions/2026-09-04-civil-registry-decisions.md); the same-day research reports (error
contract; adapter — docs/sessions/2026-09-04-civil-registry-http-adapter.md).

## Decisions (product owner, 2026-09-04) — what the adapter does

Full text: docs/sessions/2026-09-04-civil-registry-decisions.md. These override anything below
that reads as an open question.

| Response from the registry | Outcome | Journey state |
|---|---|---|
| 2xx, `application/json`, parses to a populated record, `IDENTITY_NUMBER` equals the number sent | success | `ok` |
| Any other **response** — HTTP 400 HTML page, 5xx, 404, 204, empty or unparseable body, a populated record with a different `IDENTITY_NUMBER` | "invalid / not found"; nothing finer is distinguished | `not_found` |
| **No response** — connect failure, read timeout, TLS or DNS failure | "did not reach the registry" | `unreachable` |

- `IDENTITY_NUMBER` is read from the found record, not copied from the request (product owner). The equality check is a real guard; exact match is assumed. No cross-field corroboration is required.
- The national number is forwarded **canonicalised to its ASCII digits** (`RegistryFieldNormaliser.digitsOnly`), applied at the adapter boundary only. **No** length, structure or check-digit validation — nothing observed supports one and the product owner declined to assume (Q10); stripping a separator the document itself printed is canonicalisation, not a structural claim. No special-casing of all-zeros or any other value (Q4).

### The national number must be canonicalised — [OBSERVED] S7-12, 2026-09-07

  **Until S7-12 this adapter forwarded the scanned value verbatim, and that made a real passport lookup
  impossible.** A Sudanese passport prints the national number grouped — 13 characters carrying 11 digits,
  in a 3-4-4 pattern — and Uqudo transcribes the document faithfully, which is correct OCR behaviour. The
  registry binds `NID` as a string and matches on the bare digits (the five authorised probes below already
  proved the string binding), so every hyphenated number came back as the service's 400/HTML not-found page.
  The customer was then shown "the service is temporarily unavailable, try again shortly" for a lookup that
  could never succeed, with no exit from the retry loop. **No test caught it: every test supplies an
  already-bare number.**

  Two rules follow, and the second is not optional:

  1. **What we SEND is canonicalised.** `app.scan_result.identity_number` keeps the document's own form —
     it is evidence, and the audit artifact is byte-identical to the response. Only the wire is canonical.
  2. **What comes BACK is not.** The identity-match comparison uses the exact string sent, while `returned`
     keeps the whitespace-only strip. Applying `digitsOnly` to both sides folds a returned `…0001x` onto
     `…0001` and reinstates prefix matching — BL-030's worst failure mode. An S7-12 draft did exactly that
     and `nearMissesThatAreNotWhitespaceAreMismatches` caught it. The asymmetry is the fix, not an oversight.

  If the service ever answers in grouped form — never observed — this reads as a mismatch and fails closed,
  logging both lengths. Correct default for a guard about identity.
- `BIRTH_DATE`: parse `DD/MM/YYYY`; on any parse failure leave `date_of_birth` null, never reject. `GENDER`: trim and lowercase; accept `m`/`f`, else null. The raw values need no new columns: the whole raw response is retained byte-identical as the Civil Registry audit artifact that PROJECT_PLAN.md's Architecture already requires, so nothing is lost. Safe choices covering the possibilities (Q11).
- `PHOTOGRAPH` is treated as always present. No other field is guaranteed; every field is optional and stored as received.
- No authentication is sent, none is required. The open, internet-reachable endpoint is used as the bank gave it; hardening is a later improvement outside this project.
- No rate limit is known (Q9). No synthetic test numbers are known to the product owner (Q8); real-service testing uses only the values already exercised (see "Error contract"), and no further probing beyond what the product owner authorised.

Derived from those answers by engineering, not themselves product-owner decisions (first written
by the decisions session, corrected by the S3-14 build research the same day): the number is
forwarded exactly as the server-side JWS parser supplied it (non-blank, required by the parser;
the empty string is a known 400 and would be `not_found` anyway); explicit timeouts — **connect
5 s, read 15 s**, not the ~30 s first written: no timing was ever recorded for this endpoint, the
body is ~25 KB, and the mobile client gives up at 30 s (`dio_provider.dart`), so the backend must
never still be waiting when the customer's socket has gone; **no in-adapter retry** — the
"at most one retry" line first written here is withdrawn: the journey's Stage 9 retry with its
`attempts` counter is the retry, and a silent one would double the worst case to the whole
mobile budget (Q9 said one retry is *acceptable*, not required).

## Endpoint

**[OBSERVED — one real Postman response, 2026-08-23]**

```
POST http://196.1.223.27:9090/CRSAPI/Services/GetCRSData
```

**Superseded 2026-09-04 [OBSERVED, live]:** the real route is
`POST https://<registry host>:5353/CRSAPI/Services/GetCRSData` — HTTPS on a hostname, reachable
from outside the bank. Host and port are configuration. Same request key, same fifteen response
fields. Certificate: the bank's DigiCert-issued wildcard, root `DigiCert Global Root G2`,
present in the JBR 21 `cacerts`; openssl and Windows Schannel both verify; TLSv1.2. Behind it:
GlassFish 4.1 / Java 1.8 / Jersey 2.10.4 — `OPTIONS` returns a WADL declaring exactly one JSON
`POST`. The "no TLS" gap below is closed; the wording is kept as history.

Request body: `{"NID": "<national number>"}`. What is observed is the request key itself
(`NID`); nothing in this response proves which Uqudo field supplies it. Consistent with the
S1-12 finding (docs/components/uqudo-api-findings.md) that the Civil Registry lookup key is
Uqudo's `identityNumber`, not `documentNumber` — note that finding itself corrects an
assumption made in the earlier AD-002a report, rather than being AD-002a's own text.

No authentication of any kind is present on the observed request — no header, key, or
token. Whether the service requires one under different conditions is `[UNVERIFIED]`; this
bore on AD-002b's access route, closed 2026-09-04 — used as given (Decisions, Q7).

## Error contract — what has now been observed [2026-09-04, live, fabricated values only]

| Input | Result |
|---|---|
| 20-digit value | HTTP **400**, `Content-Type: text/html`, GlassFish "HTTP Status 400 - Bad Request / The request sent by the client was syntactically incorrect." No JSON body. |
| Empty string | Identical HTTP 400 HTML page. |
| `00000000000` (eleven zeros) | HTTP **200**, `application/json`, **fourteen of fifteen fields populated, including a genuine photograph of a real person** — not a placeholder, not an empty record. Content NOT recorded; the capture was deleted. |
| Well-formed, unknown number | **NOT OBSERVED as such** at discovery time. Probing stopped on the eleven-zeros result (it was the first call made; the two 400s followed, and a planned eleven-nines call was not made): a repeated-digit value is evidently not a safe not-found probe against this service. **Superseded by the five-test row below:** if the 400 page is the service's not-found response, this case has in fact been seen — and under the Decisions section it is `not_found` either way. |
| Service down | Untestable from our side. Unknown. |
| Well-formed, unknown number — candidates | **[INFERRED, none observed — docs/sessions/2026-09-04-research-civil-registry-error-contract.md]** Ranked by mechanism: (1) HTTP 204 empty body — JAX-RS maps a `null` return to 204 [DOC]; (2) HTTP 200 with all fifteen fields empty — the service is known to emit empty strings (`FIRST_NAMES` was empty in the one real record), so this is indistinguishable from success by status alone; (3) HTTP 500 GlassFish HTML page from an unhandled DAO exception; (4) HTTP 200 `{}` or `null`; (5) HTTP 404, least likely on a single-POST RPC path. The adapter must be correct under all five. **Trail only, 2026-09-04:** this ranking rests on the premise that the 400 is not the not-found response, which the five-test row below makes doubtful; it is no longer an instruction — the Decisions section applies, and every one of the five candidates lands in `not_found`. |
| The observed 400 is **not** the not-found response | **[INFERRED by the research, now DOUBTFUL — see the next row]** The research read the body-less 400 as a Jersey entity-binding or Bean-Validation failure firing before the resource method. |
| **Five authorised tests, 2026-09-04** (product owner, Q5): zeros of length 1, 3, 10, 11, 12; bodies compared by SHA-256 only, nothing inspected or kept | **[OBSERVED]** length 11 → 200, populated record A (14 of 15 fields); length **1** → 200, a **different** populated record B (14 of 15 fields, different hash); lengths 3, 10, 12 → the identical HTTP 400 HTML page. **[INFERRED]** `NID` is bound as a **string**, not a number — numeric coercion would have made `0` and `00000000000` the same lookup, and `000` would not have failed. With string binding, the 400 for 3/10/12 zeros cannot be a type failure, and a fixed-length rule admitting both 1 and 11 is implausible; the most parsimonious reading is that **the HTTP 400 HTML page is the service's own not-found response**, thrown when no row matches, and that `0` and `00000000000` are two junk or seeded rows that exist. Under the decided classification this distinction does not matter — a 400 is `not_found` either way — which is why it was not pursued further. Leading zeros are preserved (string binding). |

What this meant for the adapter before the product owner's decisions (kept as the research
trail; the "Decisions" section above is what applies):

- A non-JSON 400 must be mapped to a fail-closed outcome, never parsed — now simply `not_found`.
- A 200 with a populated record is not proof that the submitted number was genuine; the adapter
  compares `IDENTITY_NUMBER` with the scanned `identityNumber`. The port takes `identityNumber`
  as its argument but, at the time of the research, no code compared a returned `IDENTITY_NUMBER`
  — the check had to be written into the real adapter (BL-030; **built at S3-14**, see "How it is
  built"). The research's further worry that the field might be a
  literal echo, requiring cross-field corroboration, was **withdrawn by the product owner's
  answer**: the value is read from the found record.
- `FIRST_NAMES` and `LAST_NAME` are not guaranteed [OBSERVED 2026-09-04]: `FIRST_NAMES` was empty
  in the one record whose fields were identified (the all-zeros discovery call; the five tests
  recorded field counts only). Absence is not a mismatch.
- The research proposed a fifth `mismatch` state (V0008 line 51 constrains
  `app.registry_result.state` to `pending` / `ok` / `not_found` / `unreachable`); **not adopted** —
  a differing `IDENTITY_NUMBER` is `not_found` under the decided classification. BL-030 is
  narrowed to storing the returned value for audit.
- Never retry a 4xx or 5xx [INFERRED]; at most one retry, on no-response only.
- Explicit connect and read timeouts [OBSERVED sizes]: the body carries a base64 JPEG — ~25 KB
  decoded / ~33 KB base64 in the larger observed record.
- The research's "validate exactly 11 digits, reject all-zeros" rule was **not adopted**: the
  product owner declined to assume a format; the number is used as given (non-empty ASCII digits).
- `GENDER` normalised, `BIRTH_DATE` parse failure never thrown — adopted as the safe choice.

## Authentication, transport and server behaviour

- **[OBSERVED 2026-09-04]** No authentication of any kind. A bare `POST` succeeded. The WADL declares no auth. CORS lists `Authorization` as an allowed header — a browser preflight hint, not a requirement.
- **[OBSERVED]** Internet-reachable on a hostname with a publicly-trusted DigiCert wildcard; TLSv1.2 (GlassFish 4.1 on Java 1.8 cannot do TLS 1.3). A deployment that disables TLS 1.2 in its JDK security policy would break this call.
- **[INFERRED]** Combined: an unauthenticated, internet-reachable endpoint returning a citizen's full name chain, address, date of birth and photograph for an eleven-digit key. Answered by the product owner (Q7, 2026-09-04): used as given; any hardening is a later improvement outside this project. Still relevant background for OQ-001 and OQ-021.
- **[DOC — Oracle GlassFish Server Reference Manual, `create-http`, https://docs.oracle.com/cd/E18930_01/html/821-2433/create-http-1.html]** Documented listener defaults: `request-timeout-seconds` 30, keep-alive `timeout-seconds` 30, `max-connection` 256. Caveat: 3.1 manual, same Grizzly stack; this deployment's actual settings are unknown.
- **[OBSERVED, negative]** No rate limiting observed — but only eight requests were ever made (three discovery calls, five authorised tests). GlassFish 4.1 ships none; back-pressure would present as queueing and timeouts, not `429`.

## FIB precedent

**[OBSERVED 2026-09-04]** There is none. `CRSAPI`/`GetCRSData`/`IDENTITY_NUMBER`/`PHOTOGRAPH` return zero matches in every extracted tree of the FIB clone that was searched — `Web_Code/` (the Vite bundle and its source maps), `backend server fib/utility/src` (the Spring Boot tier), `backend server fib/wwwroot/` (the deployed .NET `FIB_API`, whose only three external endpoints are `fib_url`/`email_url`/`my_email_url`, and the older CRA front end), and `mobile/lib`; `civil` matched only minifier identifiers. The two zip archives at the clone root were not searched. FIB neither observed nor assumed anything about this service. Its external-call pattern (bare `RestTemplate` with no timeouts, `@Retryable(Exception.class)`, every failure collapsed to `-1`) is precedent to reject — see docs/sessions/2026-09-04-research-civil-registry-error-contract.md §3.

## Response fields

**[OBSERVED — one real Postman response, 2026-08-23]**

| Field | Notes |
|---|---|
| `IDENTITY_NUMBER` | **Read from the found record, not copied from the request** (product owner, 2026-09-04, AD-002b). In a successful lookup it equals the number sent; the adapter treats any difference as `not_found`. Eleven characters in the records observed. |
| `NAME`, `FATHER_NAME`, `GRAND_FATHER_NAME`, `GRE_GRA_FATHER_NAME` | Arabic, four-generation paternal chain |
| `MOTHER_NAME`, `MOT_FATHER_NAME`, `MOT_GRA_FATHER_NAME`, `MOT_GRE_GRA_FATHER_NAME` | Arabic, four-generation maternal chain |
| `FIRST_NAMES`, `LAST_NAME` | Latin |
| `BIRTH_DATE` | **`DD/MM/YYYY`, not ISO 8601** |
| `GENDER` | Lowercase single char (`m` observed) |
| `ADDRESS` | **One comma-separated free-text Arabic string** — in the sample, four comma-separated parts reading state , locality , district , block (the real value was removed from this card on 2026-09-04: it came from a real citizen's record; see R-031's note) |
| `PHOTOGRAPH` | **Base64 JPEG inline in the response body**, ~25 KB decoded in the sample |

## What this contract is not

- **Not REST-shaped.** **[OBSERVED]** A single `POST` to a `Services` path, plain HTTP, **no
  TLS**. Do not infer behaviour from the URL — this is the "non-REST protocols behind
  REST-looking URLs" failure mode. There is no resource model, no verbs beyond this one
  `POST`, and nothing to assume from the path's naming.
- **No error contract observed** — **superseded by the Decisions section, 2026-09-04 (R-031 retired); kept as history.** **[OBSERVED that only one response existed at the time; the error shape
  itself was `[UNVERIFIED]`]** Behaviour for an unknown NID, a malformed NID, or a service
  fault is unknown, and customer.md stage 9 has a branch ("Civil Registry unreachable or
  returning nothing") that depends on distinguishing these cases. See R-031.
- **`ADDRESS` does not map onto the structured country → state → locality hierarchy**, and
  its components are not separately addressable. **[OBSERVED]** Which real-world field this
  corresponds to is itself **`[UNVERIFIED]`**: Uqudo's own SDN_ID front (see
  docs/components/uqudo-api-findings.md line 91) exposes `placeOfBirth` and `address` as two
  distinct fields, so CR's single `ADDRESS` could be either. **CLOSED at S2-08, 2026-08-27**:
  `app.registry_result.raw_address_ar` (V0023) now stores it for comparison regardless of
  which reading is correct — it never populates the structured profile address either way,
  per field-provenance.md fields 35-42. The birthplace reading question itself remains
  unresolved, but no longer leaves the value with nowhere to go. See R-032.
- **`PHOTOGRAPH` is a second portrait**, alongside Uqudo's extracted `faceImageId`. **[OBSERVED
  response field; "second portrait" and the AD-004 scope consequence are analysis, not
  observation]** Two portrait images per profile, from two independent sources — AD-004's
  storage scope grows by one artifact kind per profile.
- **Access is currently inside the bank only.** This is delivery context supplied for this
  session, not something this response itself demonstrates. Per the delivery model this
  blocks nothing: the Civil Registry is stubbed behind its own interface, and this document
  is the contract the stub implements.
- **The name model is four generations plus a separate Latin pair.** **[OBSERVED response
  fields]** The `app` schema (`app.registry_result`, V0008: `full_name_ar`, `full_name_en`,
  `mother_name`, `citizenship`) was originally built on AD-005's simpler two-field-per-language
  assumption. **CLOSED at S2-08, 2026-08-27** (V0023): decomposed into the real four-part
  paternal chain (`name_ar_given`/`name_ar_father`/`name_ar_grandfather`/
  `name_ar_great_grandfather`), the four-part maternal chain, and the separate Latin pair
  (`first_names_en`/`last_name_en`). `citizenship` was dropped — no field in this observed
  response corresponds to it. See R-032.

## How it is built (S3-14, 2026-09-04)

S3-02's core-banking adapter pattern, transferred unchanged where the two services agree (same
classpath, same JDK `HttpClient`, same host family and TLS chain) and different only where this
service is. Research: docs/sessions/2026-09-04-civil-registry-http-adapter.md, "Research findings".

- **Port** `civilregistry.domain.CivilRegistryClient` — `RegistryLookup lookup(String identityNumber)`.
  `RegistryLookup(record, identityNumberReturned, reason, exchange)`: `found()` is true only for a
  matching populated record (`ok`); otherwise `reason` names the classification rule that fired
  (`not_found`). No response is `RegistryUnreachableException`, now carrying the request-only
  exchange (`unreachable`). `RegistryLookupResult` gained `identityNumber` (BL-030) and carries the
  portrait as decoded bytes. `RegistryExchange` is a deliberate four-field twin of core banking's
  `RawExchange` — the CLAUDE.md quarantine rule forbids importing across integration packages.
  `RegistryFieldNormaliser` (domain, pure) holds the Q11 rules.
- **Adapter** `civilregistry.http.HttpCivilRegistryClient` — plumbing package. `POST` `{"NID": "<number>"}`
  as a JSON **string** (the service binds it as a string; `0` and eleven zeros are two records, so
  leading zeros are load-bearing), no auth; `ResponseEntity<byte[]>` with the status handler
  disabled, so the HTTP 400 HTML page — the service's routine not-found — is captured and audited,
  never thrown. Takes a finished `RestClient` so `MockRestServiceServer` tests bind to the builder.
- **Selection** by `fru.civil-registry.client` = `stub` | `http`, no default (unchanged shape).
  `http` without `fru.civil-registry.http.endpoint` fails at startup naming the property. The stub
  bean is still always constructed (integration tests drive `overrideOutcome`).
- **Configuration** `fru.civil-registry.http.{endpoint,connect-timeout,read-timeout}`; endpoint has no
  default; timeouts default to **5 s / 15 s**. Host and path are configuration, never literals.
- **Timeouts, and why.** Connect timeout on the JDK `HttpClient`, read timeout on the
  `JdkClientHttpRequestFactory`, HTTP/1.1 pinned (GlassFish 4.1 / Java 1.8 speaks nothing newer),
  redirects never — because the default `RestClient` on this classpath has no timeouts at all
  (S3-02 research). 15 s read: sized for a ~25 KB body plus an unmeasured lookup, and inside the
  mobile 30 s receive timeout. **No retries** — see the derived paragraph above.
- **Classification, in code order** (the first rule that fires wins; every rule yields a reason
  code and never a value):

  | # | Condition | Journey state | `reason` |
  |---|---|---|---|
  | 0 | no response — connect/read timeout, refused, TLS, DNS | `unreachable` | `no_response` |
  | 1 | non-2xx status (the observed 400 page lands here, before any parse) | `not_found` | `non_success_status` |
  | 2 | empty body, 204 included | `not_found` | `empty_body` |
  | 3 | body is not JSON | `not_found` | `non_json_body` |
  | 4 | JSON but not an object (`null`, array, scalar) | `not_found` | `not_an_object` |
  | 5 | no `IDENTITY_NUMBER` key, or JSON null | `not_found` | `identity_number_absent` |
  | 6 | `IDENTITY_NUMBER` is not a JSON string (a number is rejected, never coerced — it would already have lost leading zeros) | `not_found` | `identity_number_not_string` |
  | 7 | blank after `strip()` | `not_found` | `identity_number_empty` |
  | 8 | `strip()`ped value ≠ `strip()`ped number sent | `not_found` | `identity_number_mismatch` — the returned value is kept |
  | 9 | otherwise | `ok` | `matched` |

- **The `IDENTITY_NUMBER` guard.** Exact `String.equals` after `strip()` on both sides — the one
  tolerated difference is leading/trailing whitespace (a fixed-width column behind a 2014-era DAO
  may pad, and padding never changes which record matched). No other normalisation: no numeric or
  leading-zero folding, no Arabic-Indic digit folding, no case folding. On a mismatch the adapter
  logs at WARN the reason, HTTP status, content type, body length, the two values' lengths and a
  `differsOnlyByWhitespace` flag (computed with no-break spaces and bidi marks removed, for the log
  only) — never a value. Routine not-found reasons log at INFO.
- **Fields.** Every field optional, stored as received after `strip()` (blank → null); `BIRTH_DATE`
  via `dd/MM/uuuu` STRICT with `Locale.ROOT` (`yyyy` under STRICT needs an era and fails everything),
  null on any failure; `GENDER` strip + lowercase, `m`/`f` else null (V0023's CHECK admits nothing
  else — a pass-through would roll the accepted scan back into a 500); `PHOTOGRAPH` decoded once
  here with the MIME base64 decoder, an undecodable or absent value → no portrait, lookup still
  `ok` (the consumer's decode used to be unguarded). Raw values recoverable from the response
  artifact only.
- **The audit record** — S3-02's two-events-one-chain shape on the existing `profile` chain, actor
  `system`, one `requestId`, written inside the persistence transaction where the completed event
  has always lived (the lookup is one leg of a multi-row state change; a rollback discards the
  evidence with the state it describes, and the customer's retry re-runs the lookup):

  | Event | Artifact | Payload |
  |---|---|---|
  | `registry_lookup_requested` — only when request bytes exist (never against the stub) | `civil_registry_request` (kind added by V0062), `application/json`, the exact bytes sent | `requestBytes` only — the body IS the national number, which never enters the hash-chained `payload_json` |
  | `registry_lookup_completed` (S3-12's event) | `civil_registry_response`, the observed `Content-Type`, the exact bytes received — absent when nothing came back | `state`, `retried`, `reason`, `httpStatus`, `responseMediaType`, `responseBytes` |

  Against the stub: one event, no artifacts, `reason` null — exactly as before.
- **`app.registry_result.identity_number_returned`** (V0062, BL-030): written on `ok` and on a
  mismatch `not_found`, through an explicit repository parameter separate from the record fields
  (which are null on any `not_found`). V0062 also re-declares `audit_artifact_kind_check` with the
  request kind — the first artifact kind added since AD-005 — under `SET LOCAL
  fru.migration_in_progress` because it is DDL in schema `audit` (R-035).
- **Stub** `StubCivilRegistryClient`: same three outcomes, keyed by `fru.civil-registry.stub.outcomes`;
  OK returns the synthetic record with `identityNumber` = the number asked; no invented exchange
  or reason, so nothing fabricated reaches the audit trail.
- **Mobile**: no change. Stage 9 never surfaces a registry outcome as an HTTP error (both
  non-success states are HTTP 200 with `registryReady=false` and a paused profile), the adapter
  introduces no new status, and `mobile/` has no identity-scan client yet (S5-07).

## Live proof

- `HttpCivilRegistryClientTest` (32, `MockRestServiceServer`) covers every row of the table above
  against fabricated bodies; `HttpCivilRegistryClientTimeoutTest` (a JDK `HttpServer` holding the
  response past the read timeout) plus revert-restore at S3-14 — with `setReadTimeout` removed the
  test waits the server out and finds the record.
- `HttpCivilRegistryClientLiveTest` (`@Tag("live")`, enabled only by
  `FRU_CIVIL_REGISTRY_LIVE_ENDPOINT`, excluded from every gate) asserts the **400/not-found path
  only**, with the three-zero value from the authorised set: every other value ever exercised
  returns a real citizen's populated record, and the product owner knows no synthetic number (Q8),
  so the `matched` path is mock-only by rule. **GREEN 2026-09-04 (S3-14 tail), 1/1** against the
  real service:

  ```
  [live] input=000 http=400 content-type=text/html bytes=1105 reason=non_success_status
  ```

  Three facts this settles: the 400 HTML page is still what the service returns for an unknown
  number, byte-for-byte the same 1,105-byte page the five authorised tests hashed; the adapter
  classifies it `non_success_status` → `not_found` against the real thing, not just a mock; and
  the page never reaches the JSON parser.
- **First real timing for this endpoint [OBSERVED, S3-14 tail]:** three curl calls with the same
  authorised value — **1.77–1.87 s total**, of which TLS handshake ~0.55–0.65 s and TCP connect
  ~0.12–0.23 s, 1,105-byte reply. This is the **not-found path only**: it exercises neither the
  record lookup nor the ~25 KB base64 photograph, so a populated-record call is necessarily
  slower by an unmeasured amount. The 15 s read default stands, now with a measured floor under
  it rather than nothing — the reject path uses about an eighth of the budget. Note the registry
  is roughly twice as slow as the core-banking middleware on the same host (~0.9 s), which is why
  it does not simply inherit that adapter's 10 s.

## Open items

- The `app` schema's name and address columns need a field-by-field provenance pass against
  the bank's paper form before any amendment, per R-032's mitigation.
- AD-002b is CLOSED (2026-09-04) and built (S3-14), and the adapter is now proven against the
  real service on the not-found path. The remaining unknowns — what the all-zeros and single-zero
  records are, the exact not-found mechanism, outage shape, whether a login could ever be
  required — are accepted as not this project's concern, by product-owner decision. **Latency is
  now partly measured** (see "Live proof"); what is still unmeasured is a populated-record call,
  which cannot be timed without submitting a real number. The first such timing from UAT should
  re-check the 15 s read default.
