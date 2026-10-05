# PROJECT_PLAN.md

## Overview

Fr_user_update is a customer data-update solution for a bank. Retail customers use a
mobile app to re-submit and verify their account details (contact info, social status,
home and work address, identity document) with OTP verification, Uqudo document scan
and liveness check, and Civil Registry lookup. A backend orchestrates the journey,
persists one customer record per submission, and calls the bank's core-banking
middleware, Uqudo and the Civil Registry. A back-office web app lets bank operators
navigate the profile database, review submitted profiles and approve or reject them with
a coded reason, print the update form, and export field data. Manual completion was in this
list until AD-022 removed it on 2026-09-16: the only journey that produces a profile is now a
mobile submission. Aggregate dashboard statistics are deferred (BL-001).

Platforms: mobile Android only (iOS dropped, AD-003 cancelled 2026-09-10 and confirmed
2026-09-14 as dropped completely rather than deferred), plus the web back office.
Android minSdkVersion 24, compileSdk 37 (raised from 36 at S5-03, since
`flutter_secure_storage` 11.0.0 requires it), targetSdk 36, ABIs armeabi-v7a and
arm64-v8a. NFC not required.

Plan files, all read at session start (see CLAUDE.md): PROJECT_PLAN.md,
EXECUTION_PLAN.md, BACKLOG.md and RISKS.md.

Journey specification: `docs/journeys/customer.md` (customer, stages 0-13) and
`docs/journeys/operator.md` (bank operators). These supersede the original Arabic journey
concept in the project knowledge base, which described ten screens and predates the bank's
paper form. Field-by-field provenance: `docs/journeys/field-provenance.md`. Reference
data: `docs/reference/`.

## AD-012 — Design_3 brand adoption

`Design_3/` is a handoff specifying two views only, an approved splash ("Splash C —
horizon") and an app header banner. It states navy `#0b1c47`, steel `#5980a6`, Amiri for
Arabic display and Barlow for Latin, radius 0 and a white ground. The shipped app was
`#105097` (measured off the bank's logo master), IBM Plex Sans Arabic, rounded containers
on a tinted `#E7E8EE` canvas (the S1 surface treatment, S8-05), with an already-approved
animated splash. The handoff covers none of the other eighteen screens and says so.

Ruling: adopt Design_3. Eight forks that decision did not answer were put to the product
owner and ruled in session; they are recorded here because they exist nowhere else, and
because a palette and a display font with no recorded reason is design decided by
momentum.

| Fork | Ruling | Why |
|---|---|---|
| 1. What navy replaces | Reseed the whole ramp from `#0b1c47`, not brand surfaces only | One coherent identity rather than two blues a shade apart meeting at the banner edge. Cost accepted: every derived tone moves, so the WCAG 1.4.11 measurements taken on the old ramp must be re-taken, and the Android launch window and its v31 splash-API variant move with it. |
| 2. How far Amiri goes | Display only; IBM Plex Sans Arabic keeps labels, fields and numbers | Amiri is a Naskh serif with no matched Latin, and nearly every screen sets an Arabic label beside Latin digits: account numbers, `SFB-` reference numbers, scanned names. Amiri carries the splash name and screen headings, where it is doing display work. |
| 3. Square corners and white paper | Keep the tinted canvas and rounded containers below the banner | The handoff's radius-0 white ground is stated for a splash and a header, neither of which has form fields. The tinted canvas was built at S8-05 to fix "no visual hierarchy anywhere", five walk comments with one cause, and squaring it would return twenty screens to the state that treatment replaced. Known seam: the banner is square-edged and the content below it is rounded, and they meet at the dune curve. |
| 4. How much of the splash moves | Entrances only (trek and rise), then settle | Three infinite animations keep the GPU awake for the whole splash on the Huawei Y5 2019 (API 28) the walk runs on. The handoff itself specifies the settled end state under `prefers-reduced-motion`, so this is a documented variant rather than a departure. |
| 5. Which mark the launcher carries | The pearl | Reverses the same day's "leave it": the launcher showed the octagonal emblem on the new navy ground while the splash showed the pearl, so the mark visibly swapped between the home screen and the app. Applied to the legacy raster, the adaptive foreground and the Android 12+ native splash icon, so all three surfaces show one mark from one source. `generate_brand_assets.py` was deleted rather than left in place, because two scripts each claiming to produce the app's identity from a different source is how the launcher came to disagree with the splash. `generate_design3_assets.py` now owns every mark. |
| 6. Splash hold | Minimum 4000 ms | Client decision: the splash is the brand moment and they want it held. This inverts D7.3, which said the screen fills a wait that already exists and must never manufacture one; it now manufactures one deliberately. The mechanism is the handoff's own, at the client's value. |
| 7. Progress strip segments | Thirteen, not the handoff's twelve | The handoff's caption says "twelve segments covering 1a, 1b, 2 and stages 3-12", but that list is thirteen screens, and its drawing has twelve bars only because twelve were drawn. Ruled: one segment per screen the customer actually sees, labelled «الخطوة ن من ١٣». `/final-stages` carries no segment, the handoff's own stated exclusion, and the terminal screens are outcomes rather than steps and carry no strip. The enum in `core/widgets/journey_progress.dart` is the list, and a test binds each step to the screen that declares it. |
| 8. Where the progress strip sits | Below the banner, on the page, not inside it | `BrandBanner`'s bottom 28 dp is the dune curve, so a strip inside the bar needs the bar to grow and the curve to move, re-opening a layout S8-16 spent a session tuning across nineteen screens at once. On the page it reads as the top of the screen's own content and the bar is untouched. |

Decided without asking, and recorded as such: the banner's sign-out button is dropped,
because this app has no customer authentication and no session to end, so the control
would do nothing; and the supplied 1318 px pearl logo is generated into `mobile/assets/`
by `generate_design3_assets.py` rather than hand-edited.

Costs paid immediately, recorded because they recur. Fork 6 adds four seconds to every
cold start and every resume, and a customer working through twelve stages opens the app
several times and pays it each time; the launch check itself typically resolves well
inside four seconds, so the customer waits on the minimum rather than the work. Fork 8's
strip is 44 dp, which moved every screen's content down, pushed Stage 12's submit control
past a fold that was already tight (fixed in the same commit, that view now scrolls) and
exposed BL-130, a latent crash in Stage 4's income-source control.

Two things fork 6's ruling did not settle, decided in build. A journey-ending answer
bypasses the hold: `LaunchTerminal`, `LaunchEnded` and `LaunchBlocked` are answers the
customer cannot act on until they see them, and `LaunchBlocked` has a second reason, since
its screen renders a live countdown and four held seconds is four seconds that countdown
is already wrong by. A launch-check error renders in place on the splash, so there is no
navigation to delay. And nothing was added to fill the time, because padding the extra
seconds with motion would be fork 4 reversed in a different shape; the composition settles
at 1.8 s and holds, with the progress hairline still running, meaning "not yet routed".
BL-129 proposes changing that hairline to build up, which would reverse this note, and
says so.

Owed from the bank, not from us: the handoff's wordmarks are raster extractions from JPEG
screenshots and carry edge artifacts at size. The handoff asks for the vector originals
from the bank's brand team before release. That ask has not been made.

## Constraints

Hard constraints, true regardless of how the system is built.

- Arabic-first UI, full RTL layout. Sudan market.
- Core banking is a secure HTTPS/JSON call to the bank's middleware:
  `POST https://<middleware host>/OMNI_PH3/resources/bankRoutes/CheckAccount`, request
  `{"Account": "<account number>"}`, account number only and no branch (OQ-024), response
  `{"Response_Code": <number or string>, "Response_Message": "<text>"}`; the code list is
  OQ-025. Host and path are configuration, never hardcoded. This replaced the earlier
  assumption of an Oracle stored procedure at AD-007, 2026-09-04. The account number is
  the customer's identity bank-wide, and the branch the customer selects is descriptive
  data on the profile, never part of its identity (BL-032, V0061).
- The core banking system is READ-ONLY to this solution. There is no write path to the
  core and no operator action pushes data into it. This backend is the system of record
  for updated customer profiles.
- Uqudo eKYC: token issuance, document scan and validation, document detail retrieval
  including document image and extracted portrait, and liveness verification with its
  detail retrieval. `uqudosdk_flutter` 3.10.0 is pinned in `mobile/pubspec.yaml`.
- Civil Registry lookup by national number.
- Outbound SMS, WhatsApp and email throughout the journey, not only on completion: three
  independent OTP codes at channel verification (one per channel, because a single code
  sent to several channels proves none of them), a submission notification, and a message
  on every subsequent status transition. Sizing and provider selection must assume several
  messages per customer, not one.
- PII and identity-document images stored at rest.
- The app must collect the same data as the bank's paper form (استمارة بيانات تحديث العملاء),
  segmented into screens per `docs/journeys/customer.md`. Fields that Uqudo or the Civil
  Registry supply are by default never asked of the customer; the authoritative mapping is
  `docs/journeys/field-provenance.md`, which also records the product owner's overrides.
- No reference list is hardcoded. Occupations, branches, administrative divisions, income
  sources and rejection reason codes are server-supplied, cached by the app,
  version-checked on each connection, and served from cache offline. The list version used
  for a submission is recorded on the profile.
- Scale: approximately 100,000 total bank accounts, one submission per account since
  completion is terminal, over a 12-18 month campaign. That gives at most 100,000 profiles,
  6-10 million audit rows and roughly 4 GB of audit artifact bodies, an average write rate
  of about three submissions per hour. No partitioning is required anywhere, and artifact
  bodies stay in `bytea`. This supersedes AD-005's `[UNVERIFIED]` assumption of ~3 million
  accounts and ~250 million audit rows.
- Coverage gates: 80% overall is enforced in all three tiers. The 90% business-logic tier
  is not enforced anywhere, because only the backend has a reserved logic-package
  convention to scope a rule to. Tracked as S1-08.
- Regulatory regime and data residency are not supplied: OQ-001 and OQ-002.

Stack settled by AD-001: Flutter, Spring Boot on Java 21, React with TypeScript and Vite.
Repo layout is a monorepo with `mobile/`, `backend/` and `backoffice/` as siblings.

Reference source: the FIB Uqudo eKYC integration, cloned to `../FIB` as a sibling of this
repository and read from local disk only (CLAUDE.md hard rules). It is a monorepo with a
Flutter app, a Spring Boot backend under `backend server fib/utility/`, and a `Web_Code/`
front end that in the clone is a built Vite bundle with no source tree, so anyone
researching the back-office web UX must work from the source maps or from the live site.
The Uqudo-relevant file inventory taken at bootstrap is in
`docs/sessions/2026-08-19-bootstrap-a.md`; what the reference actually does, and where it
must not be copied, is in `docs/components/uqudo-sdk.md`. Benchmark UX against
https://mb1.sfbank-sd.com for colours, logo and visual language.

## Architecture

Settled facts. Each is the residue of a research session; the session reports hold the
evidence and the reasoning, and `docs/components/` holds the component cards.

From AD-001 (`docs/sessions/2026-08-19-research-ad-001-stack.md`):

- Uqudo `enroll()` and `faceSession()` return a JWS compact string, not JSON. Signature
  verification and parsing are server-side only, against the JWKS at
  `https://id.uqudo.io/api/.well-known/jwks.json`, and the mobile-to-backend contract
  forwards the raw JWS. The FIB reference parses it client-side; we do not.
- Uqudo image fields are IDs, not bytes, and each is a separate authenticated download.
- Uqudo client credentials live only in the backend, never in the app.
- Uqudo supports armeabi-v7a and arm64-v8a only, and the scan and liveness flow does not
  function on an x86_64 emulator, so a physical arm64 Android device is required test
  equipment.
- Two-layer persistence with per-field ownership: the device holds customer-entered fields
  and is authoritative for them on reconcile, while the backend owns all system-derived
  data (verified channel states, Uqudo results, Civil Registry data, account status,
  session and profile status) and alone decides whether a session is still open. Note that
  customer.md's Stage 13 also states that every mutating call carries an idempotency key,
  and no client key exists: idempotency is achieved structurally instead. That gap is
  BL-138 (the document) and BL-036 (the two endpoints where structure does not cover it).
- The audit trail is a distinct persistence component: append-only, hash-chained,
  tamper-evident, structurally separate from the mutable profile, with its own access
  control and a longer retention. Raw artifacts are stored, not only parsed values: the
  Uqudo JWS, the raw Civil Registry response, and the core-banking request and response.
- Profile status model: `in_progress`, `awaiting_registry`, `blocked_scan`,
  `blocked_liveness`, `abandoned`, `submitted`, `approved`, `rejected`,
  `terminated_registry_mismatch`. Every transition is written to the database, recorded in
  audit with actor and reason, and communicated to all verified channels. Status history is
  retained and readable, not only the current status.
- Profile provenance, digital or manual, is recorded permanently and never merged in
  counts, exports or dashboard figures. Since AD-022 (2026-09-16) `manual` means "an operator
  keyed at least one customer-entered field", never "no identity evidence" — the journey that
  produced an evidence-less profile is gone. **Built 2026-09-16 (S9-02), and DERIVED rather
  than stored:** `app.derived_provenance()` (V0073) resolves it as "the stored column already
  said manual, OR an operator has keyed at least one field on this profile", reading
  `app.profile_field_edit`. `app.profile.provenance` itself is write-never — nothing has
  written it since AD-022 deleted manual completion, and BL-135 deliberately did not restore a
  writer. The stored value survives only as that function's first disjunct, which is what keeps
  the profiles completed manually BEFORE the ruling distinguishable. BL-155 closed.
- Design principle: the solution produces a verified identity claim; the operator judges it
  and decides. The system does not establish that the document belongs to the account
  holder, because `CheckAccount` returns only a found-or-not code and this system holds no
  prior identity data. That is the intended division of responsibility, not a gap.

From AD-002a (`docs/sessions/2026-08-21-research-ad-002a-uqudo-integration.md`):

- Uqudo returns no liveness score and no liveness field. Liveness failure produces no JWS,
  only a thrown `PlatformException` terminating as
  `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS`. The research's other half, that
  face-match failure arrives as a JWS with `face.match=false`, is contradicted by 2026-09-04
  evidence: the SDK ships a "your face didn't match" dialog and gates on match internally,
  so a mismatch most likely terminates on the same channel as a liveness failure. The
  response was to enable `returnDataForIncompleteSession()` (BL-028, built). AD-002a's
  two-invocation decision is unaffected, and the residual that an impostor looks like a
  camera failure is accepted for Phase 1 (R-016 retired; BL-029 is the follow-up).
- `FaceSessionConfigurationBuilder.setMinimumMatchLevel()` is never called. If the SDK
  enforced the threshold it would consume a low match as an internal retry and issue no
  signed artifact, destroying the fraud signal. The threshold is enforced server-side from
  the JWS.
- A Uqudo face session and its uploaded image are deleted after 600 seconds, a tighter
  clock than the 1800-second access token.
- A JWS can verify perfectly while its images have already been deleted, so the accept
  decision for a scan comes after the image download and checksum step, not after signature
  verification. That is an ordering constraint on the Stage 8 chain.
- The face-session reference image is `documents[0].scan.faceImageId`, the portrait Uqudo
  extracted, not `frontImageId`. The FIB reference uploads the front page instead; do not
  copy it.
- These must never be called: `enableFacialRecognition()` on enrolment,
  `setMinimumMatchLevel()` on the face session, `disableSecureWindow()`,
  `enableRootedDeviceUsage()`, `allowNonPhysicalDocuments()`, `enableAgeVerification()`.

From the Uqudo OpenAPI specifications and Sudan document field pages
(`docs/components/uqudo-api-findings.md`, S1-12), as corrected by the S1-02 device run:

- The Civil Registry lookup key is `identityNumber`, not `documentNumber`. Both Sudan
  document types expose both, and `documentNumber` is the MRZ value, so querying the
  registry with it would fail or succeed against the wrong record. Both accepted document
  types carry `identityNumber`, so no customer reaches Stage 9 without a lookup key.
- The OCR field set is per-document-type. The passport uses `fullName` and
  `fullNameArabic`; SDN_ID uses `name`, Arabic on the front and English on the back and
  only in the latest card version. SDN_ID exists in two card versions, the older with no
  English name and no `bloodType`, and the parser must handle both.
- Uqudo keeps session images for the enrolment JWS's own lifetime, two hours from `iat`,
  not the documented 30 minutes. Measured at S1-02: all images 200 at +25, +31, +35, +45
  and +90 minutes, and 404 at +120, one second after `exp`. So a dropped upload can be
  retried within two hours. `GET /api/v1/info/img/{id}` returns `image/jpeg`.
- `POST /api/v1/face` accepts image bytes only, multipart or base64 JSON and never an image
  id, max 5 MB, JPEG or PNG, returning a `sessionId` valid ten minutes.
- `jti` on the enrolment JWS is the Uqudo session id. On the face-session JWS it is not:
  the binding and the purge id are `data.sessionId` and `jti` is a separate UUID. The
  parser and purge were corrected at R-034.
- `DELETE /api/v1/info/{sessionId}` purges Uqudo's cached session data early. Call it once
  the backend has downloaded and stored every image. This is a deliberate privacy control
  rather than housekeeping: it minimises how long customer identity data sits in the
  vendor's cache. It returns 204 for any UUID, so the caller's choice of id is the whole
  control and there is no server-side signal that a purge did nothing.
- The backend's face-match threshold starts at 3, borrowed from the documented default on
  `POST /api/v1/face/match`, an endpoint this journey never calls. The Face Session API has
  no threshold parameter at all and Uqudo applies no documented default anywhere in this
  flow. The backend enforces 3 against `face.matchLevel`.
- `mrzVerified` is an MRZ checksum boolean present on both document types, stored as an
  integrity signal on the profile and in the audit trail.
- Of the whole Info API only `GET /api/v1/info/img/{id}` and `DELETE /api/v1/info/{id}` are
  relevant; both `GET /api/v1/info` variants exist only for the QR-code flow.

From AD-005 (`docs/sessions/2026-08-22-research-ad-005-persistence.md`):

- Persistence is PostgreSQL 18, one instance, three schemas. Append-only is enforced by
  four layers, none sufficient alone: role separation and REVOKE; BEFORE
  UPDATE/DELETE/TRUNCATE triggers; an event trigger against DDL on the audit schema; and a
  SHA-256 hash chain. The requirement is tamper-evident, not tamper-proof, since no option
  in any database prevents a superuser, and the periodically exported seal closes the loop
  (R-037, still open).
- Notification dispatch is never inside the status-transition transaction. The transition,
  its audit record and the outbox rows are atomic; the send is a separate dispatcher
  polling with FOR UPDATE SKIP LOCKED.
- Arabic ordering is computed server-side under `ar-x-icu` and shipped to the device as a
  `sort_ordinal` integer. The device never attempts Arabic collation.
- Key libraries: PostgreSQL 18, Flyway 12, drift with sqlite3 on mobile.
- The four-eyes rule this research specified is gone, removed by AD-013 and built at S8-27.
  The approve statement keeps only its status guard. **Superseded in part by AD-022
  (2026-09-16): manual completion is gone, so the action this rule governed no longer exists,
  and `is_manual_completion` is no longer WRITTEN — restoring the predicate would need a write
  path rebuilt too, so the reversal is no longer free.** The column is kept (V0067, V0071) so
  pre-ruling profiles stay distinguishable; V0067 records the correction against V0009's
  comment and V0071 records the column as write-never. No separation of duties remains: see
  R-054.

From the client-component research, S3-10
(`docs/sessions/2026-08-30-research-client-components.md` §8.1):

- Phone numbers are E.164 everywhere: wire, storage, comparison and display masking, with
  non-ASCII digits rejected at the boundary. The reason is specific:
  `ContactChannelsService`'s re-entry path writes `previousPhoneNumber` into the
  `session_reentered` audit payload, so if one path stored `0912345678` and another
  `+249912345678`, that payload would record a false delta into an append-only trail,
  uncorrectable afterwards. Retrofitted at S3-11 (BL-016).

From AD-002c (`docs/sessions/2026-08-29-research-ad-002c-messaging.md`):

- Messaging is one port, `MessageSender`, with three closed channels matching the database
  CHECK constraints exactly. The result record is flat by necessity, because it flattens
  into `payload_json` through `CanonicalJson`, which rejects nested objects, `Instant`,
  `Duration` and enums-as-objects.
- `billedSegments` is recorded per message. Arabic forces UCS-2, 70 characters
  single-segment and 67 concatenated, so a typical OTP is two segments and a rejection
  notice three. The blended estimate is 2.2 segments per SMS, and recording the real figure
  from day one is what makes that estimate correctable (R-040).

## Module map

- `mobile/` — Flutter app for retail customers, Android only. Owns the on-device journey:
  OTP entry, Uqudo document-scan and liveness screens, contact, social and address forms.
  Forwards Uqudo's raw JWS to the backend untouched and never parses or verifies it
  locally (CLAUDE.md hard rules).
- `backend/` — Spring Boot orchestrator. Owns every external integration (Uqudo token
  issuance, JWS verification and image retrieval; the core-banking `CheckAccount` call;
  Civil Registry lookup; outbound SMS, WhatsApp and email) and persistence of one customer
  record per submission. The only tier holding Uqudo client credentials. Its package
  layout was settled at S3-01 and is binding on every later slice: features are the
  top-level cut, with `domain` and `service` reserved for business logic. That is a
  structural convention rather than an architecture decision with alternatives costed
  against constraints, so it lives here and in CLAUDE.md rather than in the decisions log.
- `backoffice/` — React, TypeScript and Vite web app for bank operators. Reads
  backend-held profile data and performs the system's write actions: approving or rejecting a
  submitted profile with a coded reason, printing the update form (which stores an artifact and
  appends an audit event), and manual per-field data entry on customer-entered fields only
  (AD-015 as narrowed 2026-09-14 and again by AD-021, with Civil Registry and Uqudo fields and
  every list-picked value permanently read-only; **BUILT IN FULL 2026-09-16 at S9-02, backend
  and editing UI both**, BL-135). The editable set is DERIVED PER PROFILE and sent to the
  browser, which draws a «تعديل» chip where the key is present and derives nothing itself; the
  server derives it again on the write path, so a chip is a courtesy and never the control. The
  window is `submitted` and `rejected` only. Marking a profile manually complete was a fourth until AD-022 removed
  it on 2026-09-16. Also navigates submissions, checks per-profile status and history, and exports
  field data. Aggregate statistics are deferred (BL-001). Three roles in a hierarchy
  (AD-013, built at S8-27 and S8-28; narrowed by AD-022 at S9-01): viewer views; operator
  views, prints, approves and rejects; admin does everything an operator may, plus sole
  authority to create and manage back-office users. The role ladder is three deep but the
  access-level enum stays two-valued, with admin mapping onto `OPERATOR`, since "everything
  an operator may" is exactly what that level means; admin's extra power is the
  `/api/v1/admin/**` surface, not an access level. Full specification in
  `docs/journeys/operator.md`.

Both client tiers have feature code — `mobile/lib/features/` holds 6 features and
`mobile/lib/core/` 17 modules across 102 Dart files, and `backoffice/src/` holds 5
directories across 44 TypeScript files — but neither has the backend's reserved
logic-package convention, which is the remaining condition on S1-08.

## Decisions log

What was decided and the reason that decided it. The source column holds the full
reasoning, the alternatives costed, and the evidence; this table is the index, not the
record. Reports are under `docs/sessions/`.

| ID | Date | Decision | Rationale | Source |
|---|---|---|---|---|
| AD-001 | 2026-08-19 | Flutter for mobile, Spring Boot on Java 21 LTS for the backend, React 19 with TypeScript, Vite and Ant Design for the back office. | Uqudo first-party SDK availability, and framework-level RTL for an Arabic-first product. The tight-deadline flip to a browser or PWA was evaluated and overridden: the hard constraint on PII and identity-document images requires screenshot blocking, certificate pinning and secure storage, which a browser cannot provide. | 2026-08-19-research-ad-001-stack.md |
| AD-002a | 2026-08-21 | Two separate Uqudo SDK invocations: `enroll()` at Stage 8 with facial recognition disabled, and `faceSession()` at Stage 10 against a face session the backend creates from the document portrait (`documents[0].scan.faceImageId`). One session resource with attempt sub-resources. | Route A, facial recognition inside the scan, was rejected because it captures the face before the Stage 9 review screen and before Stage 10's preparation screen. Replay protection differs by invocation and that is deliberate: for enrolment the backend mints `sessionId` and `nonce` per attempt and `UqudoJwsParser` rejects a JWS whose `data.nonce` does not match, while for the face session no nonce is minted at all and binding rests on the server-minted, single-use `sessionId`. | 2026-08-21-research-ad-002a-uqudo-integration.md |
| AD-005 | 2026-08-22 | PostgreSQL 18, one instance, three schemas: `app` (mutable profile), `audit` (append-only, hash-chained) and `ref` (reference-data version registry). Two roles, `fru_migrator` owning everything and `fru_app` owning nothing and holding only INSERT and SELECT on `audit`. Flyway 12, no Redis. Mobile local store is drift with sqlite3, two databases, session (cleared on completion and abandonment) and reference (kept). Absorbs AD-002g. | Transactional DDL is the deciding factor for a solo developer with no CI and no DBA: PostgreSQL rolls a failed migration back automatically, MySQL and Oracle cannot, SQL Server only partially. SQL Server 2022 ledger tables are genuinely better for append-only and were rejected anyway, because Express caps at 10 GB and Standard means choosing a per-core licence on the bank's behalf. Oracle rejected on the Free edition's 12 GB cap and no transactional DDL; MongoDB rejected because the data is relational and SSPL is a procurement obstacle. | 2026-08-22-research-ad-005-persistence.md |
| AD-006 | 2026-08-30 | Mobile assembles `signature`, `phone_numbers_parser` (validation core only, inside a bespoke field), `image_picker`, `file_picker`, `image` and `crypto`; it rejects `pinput`, `country_picker`, `dropdown_search`, `flutter_form_builder` and `flutter_image_compress`. The back office maps onto Ant Design's `Table`, `Descriptions`, `Timeline`, `Image` and `Modal`, with all `Table` filtering and sorting kept server-side. Export is generated server-side with Apache POI SXSSF, never in the browser. | The named rejections each have a specific reason worth keeping: `pinput` renders its OTP boxes right-to-left under `Directionality.rtl` while the value stays correct, so every unit test passes and only an Arabic-speaking human would catch it; `country_picker` embeds a hardcoded non-Arabic country list, forbidden outright by the reference-list rule; `dropdown_search` would be six package configurations where one bespoke picker serves six call sites over one row shape; `flutter_form_builder` owns form state in a `GlobalKey` where Stage 13's ownership model requires drift to be sole owner. For the back office, `ref.ar_fold()` and `sort_ordinal` have no browser equivalent and a client-side `localeCompare('ar')` would silently disagree with `ar-x-icu`; every search and filter is also its own audit event, which a client-side filter cannot produce. The npm XLSX ecosystem fails the maintenance filter, and more decisively the profile list is server-paginated so the browser never holds the rows an export needs. | 2026-08-30-research-client-components.md |
| AD-002c (port) | 2026-08-29 | One `MessageSender` port with a sealed per-channel payload, an `Urgency` value distinguishing interactive OTP from deferred notification, a flat result record, and the implementation selected once at startup from one property with no default. `MessageChannel` is closed at sms, whatsapp and email, pinned to the CHECK constraints; a fourth channel is a migration. | The three channels are not interchangeable: WhatsApp needs a pre-approved template name and parameters, SMS a body and sender ID, email a subject, so a sealed payload models what is real rather than a lowest common denominator. `Urgency` as a request field rather than two port methods makes a third route class an added enum constant instead of a breaking change across every adapter. The result record is flat because `CanonicalJson` accepts only flat objects and rejects everything else, which is the constraint most likely to be discovered late. | 2026-08-29-research-ad-002c-messaging.md |
| AD-002f | 2026-08-31 | Reference-data delivery is a two-endpoint, version-addressed, immutable-document contract: a mutable manifest with ETag and 304, and immutable per-version list documents cached for a year. Whole-list fetch of only the lists whose version or content hash differ, never item-level deltas. `content_hash` is the SHA-256 of the exact served bytes. The address cascade's root becomes declared data on `ref.reference_list_version`, composite-FK'd to the country list with a deferred constraint trigger. The session pins one version per list at Stage 3 entry. | A CHECK cannot express the cascade-root invariant, since PostgreSQL does not support a CHECK referencing other rows, so the root is declared data guarded by a composite FK plus a trigger, deferred to commit because every seed migration inserts a whole list in one statement. Hash-of-served-bytes beats enumerating more columns into SQL canonicalisation: the client just hashes what it received, needing no canonical-JSON implementation. Whole-list beats item-level delta because two of the seven lists are known to be replaced outright, and a merged locally-constructed list is one no server ever signed. Session pinning is accepted because completion is terminal, one submission per account, and lists change rarely. This redefined R-033's envelope without closing it: population is a by-hand publication step that can be silently skipped, which is what R-033 now tracks. | 2026-08-31-research-ad-002f-reference-data.md; 2026-08-31-s4-03-reference-documents.md |
| AD-004 | 2026-09-02 | Artifact bytes are stored in PostgreSQL, in the profile database (`app.artifact_ref.body`, `STORAGE EXTERNAL`), with no object store. Checksum-verified reads go through `app.artifact_read()`, and a 90-day abandoned-artifact purge is `app.purge_abandoned_artifacts()`. Originals are stored byte-identical and raw capture frames excluded deliberately. | The delivery model decides it: "one PostgreSQL instance, restore it and run" is a materially simpler handover to the bank than PostgreSQL plus an object store plus bucket policies, credentials and lifecycle rules their infrastructure team must accept and can misconfigure unseen. The usual objection to BLOBs only half-applies, because PostgreSQL moves anything over about 2 KB out-of-line into TOAST automatically, so an ordinary profile query never reads image bytes unless it asks for them, live-proven by the main table staying one page regardless of body size. Estimated at about 4.8 MB per completed profile and 350-500 GB at full campaign scale. | 2026-09-02-s5-06-artifact-storage.md |
| AD-002e | 2026-09-02 | Back-office authentication: two Spring Security filter chains, with the operator, auth and admin prefixes authenticated by form login, session and CSRF, and a catch-all `permitAll` keeping the nine customer prefixes unauthenticated. `must_change_password` grants only `ROLE_PASSWORD_CHANGE_REQUIRED`, enforced by the authorization filter rather than a controller check. The session timeout is Boot's 30-minute default. Failed sign-ins record `accountExists` and `user_id`, never the submitted username. Account creation is a CLI tool, not an HTTP endpoint. Partly superseded by AD-013, which reverses the ruling that admin is not a back-office access level; everything else stands. | `user_id` is a UUID because the four-eyes rule was an append-only, uncorrectable predicate matching on `actor_id`, and a username would let a rename silently defeat it: a structural choice, not a stylistic one. The restricted-authority approach to forced password change, rather than `credentialsNonExpired=false`, is what lets the account authenticate into a session it can then use to fix itself. Building an admin HTTP surface before the authorization layer existed to protect it would have been circular. **Record-keeping note, 2026-09-14:** AD-013 asked that this row be left unedited so its reasoning and its 403-for-admin proof stayed readable as what was true until that day. The plan-file cleanup compressed it anyway, which is a reversal and is said here rather than left silent; the full pre-AD-013 text is in git and the 403-for-admin proof is in the two reports named opposite. | 2026-09-02-research-ad-002e-auth.md; 2026-09-02-s4-05-operator-auth.md |
| AD-007 | 2026-09-04 | Core banking is reached by an HTTPS/JSON call to the bank's middleware, `POST .../CheckAccount` with `{"Account": "<account number>"}`, replacing the earlier assumption of an Oracle stored procedure called over JDBC with branch and account. No Oracle driver, no server-version question (OQ-006 moot), no database access request. Branch is not part of the check (OQ-024). | The product owner supplied the real endpoint, request and a live example response, and the same-day S1-04 discovery calls, on fabricated account values only, confirmed a plain HTTPS POST with no authentication header and a publicly-trusted certificate. The Oracle line came from the initial brief and was flagged "to be verified" from day one; this is that verification. | 2026-09-04-plan-reconciliation-and-corebanking-discovery.md |
| AD-002b | 2026-09-04 | The Civil Registry's `GetCRSData` endpoint is used exactly as given: HTTPS on a configured hostname, a bare JSON POST with no authentication. Outcome classification is deliberately coarse. A 2xx JSON body parsing to a populated record whose `IDENTITY_NUMBER` equals the number sent is `ok`; any other response, including an HTTP 400 HTML page, a 5xx, a 204, an empty or unparseable body, or a populated record with a different identity number, is `not_found`; no response at all is `unreachable`. Nothing finer is distinguished. The national number is forwarded as the scanned `identityNumber` with no length, structure or check-digit validation. The photograph is treated as always present and every other field is optional. | The product owner's answers to the research's twelve questions make finer distinctions unnecessary: the journey only needs to know whether the registry was reached and whether it returned this customer's record, and everything the adapter cannot classify collapses into states that already exist, so no new schema state is needed. `IDENTITY_NUMBER` is read from the found record rather than echoed, so the equality check is a real guard. The unauthenticated, internet-reachable endpoint is accepted as the bank's own arrangement. | 2026-09-04-civil-registry-decisions.md |
| AD-008 | 2026-09-05 | The identity-scan stage is atomic: a customer who does not complete it in one session does not resume inside it. On a device-less re-entry the prior active identity cycle and its artifacts are superseded and not inherited, the resume point is capped at identity-type selection, and the customer rescans. Manually-entered earlier-stage data is not discarded. The per-type scan-attempt budget resets on this forced restart, while the cumulative 24-hour block still keys on attempts across sessions, so abuse stays bounded. Governs the no-local-state case only; a same-device resume is unchanged. | A device-less re-entry inherited the previous session's accepted cycle, face result and signature, so a person entering another customer's account number on a new device could submit a profile carrying that customer's scan and face match. customer.md already forbade this and no code enforced it. An in-journey OTP was considered as alternative proof and rejected: this is a data-update campaign where the contact channel is entered during the flow, so an OTP proves control of a channel the current person just supplied, not that they are the account holder. Rescanning re-proves identity, because the face must match the document. | 2026-09-05-filing-sweep-and-ad008.md |
| AD-003 | 2026-09-10 | **Cancelled.** iOS is dropped and production V1 is Android only. | The question this decision asked, how to build, sign and release an iOS app with no local macOS machine, no longer arises. Confirmed 2026-09-14 as dropped completely rather than deferred, so every dependent row is dispositioned rather than parked. Reviving iOS re-opens AD-003, BL-052, BL-088 and R-003 together, at full cost: the work is zero percent done, not half done. | PROJECT_PLAN decisions log |
| AD-009 | 2026-09-07 | The `@agent-researcher` gate is waived for the Airtel Sudan SMS gateway, and for that integration only. The captured contract is the basis of record. | Airtel Sudan publishes no public API documentation at all; it is a contract-only gateway the bank already holds an account with, so a researcher pass would have no authoritative source to read beyond the Postman captures this project took itself, and could only restate them with less evidence. The waiver does not waive the capture requirement, which is still owed on BL-080, and does not weaken the gate for Uqudo, core banking or the Civil Registry, where real documentation exists. | 2026-09-07-sms-adapter.md |
| AD-010 | 2026-09-07 | The mobile app's public API hostname is one bank-owned subdomain of `sfbank-sd.com`, delegated by NS record to a Route 53 public hosted zone in the existing account. **V2-scoped:** the 2026-09-10 hosting decision puts V1 on the CloudFront default hostname instead. | Researched and recommended rather than ruled. What is outstanding is not ours: the bank choosing the hostname string, and the bank creating one NS record. The string is the blocking half, because the name is compiled into the APK by `--dart-define`; the NS record can follow later without a rebuild. See BL-074. | 2026-09-07-research-ad-010-mobile-api-domain.md |
| AD-002d (provider) | 2026-09-06 | The hosting provider is AWS. This closes the "which host" half of AD-002d. | Product-owner decision, with a requirement-by-requirement service mapping (M-1 to M-22) produced against it. What remained open under AD-002d afterwards is R-037 alone. | 2026-09-06-aws-hosting-requirements.md |
| AD-013 | 2026-09-13 | The back-office role ladder is a hierarchy with admin at the top, superseding AD-002e's ruling that admin is not a back-office access level. Viewer views only; operator views, prints, approves and rejects (and manually completed, until AD-022 removed that on 2026-09-16); admin does everything an operator may plus sole authority to create and manage back-office users, through the UI rather than the CLI. Taken in the same decision: the four-eyes rule is removed entirely, keeping `is_manual_completion` so it can be switched back on without a migration. **CORRECTED 2026-09-16 (AD-022/S9-01): this reversal is no longer cheap.** The column has no writer since manual completion was deleted, so restoring the predicate would need a write path rebuilt too — and the action the rule governed no longer exists. The column is kept (V0067, V0071) so pre-ruling profiles stay distinguishable, which is a different reason. **CORRECTED 2026-09-16 (AD-022/S9-01): this reversal is no longer cheap.** The column has no writer since manual completion was deleted, so restoring the predicate would need a write path rebuilt too — and the action the rule governed no longer exists. The column is kept (V0067, V0071) so pre-ruling profiles stay distinguishable, which is a different reason. | The product owner's model is a straightforward capability hierarchy, and the separation AD-002e protected was already defeatable by one person: an admin creates accounts and sets their initial passwords, so an admin could always create a second account, complete a profile as it, and approve as themselves. Granting admin operator powers removes the last friction rather than opening the hole. The cost is accepted explicitly and filed as R-054. | `.scratch/backoffice-remaining/issues/06-does-admin-become-a-superuser.md`; 2026-09-13-s8-28-admin-superuser.md |
| AD-014 | 2026-09-13 | The printed update form is rendered server-side with Apache FOP 2.11, XSL-FO to PDF, embedding the IBM Plex Sans Arabic face the repo already carries. **A SECOND PDF LIBRARY JOINED IT 2026-09-14: `org.apache.pdfbox:pdfbox` 3.0.3 is now MAIN scope.** FOP renders XSL-FO and cannot lay out an EXISTING PDF page, and the product owner ruled that a printed salary certificate is a separate PAGE rather than a separate FILE, so the bundle is assembled in two steps: FOP writes the form and its image attachments, PDFBox appends the certificate's pages. AD-014 still names the renderer; it is no longer the whole PDF story. PDF/A is not requested (product-owner ruling 2026-09-14): plain PDF now, archival conformance to be put to the bank separately, since it changes font-embedding rules and is expensive to retrofit. | The only Apache-2.0 candidate documented by its own project to apply the font's OpenType GSUB and GPOS tables rather than pre-shaping Arabic into the U+FE70 compatibility block that Unicode says must not be used for interchange. Also native `writing-mode="rl-tb"`, an implicit UAX#9 bidi pass, and `fo:page-number-citation-last` for the footer. iText was ruled out on licence, since AGPLv3 forbids closed-source network deployment and pdfCalligraph is commercial-only. OpenPDF is clean on licence but has an open upstream bug rendering English right-to-left alongside Arabic, which is our exact requirement. Headless Chromium renders better but costs a 400 MB browser and its CVE stream in the Fargate image for throughput we do not need. No longer conditional: the acceptance render ran and passed at S8-33. | 2026-09-13-research-arabic-pdf-toolchain.md; 2026-09-14-s8-33-printed-form.md |
| AD-015 | 2026-09-13 | **BUILT IN FULL 2026-09-16 (S9-02), backend and editing UI.** The back office becomes a data-entry tier: an operator may enter or correct any customer-entered field, with the value stored exactly as a mobile-supplied one, the source flagged manual and the entering operator recorded. Narrowed 2026-09-14, and the narrowing is load-bearing: editable means the 36 fields whose Source is S3 in field-provenance.md. The 6 Civil Registry fields, the 7 Uqudo fields and the system-generated form date are never editable by anyone. This supersedes the architecture statement that the back office performs only two write actions. | The branch runs the whole update process during a customer visit, so an operator must be able to fill anything the customer would have filled on the phone; a back office that can only approve or reject cannot serve a customer at the counter. Bounds that make it safe: editing stops at `approved`, so approval keeps meaning somebody approved that data; viewers cannot edit; validation and reference lists match the mobile app field for field, so no value can enter that the app could not produce; and an edit does not void the Uqudo scan or registry result, which are facts about a moment that happened and are stored independently. The narrowing restores R-054's bound, that an operator cannot invent a Uqudo scan or a Civil Registry result, which the un-narrowed version had silently removed. What remains true is that one person can key the customer-supplied data and approve it. **NARROWED AGAIN 2026-09-16 (S9-02), and this one is a correction rather than a tightening: "editing is allowed at any status before `approved`" is UNSAFE AS WRITTEN.** `app.profile_customer_data` is DEVICE-AUTHORITATIVE on reconcile (V0006's own header) and every customer stage write is a full-row replace, so an operator edit to a profile still `in_progress` is silently destroyed by that customer's next stage submission — no conflict, no error, nothing in the audit trail saying the edit was lost. The editable window is therefore `submitted` and `rejected` ONLY (`FieldEditService.EDITABLE_STATUSES`). `rejected` is included because a rejected profile can still be approved, so it is genuinely before `approved`. | `.scratch/backoffice-remaining/issues/10-manual-field-entry.md`; BL-135; 2026-09-13-backoffice-decision-layer.md; 2026-09-16-s9-02-per-field-editing.md |
| AD-016 | 2026-09-13 | The salary certificate is a profile artifact, stored exactly like the identity-document images and the signature: in `app.artifact_ref` with its bytes in the database, one row per profile, served through the same authorised endpoint as every other artifact kind. This ratifies the built design rather than changing it. | Recorded so the model is a decision on the record rather than an accident of implementation order, because the certificate reached that shape through four sessions that each solved a smaller problem. The one structural difference from identity documents is right: identity artifacts are keyed to a cycle, so a re-scan supersedes the previous cycle's images, while the certificate and signature are keyed to the profile with `cycle_id` NULL, because a customer re-scanning their passport has not replaced their salary certificate. The certificate remains optional and gates nothing. | BL-022; BL-105; BL-136; 2026-09-13-s8-24-salary-certificate-viewable.md |
| AD-017 | 2026-09-14 | Customer-session authentication is dropped. Not deferred and not scheduled: R-051 will not be built, in V1 or after. Supersedes the 2026-09-10 ruling that deferred it to V2. | The V1 operator group is small, known and trusted, and the product owner judges the identifier unlikely to escape it. The limit of that rationale is recorded rather than smoothed over: a trusted group bounds misuse by the group, not the identifier escaping it. The accepted exposure is that the image endpoint serves a customer's passport and national-ID scans, the Uqudo portrait and the registry photograph to anyone holding that profile's UUID, with no customer-session authentication anywhere on `/api/v1/**`, and a served image cannot be unserved, so dropping the fix does not end the exposure but stops anyone planning to end it. BL-137 and BL-041's residual are accepted with it and must not later be reported as covered. R-037 is a separate row and stays open. The recommendation on the table was an opaque database-backed bearer minted at OTP verification, one filter plus 147 call sites; it was not rejected on cost but on judgement. | 2026-09-13-s8-26-r051-decision-material.md |
| AD-018 | 2026-09-14 | Separation of duties is not restored and not compensated. R-054 is accepted as-is for V1: no audit-review screen, nobody assigned to read the operator trail, and no alert when the entering and approving actor match. | The bank's back office is small enough that requiring two accounts per profile was judged unworkable at AD-013, and the same staffing reality rules out a reviewer role. Detection was weighed against prevention and neither was taken, recorded as a choice rather than an omission so a future reader does not read the silence as an oversight. The accepted exposure is that one account can key a profile's data in and approve it with nothing preventing or detecting it. R-054's own text names R-037 as a prerequisite of its mitigation, and with the trail now the sole control rather than a supplementary one, R-037 becomes more load-bearing, not less. | AD-013; R-054; R-037 |
| AD-019 | 2026-09-14 | A device-less re-entry inherits the previous person's profile-keyed artifacts. Accepted; BL-143 and BL-062 close as won't-fix. | AD-008's supersede marks the identity cycle and the signature superseded, but the salary certificate is profile-keyed with `cycle_id` NULL and nothing reaches it, so a re-entered profile carries and shows an operator the previous person's certificate. The signature half is the same shape, since V0046's unique index is not state-scoped. Judged rare enough not to earn the work. The accepted exposure is that an operator reviewing a re-entered profile may see one real person's pay document presented as another's, with nothing on screen indicating it. Recorded as a decision rather than left as an open backlog row so the next session does not re-cost it. | AD-008; BL-062; BL-143; 2026-09-13-s8-29-bl122-certificate-states.md |
| AD-020 | 2026-09-15 | Per-channel availability is published to the mobile app. Stage 1b's contract (and AD-002f's manifest shape, whichever the build chooses) gains an additive field naming which of SMS, WhatsApp and email THIS deployment can actually verify, and `ContactChannelsScreen` offers only those — a channel the deployment cannot verify is not presented as a choice. **APPROVED AS A DECISION ONLY, 2026-09-15 — NOT TO BE BUILT YET, AND PARKED THE SAME DAY BEHIND THE BACK-OFFICE MATCH-TABLE REDESIGN.** The whole channel-availability topic, BL-149 included, is closed to discussion until that redesign ships. The build stays BL-086, and BL-125's declined-aware row is its natural companion. | Product-owner decision, taken on the journey assessment of 2026-09-15. The backend control (`fru.messaging.<channel>.enabled`, `ChannelSelection.resolve`) is load-bearing and correct, but `EntryRepository.pendingVerificationChannels` filters only `declined` channels out of Stage 2, which does NOT cover a channel left enabled against a stub provider — email on `application-aws.properties` is exactly that, so a customer CAN be left waiting on a code that was never sent (corrected within the session; see R-042 and BL-149). The harm is therefore wider than first recorded here, not narrower. Also live: a customer is offered a WhatsApp or email choice that silently does nothing and is never told why, and a WhatsApp-only selection on a WhatsApp-disabled deployment fails client-side gating's blind spot and returns a bodyless 400 the app cannot explain (`ContactChannelsRejectedException`). Recorded now rather than built so the shape of the contract change is settled before a session needs it. | R-042; BL-086; BL-125; AD-002f; 2026-09-15-journey-assessment.md |
| AD-021 | 2026-09-15 | **The back-office profile screen and the printed form are restructured into three sections, and the identity comparison is abandoned.** (1) Every field the Civil Registry supplies is taken from it as it comes, with NO comparison against the scanned document — fields 5, 6, 7, 8, 9 and 21, wholly read-only. (2) The address is the single exception and is NOT taken from the registry: it is what the campaign exists to refresh, so it comes from the customer and the operator may correct it. (3) Customer-entry fields are taken from the customer and are editable, **but only where the customer typed FREE TEXT** — a list-picked value is never editable. Screen sections: ١ identity data (registry, no address), ٢ identity verification (document data, the five images, signature, salary certificate), ٣ customer-declared data segmented exactly as the mobile app segments it. The printed form follows the same three sections. | Product-owner ruling relayed from the bank, 2026-09-15. Largely a GENERALISATION of a decision already taken rather than a new one: the 2026-09-14 (S8-33) display ruling in field-provenance.md already said "Civil Registry only — 5, 6, 7, 9, 21" and "Customer entry only — 4, 18, 23, 43, and 35-41" for the printed form; this extends the same rule to the screen and adds the free-text bound on editing. Consequences recorded rather than left implicit: **the match table designed earlier the same day is superseded** and the comparison it exists for is not wanted; **BL-150's conflict-resolution half dissolves with it**, since without a comparison there are no conflicts to adjudicate, which also retires the AD-015 collision that made BL-150 blocking; and **AD-015 narrows a second time** — from the 36 S3 fields to the free-text subset of them, 14 fields on a Sudan-resident profile. ~~Three points the ruling does not settle are open, listed in BL-152 and NOT decided here.~~ **ALL THREE SETTLED 2026-09-16 (BL-152 closed):** (1) phone and email are read-only, by AD-022 ruling 4; (2) the digits-only fields 16 and 19 are NOT editable, by product-owner ruling at S9-02; (3) editability is DERIVED PER PROFILE and is built that way — `EditableFieldPolicy`, proved by two profiles differing only in home country producing different sets. Two further rulings the same day, both of which this decision implied without stating: field 20's edit affordance reaches only its «أخرى» free text, never the coded multi-select (anything else contradicts this ruling's own free-text bound); and field 23 is editable only where the Uqudo scan supplied no `placeOfBirth`, which closes the third of BL-135's four edges. The derived free-text rule also extends to fields 24, 29 and 30, which `docs/backoffice-redesign.md` §3 named only for 36/37 although the journey rule and the column structure are identical. | AD-015; BL-135; BL-150; BL-152; field-provenance.md; 2026-09-15-journey-assessment.md; 2026-09-16-s9-02-per-field-editing.md |
| AD-022 | 2026-09-16 | **The operator surface is narrowed to one journey, and the approved AZ design is the build target.** Four rulings. **(1) Manual completion is REMOVED, not deferred.** The only journey that produces a profile is a mobile submission; an operator may edit the editable fields and then approve, reject and/or print, and nothing else. The «إكمال يدوي» action, its endpoint, service, repository and modal are deleted. **(2) The printed form is the approved design**, with the attachment option kept and the ATTRIBUTED/UNATTRIBUTED variant choice REMOVED — the approved form always prints the operator who printed it, in the page footer, and the product owner judges that sufficient. **(3) Face-match results are not displayed** on screen or form beyond the existing «التحقق الحي — ناجح» line. The face match is still RUN, STORED and AUDITED exactly as now — only the display goes. **(4) Contact channels are not editable in the back office, and only VERIFIED channels are shown** on screen and on the form; declined and unverified channels are not rendered at all. | Product-owner decisions, 2026-09-16, taken with the approved design in hand. (1) settles BL-152’s first question and supersedes BL-004: with no manual completion there is no route for a branch-only customer, accepted. It also retires R-018 outright and narrows R-054, since an operator can no longer complete a profile at all. (2) removes a shipped capability (V0070’s two artifact kinds, `PrintedFormVariant`, the modal’s two-way choice). (3) is a DISPLAY ruling only and must not be read as removing the control: R-016’s mitigation narrows from “surfaced to operators” to “retained and queryable”, and that narrowing is the accepted cost. **Correction (S9-01): that mitigation sentence is NOT in RISKS.md**, where R-016 has been retired since 2026-09-04 and is about the CUSTOMER’s experience; it lives in `docs/journeys/journey-open-items.md`, which is where the narrowing was applied. (4) settles BL-152’s second question. **S9-02 (2026-09-16) closed the two loose ends ruling 3 left:** BL-154 — REJ-03, the rejection reason whose on-screen evidence ruling 3 removed, is WITHDRAWN by product-owner ruling (V0074, `is_active = false`, so the server refuses it while every historical rejection keeps its label); and BL-155 — provenance has a meaning again, derived rather than stored. **SEVEN FURTHER RULINGS AT S9-03 (2026-09-16), all on the printed form.** (a) The identity band becomes a COMPACT LINE: customer name, submission time, print time. The reference is dropped from it (the header and footer both carry it), and so are «المشغّل الطابع» — the new footer names the same person — and «مصدر البيانات». (b) An unverified or declined channel OMITS ITS ROW ENTIRELY rather than printing «غير متاح», which is ruling 4 read literally. (c) The liveness frame is SUPPRESSED FROM THE PAGE-1 TILES but keeps its appended sheet; it is still captured, stored and audited. (d) Fields 4 and 48 RESOLVE TO THE ARABIC COUNTRY NAME, reversing the assembler's standing comment, which BL-157 had already shown to rest on a false premise. (e) Field 19 KEEPS its thousands separator and gains « ج.س», departing from the artboard, which prints the figure ungrouped — judged mock carelessness rather than intent on a money figure read off paper. (f) Field 12 is INFLECTED BY SEX, «متزوجة» for a married woman: the artboards show the feminine form in one instance and a bank form addressing a woman as «متزوج» is a visible defect on a document she may be handed. (e) and (f) were, as of S9-03, the only two deliberate departures from the approved artboards; everything else followed them. **That sentence is no longer true and is left standing as written so the change to it is visible: S9-06 (2026-09-18) adds (h), (i) and (j) below, and the departures are now five.** The rule it expresses is unchanged and is why they are recorded here at all — the artboards still draw the AZ lockup, the muted internal-use line and the AZ watermark, so a session reading only `Design_3/` will find three things the code deliberately does not do. **(g) EXISTING PROFILES ARE NOT A CONSTRAINT.** The product owner ruled that every profile will be flushed once the design is implemented in full, so no behaviour is preserved for pre-AD-022 manually completed profiles — which is what settles the «يدوي» question S9-03's commit 3 raised: such a profile now carries no per-field marker at all, and that omission needs no remedy. **THREE FURTHER RULINGS AT S9-06 (2026-09-18), all on the printed form and all departures from the artboards — (h) and (i) on its header, (j) on its ground.** (h) **THE PAPER CARRIES THE BANK'S IDENTITY, NOT THE VENDOR'S.** The AZ lockup is replaced by the bank's circular logo, and the muted line «البنك السوداني الفرنسي — للاستخدام الداخلي» by the bank's name drawn in its own logo calligraphy — an IMAGE, cropped to the Arabic line, because the lettering is part of the mark and no installed typeface reproduces it — followed by «بياناتي», the mobile app's name. The internal-use notice is NOT printed anywhere on the form. Ticket 05 decision 7 is untouched by this: it rules that the form is internal and is never handed to the customer, which is a statement about process and never required the notice to be printed — the "says so on its face" reading was a code comment's gloss, corrected here. The screen keeps its AZ branding; this ruling is about the paper only. (i) **«بياناتي» IS SET IN AMIRI**, the face the mobile splash screen uses, in the splash's own decorative tatweel spelling, so the paper names the app exactly as the app does. This narrows wayfinder ticket 08 decision 2, which set the whole form in IBM Plex Sans Arabic: that rule still governs every other span, and Amiri is embedded for this one. (j) **THE AZ WATERMARK COMES OFF THE PAGE**, taken in its own commit so that a header problem and a watermark problem stay independently revertable. The region background and the `fox:` extension attributes that sized it are removed, and with them the only use this document had for FOP's extension namespace. `brand/az-watermark.jpg` and `brand/az-lockup.png` are both now referenced by no code; both are deliberately KEPT, on the precedent S9-03 set for the roundel it displaced — removing a committed brand asset is a decision about what the bank's resources hold, not a tidy-up. **(k) TWO CLARIFICATIONS, product owner, 2026-09-18, both confirming that what looks like drift is intent.** First: **the SCREEN is AZ-branded and the PAPER is bank-branded, deliberately and permanently.** The back office is an internal operator tool and carries the vendor's identity; the printed form is a bank document filed in a branch and carries the bank's. The split is not a migration half-done and is not to be "finished" in either direction. Second: **the internal-use notice is retired from EVERY surface, and was never a requirement.** It is gone from the printed form by (h) and from the back office's print dialog by this ruling. Ticket 05 decision 7 rules that the form is internal as a matter of PROCESS — it never asked for those words to appear anywhere. The requirement came from an earlier session writing a gloss into a code comment and later sessions reading it back as fact, which is also how «للاستخدام الداخلي — لا تُسلَّم للعميل» reached the print dialog. Decision 7 is untouched and still governs how the form is handled. **FOUR FURTHER RULINGS AT S9-07 (2026-09-18). Rulings (a) through (k) above are NOT amended by these — they are the record of decisions already taken, and (l) exists precisely because one of them was being read wider than it was meant.** **(l) RULING (h) REACHES THE HEADER MARK AND THE MUTED LINE, AND STOPS THERE.** The paper still prints «AZ Omni eKYC» in the start cell of every page's footer (`PrintedFormDocument.PRODUCT_NAME`, drawn at `FoDocumentWriter:626`) and is still drawn entirely in the AZ palette (`FoDocumentWriter:60-65`, whose own comment says "'branded AZ' means these values"). Both are DELIBERATE and neither is drift. The product owner was asked directly, having been shown that (h)'s words — "the paper carries the bank's identity, not the vendor's" — read wider than what was built, and ruled that (h) governs the header lockup and the muted line only. Extending it to the product name or the palette would be a new ruling and is not taken here. Recorded because the gap between (h)'s wording and (h)'s scope is exactly the kind of thing a later session finds, reads as a half-done migration, and "finishes" — the same failure mode (k) exists to prevent for the screen. **(m) BL-164: THE FORM PRINTS ONE SPOUSE ROW, THE ONE THE RESOLVED SEX SELECTS** — field 13 «اسم الزوج» for a woman, field 14 «اسم الزوجة» for a man, never both. This is (f)'s reasoning applied where (f) did not reach: telling a married woman «اسم الزوجة: غير متاح» is the same visible defect on the same document as addressing her «متزوج». It also makes the paper agree with the screen, which has rendered a single sex-selected row since BL-161 (`ProfileDetailPage.tsx:722-728`). The alternative on the table — keep both rows because the bank's 54-field schema has both — was declined: the form is a document for a person to read, not a dump of the schema, and the artboard `printed-form-p2.dc.html` draws field 13 alone. Which row is chosen follows `PrintedFormAssembler.effectiveSex()` (registry first, then declared), unchanged. Whether an EMPTY row prints is untouched by this ruling: an unmarried customer still gets their one row reading «غير متاح». **(n) BL-163: THE FOOTER NAMES THE OPERATOR BY USERNAME**, not by UUID and not by display name. The artboard draws `faheem.operator` and `docs/journeys/operator.md`'s footer bullet already documented the footer as «طبع بواسطة الموظف: \<username\>», so the code was the thing out of step. (Cited by section rather than by line: the same commit reflows that file, and a line number in an AD row goes stale the moment anyone edits above it.) The display name was declined for two reasons given together: it is operator-supplied, making it untrusted text on a bank document, and BL-165 is live proof that it can be wrong. A username is system-assigned and ASCII, so it also stays correct through `FoDocumentWriter.latin()`'s LTR bidi override, where an Arabic display name would not. **`OperatorIdentity` is NOT widened and AD-002e is NOT disturbed** — the username is resolved at print time from the UUID the print path already holds, via `OperatorUserRepository.findActiveById`, so `operatorId()` remains the UUID and every audit payload keeps it. **(o) THE APPROVED ARTBOARDS ARE REDRAWN to match the code**, rather than annotated or left to AD-022 alone, so that a session reading `Design_3/` in isolation can no longer conclude the code is wrong. The as-approved drawings are not destroyed: they are recoverable in full at `git show 62f28fa:Design_3/backoffice/approved/printed-form-p*.dc.html`, and each redrawn file carries a comment saying so. Two things were found in the redraw and are recorded rather than silently fixed: the artboards ALREADY agreed with the code on (f), drawing «متزوجة»; and (e)'s wording overstates its own departure, since the artboard already carried « ج.س» and only the thousands grouping ever differed. **(o) RETIRES ONE SENTENCE EARLIER IN THIS CELL, explicitly rather than by implication:** "the artboards still draw the AZ lockup, the muted internal-use line and the AZ watermark, so a session reading only `Design_3/` will find three things the code deliberately does not do" is FALSE as of this ruling, and is left standing as written on the same precedent the sentence before it set — the change to it should be visible, not tidied away. What survives it is the RULE it expresses, which is why the departures are recorded here at all. Note the artboards are now AHEAD of the code on two points for the length of this session: p2 draws one spouse row and both footers draw a username, which (m) and (n) make true in the two commits that follow this one. | AD-013; AD-015; AD-018; AD-021; BL-004; BL-152; BL-154; BL-155; BL-161; BL-163; BL-164; R-016; R-018; R-054; docs/backoffice-redesign.md; 2026-09-16-s9-02-per-field-editing.md; 2026-09-18-s9-06-bank-branded-form.md; 2026-09-18-s9-07-cleanup-and-reconciliation.md |

## Open questions

Open, and blocking something. Each names what it blocks.

- **OQ-001 — the regulatory and compliance regime** (for example Bank of Sudan
  requirements) applicable to this data-update flow, and the consent position for
  processing this data at all. Ruled 2026-09-11: deferred to V2, not a V1 gate, on the
  grounds that the V1 group is controlled, trusted and known so consent is obtainable
  directly, and that these are the bank's own customers and the bank carries the duty. The
  exposure accepted for V1's duration: real identity documents and real national numbers go
  into the AWS account before the legal position for doing so exists, and if that position
  turns out unfavourable there is no remedy, because the audit store is append-only by
  design. The ask should still go to the bank now. Deferring the gate does not defer the
  question, and the answer has a lead time the V1 timetable does not control; a deferral
  that quietly becomes a decision never to ask is the failure mode this row exists to
  prevent.
- **OQ-008 — the back-office operator browser and hardware baseline.** The evergreen-browser
  assumption is unverified and the back office is built on it.
- **OQ-010 (document-type half) — are Sudan document types enabled on the project's Uqudo
  tenant?** SDN_ID enrolment has not been exercised on any tenant. The reasoning that used
  to stand in for it, that FIB's tenant is licensed for SDN_ID, does not transfer to this
  project's own tenant and no longer supports anything. `isEnrollmentSupported(SDN_ID)`
  returning true says nothing about a tenant either, being a compile-time SDK enum property.
  First real check is the bank's UAT with a card in hand. See BL-083, which gates the
  rollout on exactly that run. The provisioning half is answered: the project has its own
  tenant and it is the one the hardware runs used.
- **OQ-012 — the corrected administrative-divisions dataset.** The supplied file is interim:
  West Kordofan, Central Darfur and East Darfur are absent (a pre-2011 source, 15 states
  where Sudan has 18), the confidence column its own README describes is missing, and the
  States and Regions sheets still contain South Sudan with zero localities. The address
  structure is fixed, so this is a data swap rather than a design dependency. Ruled for V1
  as ship-as-is (R-014); the corrected dataset is V2 scope.
- **OQ-014 — the full dashboard metric list.** Refine when BL-001 is scheduled.
- **OQ-015 — does the bank have a mandated database platform standard?** The only input that
  would change AD-005. It does not block development, since we build on PostgreSQL either
  way and the port is bounded and costed. See R-027.
- **OQ-026 — what is Uqudo's billed unit, and may one access token serve multiple SDK
  launches and multiple end customers?** Three commercial questions, none answerable by
  research: is the metered unit a token generation, an enrolment, a face session or a
  verification; may a single tenant-scoped token legitimately back many launches across many
  customers; and is the token endpoint rate-limited or quota'd. Established 2026-09-06 that
  Uqudo publishes no billing, pricing, metering, quota or rate-limit documentation
  whatsoever, and the only public unit language is "verification counts" on their marketing
  FAQ, which names something other than the token. A product-owner statement that billing is
  per token generation is on record and uncorroborated either way. Now answerable by reading
  a document this project is itself party to, since OQ-010's answer means the contract is
  the project's own rather than FIB's. What turns on it: BL-063 (token reuse, the largest
  identified cost saving), the sizing of the lifetime token cap, and BL-036's cost
  justification.

Also open, and recorded under Delivery notes at the foot of this file because they are bank
commercial questions rather than build questions: OQ-016's residual (the Airtel gateway's
rate limit, whether it reports delivery or only acceptance, and whether it reaches all
Sudanese networks), OQ-019, OQ-020, OQ-021 and OQ-022.

### Answered

Kept as one line each so a closure is visible as a decision rather than a gap.

- **OQ-002 — data residency and hosting limits.** Closed 2026-09-07 by product-owner
  decision: out of scope for this delivery, the bank's concern rather than ours. One
  consequence stated once and not re-litigated: the staging stack runs in `eu-central-1` and
  now holds a real Sudanese national record and passport images. Closing the question does
  not remove the exposure, it assigns it to the bank; if the bank later requires in-country
  hosting, the remedy is a region migration of an encrypted RDS instance plus its KMS key,
  which is materially harder than choosing the region up front.
- **OQ-003 — deadline.** Answered 2026-08-19: tight, but delivery is a native Android app.
- **OQ-004 — expected scale.** Answered 2026-08-22: approximately 100,000 accounts, one
  submission each, over 12-18 months. See Constraints; supersedes AD-005's `[UNVERIFIED]`
  assumption of ~3 million accounts.
- **OQ-005 — OTP destination binding.** Answered by the journey specification: the customer
  supplies their own phone and email before any identity check and the OTP goes there. This
  is deliberate and accepted. The system produces a verified identity claim, a live person
  matching a genuine document that matches the Civil Registry, and the operator judges it;
  it does not establish that the document belongs to the account holder.
- **OQ-006 — the bank's Oracle server version.** Moot 2026-09-04 (AD-007): the core-banking
  check is an HTTPS/JSON middleware call, so no Oracle driver or server version is needed.
- **OQ-007 — app store distribution.** Answered 2026-09-07: Google Play has no
  Sudan-specific obstacle and the normal Play requirements apply, so distribution is not a
  blocker. The remaining technical readiness is BL-082. The immutable-package-id constraint
  stands and matters: the identifier chosen at first upload is permanent.
- **OQ-009 — Android-first launch.** Answered 2026-08-19 and superseded 2026-09-14: there is
  no Android-first any more, only Android. iOS is dropped and does not follow later.
- **OQ-011 — is it acceptable for real customer documents to pass through FIB's Uqudo
  tenant?** No longer applicable as posed, 2026-09-06: OQ-010 is answered and no customer
  data goes to FIB's tenant, so the third-party-disclosure question has lost its subject.
  That does not close OQ-001, which never depended on whose tenant it was.
- **OQ-013 — how operator accounts are provisioned and authenticated.** Answered 2026-09-02
  by AD-002e and closed at S4-05: a CLI provisioning tool and Spring Security session auth
  with a forced first-password-change. An HTTP admin surface is BL-023.
- **OQ-017 — is an alphanumeric sender ID registered with TPRA and the networks?** Closed
  2026-09-07: registration will not be requested, because the sender-ID relationship is the
  bank's with their provider. R-041 stays live as an accepted exposure.
- **OQ-018 — is the client bank state-owned or privately owned?** Closed 2026-09-07 as out
  of scope, and largely moot in any case since SMS is a domestic route. Worth recording that
  the research inferred this question partly from the Java package root we chose ourselves,
  which was never evidence about the bank; that root was renamed at S8-06 precisely because
  it was factually wrong.
- **OQ-023 — the Civil Registry's unknowns after the access route was confirmed.** Answered
  2026-09-04 by live discovery and then by product-owner decision on all twelve research
  questions, closing AD-002b. In summary: the certificate is trusted by the default store;
  no login is required; a malformed value returns an HTTP 400 HTML page, so the adapter must
  treat a non-JSON 400 as malformed input and never parse it; not-found and outage shapes
  are deliberately not distinguished further; `IDENTITY_NUMBER` is read from the found
  record so the echo check is meaningful; and the photograph is always present with no other
  field guaranteed. One thing worth keeping: probing was stopped because the repeated-digit
  value `00000000000` returned HTTP 200 with a populated record for a real person including
  a genuine photograph. Nothing from it was recorded and the capture was deleted. No further
  fabricated-value probes against this service without the bank's say-so.
- **OQ-024 — is the branch part of the core-banking account check?** Answered 2026-09-04: no.
  Branch is stored as profile data collected elsewhere in the journey and is not sent to the
  check. Done at S3-02, with the profile's own identity following at BL-032.
- **OQ-025 — the complete list of `CheckAccount` response codes.** Answered 2026-09-07 via
  the bank: `1`, `0` and `-1` are the complete list, and that includes dormant, closed and
  frozen accounts. Two consequences. The adapter's "any other integer is unmapped" branch is
  defensive-only rather than expected, which is the right way round. And there is no
  dormant, closed or frozen distinction at this boundary at all, because the bank maps its
  internal account states onto these three codes, so `1` means eligible for a profile update
  and the state behind it is the bank's business. **Therefore this system must not implement
  an eligibility check of its own.** A later reader seeing `1 = Account Found` could
  reasonably add one; it would duplicate a rule the bank already owns, decided on data we do
  not hold, and the two would drift apart silently.

## Open architecture decisions

Three. Everything else once listed here is closed and lives in the decisions log.

Note on numbering: **AD-002** is not a row and never was. It was an umbrella that split
into lettered parts. Older documents cite the bare `AD-002`; read it as "whichever
lettered part the sentence is about". The parts, all in the decisions log except the last:

- AD-002a — Uqudo integration. Closed.
- AD-002b — Civil Registry interface. Closed.
- AD-002c — messaging. The port is closed; provider selection is open, below.
- AD-002d — hosting. The provider half is closed (AWS); R-037 is what remained.
- AD-002e — back-office authentication. Closed, partly superseded by AD-013.
- AD-002f — reference-data delivery. Closed.
- AD-002g — audit store. Never answered separately: absorbed into AD-005.

- **AD-002c — provider selection for outbound messaging.** The port design is closed; what
  remains open is which providers sit behind it, a bank commercial decision rather than a
  library choice. SMS is effectively settled in practice: the bank supplied an Airtel Sudan
  route and the adapter ships (BL-080, AD-009). WhatsApp is stubbed until a WhatsApp
  Business Account exists, and email needs the bank's own SMTP relay and a sending domain
  (OQ-022). Gated on the bank commercial questions under Delivery notes.
- **AD-010 — the mobile app's public API hostname.** Not open as a technical question: the
  mechanism is researched and recommended, and the decisions-log row records it. What is
  outstanding is not ours, namely the bank choosing the hostname string and creating one NS
  record. V2-scoped, because the 2026-09-10 hosting decision puts V1 on the CloudFront
  default hostname. See BL-074, and do not re-raise it as a V1 bank ask; it has been
  re-raised once already.
- **AD-011 — Stage 1b channel selection and Stage 2 OTP verification: two screens or one,
  and how a customer corrects a wrong phone number or email address.** Opened 2026-09-10 and
  not settled. Research, evidence and a recommendation are in
  `docs/sessions/2026-09-10-research-ad-011-channel-otp-structure.md`; the decision is the
  product owner's. The report recommends keeping the two screens and adding the in-place
  email edit before the V1 APK, deferring any merge past release. The structural argument is
  that the two repairs are not symmetric: correcting the phone is a
  `POST /api/v1/contact-channels` re-entry that wipes every verified channel state and
  re-issues every code, while correcting the email is a `correctedEmailAddress` field on
  `POST /api/v1/otp/resend` touching only the email row. So the route boundary is doing real
  work, and a merged screen would put a phone-unverifying control one tap from an email typo
  fix. Four structures were evaluated, and the 5-inch constraint disqualifies the
  single-viewport merge specifically, since the merged content is roughly twice the tallest
  screen this app ships and BL-087 already produced that failure at that size. The in-place
  email edit was mandatory under every structure and shipped at S8-10 (BL-101), so closing
  AD-011 either way does not change that. BL-099 would be removed by construction under the
  one-route option.

## Delivery model

Delivery is phased (2026-09-03). Phase 1 was a working APK, backend and back office,
demonstrable end to end with every external system stubbed behind interfaces and no
hosting decided. Phase 2 is real integrations and hosting.

**V1 is PRODUCTION** (product-owner decision, 2026-09-10). It serves a controlled, fully
trusted group of real customers, with real accounts, real identity documents and real
messages. It is not a pilot, not a demo and not a test; the word "pilot" is retired from
these plan files, and where it survives it is part of a session-report filename, which is
an address rather than a claim. Two consequences decide how everything below is read.
Controlled and trusted bounds who uses the system, not what the system discloses: a
trusted group cannot misuse what it is given, but an identifier that leaves the group is
outside the group's control. And anything a real customer can reach is live, so a defect
that "only" strands a tester strands a customer of the bank, and a customer-facing
falsehood is a falsehood told by the bank.

Phase 1 scope cuts, 2026-09-04: the back-office export UI and the admin HTTP screen, now
BL-026 and BL-023. The backend export endpoint already exists and was not part of the cut.

### Phase 2 entry gates

What must be resolved before real production. Each is a separate, explicit block, and each
ruling is recorded with the exposure it accepts, because a deferral without its consequence
is how these get rediscovered.

- **BL-082 — Google Play readiness. Ruled 2026-09-10: a V1 blocker for any Play Store
  route.** A debug signing key, no AAB ever built, no hosted privacy-policy URL and no Data
  safety declaration. Does not block direct-APK distribution; blocks the store.
- **BL-072 — AWS account ownership. Deferred to V2.** Exposure accepted: the account stays
  rooted on a personal mailbox, so whoever controls that mailbox controls the account, its
  KMS keys and every backup of every identity document. A total-compromise path rather than
  a degraded one, because the keys that make encryption meaningful are in the same account
  as the backups they protect. The handover checklist is `docs/road-to-production.md` §3.6.
- **OQ-001 — the regulatory position. Deferred to V2, not a V1 gate.** Exposure and grounds
  under Open questions above.
- **AD-002d's remainder and OQ-002** remain prerequisites for production in the ordinary
  sense.

Two items that used to sit here and no longer do, recorded so they are not re-filed as
gates. **R-051 is dropped outright** by AD-017, not deferred: customer-session
authentication will not be built, the accepted exposure is permanent rather than
time-boxed, and BL-137 plus BL-041's residual are accepted with it. **BL-089 is not a
blocker**: core banking stays stubbed in V1 by the 2026-09-12 ruling, the real adapter
exists and is tested against captures, and the bank decides if and when to point us at
their middleware. The exposures of shipping on a stub are on BL-089 itself.

When a new risk or decision is found that must block production but not Phase 1, add it
here as well as to RISKS.md, so this list stays the single place to check before go-live.

### Hosting

**V1 runs fully on our AWS, with no bank involvement, on the CloudFront default hostname;
the bank subdomain is V2** (product-owner decision, 2026-09-10). Four consequences
accepted: the app resolves on an AWS-owned name the bank does not control and which
therefore cannot carry a bank certificate, since no ACM certificate can be issued for
`*.cloudfront.net`; a Sudanese bank's identity-document app asks real customers to trust a
hostname that does not carry the bank's name, which is the objection AD-010 raised and V1
accepts knowingly for one release; that name does not survive replacing the distribution
(BL-074), so the address is effectively single-use; and the V1 to V2 move is an APK rebuild
and redistribution rather than a config change, so every V1 customer must install a new
APK.

**Hosting ownership** (product-owner ruling, 2026-09-14): we host on AWS, we give the bank
a hosting-requirements specification, and the bank decides if and when it takes hosting
in-house. That is the whole of our obligation. Concretely: the bank domain is not on the V1
critical path, not a blocker, not an entry gate and not step 1 of anything; the deliverable
is `docs/bank-hosting-specification.md`, a specification for agreement rather than a
commitment by us to migrate anything; the handover checklist at road-to-production §3.6 is
a different thing and still owed, since §3.6 transfers the account we run while the
specification describes infrastructure they would run. What we do not do: chase the bank
for a domain, block a release on their infrastructure decision, or plan a migration nobody
asked for.

### V2 scope

Recorded together because each is something V1 ships without, and each states what V1
costs rather than what V2 gains.

| Deferred to V2 | What V1 costs, for the life of V1 |
|---|---|
| WhatsApp and email messaging | V1 is SMS-only. Every OTP, the submission notification and every status message reaches the customer by SMS or not at all. A customer whose number is unreachable (R-041's silent partial delivery by network, BL-094's non-Sudanese numbers) has no second channel, so a delivery failure is a journey failure with no fallback. The journey was designed for three independent channels; V1 runs on one. |
| The bank subdomain | V1 resolves on an AWS-owned CloudFront hostname the bank does not control and which cannot carry a bank certificate. Customers are asked to trust an identity-document app on a name that is not the bank's. The move to V2 is an APK rebuild and redistribution. |
| The corrected administrative-divisions dataset (R-014) | A resident of West Kordofan, East Darfur or Central Darfur cannot select their real state at any of the three stages that require one, so they abandon the journey or record a state they do not live in, and a data-update exercise knowingly collects wrong address data for those customers. |
| AWS account ownership (BL-072) | The account stays rooted on a personal mailbox, so whoever controls it controls the KMS keys and every backup of every identity document. |
| Customer-session authentication | Not deferred any more: dropped outright by AD-017. The identity-image endpoint serves a customer's passport and national-ID scans to anyone holding that profile's UUID, permanently. |
| National ID card testing (BL-083) | Not deferred: ruled 2026-09-11 that national ID stays offered in V1 with the rollout gated on one card-holder running a real card end to end. See BL-083 for what would trigger removing the option. |

## Delivery notes — outside the deliverable

Bank commercial questions gating messaging provider selection. Kept rather than deleted,
for whoever the bank hands that decision to, but they are not build questions for this
project and AD-002c's port does not depend on their answers. R-041 and R-043 in RISKS.md
match them.

- **OQ-016 — the bank's own SMS gateway.** Largely answered 2026-09-07: not the bank's own
  gateway, but a working Airtel Sudan endpoint with its own credentials and the sender id
  `SFB`. A domestic route, so the $427,000 international figure is off the table and the
  foreign-settlement and cross-border-PII questions do not arise for SMS. The request shape,
  the success and failure responses and the status-code behaviour are captured on BL-080,
  which is the implementation brief. **Still open:** the rate limit; whether it reports
  delivery or only acceptance, since the observed reply returns an `apiMsgId` at send time
  and nothing about delivery, which points to acceptance-only and would mean the journey's
  per-channel delivery result can only ever mean accepted; and whether it delivers across
  all Sudanese networks or only to its own subscribers, which is a go/no-go for using it as
  the sole channel and is entangled with R-041.
- **OQ-019 — in whose name is each messaging account held, and who pays?** The delivery
  model says the bank. A WhatsApp Business Account in particular must be the bank's, or
  every template needs re-approval on any provider change. If any account were ours we would
  carry the sanctions exposure and the credit risk on a large line item, and the bank would
  inherit a supplier it did not choose.
- **OQ-020 — can the bank settle in USD or EUR with a foreign supplier?** The obstacle for
  international options is de-risking rather than legal prohibition: whether the payment
  clears, not whether the provider will sell. A treasury question, to be asked before any
  provider conversation rather than after. Does not arise on the domestic SMS route.
- **OQ-021 — has compliance reviewed sending customer PII through a foreign messaging
  provider?** A status notification naming a rejection reason and a reference number is
  customer data crossing a border. Related to OQ-001. Does not arise on a domestic route.
- **OQ-022 — which domain sends customer email, and who controls its DNS for SPF, DKIM and
  DMARC?** Required under every email option, and it decides whether OTP mail lands in the
  inbox or in spam: the difference between email being a channel and being decoration.
