# 2026-09-07 — Airtel Sudan SMS adapter (BL-080, road map 1.1, S8-01)

**Backend only.** No mobile, no backoffice, no journey logic, no outbox-scheduler change, no change
to WhatsApp/email provider selection.

**Headline: the happy path is built and testable. The two failure-response handlers are defensive
and unverified, deferred to production per product-owner decision. No code has reached a real
handset through this adapter — the one thing road map 1.1 calls "done" is the one thing this
session did not do.**

## What existed before, and what the brief got wrong

Three claims in the session brief were checked against source before building. Two were wrong, both
in the project's favour, and correcting them removed work rather than adding it.

| Brief said | Source said |
|---|---|
| "If the port has no non-retryable signal, the requirement cannot be met as written" | `DispatchOutcome.PERMANENT_FAILURE` exists and is documented for exactly this case; `notification/domain/OutboxState.java:36-42` maps it to terminal `FAILED`, never retried. **No port change needed.** |
| Provider value `airtel` | `docs/road-to-production.md:133` and every sibling integration say `http` (`fru.core-banking.client=http`). **Built as `http`.** |
| Number format "may want local format — verify, don't assume" | Already stated twice: BL-080 and the road map both say we store `+249…` and Airtel wants `0…`. Not an open question. |
| AD-009 records the researcher waiver | **AD-009 did not exist.** Only AD-008 was in PROJECT_PLAN.md. Written this session. |

`providerMessageId`, `providerStatusCode`, `providerStatusText` and `billedSegments` already carry
apiMsgId, the status word, Airtel's own reason and the unit count — so BL-080's "the schema needs no
change" holds, and no migration was written.

## Built

New package `backend/src/main/java/sd/gov/bank/fruserupdate/messaging/airtel/` — plumbing, outside
`domain`/`service` per CLAUDE.md, one package per external system so it is its own quarantine
boundary:

- `AirtelSmsProperties` — `fru.messaging.sms.http.*`. Endpoint, username, password, sender id have
  **no defaults**; timeouts default to 5 s / 15 s.
- `AirtelDestination` — `+249` + 9 digits → `0` + 9 digits. Pure.
- `AirtelSmsResponse` / `AirtelSmsResponseParser` — the captured 3-line contract. Pure.
- `AirtelSmsSender` — `MessageSender`, `supportedChannels() == {SMS}`.

`MessageSenderConfiguration` gains `HTTP = "http"`, `ACCEPTED_PROVIDERS = List.of(STUB, HTTP)`, the
`restClient(...)` factory and a startup check naming missing properties but never values.

### Design decisions and why

**`http` is accepted for SMS only.** WhatsApp and email have no adapter at all (SMS-only release).
`fru.messaging.whatsapp.provider=http` fails at startup naming the property, rather than mapping an
SMS-only sender onto a channel it cannot serve.

**A non-`+249` destination is `REJECTED` locally and never sent.** `PhoneNumberNormalizer:12-17`
deliberately passes a foreign number through unchanged — the journey allows a customer verified from
abroad — so `+9715…` genuinely reaches this adapter. It has no `0…` national form; sending it anyway
returns `Invalid Sudanese number`, which is indistinguishable from BL-080's trap and would be
recorded as the customer's number being bad. `REJECTED` is terminal in `OutboxState` and blames the
destination, which is correct.

**An unrecognised body is `TRANSIENT_FAILURE`, logged at ERROR — never `PERMANENT_FAILURE`.**
PERMANENT is terminal, so one gateway maintenance page misread as an auth failure would permanently
kill a customer's OTP with no retry. ERROR level is the compromise: retries continue, and a real
outage is still visible to an operator.

**`billedSegments`** takes the parsed `Total Units` when readable (so `0` on a recognised failure —
Airtel's own statement), `SEGMENTS_NOT_APPLICABLE` (-1) when the body did not parse, and `0` on a
local reject, where nothing was sent so nothing was billed. `Ucs2Segmenter` is deliberately not used:
the provider's own count is the billable truth, and recording it is the only way the Arabic cost
multiplier becomes measured rather than estimated.

**`providerStatusCode`** is the status word verbatim (`completed` / `failed`) where the provider gave
one, and one of `UNRECOGNISED_RESPONSE` / `DESTINATION_NOT_SUDANESE` / `NO_RESPONSE` where it did
not — our own codes, the precedent being `ChannelRoutingMessageSender.CHANNEL_NOT_CONFIGURED`.

### The credential is in the query string — three vectors, all closed

The captured contract is a GET with `username` and `password` as query parameters, so **the request
URI is itself a credential**. Three paths would have persisted it, and the second is not
hypothetical — the proven adapter in this codebase does exactly it:

1. **Logging the URI.** No `log.` call in `AirtelSmsSender` carries the URI, the destination, or the
   body. Reason code, HTTP status, content type and byte length are the whole vocabulary, following
   `HttpCivilRegistryClient`'s stated discipline.
2. **`RestClientException.getMessage()`.** `HttpCoreBankingClient.java:96` copies that message into
   its own exception; for a connect/read failure the JDK client builds it from the full request URI.
   This adapter uses `noResponse.getClass().getSimpleName()` and nothing else.
3. **A raw response body reaching `providerStatusText`.** An HTML error or proxy page commonly echoes
   the requested URL, and that field is written to `app.notification_outbox` and into the audit
   payload via `canonicalPayload()`. `AirtelSmsResponse` deliberately does not carry the raw body; an
   unrecognised response travels as our own bounded description.

Plus `AirtelSmsProperties.toString()` redacts, because a record's generated `toString` prints every
component and one `log.debug("props={}", properties)` would be enough.

### The uncaptured wrong-password response — flagged, not faked

**No auth-failure detection is implemented, because none is possible from what has been observed.**
BL-080 records that HTTP status is 200 on both captured paths — confirmed — so there is no 401/403 to
key on, and the only observed failure reason is a per-customer one. Keying a terminal outcome off a
guessed reason substring would be worse than not detecting it.

So there is **no auth-specific branch anywhere in the adapter**. The defensive handling is the
fail-closed rule: positively confirm success, treat everything else as a failure, and make the
unrecognised case loud. The marking is discoverable —
`AirtelSmsSenderTest.anUnrecognisedBodyIsATransientFailureUnverifiedStandInForTheUncapturedAuthFailure`
says so in its name, and its javadoc points at road map 0.2 and states what must change if the real
capture turns out to be distinguishable.

**Arabic body:** sent UTF-8, percent-encoded, `%20` for space rather than the form-encoded `+`,
because the 2026-09-07 capture that proved an Arabic body works was taken through Postman, which
sends `%20`. Encoding is done explicitly rather than through `UriComponentsBuilder` so no URI-template
semantics are applied to a credential — a password containing `{}` would otherwise be read as a
placeholder.

## Tests

71 new/changed tests in the two touched packages. Direct vs revert-restore, stated per the rule:

| Test | Count | Proof kind |
|---|---|---|
| `AirtelDestinationTest` | 15 | **Direct** — asserts the converted value; cannot pass against a missing conversion |
| `AirtelSmsResponseParserTest` | 19 | **Direct** — asserts apiMsgId, units and the verbatim reason against the captured shapes |
| `AirtelSmsSenderTest` | 10 | **Direct** — asserts the outcome each captured response must produce |
| `AirtelSmsSenderCredentialLoggingTest` | 4 | **Revert-restore** (below) — asserts an ABSENCE, which a green run could coexist with a leak on |
| `AirtelSmsSenderTimeoutTest` | 1 | **Revert-restore** (below) — `MockRestServiceServer` replaces the factory the timeouts live on, so it structurally cannot prove them |
| `MessageSenderConfigurationTest` | 22 | **Direct** |
| `AirtelSmsSenderLiveTest` | 2 | Env-gated, `@Tag("live")`, inert. Not run this session |

### Revert-restore proof 1 — the credential guard

Reverted `noResponse.getClass().getSimpleName()` to the `HttpCoreBankingClient` pattern
`noResponse.getMessage()`:

```
[ERROR] AirtelSmsSenderCredentialLoggingTest.noResponsePathNeverLeaksTheCredential:152
  ->assertNothingLeaked:175 leaked the request URI: Airtel SMS send got no response:
  exception=I/O error on GET request for "https://sms.invalid/api/html_send_sms/":
  connection reset ==> expected: <false> but was: <true>
[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
```

**An honest limitation of this proof, stated because it changes what it proves.** The mock's
exception message carried the URI *without* its query string, so what failed was the assertion
forbidding the request URI — not an assertion on the password itself. The password-specific
assertions alone would **not** have caught this revert. That is an argument for the assertion set as
written (it forbids the whole URI, the host, the username and the literal `password=`, not just the
secret) rather than against it: against the real JDK client the message carries the full URI
including the query, and the broader guard catches both cases. Restored and re-run green.

### Revert-restore proof 2 — the read timeout

Removed `factory.setReadTimeout(properties.readTimeout())` from
`MessageSenderConfiguration.restClient`:

```
[ERROR] AirtelSmsSenderTimeoutTest.aGatewaySlowerThanTheReadTimeoutIsATransientFailure:113
  expected: <TRANSIENT_FAILURE> but was: <ACCEPTED>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

The handler holds 3 s then returns a valid captured success body, so the broken build parses it and
reports `ACCEPTED` after ~5 s. The valid body is deliberate: had the handler returned garbage, the
broken build would still have produced a transient failure and the test would have passed against it
for the wrong reason. Restored and re-run green.

A javadoc claim in that test was wrong on first write — it said asserting only `TRANSIENT_FAILURE`
would still pass against the broken build. The revert showed the result is `ACCEPTED`, so the outcome
assertion alone does catch it. Corrected in the file.

## Gate

`./mvnw verify -Pdb-integration-test`, run from `backend/`, with Docker Desktop started so the
Testcontainers suite actually runs — without `-Pdb-integration-test` the JaCoCo check measures
~60% and proves only Spotless and the non-integration tests.

Earlier runs, one line each per the rule: targeted `-Dtest='Airtel*,MessageSenderConfigurationTest'`
— 70 tests, all passing; full `verify -Pdb-integration-test` before the review pass — 1090 tests,
all passing. **Final run, after the review fixes, verbatim:**

```
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1091, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 456 files clean - 0 needs changes to be clean, 0 were already clean, 456 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:35 min
[INFO] Finished at: 2026-09-07T12:17:39+02:00
[INFO] ------------------------------------------------------------------------
```

iOS support check: **not applicable** — no Flutter package was added; backend only.

## Review findings

`@agent-reviewer` against the diff and BL-080. Verdict: **no blocker** — no credential, real endpoint
or real phone number committed; no path puts the request URI, username, password or a raw response
body into a log or a persisted field; the uncaptured-response handling is genuinely flagged rather
than guessed; and it could not construct a non-success that the parser reports as SUCCESS.

| Finding | Disposition |
|---|---|
| **SHOULD FIX — the memoisation rationale was wrong.** `select()` resolves the supplier *inside* the HTTP branch and `isEnabled()` is checked afterwards, so `sms.provider=http` + `sms.enabled=false` still builds the client and still demands credentials. Since `http` is SMS-only, at most one channel can ever select it, so the memoisation could never deduplicate anything either — it was inert | **Fixed, in the records rather than the code.** The eager build is *correct*: it matches this class's existing rule that a typo'd provider on a disabled channel must still fail startup, so a config nobody re-checked cannot hide behind `enabled=false` and activate silently later. The code comment and the S8-01 row now say what is true, the inert `memoize()` helper is deleted, and the previously-untested case is asserted by `httpOnADisabledSmsChannelStillValidatesItsCredentials`. **One correction to the finding:** it said the claim was in three permanent records; it was in two — this report never carried it |
| `RECIPIENT_FAILED` skips the echo check the success path applies | **Documented, not changed.** Defensive depth, not a live defect: the outcome is a rejection either way and we send one recipient per request. The asymmetry is now stated in the parser javadoc instead of being left unremarked |
| The `(units=N)` group is captured but never read; a disagreement with `Total Units` is silently discarded | **Documented, not changed.** `Total Units` is the billed total and is what the plan specified. Now stated as deliberate |
| A negative `Total Units` parses and aliases onto `SEGMENTS_NOT_APPLICABLE` (-1) | **Not changed.** Never observed; recorded here rather than adding an unexercised branch |
| **No attempt cap behind TRANSIENT_FAILURE** — nothing reads `attempt_count`, so a persistently unrecognised response retries every 30 s forever; and if Airtel's format ever changed, each retry would deliver a real SMS that Airtel accepts and bills while the outbox records none | **Filed as BL-084.** Out of scope by this session's own terms (no outbox-dispatcher change), but newly consequential — this adapter is the first thing in the system that can produce a *permanent* TRANSIENT_FAILURE, which is exactly where the uncaptured auth failure lands |
| `SENDER_ID = "SFB"` is the bank's real sender id, while the test javadoc claimed every value was synthetic | **Javadoc corrected.** Not a secret — customer-visible, already in BL-080 — but the claim was false |
| Revert-restore proof (a) assessed as strong enough: `assertNothingLeaked` also forbids the endpoint host, and any real JDK-client leak necessarily contains it | Noted. The limitation stays stated below rather than quietly dropped |

## What this session did NOT do

- **No live send. No real handset received anything.** `AirtelSmsSenderLiveTest` exists, is
  `@Tag("live")` and env-gated, and was not run. Road map 1.1's "a real code arrives on a real
  handset" is therefore **not** met, and the EXECUTION_PLAN row says so rather than claiming the item
  closed.
- **The wrong-password capture is still owed** (road map 0.2). It no longer blocks 1.1 — the plan
  files were amended, not quietly bypassed — but the adapter's handling of it stays `[UNVERIFIED]`.
- **`Ask whether the same service accepts POST` is unresolved.** The credential is in the query
  string on every send, which every proxy and access log on the path records. Still worth asking;
  tracked on BL-080.

## R-041 — carry this to the bank when the APK ships

**The adapter cannot tell you whether a message was delivered, and no amount of adapter work will
change that.** `ACCEPTED` means Airtel said `Status: completed` and handed back an `apiMsgId` — the
gateway took the message. There is no delivery-receipt path from this gateway at all.

R-041 is live and **accepted, not mitigated**: the sender ID is unregistered by product-owner
decision, and an unregistered alphanumeric sender can be dropped by a network with no error returned.
So **a green send and a silent handset is R-041, not an adapter bug**, and the two are
indistinguishable from the backend. If pilot delivery is patchy by network, check this first.

## Staff-test framing — tell the bank this, it is not a code item

This is built on **one tested network's** behaviour, on the product owner's assumption that Sudanese
networks behave alike on the happy path. That assumption is supported for the *response* — a
malformed-number capture the same day showed a uniform failure shape — but **response uniformity is
not delivery uniformity**. The reply carries an `apiMsgId` at send time and says nothing about
delivery.

Consequence: **a bank-staff tester on a different network may not receive a code**, and the backend
will show a successful send. Either tell testers to test from the validated network, or accept the
uncertainty and treat a silent handset as data about R-041 rather than as an adapter defect. Three
phones on three networks at pilot closes it cheaply.

## Files

New: `messaging/airtel/{AirtelSmsProperties,AirtelDestination,AirtelSmsResponse,AirtelSmsResponseParser,AirtelSmsSender}.java`;
tests `messaging/airtel/{AirtelDestinationTest,AirtelSmsResponseParserTest,AirtelSmsSenderTest,AirtelSmsSenderCredentialLoggingTest,AirtelSmsSenderTimeoutTest,AirtelSmsSenderLiveTest}.java`;
`messaging/config/MessageSenderConfigurationTestAccess.java`.

Modified: `messaging/config/MessageSenderConfiguration.java`, `application.properties`,
`messaging/config/MessageSenderConfigurationTest.java`, `BACKLOG.md` (BL-080), `EXECUTION_PLAN.md`
(S8-01), `PROJECT_PLAN.md` (AD-009), `RISKS.md` (R-041), `docs/road-to-production.md` (0.2, 1.1),
`docs/components/messaging.md`.

**No credential, no real phone number, no filled endpoint URL appears in any of them.**
