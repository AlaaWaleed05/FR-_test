# Open work assessment — mobile and backend (2026-09-04)

Written after R-034 closed (`docs/sessions/2026-09-04-r034-parser-rewrite.md`), in plain
language. It answers three questions: what is still open, in what order it should be done, and
what blocks each item. It also records four outcomes from the product owner's comments the same
day, each with the evidence behind it. **Nothing in this file has been applied to
PROJECT_PLAN.md, RISKS.md, BACKLOG.md or the component cards yet** — the "Decisions to file"
section at the end lists exactly what a follow-up session must change.

## Where things stand

The customer app works from the start of the journey through the data-entry screens. The
backend is complete for the whole journey, with every outside system replaced by a test double.
The back office can sign in, list profiles, view one, approve, reject, and mark a profile
complete by hand.

## Four outcomes from the product owner's comments

### 1. Arabic text for the Uqudo camera screens — keep it, defer it

Question asked: does FIB's web app localise the Uqudo screens, or is English acceptable?

Evidence, read from FIB's shipped production web bundles (`../FIB/Web_Code/assets/OnboardingJourny-*.js`),
not from documentation: the enrolment call passes `texts: language === "en" ? <English set> : <Arabic set>`
to the Uqudo web SDK, and the Arabic set is complete (`startingCamera: "تشغيل الكاميرا..."`,
`validating: "جارٍ التحقق..."`, exit dialog, buttons, and so on). FIB's mobile app, which never
reached production, did not localise. So FIB's proven solution DOES show the Uqudo screens in
Arabic.

Outcome: keep the work, do it after the Phase 1 demo as its own slice. The mobile SDK takes
string resource files rather than a text object (about 176 strings, R-002); FIB's Arabic
vocabulary is a starting point, though its keys differ and cannot be copied as-is. Running the
demo in English first costs nothing later.

### 2. Face mismatch — closed by reasoning, no further test

Constraint: no second person will be available for a non-matching-face test.

Evidence: FIB's production liveness step (`LivenessCheckJourny-*.js`) calls `faceSession` with
`sessionId` and `maxAttempts: 3`, treats `onSuccess` as passed without reading any match value,
and treats `onError` as failed. It sets no threshold. It has run in production on the same Uqudo
tenant. Together with what S1-02 established (the SDK ships a "your face didn't match, try again"
dialog, so the mismatch decision is made inside the SDK), the reading is:

- A success callback means the person matched to Uqudo's own satisfaction.
- A mismatch is retried inside the SDK and, after the attempts run out, arrives on the
  terminated channel — the same channel as a liveness failure.
- Our backend already handles both: a signed result records `match`/`matchLevel`; a terminated
  session is counted and, since R-034, carries Uqudo's partial artifact (BL-028).

What this means: an impostor cannot pass. What is lost is a label — after the attempts run out
the record says "face check failed" and cannot say whether it was a fraudster or a bad camera
moment. The operator sees the failure count either way and judges the profile by hand.

Outcome: close R-016 on this reasoning, with that residual written into the row. Keep BL-028's
partial artifact as the extra evidence. The server-side threshold of 3 stays; it is harmless.

### 3. Civil Registry — reachable, contract confirmed

Endpoint supplied: `https://mb1.sfbank-sd.com:5353/CRSAPI/Services/GetCRSData`, request
`{"NID": "<national number>"}`, response with fifteen fields.

Check against `docs/components/civil-registry.md` (contract observed 2026-08-23): same path, same
request key, and the same fifteen response fields — `FIRST_NAMES`, `PHOTOGRAPH`, `IDENTITY_NUMBER`,
`LAST_NAME`, `GRE_GRA_FATHER_NAME`, `MOT_FATHER_NAME`, `NAME`, `MOT_GRE_GRA_FATHER_NAME`,
`GRAND_FATHER_NAME`, `MOTHER_NAME`, `MOT_GRA_FATHER_NAME`, `ADDRESS`, `BIRTH_DATE`, `GENDER`,
`FATHER_NAME`. Two improvements over the earlier observation: HTTPS on a hostname instead of plain
HTTP on a bare address, and reachable from outside the bank.

Outcome: the "access route" half of AD-002b is answered. Still unknown, and worth one short
spike before the real adapter is written:

- what comes back for an unknown or malformed number, and when the service is down (R-031);
- whether the certificate on port 5353 is trusted by a standard Java runtime;
- whether any authentication is expected (none was observed on either occasion).

### 4. Core banking — an HTTPS JSON call, not an Oracle procedure

Endpoint supplied: `https://mb1.sfbank-sd.com:9191/OMNI_PH3/resources/bankRoutes/CheckAccount`,
request `{"Account": "<account number>"}`, response
`{"Response_Code": "1", "Response_Message": "Account Found"}`.

This changes the plan, not just a task. PROJECT_PLAN.md's Constraints say Oracle via the stored
procedure `ProcessOmniCheckAct` over a database driver; S1-04 and S3-02 were scoped on that.
Consequences:

- No Oracle driver, no server-version question (OQ-006 becomes moot), no database access request.
  S1-04 and S3-02 collapse into one HTTP adapter task behind the existing `CoreBankingClient` port.
- The request carries only the account number. The app, the backend port, the audit record and
  the first customer screen all carry branch **plus** account. Either the branch is not needed, or
  it is embedded in the account string. This needs a yes or no from the bank before the adapter
  is written, because it changes what the customer is asked for.
- The response is a text code plus a message. We assumed numeric 1 active / 2 inactive / -1
  invalid; "Account Found" suggests found-or-not-found semantics. The full list of codes and
  messages is needed. The port's `int` return will most likely become a small result type.
- Same certificate question as the registry (port 9191).

The sample account number in the product owner's message is treated as a live value and is
recorded nowhere — not here, not in a fixture, not in a log (CLAUDE.md hard rule).

Outcome: record as a decision that supersedes the Constraints line; run the researcher step, then
one spike, then the adapter.

## What is left on the customer app

| Item | What it is | Depends on | Blocked by |
|---|---|---|---|
| S5-07 | Identity type, document scan, registry review screens | nothing | nothing — SDN_ID card first scanned at UAT |
| S5-08 | Face check, signature, submit screens; enable the incomplete-session flag and forward the partial JWS (BL-028) | S5-07 | nothing |
| BL-021 (mobile half) | Show "blocked until" at launch | nothing | nothing |
| BL-022 (client half) | Send the captured salary certificate to its endpoint | nothing | nothing |
| R-002 | Arabic strings for the Uqudo screens (~176) | S5-08 | decision above: deferred, not dropped |
| BL-006 / BL-018 | Resume without local data; write `resume_stage` | S5-07, S5-08, a backend read endpoint | nothing |
| BL-014, BL-002 | Arabic-fold golden fixture; screenshot blocking | nothing | nothing |
| S1-08 (mobile) | 90% business-logic coverage tier | nothing (feature dirs now exist) | nothing |
| S1-03 | iOS build | AD-003 | no macOS machine, no iPhone — Phase 2 |
| R-006 | Android 16 behaviour | — | accepted residual, UAT |

## What is left on the backend

| Item | What it is | Depends on | Blocked by |
|---|---|---|---|
| S6-04 | Runner that sends the queued messages | nothing | nothing — needed for any demo of a notification |
| BL-007 | Per-install identifier and rate limiting on four endpoint families | a decision on the identifier | changes the wire contract mobile consumes; decide before S5-08 closes |
| BL-010, BL-011 | Two narrow races | nothing | nothing |
| Real Civil Registry adapter | replaces the stub | outcome 3 spike | error contract, certificate, auth (R-031) |
| Real account-check adapter | replaces the stub | outcome 4 spike, bank answers on branch and codes | Constraints line to be superseded first |
| Real Uqudo verifier | RS256/JWKS against the observed contract | nothing (buildable on FIB's tenant) | production needs our own tenant (OQ-010) |
| BL-027 | Device attestation flags | product decision | tenant has never emitted it |
| S1-08 (backend), R-035, R-028/R-033 | Coverage tier; Layer 3 in the test fixture; operational post-migrate steps | nothing | nothing |
| BL-008 | Account-check error contract | the real adapter | — |
| AD-002d, R-026, R-037 | Hosting; disk encryption; seal export | OQ-001, OQ-002 | bank |
| Messaging providers | SMS, WhatsApp, email | OQ-016 to OQ-022 | bank commercial decision |
| OQ-012 / R-014 | Corrected states and localities | — | bank data |
| BL-019, BL-020, BL-005 | Reference-number format; export columns; Arabic rejection copy | — | bank |
| BL-026, BL-023 | Export UI; admin screens | — | cut from Phase 1 |

## Suggested order

1. S6-04 (backend) in parallel with S5-07 (mobile).
2. S5-08, absorbing BL-021 and BL-022's client halves.
3. Two short spikes, registry and account check, each preceded by the researcher step.
4. File the four decisions above (see next section).
5. Real registry and account-check adapters; then BL-007 across both tiers.
6. R-002 Arabic strings; then BL-006/BL-018; then the hardening items.
7. Everything else waits on the bank; the real Uqudo verifier can fill slack.

## Decisions to file (not yet done)

- RISKS.md R-002: decision "deferred to after the Phase 1 demo, not dropped", with the FIB web
  evidence.
- RISKS.md R-016: close by reasoning, residual stated as above; PROJECT_PLAN.md AD-002a premise
  note updated to match.
- docs/components/civil-registry.md and PROJECT_PLAN.md AD-002b: the HTTPS hostname endpoint,
  access route answered, remaining open items narrowed to error contract / certificate / auth.
- PROJECT_PLAN.md Constraints and docs/components/core-banking.md: core banking is an HTTPS JSON
  endpoint on the bank's middleware; S1-04/S3-02 re-scoped; OQ-006 moot; new open questions on the
  branch field and the response-code list.
- EXECUTION_PLAN.md: re-scope S1-04/S3-02; add the two spikes.
