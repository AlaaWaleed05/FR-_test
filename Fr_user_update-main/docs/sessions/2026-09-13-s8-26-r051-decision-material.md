# S8-26 — R-051: the decision material, not the fix

**Date:** 2026-09-13 · **Tier:** none — research and plan files only
**Outcome:** R-051 is researched and **still open**. AD-002d is still open. Nothing was decided.

No code in any tier changed, so no test or analyze gate applies — the S8-18 and S8-25 precedent.

---

## Why this session is research and not a build

The product owner picked R-051 as the next thing to build. **It cannot be built.**

`PROJECT_PLAN.md:663-667` names R-051 by name as an unresolved part of AD-002d, and CLAUDE.md says
an architecture decision listed as open is not to be settled in passing — *"If work requires one,
stop and say so."* So the session stopped, said so, and produced what a ruling needs instead. Three
things made it unambiguous rather than a judgement call:

1. **It reverses a closed decision.** AD-002e settled the `/api/v1/**` catch-all as `permitAll`.
2. **No customer credential exists anywhere.** Every `CREATE TABLE` across V0001–V0066 was reviewed;
   the only identity tables are `app.operator_role` / `app.operator_user` (V0057).
3. **The image endpoint is not the whole exposure.** `POST /submission/current` and
   `POST /identity-scan/registry-review/current` return names, national number and address on the
   same UUID-only surface. A one-endpoint fix would look finished and not be.

---

## What was produced

`docs/sessions/2026-09-13-research-r051-customer-session-auth.md` — four options with consequences,
one recommendation, and the conditions under which each rival wins. `@agent-researcher` ran first,
per CLAUDE.md.

**The recommendation, in one line:** an opaque DB-backed bearer token, minted at OTP verification,
profile-bound, non-rotating, validated by one `OncePerRequestFilter` on chain 2 — shipped
mint-and-attach first, enforce second.

**Six load-bearing claims were re-verified against source rather than accepted from the agent:**
the spike controller's `@Profile("uqudo-spike")` gate (UqudoSpikeController.java:37);
`nimbus-jose-jwt` 9.37.3 as a direct dependency with the quoted rationale (pom.xml:108-123);
`reviewImage`'s missing profile-status check (IdentityScanService.java:1404-1409); `dioProvider`
having no headers and no interceptors (dio_provider.dart:6-18); the verbatim idempotency comment
(journey_api.dart:31-32); and the supersede firing in the same call that returns the profile id
(ContactChannelsService.java:291-292 against ContactChannelsController.java:84). All six held.

---

## Five findings that change the shape of the problem

None of these are in R-051's own text, and two of them change how the row should be read.

**1. The journey already specifies the model; the backend never enforced it.** `customer.md:1218`
says *"The backend alone decides whether the session is still open."* That single sentence is why a
stateless JWT is the wrong answer — a minted JWT is valid until `exp` whatever the database says.
The mint point (OTP verification) and the loss path (no local state → full re-OTP) are both written
down at `customer.md:1231-1246`. This is implementing a session the journey assumes, not inventing
one.

**2. R-051 is narrower than filed.** The AD-008 supersede fires in the *same call* that returns the
profile id, and `reviewImage` reads through `activeCycleArtifact` — so "learn a UUID at Stage 1b,
then fetch the images" is already self-defeating, because the cycle is `superseded` by the time the
caller holds the id. The live exposure depends on the id leaking by some other route: a shared
device, a proxy, a log, an over-the-shoulder read.

**3. And R-051 has a hole no session fix reaches unless it is designed for.** `reviewImage` checks
for an active cycle and **not** for profile status (IdentityScanService.java:1404-1409). So a
**submitted or approved** profile's identity images stay fetchable indefinitely to anyone holding
its id — and that profile can never be superseded, because `POST /contact-channels` refuses it with
a 409. A session fix helps only if a terminal profile's credential is revoked *and* the endpoint
refuses it. Recorded on R-051 so the implementing task cannot drop it.

**4. BL-041's residual survives untouched, and this is the most likely misreading of the whole
report.** The supersede lives inside `POST /contact-channels`, which is structurally in the pre-credential window
— it is the call that *creates* the profile. The legitimate device-less customer has no credential
by construction, so requiring one there breaks the supported path and blocks nothing an attacker
cannot route around. Anyone with an account number can still force a real customer to rescan,
repeatedly, after this work as before it. **It must not be reported as covered.** (The BL-041 row
itself is closed — it was built as AD-008 specifies. What survives is the residual that closure
recorded, whose home is R-051.)

**5. Ordering is safety-critical, not a preference.** The API hostname is compiled into the APK via
`--dart-define` (app_config.dart:6-9, AD-010). A backend that starts 401-ing before a
credential-minting APK is in customers' hands **bricks every installed handset, with no server-side
remedy.** Mint-and-attach must ship first and be in the field before enforcement flips.

---

## Measured rather than estimated

- **The pre-credential window is six endpoints across four prefixes:** `POST /account-check` (also
  the resume probe on every launch that has local state), `POST /contact-channels` (creates the
  profile id), `POST /otp/verify` (the mint point), `POST /otp/resend`, and **both `reference`
  reads** — the branch list is Stage 1a's picker source, fetched before a profile exists. The
  first draft had four and called `reference` a judgement call; the review corrected it.
  Everything else can require a credential.
- **147 customer-endpoint call sites across 14 integration classes run the real filter chain** and
  would 401 the moment enforcement lands; 127 across 14 classes use `addFilters = false` and are
  unaffected. One of the 147 is `unauthenticatedCustomerPrefixIsStillReachable` — a test whose
  whole purpose is to assert the behaviour enforcement removes. Not a test to fix; a test to
  rewrite deliberately. The real gate is `./mvnw verify -Pdb-integration-test`, and it is precisely the
  integration suite that breaks.
- **Mobile cost is one interceptor in one file**, because all seven API clients share a single
  `Provider<Dio>` that currently sets no headers at all. The real cost is not the header: it is
  putting an `async` credential read in front of a synchronous provider.

## A sharpening the product owner should rule knowing

A credential minted at OTP verification is a **session-continuity credential, not an authorisation
to act on an account.** `customer.md:154-157` is explicit that OTP *"proves control of a handset and
nothing more"*, and AD-008 rejected in-journey OTP as an account-holder proof for that reason. So
this stops *lateral* access — one party reading another's profile by holding a UUID — and does
nothing about someone who knows an account number and verifies an OTP on their own phone. That is an
accepted, documented position whose compensating control is AD-008's forced rescan.

---

## Filed, not fixed

**BL-137** — `GET /api/v1/identity-scan/image/{kind}?profileId=…` is the only customer endpoint
putting a profile id in a request line, and this project already fixed that pattern elsewhere
*citing R-051 by name* (dio_clients_test.dart:163-175). It is harmless only because no access
logging is configured anywhere in `infra/aws` — so the day someone enables CloudFront or ALB logs,
identity-document request lines carrying profile ids start landing in a bucket, and the person
enabling logging has no reason to suspect it.

**BL-138** — `customer.md:1224-1226` states *"Every mutating call from the app carries an
idempotency key."* No such key exists; three source comments say idempotency is structural instead.
Surfaced because the question "would a rotating credential collide with idempotency?" had to be
answered — it would, which is why the recommendation is non-rotating.

Neither was fixed in passing: one touches a customer endpoint's wire contract and belongs with
R-051's own build, the other edits the journey specification and is its own review.

---

## Documentation owed, drafted here rather than filed

A card describing an undecided mechanism as though it existed is the frozen-comment defect one level
up, so neither of these is written into `docs/components/` until a ruling exists.

### Correction owed to `docs/components/backoffice-auth.md`

To be added after its chain-2 sentence:

> **Chain 2 is an open question, not a settled state — R-051 (🔴 Live).** `permitAll` on this
> catch-all is what makes `GET /api/v1/identity-scan/image/{kind}` serve identity documents to any
> holder of a profile UUID. PROJECT_PLAN.md:663-667 names R-051 as an unresolved part of AD-002d and
> a hard Phase 2 entry gate. Do not cite this section as evidence that the customer surface is
> *meant* to stay unauthenticated.
>
> Six endpoints across four prefixes can never carry a credential, because they run before one
> can exist: `POST /account-check` (also the resume probe on every launch that has local state),
> `POST /contact-channels` (creates the profile id), `POST /otp/verify`, `POST /otp/resend`, and
> both `reference` reads — the branch list is Stage 1a's picker source. Note the back office is a
> second consumer of `reference` (backoffice/src/api/reference.ts), so gating those two behind a
> CUSTOMER credential would break the operator UI as well.

### Draft card — `docs/components/customer-session-auth.md`

To be filed only once ruled on, and never with `Status: built`:

```
Status: NOT DECIDED — research only · Last verified: 2026-09-13
Governing: R-051 · AD-002d · AD-008 · BL-041 · customer.md Stage 13

Current state: no customer credential exists anywhere. pending_scan_session_id (V0040) and
pending_face_session_id (V0044) are Uqudo JWS BINDING values, not credentials and not budgets —
the budget is separate (V0039, V0043, V0065).

Recommended shape (NOT RULED ON): opaque 32-byte token, SHA-256 hash in app.customer_session,
minted at first phone-channel `verified`, Authorization: Bearer, validated by
CustomerSessionFilter modelled on OperatorIdentityFilter, profile-bound (never device-bound —
AD-008), NON-ROTATING (idempotency here is structural, not key-based), 30-day idle expiry to
match customer.md:1323, revoked on terminal status / re-mint / idle / supersede.

Device storage: the existing encrypted session.sqlite beside LocalProgress.profileId — NOT a
second flutter_secure_storage entry. A Keystore reset then degrades through the existing
discard-and-regenerate path straight into the journey's own no-local-state case.

Does not fix: BL-041 · account enumeration (accepted) · resend cycling · R-026 · rate limiting ·
profile ids in the image endpoint's query string · terminal-profile images.
```

---

## The review found four real defects in the report itself

`@agent-reviewer` was run against the research report as a document — is it TRUE — rather than
against code. It sampled the citations and found four things wrong, all corrected before commit.
Recording them because a research report's only value is that its claims hold, and two of these
would have propagated into a build.

| # | Finding | Disposition |
|---|---|---|
| 1 | **The pre-credential window is six endpoints, not four.** The report demoted the two `reference` reads to a "judgement call" on the stated ground that they are fetched at the Stage 2→3 boundary, so a credential would exist. False: the **branch list is Stage 1a's picker source** (account_entry_screen.dart:134-135 → entry_providers.dart:21-28), fetched before a profile exists. The wrong number had already propagated into RISKS, PROJECT_PLAN and this report. | **Fixed in all four places.** `reference` stays open by necessity, not preference. |
| 2 | **The mint and revoke rules contradicted each other on the ordinary path.** Mint was "when a phone channel FIRST reaches verified"; revoke was "a fresh OTP verification for the same profile, revoking all prior credentials". This system sends three independent OTP codes, one per channel, and `/otp/verify` is called once per channel — so verifying a second channel would revoke the first credential and mint no replacement, **401ing every customer who verified two channels for the rest of their journey.** | **Fixed.** The mint is idempotent within a journey: first verify mints, later verifies return the same live credential, only a Stage 1b re-entry revokes and re-mints. This is the finding that most justifies having run the review. |
| 3 | **The spike endpoints are not on bare `permitAll`**, and there are ten of them, not eleven. `UqudoSpikeKeyFilter` carries the same `@Profile` and 403s any `/api/v1/spike/**` request without a matching `X-Spike-Key`, written for exactly the reason the report raised. | **Fixed**, and the [UNVERIFIED] narrowed to the deployed profile value and whether a key is configured. |
| 4 | **One [UNVERIFIED] item was checkable from local disk** — whether Dio merges `Options` into base headers. dio 5.11.0 is pinned in `pubspec.lock` and its source is in the pub cache: `options.dart:323-326` merges. | **Fixed** — verified against the pinned package's own source rather than its documentation, and removed from the unverified list. |

Five further notes were accepted and applied: `/account-check` is not called on *every* launch (a
fresh install with no local state makes no backend call at all); the test-impact split is 14/14
classes and 147/127 sites, not 13/15; the back office is a second consumer of `/reference`;
idle expiry costs a write per request as well as a read; and BL-041 is a CLOSED row whose
*residual* is what survives, which the report had twice called an open vulnerability.

**One gap the review surfaced that is now recorded in the report rather than fixed:** a customer
returning at day 31 has valid local state, gets `proceed` from `/account-check`, resumes onto their
recorded stage, and then 401s on the first enforced call. Stage 13 has no case for "local state
present, credential gone" and the app has no screen for it. Whoever builds this owes a defined
behaviour, and it is a product decision — the obvious answer (treat it as no-local-state, discard
and re-OTP) throws away customer-entered work the journey promises to keep.

---

## Gates

None applies. Nothing in `backend/`, `backoffice/` or `mobile/` was touched — stated explicitly so
it reads as "none applies" rather than "forgotten".

The artifact is the report, and it is verified by being checkable: every codebase claim carries a
`file:line`, everything unconfirmed is marked [UNVERIFIED] (six items, listed in its §6), and the
three "what this does NOT fix" statements the plan required — the pre-credential window, BL-041, and
the secure-storage failure mode — each appear explicitly in its §5 rather than being left for a
later session to discover.

## No real data

No account number, national number, image or customer record appears in the report or in this one.
Nothing was read from any database.

---

## Commit proof

Captured AFTER the push, per CLAUDE.md. Single commit, straight to `main`, no branch.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   6a870df..b670833  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	docs/bank-hosting-specification.md

nothing added to commit but untracked files present (use "git add" to track)
```

`docs/bank-hosting-specification.md` predates this session and is deliberately left untracked.
