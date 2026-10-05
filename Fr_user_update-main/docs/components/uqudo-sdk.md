# Component: Uqudo eKYC SDK

Status: researched, spiked on a device (S1-02), parser rewritten against the observed shapes (R-034) · Last verified: 2026-09-04 · Sources:
docs/sessions/2026-08-19-research-ad-001-stack.md,
docs/sessions/2026-08-21-research-ad-002a-uqudo-integration.md,
docs/sessions/2026-09-02-research-uqudo-open-items.md,
docs/components/uqudo-api-findings.md,
docs/components/uqudo-face-api.openapi.yaml,
docs/components/uqudo-info-api.openapi.yaml

## What it is
Third-party eKYC provider (MEA-focused). Supplies its own full-screen UI for document
scan and facial liveness. Requires a commercial licence and a tenant. [OBSERVED
plugin README.md line 20]

## Evidence downgrade — read before citing FIB  [2026-09-04]
FIB's **mobile** app (`../FIB/mobile`) was never tested in production — product-owner
statement, 2026-09-04. Every `[OBSERVED ../FIB/mobile/...]` citation in this card is therefore
evidence of what FIB *wrote*, not of what Uqudo's mobile SDK *does*; treat each as
`[UNVERIFIED]` for SDK behaviour. FIB's **web** app (`../FIB/Web_Code`, Uqudo Web SDK 4.x,
source maps in `assets/*.map`) IS in production on the same tenant; it is valid evidence for the
platform-independent **result shape** (`data.documents[0].scan.front/back`, `verifications[0].*`
scores, `faceImageId`, SDN_ID in production) and for nothing about mobile SDK runtime behaviour.

## Docs — machine-readable
- ⚠️ URL RESTRUCTURE 2026-09-04: every `docs.uqudo.com/<path>` URL cited in this card now 404s.
  Live prefix `https://docs.uqudo.com/docs/kyc/...`. Index `https://docs.uqudo.com/docs/llms.txt`;
  sitemap `https://docs.uqudo.com/docs/sitemap.md`; whole corpus in one file
  `https://docs.uqudo.com/docs/llms-full.txt`. [DOC 2026-09-04]
- ⚠️ **`llms-full.txt` is NOT a reliable absence oracle via WebFetch (2026-09-06).** It was
  recommended here as "the fastest way to settle an absence question" — that advice is now
  withdrawn. A sweep of it returned "NOT PRESENT" for `expires_in`, a string that had just been
  read verbatim off `uqudo-api/authorisation.md` minutes earlier: the file is being truncated
  before the fetch tool's model sees all of it, so a negative result from it means nothing.
  **Settle absence questions by fetching the individual candidate pages and naming them.**
  [OBSERVED 2026-09-06]
- Index: https://docs.uqudo.com/llms.txt  [DOC — dead since 2026-09-04, see above]
- Any page: append `.md` to the URL for a markdown variant. [DOC]
- Caveat: `integration/flutter/*` now carries real per-flow pages
  (`enrolment-flow.md`, `face-session-flow.md`) but they are thin — the technical
  detail still lives under `integration/android/*`. `integration/web/*` documents the
  separate 4.x web line and has a DIFFERENT error-code set; never map mobile codes
  from it. `integration/dotnet.md` remains a pointer only. [OBSERVED 2026-08-21]
- pub.dev dartdoc gives version-stamped signatures without downloading the package:
  https://pub.dev/documentation/uqudosdk_flutter/latest/ [DOC]
- Several API pages reference an OpenAPI/Swagger spec whose link is absent from the
  markdown variants. Locate it via the Customer Portal — it would close the Face API
  request-body gap. [OBSERVED 2026-08-21]
- **Face and Info OpenAPI specs obtained and filed 2026-08-22:**
  `docs/components/uqudo-face-api.openapi.yaml` and
  `docs/components/uqudo-info-api.openapi.yaml`. Findings folded into this card; full
  writeup at `docs/components/uqudo-api-findings.md`.
- The country-specific OCR field pages under `uqudo-api/scan/*` are SHARED documentation:
  they define both the Scan API response and the SDK result's `scan.front`/`scan.back`,
  differing only in the face image. That is why they read as Scan-API-shaped. [DOC
  scan-object, 2026-09-02]
- Re-checked 2026-09-02: the **Scan API OpenAPI spec is NOT in `llms.txt`** — the index has
  Scan/Face/Info/Lookup/Background-Check/Authorisation as prose pages only, no spec URL
  anywhere. The Customer Portal remains the only known route. [OBSERVED llms.txt]

## Platform matrix (as of 2026-08-19)
| Platform | Package | Version | Released |
|---|---|---|---|
| Android native | `io.uqudo.sdk:Uqudo` @ https://rm.dev.uqudo.io/repository/uqudo-public/ | 3.10.0 | — [DOC] |
| iOS native | pod `UqudoSDK` + `OpenSSL-Universal` 3.3.3001 (exact) | 3.10.0 | — [DOC] |
| Flutter | `uqudosdk_flutter` (pub.dev) | 3.10.0 | 2026-08-10 [OBSERVED] |
| React Native | `uqudosdk-react-native` (npm) | 3.10.0 | 2026-08-10 [OBSERVED] |
| Capacitor | `uqudosdk-capacitor` (npm) | 3.10.0 | 2026-08-10 [OBSERVED] |
| Cordova | `uqudosdk-cordova` (npm) | 3.10.0 | 2026-08-10 [OBSERVED] |
| Web | `uqudosdk-web` (npm), WASM, separate 4.x line | 4.1.3 | 2026-06-24 [OBSERVED] |
| .NET | doc page + GitHub sample only — **nothing on NuGet** | n/a | [OBSERVED] |

Cadence: a release every ~6–10 weeks since 2025-05; all mobile wrappers ship the same
day. [OBSERVED pub.dev + npm publish dates]

## Platform floors
- Android `minSdkVersion` **23** (since 3.8.0), `compileSdk` **≥ 35** (since 3.6.0),
  `targetSdk` 34 mandatory since 3.0.0. [OBSERVED CHANGELOG + plugin build.gradle]
- Android ABIs: **armeabi-v7a and arm64-v8a only**. x86_64 builds but the SDK does not
  function. Re-verified 2026-09-03: changelog 2.5.0's wording ("the SDK part, armeabi-v7a
  and arm64-v8a remain the only supported architectures") is still the current text and
  nothing between 2.5.0 and 3.10.0 supersedes it. [DOC changelog/mobile-sdk.md, re-checked
  2026-09-03]
- **Uqudo excludes emulators by name, in the current docs** — a stronger source than the
  2.5.0 ABI note: "The Uqudo SDK is heavily dependent on the mobile camera for scanning
  capabilities; therefore, it is not supported on Android emulators." The same page states
  laptop camera quality is inadequate for testing. → a physical arm64 handset is required;
  no cloud device farm, virtual device or webcam relay substitutes.
  [DOC integration/android.md, 2026-09-03]
- No ABI, emulator, minSdk or `abiFilters` statement exists on `prepare-environment.md`;
  the changelog is the only place Uqudo states the ABI restriction. [OBSERVED 2026-09-03]
- iOS **12.0**; **13.0+** only if NFC is used. arm64. [OBSERVED podspec; DOC]
- `targetSdk 36`: compileSdk (what the SDK was built against) and targetSdk (the runtime
  behaviour contract the app opts into) are independent. Gradle requires only that our
  compileSdk ≥ the AAR's declared minCompileSdk (35). Building at compileSdk/targetSdk
  36 is therefore not blocked. What is unverified is whether the SDK's full-screen
  camera UI behaves correctly under API 36 runtime behaviour changes — resolved by
  S1-02 on a physical arm64 device, not by vendor correspondence. [UNVERIFIED]
  ⚠️ Android 16 behaviour changes are enforced by the PLATFORM, so a device below API 36
  cannot exercise them at all. S1-02 on an Android 14/15 handset answers every other
  measurement and leaves R-006 untouched. The spike device must run API 36+. [DOC
  developer.android.com/about/versions/16/behavior-changes-16, 2026-09-03]

## Sudan
`DocumentType` includes `SDN_ID`, `SDN_DL`, `SDN_VL` at **3.10.0** (81 enum values;
3.9.0 added OMN_ID_NATIONAL_MRZ, OMN_ID_INVESTOR, PASSPORT_OMN; nothing removed).
[DOC pub.dev dartdoc 3.10.0, verified 2026-08-21] Also present at 3.8.0+1
[OBSERVED lib/uqudosdk_flutter.dart lines 28-30]. Enum presence is NOT tenant
enablement — call `UqudoIdPlugin.isEnrollmentSupported(DocumentType.SDN_ID)` at
S1-02. FIB uses SDN_ID + PASSPORT in production on ITS tenant. [OBSERVED]

### Sudan document field pages — the parser contract  [DOC 2026-08-22]
Sources: `scan/country-specific-ids/sdn_id-sudan-id` and `scan/passports/sdn-sudan`.

**⚠️ The Civil Registry lookup key is `identityNumber`, NOT `documentNumber`.** Both
document types expose both fields; `documentNumber` is the MRZ value — the passport
booklet number or card number. Querying the registry with it would fail, or succeed
against the wrong record. Both accepted document types carry `identityNumber`, so no
customer reaches the Civil Registry review stage without a lookup key.

**SDN_ID exists in two card versions** — the parser must handle both:

| | Previous version | Latest version |
|---|---|---|
| Front | faceImage · name (Arabic) · dateOfBirth(+Formatted) · placeOfBirth (Arabic) · **identityNumber** · address (Arabic) · occupation (Arabic) | same, **plus bloodType** |
| Back | placeOfIssue · dateOfExpiryFull(+Formatted) · issueDate(+Formatted) · MRZ block | same, **plus name (English)** |

MRZ block, both versions: `secondaryId` · `primaryId` · `dateOfBirth(+Formatted)` ·
`dateOfExpiry(+Formatted)` · `documentNumber` · `nationality` · `issuer` · `sex` ·
`documentCode` · `mrzText` · `mrzVerified` · `opt1` · `opt2`.

**SDN passport — single field set:** `issueDate(+Formatted)` · `placeOfBirth` ·
`dateOfBirthFull(+Formatted)` · **`fullName` (English)** · **`fullNameArabic`** ·
**`identityNumber`** · `placeOfIssue` · `faceImage` · the same MRZ block, `opt1` only.

**The OCR field set is per-document-type, not generic.** The passport uses `fullName` /
`fullNameArabic`; SDN_ID uses `name` — Arabic on the front, English on the back and only
in the latest card version. An older SDN_ID card yields no English name and no
`bloodType` — harmless, since the provenance mapping takes both names from the Civil
Registry regardless.

**SDN_ID closed by evidence, not by a scan (2026-09-04)** — no card was available at S1-02
and none will be before the solution is finalised (product-owner decision):
- The passport JWS proved the field pages are a **subset guarantee**: every documented field
  appeared, spelled as documented, plus undocumented extras. The SDN_ID page is the same page
  family and pipeline → build the parser from its tables, tolerate extras. [OBSERVED S1-02]
- Side placement: `scan-object` gives no rule; the country page's front/back tables are the
  sole authority, corroborated by a two-sided UAE_ID sample on `uqudo-api/scan.md` (identity
  front, MRZ back) and by FIB's **production web app**, which reads SDN_ID's Arabic name from
  `scan.front.name` and the English name from `scan.back` — it reads `back.fullName`, the docs
  say `back.name`; accept either. [DOC 2026-09-04; OBSERVED ../FIB/Web_Code source maps]
- **`nameEn` does not exist** anywhere in Uqudo's docs — the S3-12 stub's key and its
  `cardVariant` inference are both wrong. Infer the card version from **`scan.front.bloodType`**
  (front OCR always runs); no discriminator is documented. Caveat: a latest card whose blood-type
  line failed OCR classifies as "previous" — harmless for provenance, never record it as a fact
  about the document. [DOC — absence 2026-09-04]
- **Fail closed on a missing `identityNumber`** (the Civil Registry key). A side mix-up cannot
  silently substitute `documentNumber` — different side, different name — so the failure mode is
  a null key, not a wrong lookup.
- SDN_ID enrolment remains unexercised on the **mobile** SDK and on **any** tenant — first real
  check is the bank's UAT, with a card in hand. Residual accepted. **Corrected 2026-09-06:** this
  bullet used to rest on "FIB's tenant is licensed for SDN_ID (production web app)". That remains a
  true fact about FIB, but it is no longer *our* evidence — the project runs on its own tenant
  since S5-07/S5-08 (OQ-010), and another tenant's licensing does not transfer. What the project's
  own tenant is proven licensed for is PASSPORT enrolment and face sessions, nothing more.

**`mrzVerified`** is an MRZ checksum boolean present on both document types. Store it as
an integrity signal on the profile and in the audit trail.

SDN_ID also carries `address` and `occupation` in Arabic on the card. We also collect
both from the customer; the provenance rule keeps them customer-entered — a card address
may be years stale and the campaign exists to refresh it — but the document values exist
if the bank ever wants a cross-check.

## Language / RTL
- SDK renders its own UI. **Which languages ship built in — including whether Arabic is
  among them — is NOT PUBLISHED BY UQUDO.** Checked 2026-09-02: android + ios + web
  `ui-customisation/text-and-language`, `general-strings`, `no-code-kyc/text-settings`,
  `readme/integration-options/uqudo-sdk`, the full pub.dev changelog, and the plugin
  README — every one documents only the override mechanism, none names a shipped language
  or a default. [DOC — absence, checked across every page that could plausibly state it]
- ⚠️ Uqudo's public FAQ says *"We support a total of 98 languages including English, Arabic,
  and Latin"* — that is **document OCR, not the SDK UI**. Do not cite it for this question.
  [DOC https://uqudo.com/faq/]
- **This is an artifact property, not a runtime one — does NOT need S1-02 or a device.**
  The Android AAR's `res/values-<lang>/` folders answer it by unzipping a file. Do it when
  `io.uqudo.sdk:Uqudo:3.10.0` is first resolved into the Gradle cache (dependency-add time),
  not at S1-02. Not on this machine today: `modules-2\files-2.1\io.uqudo.sdk\` does not
  exist in the local Gradle cache. [OBSERVED local Gradle cache, 2026-09-02] iOS equivalent:
  `.lproj` folders in the `UqudoSDK` pod's resource bundle, needs `pod install`, gated on
  AD-003.
- Worst-case budget scope: general ≈158 strings (most of them per-document-type labels we
  don't need), scanning ≈33, face-recognition ≈51; Reading/Lookup/Background-Check pages are
  out of scope, those flows being unused. Realistic surface ≈90–120 strings. Recommendation:
  budget as if Uqudo ships nothing — even a shipped Arabic set would be Uqudo's own wording,
  and the most visible screens in an Arabic-first journey likely want a bank-authored override
  regardless. [DOC android text-and-language pages; counts machine-extracted, approximate]
- `setLocale` implies nothing about built-in translations — it forwards to
  `UqudoSDK.setLocale(context, locale)`, which resolves against the MERGED resource set, so
  our own `values-ar/strings.xml` overriding the `uq_*` keys is the delivery mechanism either
  way. [OBSERVED UqudoIdPlugin.kt lines 175-179; DOC general-strings.md]
- **HOW `setLocale` actually reaches the SDK's resources — settled S8-09, and it corrects the
  reading that the flag is inert.** `UqudoSDK.setLocale(context, locale)` only writes
  `key_locale` into the `uqudo_preferences` SharedPreferences. The SDK's base Activity
  `io.uqudo.sdk.C.attachBaseContext` reads it back, constructs `Locale(that)`, calls
  `LocaleList.setDefault` + `Configuration.setLocales` (API ≥ 24; plain `Configuration.locale`
  below), and returns `createConfigurationContext(...)`. So EVERY SDK activity has been
  resolving its resources under `ar` since S1-02 — the English UI was the missing `values-ar`
  alone, never a dead flag. **Ordering constraint: `UqudoSDK.init()` REMOVES `key_locale`, so
  `setLocale` must be called AFTER `init()`.** Both plugin wrappers already do.
  [OBSERVED sdk-bundle-Uqudo-3.10.0.aar bytecode, 2026-09-08]
- **The AAR's real string counts, measured — R-002's ~176 was doc-derived and too high.** In
  3.10.0's `values.xml`: `uq_face_*` **52**, `uq_error_*` **51**, `uq_scan_*` **38**,
  `uq_button_*` **1**, `uq_help_*` **0** (those keys are dimens and styles, not strings);
  `uq_read_*` 208 and `uq_lookup_*` 72 belong to flows this journey does not enable. The
  shipped override set is **133 keys** —
  `mobile/android/app/src/main/res/values-ar/strings.xml`. [OBSERVED 2026-09-08]
- **Artifact check done, 2026-09-08:** the resolved AAR carries **no `values-<lang>` folder of
  any kind**. Its `res/` has `values` and `values-night-v8` beside the usual `drawable*`, `font`,
  `layout`, `navigation`, `raw` and 27 screen-width `values-sw*dp-v13` buckets — not one language
  bucket among them. Uqudo ships zero translations, confirmed on the artifact rather than
  inferred. [OBSERVED]
- **AAB caveat:** because the Arabic lives in the APP module, an Android App Bundle would let
  Play deliver only the language splits matching the DEVICE locale — an English-locale handset
  would install without the `ar` split and the SDK would fall silently back to English.
  `android.bundle.language.enableSplit=false` in `mobile/android/gradle.properties` prevents
  that. The pilot ships an APK and is unaffected either way.
  Full writeup: docs/sessions/2026-09-02-research-uqudo-open-items.md
- Strings are overridable per platform (Android string resources; iOS `uq-text.plist`).
  [DOC]
- The changelog repeatedly fixes RTL layout (3.1.3 Kurdish, 3.4.1 iOS, 3.6.0 iOS), so
  RTL is supported. [OBSERVED CHANGELOG]
- iOS note: setting `UIView.appearance().semanticContentAttribute = .forceRightToLeft`
  globally used to affect the SDK's navigation bar; fixed in 3.6.0. [OBSERVED CHANGELOG]

## Results — read this before writing any parsing code
- `enroll()` and `faceSession()` return a **JWS compact-serialization string**
  (`header.payload.signature`, RS256), **not JSON**. Parse and verify **server-side
  only**; JWKS at `https://id.uqudo.io/api/.well-known/jwks.json`. [DOC
  sdk-result/validation-and-parsing]
- Images are **references, not bytes**: `frontImageId`, `backImageId`,
  `frontFrameImageId`, `backFrameImageId`, `faceImageId`, `face.auditTrailImageId`.
  Retrieve separately with the access token. [DOC scan-object / face-object]
  **R-024 CLOSED 2026-09-02.** `scan-object`'s `front`/`back` attributes read: *"See API
  Documentation for the Scan / OCR fields. The only exception is the face image that is not
  included directly in the result but as a reference as per the `faceImageId` described
  above."* So the SDK result and the Scan API share one OCR field contract, with the face
  image as the single documented exception — the base64 `faceImage` on the country pages
  belongs to `POST /api/v1/scan` (query param `exportFaceImage`, default true), an API this
  journey never calls. The portrait round trip (download by id → re-upload bytes to
  `POST /api/v1/face`) is real and stage 10 is built correctly. [DOC scan-object;
  DOC uqudo-api/scan.md] Flutter does not differ: the plugin returns the native result
  string untouched [OBSERVED plugin UqudoIdPlugin.kt line 866, UqudoIdPlugin.dart lines 24-28]
  and Uqudo publishes one platform-independent `sdk-result/*` tree [OBSERVED llms.txt].
  Full writeup: docs/sessions/2026-09-02-research-uqudo-open-items.md
- ⚠️ The FIB reference parses the JWS **client-side**
  (`../FIB/mobile/lib/core/uqudo/uqudo_service.dart` lines 34–44) [OBSERVED]. **Do not
  copy this.** Design our mobile↔backend contract to forward the raw JWS.

## Backend surface (framework-agnostic, plain HTTPS)
- Token: `POST https://auth.uqudo.io/api/oauth/token`, form-encoded,
  `grant_type=client_credentials` + client_id + client_secret. Returns access_token
  (itself a JWS), token_type=bearer, `expires_in` **1800**, scope, jti. Backend only —
  the docs forbid this call from a mobile app. [DOC uqudo-api/authorisation.md]
  Corroborated [OBSERVED ../FIB/.../UqudoServiceImpl.java lines 46-73].
- The token is TENANT-scoped, not per-customer. **[DOC — absence, re-confirmed 2026-09-06]**
  No per-user, per-session or per-device scope is documented anywhere; corroborated by the
  six capability-only scopes observed at S1-02. It must nevertheless be handed to the device
  — the SDK requires it — so a tenant-scoped bearer token lives on the handset for up to
  ~1859s (the real `expires_in`; the documented 1800 is not exact). Inherent to the SDK.
  - ⚠️ **"Mint at point of use, never persist to disk, drop after the call" is OUR guidance,
    not Uqudo's.** It sat inside this `[DOC]` paragraph until 2026-09-06 and read as a vendor
    requirement; no Uqudo page states any of it. [UNVERIFIED as a vendor rule] Only "the docs
    forbid this call from a mobile app" is `[DOC]` in this block. Corrected because BL-063
    was filed believing it would have to overturn committed vendor guidance — it does not.
- Image: `GET {apiBase}/api/v1/info/img/{id}`, `Authorization: Bearer …`, returns raw
  binary. [DOC uqudo-api/info.md] Can return "Resource not found or expired". [DOC]
  [OBSERVED ../FIB/.../UqudoServiceImpl.java lines 76-108]
  **`404` description confirms why: "Images are kept only for the duration of the session,
  30 minutes."** [DOC Info API OpenAPI, 404 description] **`200` content type is
  `image/jpeg`** — satisfies the Face API's JPEG/PNG requirement, so the portrait can be
  piped straight through. [DOC Info API OpenAPI]
- Session result server-to-server: `GET {apiBase}/api/v1/info/{jti}`.
  Purge early: `DELETE {apiBase}/api/v1/info/{jti}` — **"The session id is the value of the
  `jti` property of the JWS returned by the uqudo SDK"**, confirming `jti` = session id.
  [DOC uqudo-api/info.md; DOC Info API OpenAPI, DELETE description] Worth calling
  deliberately, not just for housekeeping: purging once the backend has downloaded and stored
  every image minimises how long customer identity data sits in the vendor's cache at all.
  **Rationale narrowed 2026-09-06:** the stronger form of this — that the data sat in *another
  bank's* account (R-001) — ended when the project's own tenant came into use. The control is
  unchanged and still worth calling. Both `GET /api/v1/info` variants exist only for the
  QR-code flow, which this journey does not use — of the whole Info API only `/info/img/{id}`
  and the `DELETE` are relevant.
- Face session: `POST {apiBase}/api/v1/face` uploading a reference image, returns a
  session id for the SDK. **Session and image auto-deleted after 10 minutes.**
  [DOC uqudo-api/face.md] **Request body CLOSED — R-023 retired.** Image bytes only, never
  an id: `multipart/form-data` with `idPhoto` as binary, or `application/json` with
  `idPhoto` base64-encoded (`format: byte`). Limits: `413` above 5 MB, `415` outside
  JPEG/PNG. Response `201` is `{ "sessionId": "..." }`, "valid for 10 minutes" — confirming
  the field name and the 600-second window from a second source. [DOC Face API OpenAPI]
- Also on that API: `POST /api/v1/face/match` (server-side 1:1, NO liveness),
  `POST /api/v1/face/one-to-n/{search,insert}`, `DELETE …/search/{id}`. [DOC]
  **`FaceMatchRequest.minimumMatchLevel` documents "the default value is 3"** on a 1–5
  scale — Uqudo's own default, and the starting threshold the backend enforces server-side
  against `face.matchLevel` (the check AD-002a forbids letting the SDK enforce).
  [DOC Face API OpenAPI]
- `{apiBase}` is portal-supplied per tenant — configuration, never a constant. [DOC
  uqudo-api/scan.md]
- Client credentials live **only** in the backend. Never in the app.

### Token reuse, metering and limits — the whole answer is "unaddressed"  [2026-09-06]
Researched for BL-063 (`@agent-researcher`, 2026-09-06). Every absence claim below rests on
individually fetched pages, named inline — **not** on an `llms-full.txt` sweep, for the reason
in *Docs — machine-readable*.
- **Reuse across launches and across customers: NOT ADDRESSED.** No Uqudo page permits or
  forbids one token backing many SDK launches, or serving many end customers. The only
  per-launch-freshness rule Uqudo publishes concerns the *sessionId*: "always generate a new
  session ID each time you initiate the SDK" — tokens are not mentioned.
  [DOC sdk-result/security-and-best-practices.md] `setToken(token)` is documented as nothing
  but "See Authorisation". [DOC android/enrolment-flow.md, android/face-session-flow.md]
- **[OBSERVED] Reuse is live in production on FIB's tenant.** (Which this project borrowed until
  its own tenant came into use — OQ-010, answered 2026-09-06. The observation is about FIB's
  setup and stands regardless of that; what it is no longer is evidence about *our* tenant's
  tolerances.)
  `UqudoServiceImpl.getToken()` returns a Redis-cached tenant-wide token, minting only on a
  miss and caching with TTL `expires_in − configured margin`; `UqudoController` exposes it as
  `GET /token`, and the production **web** app calls that per SDK launch. One token, many
  launches, many customers, not blocked by Uqudo. Note this is the *web* app, so the
  *Evidence downgrade* section above (which scopes to FIB's **mobile** app) does not apply.
  Absence of a technical block is not permission — see the billing bullet.
- **Billing / metering unit: ABSENT.** No pricing, billing, metering, quota or credit page
  exists in the docs corpus at all. The only unit language published anywhere: "keep track of
  your license usage" [DOC readme/customer-portal.md] and "discounts for larger **verification
  counts**" [DOC uqudo.com/faq]. **Neither names the token as a unit**, and "verification"
  points elsewhere. A product-owner statement that billing is per token generation is on
  record (2026-09-06) with **no public corroboration and no public contradiction**. Settle it
  from the contract, never from research — **OQ-026**. [UNVERIFIED]
- **Rate limits / quotas / 429: ABSENT.** No 429 in `uqudo-api/scan.md`'s status table (200,
  400, 401, 403, 413, 415, 500), none in either filed OpenAPI spec, no rate-limit section on
  `uqudo-api.md`. [DOC — absence] This cuts both ways: no published limit is also no published
  assurance, so it argues neither for nor against the current mint-per-call design.
- **Caching / refresh-ahead guidance: ABSENT.** Only `expires_in` and "get the token from your
  backend, not the app". [DOC uqudo-api/authorisation.md]
- **Mid-scan token expiry: NOT DOCUMENTED.** Mobile has no token-specific `SessionStatusCode`
  (`SESSION_EXPIRED` refers to the enrolment/face *session*, not the token); a token failure
  would presumably surface as `UNEXPECTED_ERROR`, but no Uqudo sentence says so. [UNVERIFIED]
  The **web 4.x** line does have `UNAUTHORIZED — "Token expired or invalid"`, raised from an
  HTTP 401 at call time rather than a client-side `exp` check [DOC web/operation-error.md;
  OBSERVED `uqudosdk-web-*.js.map`] — **do NOT map web error codes onto mobile.** With `iat`
  stamped at scan completion (S1-02), a server call does occur at the end of the flow, so a
  token dying mid-scan would plausibly fail there, after the customer has done the work.
  Coherent, unproven — and the exposure is small: ~52 s scan against ~1859 s token life.

## Face matching — how it actually works  [DOC 2026-08-21]
Face matching is not a flag and not a backend comparison. A **Face Session** IS the
match: the backend uploads a reference image via POST /api/v1/face, gets a session id,
the app passes it to `FaceSessionConfigurationBuilder.setSessionId()`, and the SDK
performs "liveness check and face matching against the provided image".
[DOC uqudo-api/face.md]

The reference image must be `documents[0].scan.faceImageId` — the portrait Uqudo
extracted FROM the document — not `frontImageId`. FIB uploads frontImageId
[OBSERVED ../FIB/mobile/lib/features/liveness/liveness_screen.dart lines 45-51];
whether Uqudo crops a face from a full card is UNVERIFIED. Use the portrait.

Two routes exist and we chose the second:
- `enroll()` + `enableFacialRecognition()` — match inside the scan session. Rejected:
  captures the face before the stage 9 review screen and merges the two retry budgets.
- `faceSession()` + backend-created Face Session — matches the journey. CHOSEN.
- `POST /api/v1/face/match` server-side — no liveness at all. Not viable alone.

## ⚠️ Uqudo does NOT return a liveness score  [DOC 2026-08-21 — corrects R-016]
There is no liveness field of any kind in the JWS. Checked face-object, common-object,
verification-object and the data-structure index. [DOC] Independently confirmed
[OBSERVED ../FIB/docs/research/2026-06-10-uqudo-sdk-research.md lines 116-147].

The face object is ONLY: `auditTrailImageId`, `auditTrailImageIdChecksum`,
`matchLevel` (int 1-5), `match` (bool), `oneToNVerificationId`,
`oneToNVerificationMatch`. [DOC face-object.md] Published sample:
`"face": {"auditTrailImageId":"…","matchLevel":5,"match":true}`. [DOC common-object.md]

Liveness is a GATE INSIDE THE SDK, not a result. A liveness failure re-prompts the
user; exhausting maxAttempts (1-3, default 3) throws
SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS and **no JWS is produced**.
[DOC android/face-session-flow.md]

So the two failure modes arrive through different channels:
- Face-match failure → a SUCCESSFUL faceSession() returning a JWS with match:false and
  a matchLevel. Signed, scored, recordable. The fraud signal survives.
- Liveness failure → a PlatformException. No score, no artifact.

## ⚠️ Do NOT call FaceSessionConfigurationBuilder.setMinimumMatchLevel()  [revised 2026-09-04]
Still the rule — but the protection it buys is smaller than this card previously claimed.
- **[OBSERVED plugin source] "Leave it at the -1 default" has a proven mechanism.**
  `FaceSessionConfiguration.dart` initialises `minimumMatchLevel = -1`, `maxAttempts = -1`;
  `UqudoIdPlugin.kt` lines 451–456 forward each ONLY `if (... > 0)`. The native setter is
  **never invoked** — we run in the native SDK's own unconfigured state. Same on the enrolment
  side (`FacialRecognitionConfiguration.dart`, all four ints `-1`).
- **No default match level is documented** for the SDK or the Face Session API (config tables:
  Default = "None"). `POST /api/v1/face` has **no threshold parameter** (`FaceSessionInitRequest`
  = `{idPhoto}`). The documented "3" belongs to `POST /api/v1/face/match`, a different endpoint
  we never call — 3 is OUR backend threshold. [DOC — absence; OBSERVED Face API OpenAPI]
- ⚠️ **The mobile SDK gates on match INTERNALLY.** The AAR ships
  `uq_face_dialog_face_not_match = "Your face didn't match"` beside `uq_face_try_again` and
  `uq_face_retry_message` [OBSERVED sdk-bundle-Uqudo-3.10.0.aar res/values/values.xml]; the web
  4.x line has the same `FACE_LIVENESS_FAILED` / `FACE_NO_MATCH` split and FIB's production web
  integration sets no threshold and never reads `match` [OBSERVED ../FIB/Web_Code source maps].
  Most defensible reading: a non-matching face is retried by the customer and, after
  `maxAttempts`, terminates as `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` — the
  **same channel as a liveness failure** — so a `match:false` JWS may be **unreachable on the
  success path**. Both S1-02 runs (own face) returned `match:true/5`, consistent with either
  reading. [UNVERIFIED — strongly circumstantial; no Uqudo sentence states it; only a second
  person's face closes it. R-016 reopened 🔴.]
- Setting the threshold can only tighten an existing in-SDK gate; leaving it unset remains
  correct. Enforce 3 server-side from whatever `matchLevel` we do receive.

## returnDataForIncompleteSession — the only documented route to a failed-match artifact  [2026-09-04]
- Face session: "if the user or the SDK drops the session before completion and there was at
  least one failed facial recognition attempt, the SDK will return the partial data together
  with the SessionStatus object in the data attribute, that will contain the same JWS string
  that is returned in a successful scenario" [DOC android/face-session-flow.md]. Enrolment:
  same, conditioned on "the user passes at least the scanning step" [DOC android/enrolment-flow.md].
- **Delivered on the FAILURE channel**: Android `RESULT_CANCELED` → `SessionStatus.data`; in
  Flutter that is the `data` field of the `{code, message, task, data}` error object this card
  already documents — this is what populates it. Exposed at `lib/uqudosdk_flutter.dart:759`,
  bridged at `UqudoIdPlugin.kt:476`; Uqudo's own Flutter sample enables it. [OBSERVED]
- **[UNVERIFIED] whether that JWS carries `face.match:false` / a populated `face.error`** — not
  documented anywhere. Worst case: an audit artifact without the match detail.
- **Decision (product owner, 2026-09-04): ENABLE it on the face session — BL-028.** S5-08 sets
  the flag; `reportLivenessTerminated` gains an optional partial JWS the backend verifies with the
  quarantined parser and stores. Fallback to "leave it off" if the cost exceeds one builder call
  plus one optional field.
- **Backend half built at R-034 (2026-09-04):** `POST /api/v1/liveness/terminated` takes an
  optional `partialJws`; `UqudoClient.verifyAndParseIncompleteFaceSession` applies the same
  signature/`exp`/`data.sessionId` checks and tolerates an absent `face`; the artifact is
  hash-chained on the `liveness_attempt_terminated` event with the match detail (nullable) in the
  payload. The spike screen's builder calls the flag. S5-08's real screen still owes both.

## Result validity and the stale-artifact case (R-012, R-021)  [2026-08-21, updated 2026-08-22]
The 1800s ACCESS TOKEN is irrelevant to whether a JWS verifies — verification is
JWKS-based and the token plays no part. [DOC validation-and-parsing.md] Three other
clocks matter:
- `exp` in the JWS: claim documented as present, **value NOT documented anywhere**.
  [UNVERIFIED — measure exp-iat at S1-02]
- **Uqudo resource retention: documented as 30 minutes, MEASURED as 2 hours.** "Images are
  kept only for the duration of the session, 30 minutes" [DOC Info API OpenAPI, `/info/img/{id}`
  404 description] — **superseded by S1-02 (see "S1-02 observed" below): images live exactly as
  long as the enrolment JWS, 2 hours from `iat`.** This is the clock that actually binds a
  dropped-upload retry, not the 1800s access token — R-012's original premise (that the 1800s
  token would cause the backend to reject the JWS) was wrong; R-012 retired at R-034.
- Face session + its image: **10 minutes**. [DOC face.md; confirmed by the Face API
  OpenAPI's `sessionId` description] Expires before the SDK runs.

Design rule: never decide staleness by a timer. Verify → download images → checksum.
`exp` past → ARTIFACT_EXPIRED. Images 404 → IMAGES_UNAVAILABLE. Neither counts against
the retry budget. **A JWS can verify perfectly and still be worthless because the
images are gone — so the accept decision must come AFTER the image download, not
after verification.** The practical retry window for a dropped upload is therefore
**2 hours — the enrolment JWS lifetime, measured at S1-02 (not the documented 30 minutes)** —
and Stage 13's resume copy states that.

## JWS verification  [DOC 2026-08-21]
- JWKS `https://id.uqudo.io/api/.well-known/jwks.json` — CONFIG, never a constant (R-007)
- Pin `alg` to RS256 only. Reject none/HS*/EC.
- Cache JWKS 15 min, refresh-ahead, rate-limit outbound fetches. On unknown `kid`:
  one forced refresh, then fail closed. Never fetch per request. [DOC guidance]
- Validate: iss, aud (= our client id, CONFIG — the tenant changes), exp, iat (±60s),
  **enrolment:** `jti` == the sessionId we passed to setSessionId [DOC security-and-best-
  practices.md + info.md; OBSERVED S1-02], `data.nonce` == the nonce we issued,
  `data.documents[0].documentType` == what we requested (there is NO top-level `documentType`
  — OBSERVED S1-02); **face session:** `data.sessionId` == the Face Session id we created —
  its `jti` is a separate UUID (OBSERVED S1-02) — and `data.nonce` if we set one; and `jti`
  not previously accepted (global replay guard). `StubUqudoClient` (R-034) is the reference
  implementation of this list against the observed shapes.
- Library: `com.nimbusds:nimbus-jose-jwt` at the version Spring Boot manages — already
  on the classpath via spring-security-oauth2-jose, and JWKSourceBuilder gives caching,
  refresh-ahead and rate limiting. Uqudo's own sample uses io.jsonwebtoken + com.auth0
  jwks-rsa [DOC]; equally valid, two more direct dependencies.
- Failure: one exception type, one audit event with a category, raw JWS stored
  byte-identical, coarse VERIFICATION_FAILED to the app. Never echo the JWS.

## Required SDK configuration for THIS journey
MUST set: `DocumentBuilder.disableExpiryValidation()` — the journey accepts genuine
expired documents (stage 9, RESOLVED); without it the SDK rejects them.
`DocumentBuilder.disableHelpPage()` — **added S8-09.** The SDK's own intro screen ("Scan
%1$s / Fit %1$s to the frame / Start", `uq_core_activity_help.xml`) adds nothing in front of
our Stage 8 preparation screen, and it is the ONLY screen in this journey that draws the
uqudo logo: `uq_core_toolbar.xml` carries `uq_logo_icon`, and the camera, progress and
output layouts do not include that toolbar. The flag is honoured, not cosmetic — it
serialises as `isHelpPageDisabled`, `UqudoIdPlugin.kt:297-298` forwards it to the native
`DocumentBuilder.disableHelpPage()`, and both `ScannerActivity` and `CameraFragment` branch
on `Document.isHelpPageVisible()`. [OBSERVED sdk-bundle-Uqudo-3.10.0.aar bytecode + res,
2026-09-08] The FACE session has no equivalent intro screen — `uq_face_activity_facial_
recognition.xml` includes no toolbar and there is no face help fragment — so there is
nothing to skip on the liveness side and no missing flag.
`UqudoIdPlugin.setLocale('ar')`. `setAppearanceMode(AppearanceMode.LIGHT)`.
Backend-minted `setSessionId(uuidv4)` and `setNonce(…)` (nonce max 64 chars) on every
launch. [DOC android/enrolment-flow.md]

MUST NOT set: `enableAgeVerification()` (no age rule here; FIB sets it — do not copy)
· `allowNonPhysicalDocuments()` (keeps print/screen rejection live)
· `disableSecureWindow()` (default blocks screenshots — the AD-001 PII rationale)
· `enableRootedDeviceUsage()` · `EnrollmentBuilder.enableFacialRecognition()` at stage 8
· `setMinimumMatchLevel()` on the face session.
`disableTamperingRejection()` is a documented no-op since 3.8.0. [OBSERVED CHANGELOG]
`enableActiveLiveness()` deferred — Uqudo warns it "may significantly impact user
conversion rates". [DOC android/face-session-flow.md]

## Error reporting — the shape, and the trap
Both native plugins put JSON `{code, message, task, data}` into
`PlatformException.code` and leave `.message` null (Android) / "" (iOS).
[OBSERVED ../FIB/docs/research/2026-06-10-uqudo-sdk-research.md lines 206-231]
**Any mapping written against `PlatformException.message` is dead code** — FIB's
`document_scan_screen.dart` lines 122-130 matches substrings that can never appear.
`task` ∈ SCAN | READING | FACE | BACKGROUND_CHECK | LOOKUP.

Mobile SessionStatusCodes: USER_CANCEL · SESSION_EXPIRED · UNEXPECTED_ERROR ·
REQUEST_TIMEOUT (**new in 3.9.0** [DOC changelog]) ·
SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS ·
SESSION_INVALIDATED_CAMERA_NOT_AVAILABLE ·
SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED ·
SESSION_INVALIDATED_{CHIP_VALIDATION_FAILED, READING_NOT_SUPPORTED,
READING_INVALID_DOCUMENT, READING_AUTHENTICATION_FAILED} (NFC, unreachable here) ·
SESSION_INVALIDATED_OTP_TOO_MANY_ATTEMPTS (Lookup, unreachable here).
[DOC android/enrolment-flow.md + android/face-session-flow.md; OBSERVED FIB research]

Scan-quality problems are NOT error codes. Printed/screen/tampered surface as
`verifications[0].{idPrintDetection,idScreenDetection,idPhotoTamperingDetection}.score`
inside a SUCCESSFUL result (rejected in-SDK above score 50 unless
allowNonPhysicalDocuments). [DOC verification-object.md] So journey stage 8's "scan
failed" mostly arrives as USER_CANCEL or as a JWS the backend then judges.

## Image ids and when to fetch them
From enroll(), `documents[0].scan`: frontImageId · backImageId (absent for passport) ·
frontFrameImageId · backFrameImageId (both 3.3.0+) · faceImageId (the document
portrait). From faceSession(), `data.face`: auditTrailImageId. Each has a sibling
`…Checksum` = "sha256:<digest>". [DOC scan-object.md / face-object.md]
Fetch ALL of them inside the same server-side chain as JWS verification — not lazily,
not on first operator view — because retention is undocumented and expiry is real.
Verify each against its checksum; a mismatch is a hard failure. Store byte-identical,
never re-encoded — **except the raw capture frames** (frontFrameImageId/backFrameImageId),
deliberately excluded from body storage (AD-004, docs/components/persistence.md: roughly
doubles the volume for no evidentiary gain, since the cropped document is what the JWS
attests to). Then DELETE /api/v1/info/{jti}. Storage: AD-004 (closed, S5-06) — PostgreSQL,
`app.artifact_ref.body`, not object storage. See docs/components/persistence.md.

## Device attestation — present in the result, unhandled by our backend  [DOC 2026-09-03]
The SDK result carries a device-attestation object. Mobile risk flags: `debugging`,
`hooking`, `deviceMasked`, `emulated`, `rooted`, `proxy`, `gpsSpoofers`, `screenSharing`,
`vpnRunning`, `suspiciousFactoryReset`, `sdkConnectionIntercepted`, `payloadTampered`.
Uqudo names a high-risk subset — `applicationStore`, `debugging`, `hooking`, `emulated`,
`rooted`, `proxy`, `sdkConnectionIntercepted`, `payloadTampered` — and states "each flag
should be assessed according to your specific risk model". Info attributes include device
identifier, platform, manufacturer, model, timezone, IP info, GPS and user agent.
[DOC sdk-result/data-structure/device-attestation.md]

**Nothing in this backend reads any of this today.** The parser does not extract it, the
profile does not store it, no operator sees it, and no audit event records it. That is a
gap, not a decision — these are exactly the fraud signals this journey's design principle
("the solution produces a verified identity claim; the operator judges it") depends on the
operator being able to see. Decide deliberately at S1-02 against the real logged JWS, which
is the first time the object's actual shape will be observable. [UNVERIFIED — the object's
exact JSON nesting inside the JWS is not confirmed by any source in this repo.] Tracked as
BL-027.

## Release-build requirements
- Android: R8/ProGuard config required (`proguard-android-optimize.txt` +
  `proguard-rules.pro`); ABI splits with `universalApk = false`, or AAB. [DOC]
- Flutter plugin injects the Uqudo Maven repo via `rootProject.allprojects` — breaks
  under `dependencyResolutionManagement { repositoriesMode = FAIL_ON_PROJECT_REPOS }`.
  [OBSERVED plugin android/build.gradle lines 17–22]
- iOS: `OpenSSL-Universal` pinned to exactly 3.3.3001; other versions crash the SDK.
  [OBSERVED podspec; CHANGELOG 3.5.0]

## The real adapter exists  [2026-09-04, S5-09]

`fru.uqudo.client=http` selects `uqudo.http.HttpUqudoClient` — every call on this page, built
against what S1-02 exercised live rather than against this document alone. Three things about it
are worth knowing before reading anything below:

- **The parser is no longer in the stub.** `uqudo.domain.UqudoJwsParser` holds all of it and takes
  a `JwsSignatureVerifier`; the stub supplies HS256, `uqudo.http.UqudoJwksSignatureVerifier`
  supplies RS256-against-JWKS. One parser, two signature steps — which is how CLAUDE.md's
  quarantined-in-a-single-class rule survives having two clients. The section below still
  describes what that parser encodes; only its address changed.
- **`iat` is bounded in the future only**, not symmetrically. The ±60 s guidance under *JWS
  verification* below is right about clock skew and wrong as a past-bound for this journey:
  customer.md Stage 13 lets a dropped upload be re-presented for the JWS's whole 2-hour life, and a
  60-second past-bound would have turned every one of those resumes into a counted verification
  failure. `exp` bounds the past, in the parser, with its own non-counting outcome.
- **The device-facing token is minted fresh every call**; only the adapter's own server-to-server
  calls reuse a cached one. Stage 7's no-token-is-requested-here note is the reason.

Proven against `MockRestServiceServer` on the shapes S1-02 observed, plus a real-socket timeout
test and an opt-in live test that mints a token and reads the JWKS — the only two calls that touch
no customer data. (That carve-out was written when the tenant was FIB's borrowed one, R-001; it is
still a sound habit on the project's own tenant, just no longer a third-party-disclosure control.)
Everything else stayed mock-proven at the time of writing: an image download or a purge needs a real
scan, and there is no synthetic one. **A real one has since happened** — S5-07 and S5-08 ran the
scan and face paths end to end on hardware against the project's own tenant, including a 406 KB face
image fetched by JWS reference.

The tenant, its endpoints and its credentials are `fru.uqudo.http.*`, none with a default (R-007);
startup names every missing key at once rather than one per restart.

## The stub parser — rewritten at R-034 against the S1-02 shapes  [2026-09-04]

`StubUqudoClient` (`backend/src/main/java/com/sfbank/bayanati/uqudo/stub/`) supplies the HS256
signature step for R-034's quarantined parser, which now lives in `uqudo.domain.UqudoJwsParser`
(S5-09) and is shared with the real client. The S3-12/S3-13 versions of this section listed every structural assumption
the stub made from the field pages alone, each marked `[UNVERIFIED]`; S1-02 showed all of them
wrong, and the class was rewritten against the redacted real shapes (the section below, and the
session report). What it encodes now, so nobody re-reads the old assumptions as fact:

- Envelope `{iss, aud, iat, exp, jti, data}`; enrolment `jti` == our minted session id, face-session
  binding `data.sessionId` (its `jti` is a different UUID). No top-level `documentType` — it is
  `data.documents[i].documentType`.
- `documents[i].scan = {front{…}, back{…}, faceImageId, faceImageIdChecksum, frontImageId, …}`;
  checksum keys are `<imageKey>Checksum`; `backImageId: null` on a passport. Front/back placement
  per the country tables above (passport: everything on `front`; SDN_ID: MRZ block, `placeOfIssue`,
  `issueDate` and the English name on `back`).
- Scores from `verifications[i].{idScreenDetection,idPrintDetection,idPhotoTamperingDetection}.score`
  (decimals, rounded to the `smallint` columns; 50/50/70 thresholds need no finer resolution).
- Dates: `dateOfBirth` is the 6-character MRZ form; `*Formatted`/`*Full`/`*FullFormatted` are
  10 characters (separator unknown from the redacted shape) — an ordered, never-throwing fallback
  (`*FullFormatted` → `*Formatted` → `*Full` → bare key; ISO, `dd/MM/yyyy`, `dd-MM-yyyy`,
  `yyyy/MM/dd`, `yyyyMMdd`, then MRZ `yyMMdd`), `null` when nothing parses.
- SDN_ID: `identityNumber` fail-closed; card version from `front.bloodType`; English name from
  `back.fullName` (FIB) or `back.name` (docs); `nameEn` never existed.
- `face.error` / `face.falseAcceptRate` / `data.source` / `deviceAttestation`: tolerated present or
  absent, never required; `face.error` is surfaced into the audit payload only.
- `aud` is never read; no exception message carries a payload value.
- `verifyAndParseIncompleteFaceSession` (BL-028) is the same envelope check with a tolerant `face`.
- Fabricators are synthetic stand-ins for the device; `enrolmentClaims()` + `sign()` expose the
  mutable claims so a test can bend one detail of the shape. Built from the shapes, never from
  the captured payloads.

Still unobserved and marked in the class: SDN_ID's actual side placement (UAT), the partial
artifact's contents (two-person test), and any real RS256/JWKS adapter (none exists; the stub is
the only `UqudoClient`).

## S1-02 observed — the first real JWS (2026-09-03, FIB tenant, passport, Android 9)  [OBSERVED]

Everything in this section was read off a real signed JWS or a real HTTP response during the
device spike (docs/sessions/2026-09-03-s1-02-device-spike.md). It overrides the assumptions in
the two "stub implementation" sections above wherever they differ.

**Tenant note, 2026-09-06.** This spike ran on FIB's borrowed tenant, the only one available on
2026-09-03; the heading is historically accurate and stays as written. Every [OBSERVED] fact in this
section is about the *shape* of the SDK's output and the JWS — claim layout, `exp − iat`, image
retention, the `data.sessionId` binding — and holds regardless of which tenant minted the token.
S5-07/S5-08 re-observed the same shapes on the project's own tenant. Nothing here needs re-deriving.

- **Enrolment JWS**: top-level `iss, aud, data, exp, iat, jti` — no top-level `documentType`.
  `jti` == the `sessionId` we minted (UUID). `exp − iat` = **7200 s**; `iat` is stamped at scan
  completion. `data` = `{source, nonce, documents[], verifications[]}`; `data.nonce` echoes ours.
  `data.source` = `{sdkType, sdkVersion, sourceIp, devicePlatform, deviceVersion,
  deviceManufacturer, deviceModel}`. `documents[i]` = `{documentType, scan, reading, face,
  lookup}`; `scan` = `{front{…OCR…}, back{}, faceImageId, faceImageIdChecksum, frontImageId,
  frontImageIdChecksum, frontFrameImageId, frontFrameImageIdChecksum, backImageId,
  backImageIdChecksum}` — **checksum keys are `<imageKey>Checksum`**, values `sha256:<hex>`;
  images are references, never base64. Anti-spoof scores are **not** in `scan` — they are
  `verifications[i].{idScreenDetection,idPrintDetection,idPhotoTamperingDetection}.score`,
  alongside `dataConsistencyCheck`, `sourceDetection`, `mrzChecksum`, `reading/biometric/lookup
  {enabled}`. Header: `alg RS256`, `kid`, no `typ`. JWKS had one key.
- **Face-session JWS**: `{iss, aud, iat, exp, jti, data{face, sessionId[, nonce]}}`. **The binding
  is `data.sessionId` == the id from `POST /api/v1/face`; `jti` is a different UUID.** A nonce set
  via `FaceSessionConfigurationBuilder.setNonce` IS echoed as `data.nonce`. `exp − iat` = **600 s**.
  `face` = `{match, matchLevel, error, falseAcceptRate, auditTrailImageId,
  auditTrailImageIdChecksum}`. Corrects the S3-13 stub: `verifyAndParseFaceSession` must check
  `data.sessionId`, and can check a nonce.
- **`DELETE /api/v1/info/{id}` returns 204 for any UUID.** With a face JWS's `jti` → 204 and the
  audit image stayed downloadable; with the face **session id** → 204 and the image went 404 at
  once. So purge face sessions by `data.sessionId`; purging by `jti` is a silent no-op. Enrolments
  are unaffected (`jti == sessionId`).
- **Image retention = the enrolment JWS lifetime (2 hours), not the documented 30 minutes**: all
  three passport images were 200 at 25, 31, 35, 45 and 90 minutes after `iat` and **404 at 120
  minutes, one second after `exp`**. At that point the signature still verified and the `kid` was
  still in the JWKS — `exp` is enforced by our verifier alone (`ARTIFACT_EXPIRED`). The R-012
  retry window for a dropped upload is therefore 2 hours.
- **Device attestation: absent** from every JWS on this tenant/flow. BL-027 stands; whether it is
  tenant-gated is unknown.
- **Token**: `expires_in` **1859**, scope `KYC_SCAN_API KYC_FACE_SEARCH_SDK KYC_FACE_FLOW
  KYC_INFO_SDK KYC_SCAN_SDK KYC_EVENT_SDK`. `aud` is the tenant client id — never log it.
- **UI language: English with `setLocale('ar')`.** The AAR ships `res/values/` only — no
  translations. ≈176 `uq_{face,error,scan,button,help}_*` strings to override (R-002).
- **`isEnrollmentSupported(x)`** = `DocumentType.valueOf(x).enrollmentSupported`
  (`UqudoIdPlugin.kt:717`) — an SDK enum property, not a tenant call.
- **Timing on a 2 GB / API 28 handset**: SDK screen up 3.5 s (scan) / 0.9 s (face) after tap;
  passport scan 52 s incl. one blur retry; face 42–49 s; backend verify + 3 downloads ≈ 1.3 s.
  Passport images: portrait 155 KB, front 618 KB, frame 1.77 MB; face audit trail ≈ 420 KB.
- **Undocumented vendor fields seen in the real JWS** — `face.error`, `face.falseAcceptRate`
  (the string occurs nowhere in `llms-full.txt`), and the whole `data.source` object
  (`common-object` lists the enrolment `data` attributes as documents/verifications/nonce/
  backgroundCheck/deviceAttestation — no `source`). R-034 quarantine applies: nothing outside the
  parser may depend on any of them. [DOC — absence 2026-09-04]
- `common-object.md` documents the face-session `data.sessionId` as "Session ID returned by
  the Face API when uploading the image" — independent corroboration of the S1-02 binding
  finding. [DOC 2026-09-04]
- Anti-spoof rejection thresholds are NOT uniformly 50: `idScreenDetection` and
  `idPrintDetection` reject above 50, **`idPhotoTamperingDetection` above 70** ("Scores
  surpassing 40 should trigger a warning"). [DOC verification-object.md 2026-09-04]
- `deviceAttestation` is documented as a standard `data` attribute for BOTH flows with **no
  documented enablement/feature-flag/tenant gate** — its absence from every FIB-tenant JWS is
  unexplained. On the "Ask Uqudo" list. [DOC — absence 2026-09-04]
- **Not observed** (still `[UNVERIFIED]`, closed by evidence + reasoning on 2026-09-04 with
  residuals accepted — see the Sudan section and the two face sections above): the SDN_ID
  field set, its back side and two card versions (no card, none coming before finalisation);
  the `match:false` face path and `setMinimumMatchLevel` behaviour (no second person, none
  coming); anything on an API 36 device (no device, accepted for Phase 1; UAT).

## Open items
- [ ] S1-02: verify scan + liveness at runtime under targetSdk 36, physical arm64 — **ran
      2026-09-03 on API 28 only** (scan + liveness work); the API 36 half still needs a device
- [x] S1-02: record `exp - iat` from a real JWS — 7200 s enrolment, 600 s face (R-021 retired)
- [ ] S1-02: present a NON-MATCHING face — with and without setMinimumMatchLevel —
      and record whether a match:false JWS is issued — **NOT TAKEN 2026-09-03** (no second
      person); the spike app's Face D/E buttons are ready for it
- [x] S1-02: `isEnrollmentSupported(SDN_ID)` — `true`, but it is an SDK enum property, not a
      tenant check; PASSPORT enrolment proven on FIB's tenant (S1-02) and **on the project's own
      tenant** (S5-07/S5-08, 2026-09-06), **SDN_ID enrolment not yet on either**
- [x] SDK UI language, Arabic in particular (R-002) — **ANSWERED 2026-09-08 on the artifact.**
      The AAR carries `values` and `values-night-v8` only; Uqudo ships no translations. The
      localisation route is our own `values-ar` plus the already-called `setLocale('ar')`,
      which `io.uqudo.sdk.C.attachBaseContext` applies for real. Built at S8-09 (133 keys).
      Still open in BL-071: the wording is provisional pending native review, and no string
      has been seen on a device for truncation or RTL mirroring. [OBSERVED]
- [x] S1-02: the real face-session contract — nonce IS echoed (`data.nonce`), the face JWS DOES
      carry `exp` (600 s), and `DELETE /api/v1/info/{id}` purges by the face **session id**
      (`data.sessionId`) while returning a no-op 204 for the `jti` — see "S1-02 observed"
- [ ] Ask Uqudo: any narrower token scope, and token-endpoint rate limits. **Extended
      2026-09-04:** is `deviceAttestation` tenant-gated (it was absent from every JWS on FIB's
      tenant; whether it appears on the project's own tenant was not checked at S5-07/S5-08 —
      recheck at the next device run)?
      what are the `face.error` values and what is `face.falseAcceptRate`? does an
      incomplete-session JWS (`returnDataForIncompleteSession`) carry `face.match:false`? what
      is the real image-retention bound — ANSWERED 2026-09-03: 2 hours, the enrolment JWS lifetime (documented 30 is wrong)? is a
      `match:false` JWS ever issued on the success path?
      **Extended 2026-09-06 — COMMERCIAL. Routing CORRECTED the same day: put these to Uqudo
      against the project's OWN contract, not via FIB.** The earlier wording, "route via FIB, who
      hold the contract for the tenant we use", was written while the tenant was borrowed; OQ-010
      is answered. (BL-063, OQ-026): what is the billed unit — token generation, enrolment, face
      session, or "verification"? may one token back multiple SDK launches and multiple end
      customers? is the token endpoint rate-limited? **None of these is answerable from public
      documentation** — the 2026-09-06 researcher pass established that Uqudo publishes no
      billing or rate-limit material at all, so this is a contract question, not a research
      one. The token-endpoint rate-limit half of this row's original 2026-09-04 wording is
      therefore now known to be unanswerable by reading, and stays here only as a question to
      put to the vendor. [ABSENT]
- [ ] Non-matching face / `setMinimumMatchLevel`: OPEN, documentation cannot close it — first
      two-person test session (UAT). BL-028 makes the design robust to either answer.
- [x] Obtain our own tenant + credentials — **DONE; OQ-010 answered 2026-09-06.** The project's
      own Uqudo tenant is in use and proven on hardware at S5-07 (scan) and S5-08 (liveness).
      Identifier, endpoints and credentials are configuration only, never recorded here.
- [ ] Confirm SDN_ID is enabled on the project's OWN tenant and exercise an SDN_ID enrolment (UAT,
      card in hand) — OQ-010's remaining half. The FIB-tenant SDN_ID licensing that used to stand
      in for this does not transfer.
- [x] SDN_ID / SDN_DL / SDN_VL present at 3.10.0 — CLOSED 2026-08-21 [DOC]
- [x] Determine the POST /api/v1/face request body format — CLOSED 2026-08-22, R-023
      retired [DOC Face API OpenAPI]
- [x] Determine whether `jti` on a face-session JWS equals the Face API session id —
      CLOSED 2026-08-22 [DOC Info API OpenAPI]
- [x] Locate the OpenAPI/Swagger spec via the Customer Portal — CLOSED 2026-08-22, both
      Face and Info specs filed at docs/components/*.openapi.yaml
- [x] Uqudo image retention window — CLOSED 2026-08-22 at 30 minutes [DOC Info API
      OpenAPI] — closes half of R-021, corrects R-012 — **superseded 2026-09-03: measured at 2 hours (S1-02); R-012 retired at R-034**
- [x] Check whether the real JWS carries `faceImage` as base64 or `faceImageId` as a
      reference — CLOSED 2026-09-02 by documentation, R-024 retired: `faceImageId`, a
      reference. `scan-object`'s "the only exception is the face image" sentence. No
      code change; stage 10 already built this way. [DOC]
