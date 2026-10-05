# Customer-session authentication for `/api/v1/**` — research for a product-owner ruling

**Prepared:** 2026-09-13 (S8-26) · **Status:** research only. Nothing implemented, nothing decided.
**Governing:** R-051 (RISKS.md:56, 🔴 Live) · AD-002d remainder (PROJECT_PLAN.md:660-700) ·
AD-002e (PROJECT_PLAN.md:433) · AD-008 (PROJECT_PLAN.md:437) · BL-041 (BACKLOG.md:57)
**Prior art:** `docs/sessions/2026-09-02-research-ad-002e-auth.md` settled the BACK-OFFICE half.
This report covers only the customer catch-all and does not reopen it.

**Why this exists.** R-051 cannot be built: PROJECT_PLAN.md:663-667 names it as an unresolved part
of AD-002d, and CLAUDE.md forbids settling an open AD row in passing. So this session produced the
decision material instead of code. **The product owner rules; this report only recommends.**

**Independently verified before filing.** `@agent-researcher` produced this; the session lead then
re-checked the load-bearing claims against source rather than accepting them: the spike controller's
`@Profile` gate (UqudoSpikeController.java:37), `nimbus-jose-jwt` 9.37.3 as a direct dependency with
the quoted rationale (pom.xml:108-123), `reviewImage`'s missing profile-status check
(IdentityScanService.java:1404-1409), `dioProvider` having no headers and no interceptors
(dio_provider.dart:6-18), the verbatim idempotency comment (journey_api.dart:31-32), and the
supersede firing in the same call that returns the profile id (ContactChannelsService.java:291-292
against ContactChannelsController.java:84). All six held.

---

## 1. Question

> How should customer-session authentication work on this system's `/api/v1/**` customer surface,
> so that R-051 can be fixed?

**The reading taken.** R-051's done-criterion is *"an image request without a valid customer session
for that profile is refused"* (docs/road-to-production.md:221). So: what credential does the app
hold, where does it come from, what does it bind to, how long does it live, and what breaks when it
is enforced across the ten customer prefixes.

**Readings deliberately not pursued**, each needing its own ask: whether the image endpoint should
exist at all after submission (§3.3 notes the finding, does not evaluate removal); rate limiting as
an alternative control (BL-007; RISKS.md:49 already records it unaddressed); and any change to
AD-008's supersede trigger (§3.4 — a credential would hand AD-008 the device signal it says it
lacks, which is an AD-008 change and is flagged rather than proposed).

---

## 2. Answer, up front

**Option 1: an opaque, high-entropy random token**, stored server-side as a hash in a new
`app.customer_session` table, minted when a profile's first phone channel reaches `verified` at
`POST /api/v1/otp/verify`, carried as `Authorization: Bearer` on every `/api/v1/**` call, validated
by one `OncePerRequestFilter` on chain 2 — **bound to the profile only, never to a device**,
**non-rotating**, revoked on terminal status and on a Stage 1b re-entry that mints a replacement.
(Not on any later OTP verification — that phrasing was in an earlier draft and would have 401'd every
customer who verified a second channel. See §3.5.)

Three things make this the answer rather than a JWT or a servlet session:

1. Stage 13's governing rule is *"the backend alone decides whether the session is still open"*
   (customer.md:1218). A stateless JWT moves that decision into a signed claim the backend cannot
   retract, contradicting a rule the journey states twice. An opaque DB-backed token **is** that
   rule expressed as a mechanism.
2. The credential's correct home on the device is **the existing sqlite3mc-encrypted
   `session.sqlite`, beside `LocalProgress.profileId`** (session_database.dart:75) — not a second
   `flutter_secure_storage` entry. That makes the S5-03 failure mode already-solved rather than
   newly-introduced: a keystore reset already collapses to "delete the database, start fresh"
   (session_encryption.dart:47-59), landing the customer at Stage 1a with full OTP re-verification,
   which is **exactly** the journey's documented no-local-state case (customer.md:1241-1246). The
   credential degrades into behaviour the journey already specifies, with no new code path.
3. The blast radius is bounded and stageable, and the operator chain's machinery does not transfer
   (§3.7).

**And the question the brief demanded a definitive answer to: no, a customer session does NOT fix
BL-041.** That attack lives structurally in the pre-credential window and survives untouched. §3.3.

---

## 3. Evidence

### 3.0 The brief's key reading — confirmed, with one sharpening that matters

- Ordinary resume: *"No re-authentication, no review, no rescan. Device continuity is itself the
  evidence; the session was authenticated when it began."* [customer.md:1231-1232]
- Lost device: *"No local state — reinstall, new device, cleared storage — entry at stage 1a, full
  OTP re-verification"* [customer.md:1241-1242]
- *"The backend alone decides whether the session is still open. Every resume begins by asking."*
  [customer.md:1218-1219]

The mint point and the loss path are written down. The journey supports the model.

**The sharpening.** The journey is explicit that OTP is *not* an identity proof:

> *"OTP verification at 1b does **not** prove the person resuming is the person who started. The
> code is sent to a phone number the customer types in at that moment, so verification proves
> control of a handset and nothing more."* [customer.md:154-157]

AD-008 restates it — an in-journey OTP was considered as an alternative proof and rejected, because
*"an OTP proves control of a channel the current person just supplied, not that they are the account
holder"* [PROJECT_PLAN.md:437]. PROJECT_PLAN.md:275 says the same at system level: *"The system does
not establish that the document belongs to the account holder … This is the intended division of
responsibility, not a gap."*

**Consequence for the ruling.** A credential minted at OTP verification is a **session-continuity
credential, not an authorisation to act on an account.** It stops *lateral* access — one party
reading another's profile by holding a UUID — and does nothing about someone who knows an account
number and verifies an OTP on their own handset. That second case is an accepted, documented design
position compensated by AD-008's forced rescan. Rule on this knowing it.

### 3.1 Ground facts, verified

| Claim | Verdict | Evidence |
|---|---|---|
| Chain 2 is `securityMatcher("/api/v1/**")` → `permitAll`, CSRF disabled, STATELESS, deliberately not enumerated | **CONFIRMED** | SecurityConfiguration.java:205-212; rationale at :196-201 |
| Ten customer prefixes | **CONFIRMED** | `account-check` (AccountCheckController.java:18), `contact-channels` (:20), `otp` (OtpVerificationController.java:19), `data-entry` (:20), `identity-scan` (:40), `liveness` (:34), `signature` (:22), `salary-certificate` (:18), `submission` (:24), `reference` (:34) |
| Does the `spike/uqudo` controller ship? | **PROFILE-GATED, and separately key-gated** | UqudoSpikeController.java:36-38: no bean unless `uqudo-spike` is active. It carries **ten** endpoints (:65, 88, 109, 162, 181, 228, 279, 321, 330, 363) including `POST /enrolment`, `POST /face-result`, `DELETE /purge/{jti}`. **Corrected at review:** they would NOT sit on bare `permitAll` even if the profile were on — `UqudoSpikeKeyFilter` carries the same `@Profile` and 403s every `/api/v1/spike/**` request without a matching `X-Spike-Key`, for exactly this reason (:15-17: *"sits in the permitAll customer chain, so anyone on the LAN could otherwise mint tenant tokens or purge sessions"*). [UNVERIFIED] the deployed `SPRING_PROFILES_ACTIVE` and whether a spike key is configured there. |
| No customer credential exists | **CONFIRMED, with a correction of terms** | They are not budgets either. `pending_scan_session_id`/`pending_face_session_id` are **Uqudo JWS binding values** — written at token issuance, compared against the JWS's own claims at submission, closing a finding that *"an attacker holding any valid JWS could post it against their own profile"* (V0040:4-5, with the binding values themselves at :10-15; V0044:12). The budget is separate (V0039, V0043, V0065). Every `CREATE TABLE` across V0001–V0066 was reviewed: the only identity tables are `app.operator_role`/`app.operator_user` (V0057). |
| Mobile has `flutter_secure_storage: 11.0.0` for the local DB key | **CONFIRMED** | session_key_store_native.dart:17-29, one key `session_db_encryption_key` |
| Where are Dio headers set? | **CONFIRMED — one shared `Dio`, no header layer at all** | dio_provider.dart:6-18: a single `Provider<Dio>` with `baseUrl` and two timeouts, **no `headers:`, no interceptors**. All seven API clients take that instance. |

**What "send a credential on every call" actually costs on mobile: one interceptor, one file.**
Because every client shares `dioProvider`, an `InterceptorsWrapper` there covers all ten prefixes at
once. The three call sites passing their own `Options` (dio_reference_api.dart:20-21, :36;
dio_identity_scan_api.dart:121) are unaffected: Dio **merges** `Options` into base headers rather
than replacing them. Verified at review against the pinned version's own source rather than its
documentation — dio 5.11.0 (pubspec.lock), `options.dart:323-326`:
`final headers = caseInsensitiveKeyMap(baseOpt.headers); if (this.headers != null) headers.addAll(this.headers!);`

The real cost is **not** the header. It is that the credential must be read from the encrypted
database before the first request of every launch, putting an `async` dependency in front of a
currently-synchronous `Provider<Dio>`. That is a Riverpod wiring change, not an HTTP change.

### 3.2 The pre-credential window — precisely enumerated

These **must** stay open, because they execute before any credential can exist:

| Endpoint | Why | Evidence |
|---|---|---|
| `POST /api/v1/account-check` | Stage 1a, before a profile exists. **And it is the resume probe on every launch that HAS local state** — corrected at review: `launchDecision()` returns `FreshStart` with no backend call at all when `LocalProgress` is null (entry_repository.dart:445-451), so it is not literally every start. On every resuming start it is the only carrier of the *blocked / terminal / proceed* answer the "every resume begins by asking" rule depends on. | AccountCheckController.java:49-61; entry_repository.dart:423-426, 440-485 |
| `POST /api/v1/contact-channels` | Stage 1b. **This call creates the profile and its id.** Nothing can bind to a profile this call has not yet made. | ContactChannelsController.java:50-85; ContactChannelsService.java:205-206, 327-344 |
| `POST /api/v1/otp/verify` | Stage 2 — the mint point itself. | OtpVerificationController.java:43-61; `markVerified` at OtpVerificationService.java:165 |
| `POST /api/v1/otp/resend` | Stage 2, before any channel is verified by definition. Also carries `correctedEmailAddress`. | OtpVerificationController.java:72-93 |

| `GET /api/v1/reference/manifest` and `GET /api/v1/reference/lists/{code}/{version}` | **Corrected at review — these are pre-credential by NECESSITY, not by judgement.** An earlier draft of this report called them a judgement call on the stated ground that they are fetched at the Stage 2→3 boundary. That is wrong: the **branch list is Stage 1a's picker source**, fetched on the account-entry screen before a profile exists. | account_entry_screen.dart:134-135 → entry_providers.dart:21-28 (`branchCatalogInitProvider` → `syncCatalog()`) |

**Six endpoints across four prefixes.** Everything else — `data-entry` (5), `identity-scan` (9),
`liveness` (3), `signature` (1), `salary-certificate` (1), `submission` (2) — runs strictly after
Stage 2 and can require a credential.

**What stays exposed even with the recommendation fully built.** Anyone submitting a valid account
number to `POST /contact-channels` still learns that profile's UUID (it is the response field,
ContactChannelsController.java:84), still overwrites its contact details, branch and channel states
(ContactChannelsService.java:312-323), and still triggers AD-008's supersede. Account-validity
enumeration stays open through `POST /account-check` — customer.md:1331 records this as deliberate:
*"Enumeration is an accepted non-concern."*

### 3.3 BL-041 — the definitive answer

**A customer session does not fix BL-041. It lives in the pre-credential window and survives.**

1. The supersede fires inside `POST /api/v1/contact-channels`, on the re-entry branch, as
   `deviceLessReentrySuperseder.supersedeInheritedIdentity(profileId, now)`
   [ContactChannelsService.java:291-292].
2. That endpoint **is** the pre-credential window — the call that creates the profile and issues the
   OTP codes (:327-344). No credential can precede it.
3. The trigger is journey position, not a device signal, precisely *because the backend has no
   device signal to use*: *"`ContactChannelsRequest` is six fields on an unauthenticated endpoint,
   R-051"*, and the supersede's own `WHERE profile_id = ? AND state = 'active'` **is** the trigger
   [BACKLOG.md:57].
4. The legitimate customer whose device died has, by construction, **no credential** — that is the
   entire reason the path exists. "Require a credential on the re-entry branch" would break the
   supported case while blocking nothing an attacker cannot route around by presenting as a fresh
   entry.
5. R-051 already rejects the two obvious patches: *"Deliberately NOT mitigated with a rate limit or
   a client-supplied flag here: a flag on an unauthenticated endpoint is attacker-controlled"*
   [RISKS.md:56].

**Net: BL-041's residual is exactly as open after this work as before.** (The BL-041 row itself is
CLOSED — it was built as AD-008 specifies, BACKLOG.md:57. What survives is the residual that closure
recorded, and its home is R-051.) It needs rate limiting
(BL-007) or an account-holder proof this system does not possess. **It must not be reported as
covered.**

**One genuinely good piece of news, found while tracing it.** The supersede fires *in the same call*
that returns the profile id. So "learn a UUID at Stage 1b, then fetch images" is already
self-defeating: by the time the caller holds the UUID the cycle is `superseded`, and `reviewImage`
reads through `activeCycleArtifact` [IdentityScanService.java:1404-1409], which no longer matches.
R-051's live exposure therefore depends on the UUID leaking by some **other** route — a shared
device, a proxy, a log, an over-the-shoulder read, or the terminal-profile case below. That narrows
R-051's practical severity. It does not close it: a terminal profile neither supersedes nor reveals,
and its images stay readable forever to anyone already holding its id.

### 3.4 Binding — profile, not device

- AD-008 makes device-less re-entry a **first-class supported path**; the answer chosen to the
  new-device threat was rescan, not device pinning [PROJECT_PLAN.md:437].
- BL-041 records flatly that the backend **receives no device signal** at entry [BACKLOG.md:57].
- To bind to a device the app would have to *supply* a device identifier on an unauthenticated
  endpoint — which R-051 itself names as the wrong shape.
- The device is bound **implicitly and for free**: the credential lives in a sqlite3mc-encrypted
  file whose key never leaves the Android Keystore (R-025; session_key_store_native.dart:11-16). It
  cannot be copied off the handset without defeating the keystore.

**What AD-008 forbids, plainly:** any design in which *holding the previous device* is required to
continue, and any inheritance of identity artifacts across a device change. A profile-bound
credential violates neither; a device-bound one violates the first.

**Flagged, not decided.** Issuing a credential would, as a by-product, hand AD-008 the device signal
it says it lacks — a re-entry presenting no valid credential is provably device-less, which would
let the supersede trigger become explicit rather than inferred from journey position. **That is an
AD-008 change and is not proposed here.** Listed so the option is known and so a later session does
not "improve" the trigger in passing.

### 3.5 Lifetime and revocation

| Stage 13 case | Today | With the recommendation |
|---|---|---|
| Same app, local state, backend reachable (:1230-1234) | `launchDecision()` → `POST /account-check` → `_resumeOnline` [entry_repository.dart:440-465] | Unchanged. Credential sits beside `LocalProgress`; the launch probe stays credential-free. **No re-authentication, as the journey requires.** |
| Same app, local state, backend unreachable (:1236-1239) | `BackendUnreachableException` → `_resumeOffline` [:459-460] | Unchanged — no server call, no credential check. |
| No local state (:1241-1246) | Stage 1a, full re-verification | Credential died with the database; re-minted at OTP verify. **The old one must be revoked then**, or a stolen handset's token outlives the reinstall meant to replace it. |
| Returning after a block (:1248-1249) | `blocked` → `LaunchBlocked`; local state deliberately **not** cleared [:482-484] | Credential must outlive the longest block. The longest is **24 hours** (customer.md:1314). A TTL under 24h turns "wait and come back" into a forced re-OTP — a failure the journey does not describe. |
| Returning to a completed / submitted / approved / rejected profile (:1251-1256) | `terminal` → `_clearSession()` [:466-476] | Local credential destroyed with the session. **Server-side, revoke on every transition into a terminal status**, or a token outlives its journey. |

**Recommended lifetime: idle expiry tied to the profile's own activity clock — 30 days, matching
customer.md:1323's abandonment threshold — with no absolute cap.** The journey already has exactly
one clock bounding how long a session may live; inventing a second, shorter one creates a failure
class the journey does not describe and the app has no screen for.

**Revocation triggers, minimum set:** (a) transition into any terminal status; (b) **a Stage 1b
re-entry that mints a NEW credential for the profile** — revoking every prior one for it; (c) 30 days
idle; (d) the AD-008 supersede, if belt and braces is wanted.

**A contradiction in an earlier draft of this report, corrected at review and worth keeping visible
because it would have shipped as a journey-breaking bug.** Trigger (b) originally read "a fresh OTP
verification for the same profile". But this system sends **three independent OTP codes, one per
channel**, and `POST /api/v1/otp/verify` is called once per channel with the channel in the body
(OtpVerificationController.java:43-61). Paired with a mint condition of "when a phone channel FIRST
reaches verified", that rule would have had the second channel's verification revoke the credential
minted by the first and mint no replacement — 401ing every customer who verifies two channels, for
the rest of their journey. **The mint must therefore be idempotent within a journey:** the first
verified phone channel mints; every later verify on the same profile returns the SAME live
credential; only a Stage 1b re-entry revokes and re-mints.

**Non-rotating, deliberately** — see §3.6.

**A sixth resume case that Stage 13 does not have, and that this recommendation creates.** Found at
review. A customer returning at day 31 has valid `LocalProgress`, gets `proceed` from
`/account-check` (which is credential-free), resumes onto their recorded stage — and then 401s on the
first enforced call. Stage 13 has no case for "local state present, credential gone" and the app has
no screen for it. This report flags the hazard against a short JWT TTL in Option 2 and must flag it
against its own rule too. **Whoever builds this owes a defined behaviour** — the obvious candidate is
to treat it exactly as the no-local-state case (discard and re-OTP), but that discards customer-
entered work the journey promises to keep, so it is a product decision and not an implementation
detail. It is the strongest argument for a long idle window.

### 3.6 Blast radius, staging, tests, idempotency

| Prefix | Endpoints | Disposition |
|---|---|---|
| `account-check` | 1 | Stays open (launch probe) |
| `contact-channels` | 1 | Stays open (creates the profile) |
| `otp` | 2 | Stays open (mint point) |
| `data-entry` | 5 | Enforce |
| `identity-scan` | 9 | Enforce — **R-051's endpoint** |
| `liveness` | 3 | Enforce |
| `signature` | 1 | Enforce |
| `salary-certificate` | 1 (upload only; no read endpoint exists) | Enforce |
| `submission` | 2 | Enforce — `/current` is R-051's second-tier exposure |
| `reference` | 2 | **Stays open — required, not preferred.** The branch list is Stage 1a's picker source (account_entry_screen.dart:134-135 → entry_providers.dart:21-28), so it is fetched before a profile exists and no credential can precede it. Two further reasons it must not be gated even if that changed: R-033 records that a reference-fetch failure is a hard journey block visible only on fresh installs; and **the BACK OFFICE is a second consumer of the same two endpoints** (backoffice/src/api/reference.ts:5-38, the only place branch and rejection-reason labels are fetched), so gating them behind a CUSTOMER credential breaks the operator UI too. Both are non-PII public lists, so there is no confidentiality gain to weigh against any of that. |

**Staging — the shape matters more than the order.** Ship **mint-and-attach first, enforce second**:
(a) the table, (b) the mint at OTP verify, (c) the mobile interceptor and storage, (d) a filter that
validates and attaches a credential when present but does not require one. Then flip enforcement on.
Every deployed handset must hold a credential before any endpoint starts refusing, because **the
hostname is compiled into the APK** (`--dart-define`, app_config.dart:6-9; AD-010). A backend that
starts 401-ing before the credential-carrying APK has shipped **bricks every installed app, with no
server-side fix.**

"Enforce on the image endpoint first" buys less than it looks: `POST /submission/current` and
`POST /identity-scan/registry-review/current` return the names, national number and address R-051's
own text calls *"not new in kind"*. A one-endpoint fix leaves the PII and takes the images.

**A tension to see before ruling.** Enforcing per-prefix means *enumerating* chain 2, and
SecurityConfiguration.java:196-201 records the opposite as deliberate: *"Not enumerated here by
design: a new customer endpoint is unauthenticated by falling under this catch-all, not by being
added to a list that would otherwise go stale."* Enforcement inverts the default — new endpoints
become authenticated by default and the list enumerates the four *exceptions*. That is the safer
direction, but it is still a reversal of a recorded decision and should be recorded as one.

**Test impact — measured, not estimated.** 275 references to the ten prefixes across 29 test classes
(ripgrep over `backend/src/test`).

- **14 classes run the real filter chain** (`@AutoConfigureMockMvc` without `addFilters = false`),
  carrying **147 call sites** — `AccountCheckIntegrationTest` (6), `ContactChannels` (11),
  `DeviceLessReentrySupersession` (16), `OtpVerification` (7), `DataEntry` (10), `IdentityScan`
  (31), `Liveness` (12), `Signature` (7), `SalaryCertificate` (9), `Submission` (23),
  `ReferenceController` (8), `ArtifactStorage` (3), `OperatorImageHeaders` (1), and — added at review
  — **`OperatorAuthenticationIntegrationTest` (3)**. **All would 401 the moment enforcement lands**;
  each needs a helper minting a real credential. That last class matters out of proportion to its
  three sites: one of them is `unauthenticatedCustomerPrefixIsStillReachable`, **a test whose whole
  purpose is to assert the behaviour enforcement removes.** It is not a test to fix; it is a test to
  rewrite deliberately, and it is the closest thing the suite has to a statement of AD-002e's intent.
- **14 classes are unaffected** (`addFilters = false`), carrying the other 127.
- The real gate is `./mvnw verify -Pdb-integration-test`, and it is precisely the integration suite
  that breaks.

**Idempotency — the journey document is wrong about the code.** customer.md:1224-1226 states *"Every
mutating call from the app carries an idempotency key."* **It does not:** journey_api.dart:31-32
(*"Idempotent by construction (no client key — this backend achieves idempotency structurally
throughout)"*), signature_api.dart:15-17, data_entry_repository.dart:41-50.

So there is no idempotency mechanism for a credential to interact with — but there **is** one real
design constraint. If the credential rotated per request, a retry after a dropped response (the
exact case structural idempotency serves) would present a stale credential and be refused with a 401
for a write that actually landed. **Hence: non-rotating.**

**One more in the same family.** `GET /api/v1/identity-scan/image/{kind}?profileId=…` is the **only**
customer endpoint putting a profile id in a request line (IdentityScanController.java:177-179). The
project already recognised and fixed that pattern elsewhere, citing R-051 by name
(mobile/test/core/journey/dio_clients_test.dart:163-175). This endpoint is the exception never
converted. Mitigating today: no access logging is configured anywhere in `infra/aws` [OBSERVED by
absence] — which also means enabling logs later has a PII consequence nobody would think to check.

### 3.7 Would reusing the operator's machinery help?

Chain 1 is `formLogin` with `SessionCreationPolicy.IF_REQUIRED`, `sessionFixation().changeSessionId()`
and `deleteCookies("JSESSIONID")` [SecurityConfiguration.java:114, 170-190].
`server.servlet.session.timeout` is deliberately unset, giving Tomcat's 30-minute default, asserted
by test [:43-48; OperatorAuthenticationIntegrationTest.java:328-331]. **Spring Session is not in
use** — no `spring-session-*` dependency in pom.xml. So the operator session is an in-memory Tomcat
session, exactly as AD-002d says.

**Verdict: reusing it would hurt.** That in-memory session is already the single reason the fleet
cannot scale past one task; extending it to ~100,000 customer journeys turns a scaling constraint
into a correctness one — a restart would log out every in-flight customer, and no journey screen
describes that. It also needs a cookie jar in Dio, drags CSRF back onto a surface that deliberately
disabled it, and reverses `STATELESS`.

**What *does* transfer is the shape, not the mechanism.** `OperatorIdentityFilter extends
OncePerRequestFilter`, added via `addFilterAfter(..., AnonymousAuthenticationFilter.class)`,
re-reading the user row every request so revocation takes effect on a live session
[OperatorIdentityFilter.java:36-66]. Paired with `OperatorIdentityArgumentResolver` it gives
controllers a **server-derived** identity that *"is still never client-supplied"* (:33-34). A
`CustomerSessionFilter` plus a `@CustomerProfile` resolver is the same pattern one chain over — and
would let enforced endpoints stop taking `profileId` from the request body at all, which is the
strongest available version of this fix.

**Dependency facts.** `nimbus-jose-jwt` 9.37.3 is **already a direct compile dependency**
(pom.xml:119-123), used by the Uqudo stub and JWKS verifier — so a JWT option needs no new library.
But pom.xml:110-118 records that `spring-security-oauth2-jose` is **not** on the classpath, so
Spring's `oauth2ResourceServer()` DSL is unavailable without adding
`spring-security-oauth2-resource-server`. That DSL would not help for an opaque token anyway: the
default `SpringOpaqueTokenIntrospector` calls a **remote RFC 7662 introspection endpoint**, and
validating against a local database means writing a custom `OpaqueTokenIntrospector` [DOC: Spring
Security reference, servlet/oauth2/resource-server/opaque-token, read 2026-09-13]. A plain
`OncePerRequestFilter` is less machinery than a custom introspector inside a starter added for it.

---

## 4. Options, consequences, recommendation

### Option 1 — Opaque DB-backed bearer token *(RECOMMENDED)*

`app.customer_session (session_id, profile_id, token_hash, created_at, last_seen_at, revoked_at,
revoked_reason)`. 32 random bytes from `SecureRandom`, base64url, stored as SHA-256 only. Minted in
`OtpVerificationService.verify` when a phone channel first reaches `verified` (:163-165), returned on
`OtpVerifyResponse`. `Authorization: Bearer`. Validated by one `CustomerSessionFilter` on chain 2
which attaches a server-derived profile id; enforced endpoints read that, not the request body.

- ✅ Satisfies customer.md:1218 literally — revocation is one `UPDATE`, effective on the next
  request, exactly as `OperatorIdentityFilter` already does for operators.
- ✅ No new dependency, no new secret, no Spring Session.
- ✅ Scales freely: chain 2 stays `STATELESS`; state is in PostgreSQL, not a task's heap. AD-002d's
  desired-count-1 constraint is untouched.
- ✅ The whole revocation set is expressible as data.
- ⚠️ One indexed DB read per request, **and a `last_seen_at` write if idle expiry is adopted** (a
  throttled write — say, only when the stored value is over an hour old — keeps it off the hot path).
  At ~3 submissions/hour (PROJECT_PLAN.md:179) both are free; the honest cost is a second query, and
  possibly a write, on every already-cheap endpoint.
- ⚠️ A new migration and a new `fru_app` grant.
- ⚠️ ≈147 integration-test call sites need a minting helper.
- ❌ Does not fix BL-041, the pre-credential window, or R-026.

### Option 2 — Signed JWT, stateless

HS256/RS256 over `{sub, iat, exp}` using the already-present `nimbus-jose-jwt`. No table.

- ✅ Zero per-request DB cost, zero new schema, library already present.
- ❌ **Directly contradicts customer.md:1218.** A minted JWT is valid until `exp` whatever the
  backend decides; terminal status, supersede and revocation become "true in the database, false on
  the wire". Closing that needs a denylist — a table, i.e. Option 1 with extra steps and a window of
  wrongness.
- ❌ Short-TTL-plus-refresh reintroduces rotation, colliding with structural idempotency on exactly
  the retry path idempotency exists for (§3.6).
- ❌ A new signing key to provision, rotate and hold — a secret this system does not have.
- ❌ An `exp` inside the 24-hour block window forces a re-OTP the journey does not describe.

### Option 3 — Extend the operator's session mechanism to customers

- ✅ Least new code.
- ❌ In-memory Tomcat sessions are already the only reason the fleet is pinned to one task. A deploy
  or crash would silently end every in-flight customer session, with no screen for it.
- ❌ Reverses `STATELESS`, drags CSRF back onto a surface that disabled it deliberately.
- ❌ Needs a cookie jar in Dio — more mobile plumbing than a header, not less.
- ❌ Tomcat's 30-minute idle default is wrong by two orders of magnitude for a journey with a
  24-hour block in it, and :43-48 warns specifically against re-tuning it.

### Option 4 — No session; scope a capability to the image endpoint only

Stage 9's payload returns short-lived HMAC-signed per-artifact URLs; the image endpoint accepts only
those.

- ✅ Smallest diff by far; one controller, one mobile client, no test churn outside `identity-scan`.
- ✅ Closes R-051's headline without reopening chain 2's non-enumeration note.
- ❌ Leaves `/submission/current` and `/identity-scan/registry-review/current` on a UUID-only
  surface.
- ❌ Does not satisfy road-to-production.md:221's done-criterion, phrased as *a valid customer
  session for that profile*.
- ❌ The capability is minted by an endpoint that is itself UUID-only, so it inherits its parent's
  weakness. It raises cost, not category.
- ❌ Introduces a second one-off auth concept — precisely what R-051 says S5-10 declined to do.

### Recommendation

**Option 1**, shipped in **two commits: mint-and-attach first, enforce second**, for the APK
lifecycle reason in §3.6.

**Conditions under which it flips:**

1. **→ Option 2** if the bank mandates edge verification (an API Gateway JWT authoriser, or a WAF
   rule that must read the credential). An opaque token is opaque to the edge by definition. The
   flip then costs the denylist table — most of Option 1 anyway, so the flip is real but partial.
2. **→ Option 2** if the per-request DB read is ever *measured* to matter. At PROJECT_PLAN.md:179's
   sizing it cannot be; re-measure if the campaign assumption moves by two orders of magnitude.
3. **→ Option 3** only if the customer client ever becomes a browser. It will not, under AD-001.
4. **→ Option 4** if the product owner rules that nothing outside `identity-scan` may change before
   V1. Then take it knowingly and **keep R-051 🔴 with its scope narrowed in writing** to the two
   `current` endpoints, rather than letting a partial fix read as a closure.
5. **→ reconsider wholesale** if OQ-001 (the regulatory regime, still unanswered at
   PROJECT_PLAN.md:157-159) mandates an authentication standard.

---

## 5. What this does NOT fix — required section

1. **The pre-credential window.** Four endpoints stay fully open. An unauthenticated caller keeps
   account-number enumeration (accepted, customer.md:1331), overwriting a real profile's contact
   details, branch and channel states (ContactChannelsService.java:312-323), and unbounded billed
   resend cycling (R-044's still-open half).
2. **BL-041's residual in full.** §3.3. Unchanged. The row is closed; the exposure it recorded is not.
3. **The secure-storage failure.** A Keystore reset destroys the session database and the credential
   with it (session_encryption.dart:47-59). The customer lands at Stage 1a and re-OTPs — the
   *correct* degradation, but a real cost paid by a real customer, and R-025 records that
   `flutter_secure_storage`'s actual Keystore behaviour is exercised **only through a fake** in
   automated tests. No device has confirmed it.
4. **The account-holder gap.** §3.0. Someone who knows an account number and has any phone can mint
   a *legitimate* session on another person's profile. AD-008's forced rescan is the whole
   compensating control.
5. **R-026** — encryption at rest. Still 🔴, still AD-002d's.
6. **Rate limiting.** BL-007 remains unbuilt.
7. **Profile ids in request lines and logs.** §3.6.
8. **The terminal-profile hole.** `reviewImage` checks only for an active cycle, not profile status
   (IdentityScanService.java:1404-1409), so a **submitted or approved** profile's identity images
   stay fetchable indefinitely to anyone holding the id — and that profile can never be superseded,
   because `POST /contact-channels` refuses it with a `409` (ContactChannelsController.java:70-71).
   Enforcing a session helps only if a terminal profile's credential is revoked **and** the endpoint
   refuses it. The implementing task must not drop that detail.

---

## 6. What could not be determined

- **Whether deployed `SPRING_PROFILES_ACTIVE` excludes `uqudo-spike`, and whether a spike key is
  configured there.** Both gates are real in source (`@Profile` plus `UqudoSpikeKeyFilter`); the
  deployed values are unchecked.
- **Whether any APK exists in the field.** This decides whether "mint first, enforce second" is a
  precaution or a hard requirement. AD-010 says the hostname is not even chosen, suggesting no field
  deployment — unconfirmed.
- **Whether any support process fetches a customer image through the *customer* endpoint.**
  `operator.web.ProfileImageController` exists for the operator path, so probably not; not every
  caller was traced.
- **What OQ-001 will require.** The one input that could invalidate the recommendation wholesale.
- **Exact test-run cost.** Call sites were counted statically; the suite was not run. The number of
  *tests* needing a helper is lower than 147 and higher than 13.

---

## 7. Risks of acting on this

| If the recommendation is wrong | What breaks | Cost to reverse |
|---|---|---|
| The per-request DB read matters at scale | Latency on every customer call | Low — the filter is one class; swapping validation behind the same interface is contained. |
| Enforcement ships before an APK that mints | **Every installed handset bricked**, unrecoverable from the server | Catastrophic and irreversible without a store release. This is why the two-commit order is not a preference. |
| The credential is bound to a device after all | AD-008's supported device-less path breaks; customers who lose a phone cannot continue | Medium in code, but found in UAT rather than review, because the happy path works. |
| `reference` is put behind the credential | R-033's fresh-install hard block gains a second cause | Low to reverse, high to *notice* — existing handsets are unaffected and only new installs fail. |
| Session auth is reported as closing BL-041's residual | A live denial-of-progress exposure recorded as fixed | Cheap in code, expensive in record. **The single most likely misreading of this report**, and the reason §3.3 is phrased as it is. (Note the row itself is CLOSED — BACKLOG.md:57, "built as AD-008 specifies". What survives is its recorded residual, which lives on R-051.) |

---

## 8. Documentation owed

**No `docs/components/` card covers the customer surface.** `backoffice-auth.md` covers AD-002e and
mentions chain 2 only in passing. Two pieces are owed once a ruling exists — drafted in full in this
session's report (`docs/sessions/2026-09-13-s8-26-r051-decision-material.md`) rather than filed
here, because a card describing an undecided mechanism as if it existed is the frozen-comment defect
one level up:

- a correction to `docs/components/backoffice-auth.md` recording that chain 2's `permitAll` is an
  open question, not a settled state, and naming the four pre-credential endpoints;
- a draft `docs/components/customer-session-auth.md` carrying `Status: NOT DECIDED`.

---

## 9. Noticed in passing

Recorded, not pursued.

1. **`GET /api/v1/identity-scan/image/{kind}` is the only customer endpoint putting a profile id in
   a request line**, and the project already fixed that pattern elsewhere citing R-051
   (dio_clients_test.dart:163-175). Candidate for BACKLOG.
2. **No access logging is configured anywhere in `infra/aws`** — which limits (1) today, and means
   enabling logs later has a PII consequence nobody would think to check.
3. **customer.md:1224-1226 is factually wrong about the code.** It says every mutating call carries
   an idempotency key; three source comments say there is none and idempotency is structural.
   Cheap one-line journey correction.
4. **`app.profile.resume_stage` is still a column no code writes** (BL, closed as "derived"). Not a
   defect; noted because a reader of Stage 13 would expect it.
5. **R-018's four-eyes mitigation is dead** since AD-013 (2026-09-13), and the row says so inline. It
   is 🔴 Live with a struck-through mitigation.
