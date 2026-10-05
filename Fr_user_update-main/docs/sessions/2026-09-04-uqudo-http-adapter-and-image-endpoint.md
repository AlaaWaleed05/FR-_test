# 2026-09-04 — S5-09 and S5-10: the real Uqudo adapter, and Stage 9's review images

Two backend prerequisites for S5-07 (mobile stages 7-9). Neither was in the plan at the start of
the session: they came out of a precondition review of S5-07 itself, which found three gaps between
what the stage 7-9 screens need and what the backend actually does. The product owner scheduled the
first two for this session and deferred the third.

## What the precondition review found

| # | Gap | Disposition |
|---|---|---|
| 1 | `UqudoClientConfiguration` accepted only `stub`, and `StubUqudoClient` verified HS256 against a fixed key. A real device's RS256 `enroll()` JWS could not be accepted at all — and the failure lands in `recordFailedAttempt`, so **every device attempt burned retry budget and six triggered the 24-hour block**. | Built — S5-09 |
| 2 | customer.md Stage 9 (line 721) requires the document image and extracted portrait beside the registry data. `ScanDisplayPayload` carried no image reference of any kind and no customer-facing endpoint read `app.artifact_ref.body`. | Built — S5-10 |
| 3 | `IdentityScanController.run()` maps eight distinct exceptions onto one bare `409`, and `server.error.include-message` is unset (Spring default `never`) with no `@ControllerAdvice`, so not even the reason text reaches the device. | **Deferred — BL-033** |

Gap 3 was deferred deliberately, not overlooked: the open question is which of the eight are worth
distinguishing to a customer and which should be grouped — a UX and error-taxonomy question, not a
mapping exercise. It still blocks S5-07's error screens; it does not block the happy path.

## Decisions

- **No `@agent-researcher` run.** CLAUDE.md requires one before third-party SDK integration,
  "without exception" for Uqudo. Put to the product owner with the reasoning and skipped: the rule
  exists so we never integrate from documentation alone, and S1-02 already satisfied that against
  the live FIB tenant (token lifetime, JWS `exp`, image retention, the face-session binding), with
  `spike/UqudoSpikeHttp.java` and `spike/UqudoSpikeJws.java` as working code against these exact
  endpoints. Recorded here rather than passed over silently.
- **No audit event per customer image fetch.** operator.md's "viewing an image is its own audit
  event" is a control over which *operator* viewed whose document; a customer viewing their own
  scan is not that, and the screen re-renders and retries freely.
- **SDN_ID device testing dropped** (product owner). The national-ID parser branch stays
  `[UNVERIFIED]`; passport is the only shape ever observed.
- **R-046 not settled, AD-002e not reopened.** See "What this deliberately did not do".

## S5-09 — the real Uqudo client

**The parser was extracted, not duplicated.** CLAUDE.md requires the JWS parser stay quarantined in
a single class, and two clients that each knew the field names would be two. `uqudo.domain.
UqudoJwsParser` now holds the whole R-034 parser and takes a `JwsSignatureVerifier`; the stub
supplies HS256, `uqudo.http.UqudoJwksSignatureVerifier` supplies RS256-against-JWKS. The seam was
already there — the old `verifiedPayload(String)` was the only method touching the signature.

*Proof the extraction changed no behaviour:* `StubUqudoClientTest` passes with **no edits at all**
(35/35). It is absent from `git status`. The reviewer additionally verified this mechanically — every
code line in the new parser also exists in `git show HEAD:...StubUqudoClient.java`, and the only old
lines present in neither new file are two imports and the two-line `MAPPER.readTree` that the
verifier seam replaced.

**Signature rules** (`UqudoJwksSignatureVerifier`), each with a test:

- `alg` pinned to RS256. `none`, HS256 and EC are refused before a key is looked up.
- `kid` must resolve in a caching / refresh-ahead / rate-limited JWKS, or fail closed — no
  fall-back to "any RSA key in the set".
- `iss` and `aud` must equal the configured tenant (`aud` is the client id, observed S1-02).

**`iat` is bounded in the future only, not symmetrically.** The component card says "iat (±60s)",
which is right about clock skew and wrong as a past-bound for this journey: customer.md Stage 13
lets a dropped upload be re-presented for the JWS's whole 2-hour life, so a 60-second past-bound
would have turned **every resume into a counted verification failure**. `exp` bounds the past, in
the parser, keeping its distinct non-counting `ArtifactExpiredException`. Two tests pin both halves:
an `iat` 115 minutes old is accepted; an expired JWS reaches the parser rather than being rejected
by the verifier.

**The device-facing token is minted fresh every call.** `issueAccessToken()` deliberately never
serves the internal cache — that token crosses to a handset at tap-to-scan, and customer.md Stage 7
moved token issuance there precisely so the device never holds a nearly-dead one. The adapter's own
server-to-server calls do reuse a cached token, refreshed 60 s before expiry. Both behaviours are
asserted.

**What was carried over from S1-02 rather than from documentation:** the multipart build must use
`LinkedMultiValueMap`, not `MultipartBodyBuilder` (the latter references Reactive Streams, absent
from this WebMVC-only classpath — a live `ClassNotFoundException`); the image `404` is the routine
answer at exactly `iat`+2 h; `DELETE /info/{id}` answers `204` for any UUID at all, so a `204` is
not evidence anything was purged.

**Configuration:** `fru.uqudo.http.*`, no host or credential with a default (R-007 — the tenant
changes before production, and a default in a committed file is what that rule forbids). Startup
names every missing key at once. Credentials come only from the gitignored local config.

### One bug the tests caught

`HttpStatus.PAYLOAD_TOO_LARGE` and `HttpStatus.CONTENT_TOO_LARGE` are both 413 in Spring, so
`HttpStatus.resolve(413) == HttpStatus.PAYLOAD_TOO_LARGE` was false and the 413 branch never
matched. All status comparisons are now integers, with the reason recorded in a comment.

## S5-10 — `GET /api/v1/identity-scan/image/{kind}`

No migration. `app.artifact_read()` (V0054) was already the sanctioned checksum-verified read path,
with `JdbcLivenessRepository` as the working precedent.

**The cycle predicate is the load-bearing detail.** The query keys on `identity_cycle.state =
'active'` and deliberately **not** on `accepted_at IS NOT NULL`. Stage 9 is the screen where
acceptance is still being decided — `markCycleAccepted` runs inside `/registry-review/accept` — so
an acceptance predicate (which Stage 10's reference-image read correctly uses, because it runs
after that decision) would have returned 404 for exactly the window the screen needs. Same table,
different moment, different predicate. Verified safe: V0008 has a unique index on one active cycle
per profile, and `SUPERSEDE_ACTIVE_CYCLE` flips the prior one before a new one is inserted, so no
superseded or abandoned cycle's images can be reached.

**Allowlist, not denylist** (`ScanImageKinds`): doc front/back, `portrait_uqudo`,
`portrait_registry`. Capture frames are excluded because AD-004 stores no bytes for them at all;
liveness, signature and salary certificate because they are not Stage 9 review material. A future
artifact kind becomes fetchable only when someone decides it should.

`ScanDisplayPayload`/`ScanDisplayResponse` gained `availableImageKinds` so the screen requests only
what exists — a passport has no `doc_back`, and `portrait_registry` appears only once the registry
lookup succeeded — rather than probing. The listing query reads metadata only and never calls
`app.artifact_read()`, so answering "which images are there" does not pull every body out of TOAST.

Absent, purged, unknown kind and unknown profile are **all one `404`**: distinguishing them would
confirm a guessed profile id. `Cache-Control: no-store`, asserted in the integration test because
R-051 names it as a mitigation.

## Review findings and dispositions

`@agent-reviewer` against the diff and the approved plan. It confirmed all six things I asked it to
check (extraction behaviour-preserving, the `iat` reasoning, the cycle predicate, SQL binding order,
secret/PII discipline) and then found the following.

| Finding | Severity | Disposition |
|---|---|---|
| `BL-031` was already taken by a closed S3-02 item — duplicate ID against a register whose IDs are permanent | Blocker | **Fixed** — renumbered to BL-033, and the S5-07 row's reference updated |
| `R-048` already taken, **and** the row was appended to the "Delivery notes — outside the deliverable" table, whose own preamble says those are not risks to what we build — so a live risk this diff ships was invisible to the active register | Blocker | **Fixed** — renumbered to R-051 and moved into the active register |
| `purgeSession` claimed in its own comment that it cannot throw, but the token mint and the DELETE's transport errors both escaped. It runs *after* the accept commits, so it cannot roll anything back — it can only turn a successful scan into a 500 whose retry then hits `JwsAlreadyAcceptedException` | Should fix | **Fixed** — whole body wrapped, two tests added (transport failure, failed token mint) |
| `downloadImage` let transport errors and unexpected statuses (401 on a revoked token, 429, 5xx) escape as unaudited 500s, in a stage where every other exit is audited — a JWS that verified fine plus a transient blip left no trace at all | Should fix | **Fixed** — every way of not getting bytes is now `ImageUnavailableException`, the audited non-counting outcome. New `ImageUnavailableException(imageId, reason)` carries the real cause into the audit payload's `reason`, so the event name is coarse but the trail is not |
| `Instant.now()`/`Year.now()` moved into a `domain` package, which CLAUDE.md says must be exercisable with "no clock" — legal where they were (`uqudo.stub` is plumbing), a rule violation where the extraction put them | Note | **Fixed** — `Clock` injected via a second constructor; the single-arg one delegates, so no call site changed |
| `Cache-Control: no-store` asserted nowhere, though R-051 rests on it | Note | **Fixed** — asserted in the integration test loop |
| `UqudoClient`'s port Javadoc still said "only a stub exists today" | Note | **Fixed** |
| `.env.example` claimed those two variables configure the real adapter; they do not, because the `fru.uqudo.http.*` block is deliberately un-defaulted | Note | **Fixed** — reworded to say so explicitly, including that the resulting startup failure is intended |

`createFaceSession`'s transport errors were left as a 500 by decision, not oversight: it runs before
any state change and consumes nothing, so a failure there loses nothing and the customer simply
retries. Giving it a journey outcome is part of BL-033's taxonomy question.

## What this deliberately did not do

- **R-046 stays open and unprejudiced.** It exists because an HTML `<img>` cannot carry an
  `Authorization` header; this endpoint's client is Flutter, which sets its own headers and renders
  from bytes. No signed-URL scheme was introduced and `artifact_ref.storage_key` was left untouched,
  so the back-office decision is exactly as open as it was. Noted on the R-046 row.
- **AD-002e was not reopened.** The new endpoint inherits the settled `/api/v1/**` `permitAll`
  chain like every other customer endpoint. That this exposes identity-document images to anyone
  holding a profile UUID is real and is now **R-051**, not a one-off auth scheme invented in
  passing. The honest fix is a customer-session credential across the whole surface, which is
  AD-002d's Phase 2 question.

## Not proven by these gates

- **That a real device scan completes end to end.** The SDK needs arm64/armeabi-v7a hardware; that
  is S5-07's own live step on the S1-02 handset, and it needs the product owner present.
- **Anything about SDN_ID.** Only a passport JWS has ever been observed. The national-ID branch of
  the parser is closed by evidence and reasoning, not by a scan, and green tests on the passport
  path do not speak for it.
- **The real image download, purge and face-session calls.** Proven against
  `MockRestServiceServer` on the shapes S1-02 observed. Each needs a real scan to exercise live, and
  there is no synthetic one — on FIB's borrowed tenant (R-001) that would mean reaching for another
  bank's customer data. The opt-in `HttpUqudoClientLiveTest` covers the only two calls that touch no
  customer data: minting a token and reading the JWKS. **Not run this session** — no credentials
  were supplied.

## Gate

`./mvnw verify -Pdb-integration-test` — the real backend coverage gate (plain `verify` measures
60.52% and proves Spotless plus the non-integration tests only).

```
[INFO] Results:
[INFO]
[INFO] Tests run: 880, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 316 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 430 files clean - 0 needs changes to be clean, 0 were already clean, 430 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 316 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:43 min
```

Only the backend tier is in this diff (`git status` shows no `mobile/` or `backoffice/` path),
so the other two tiers’ gates do not apply.

## Files

**New (main)** — `uqudo/domain/UqudoJwsParser.java`, `uqudo/domain/JwsSignatureVerifier.java`,
`uqudo/http/{UqudoHttpProperties,UqudoJwksSignatureVerifier,HttpUqudoClient}.java`,
`identityscan/domain/{ScanArtifact,ScanImageKinds}.java`.

**New (test)** — `uqudo/config/{UqudoClientConfigurationTest,UqudoClientConfigurationTestAccess}`,
`uqudo/http/{HttpUqudoClientTest,HttpUqudoClientTimeoutTest,HttpUqudoClientLiveTest,
UqudoJwksSignatureVerifierTest}`, `identityscan/domain/ScanImageKindsTest`.

**Modified** — `uqudo/stub/StubUqudoClient` (delegates parsing, keeps `fabricate*`),
`uqudo/config/UqudoClientConfiguration`, `uqudo/domain/{UqudoClient,ImageUnavailableException}`,
`identityscan/{domain/IdentityScanRepository, jdbc/JdbcIdentityScanRepository,
service/IdentityScanService, service/ScanDisplayPayload, web/IdentityScanController,
web/ScanDisplayResponse}`, `application.properties`, `.env.example`,
`IdentityScanIntegrationTest`, `EXECUTION_PLAN.md`, `BACKLOG.md`, `RISKS.md`,
`docs/components/uqudo-sdk.md`.

## Commit

```
$ git log --oneline -1
cc8384d S5-09 + S5-10: the real Uqudo RS256/JWKS adapter, and Stage 9's review-image endpoint

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `main` (`b522bd8..cc8384d`), per CLAUDE.md’s straight-to-main norm — no branch.
