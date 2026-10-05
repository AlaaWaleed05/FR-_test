# AD-002a — Uqudo integration architecture and the mobile↔backend identity contract

**Date:** 2026-08-21 · **Scope:** Uqudo only. Oracle, Civil Registry protocol, messaging, hosting, back-office auth, reference-data delivery and the audit store are out of scope and decided elsewhere.

**Reading of the question I pursued:** the contract that supports `docs/journeys/customer.md` stages 7–10 and 12 exactly as written, with no change to the journey's screen sequence. Readings I did *not* pursue: collapsing stages 8–10 into a single Uqudo SDK session (which would remove the stage 9 Civil Registry review screen from between scan and face), and using Uqudo's Lookup flow as a substitute for the Civil Registry (out of scope, and Sudan is not in the documented lookup set).

---

## 1. Question

What is the correct Uqudo integration architecture for this project, and what is the exact mobile↔backend contract for token issuance, document scan, image retrieval, Civil Registry trigger, and the face session that performs liveness **and** face matching against the scanned document portrait?

---

## 2. Answer

Use **two separate Uqudo SDK invocations**: `enroll()` at stage 8 with facial recognition **disabled**, and `faceSession()` at stage 10 against a Uqudo **Face Session** that our backend creates by uploading the document portrait it downloaded from the stage-8 result. Face matching is not a parameter on the face session and not a backend comparison — it is what a Face Session *is*: the SDK matches the live capture against the image the backend uploaded, and returns `face.match` (boolean) with `face.matchLevel` (1–5).

Shape the contract as **one session resource with attempt sub-resources** (option A in §5). The backend mints the Uqudo `sessionId` and `nonce` for every attempt, the app echoes them into the SDK builders, and the backend validates them back out of the JWS — which gives replay protection, attempt binding and idempotency from one mechanism rather than three.

**Three findings that change the plan and must not be skimmed:**

1. **Uqudo does not return a liveness score.** There is no liveness field of any kind in the JWS. `face.match` / `face.matchLevel` are the *face-match* result and its confidence; liveness is enforced silently inside the SDK and a liveness failure never produces a JWS at all. This contradicts the mitigation text of **R-016** and stage 10's line "Uqudo returns both results with confidence figures". The journey's *intent* survives intact — the two failure modes are still cleanly distinguishable — but the wording and R-016 must be corrected. See §4.
2. **Do not set `setMinimumMatchLevel()` on the face session.** If the SDK enforces the threshold, a face-match failure is consumed as an in-SDK retry and ends as `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` — i.e. **the signed `match:false` artifact, the strongest fraud signal the journey produces, is never issued.** Leave it at default and enforce the threshold server-side from the JWS.
3. **The face session has a 600-second life, not 1800.** "The session and related image will be automatically deleted after 10 minutes" [DOC]. The journey's point-of-use token rule is right but under-stated: at stage 10 the binding clock is 10 minutes, not 30.

---

## 3. The SDK surface actually used

**Version verified:** `uqudosdk_flutter` **3.10.0** is current on pub.dev, published ~2026-08-10 [DOC pub.dev changelog, fetched 2026-08-21]. The **source I read line-by-line is 3.8.0+1**, the only copy in the local pub cache at `C:\Users\DELL\AppData\Local\Pub\Cache\hosted\pub.dev\uqudosdk_flutter-3.8.0+1\` [OBSERVED]. Where a signature below is marked 3.8.0+1 it is read from that source; I checked the 3.9.0/3.10.0 changelog for API-affecting changes and found none to these builders [DOC].

### Plugin entry points — `lib/UqudoIdPlugin.dart` [OBSERVED 3.8.0+1, lines 12–84]

```dart
static void init()
static void setLocale(String locale)
static Future<String> enroll(Enrollment object)
static Future<String> faceSession(FaceSessionConfiguration object)
static Future<String> recover(AccountRecoveryConfiguration object)
static Future<String> lookup(Lookup object)
static Future<String> reading(Reading object)
static Future<bool> isEnrollmentSupported(DocumentType documentType)
static Future<bool> isReadingSupported(DocumentType documentType)
static Future<bool> isFacialRecognitionSupported(DocumentType documentType)
static Future<bool> isLookupSupported(DocumentType documentType)
static Future<bool> isLookupFacialRecognitionSupported(DocumentType documentType)
```

All are `static` on a `MethodChannel('uqudosdk_flutter')`. `enroll` and `faceSession` return `Future<String>` — the JWS. There is **no cancellation API**: cancellation is user-driven inside the SDK's own UI and surfaces as a thrown `PlatformException`. There is no way to programmatically dismiss a running SDK session [OBSERVED — no such method exists in the plugin surface].

The 3.10.0 Flutter doc page shows an `EventChannel('io.uqudo.sdk.id/trace')` for tracing that does not appear in the 3.8.0+1 `UqudoIdPlugin.dart` [DOC flutter/enrolment-flow.md vs OBSERVED 3.8.0+1]. Not required by us; noted so nobody assumes 3.8.0+1's surface is complete for 3.10.0.

### `EnrollmentBuilder` [OBSERVED 3.8.0+1 `lib/uqudosdk_flutter.dart` lines 100–212]

```dart
setToken(token) · setNonce(nonce) · setSessionId(sessionId) · setUserIdentifier(userIdentifier)
add(document) · enableFacialRecognition([FacialRecognitionConfiguration?])
enableBackgroundCheck(cfg) · enableLookup({List<DocumentType>? documents})
enableRootedDeviceUsage() · disableSecureWindow() · returnDataForIncompleteSession()
setAppearanceMode(AppearanceMode) · allowNonPhysicalDocuments() · disableTamperingRejection()
build()
```

Wire shape sent to native, from `Enrollment.g.dart` [OBSERVED]: `documentList`, `authorizationToken`, `nonce`, `isRootedDeviceAllowed`, `isSecuredWindowsDisabled`, `facialRecognitionSpecification`, `backgroundCheckConfiguration`, `lookupConfiguration`, `sessionId`, `userIdentifier`, `isReturnDataForIncompleteSession`, `appearanceMode`, `allowNonPhysicalDocuments`, `disableTamperingRejection`.

- `setSessionId` — "UUID v4… required for QR code authentication flows… make sure to create always a new session id when you trigger the SDK flow" [DOC android/enrolment-flow.md]. It becomes the JWS `jti` (§6).
- `setNonce` — server-generated, **max 64 chars** [DOC android/enrolment-flow.md]; echoed back as `data.nonce`.
- `disableTamperingRejection()` is a documented no-op since 3.8.0 [OBSERVED FIB research quoting CHANGELOG 3.8.0].

### `DocumentBuilder` [OBSERVED 3.8.0+1 lines 331–426]

```dart
setDocumentType(DocumentType) · enableReading([ReadingConfiguration?]) · disableHelpPage()
disableExpiryValidation() · enableScanReview(front, back) · enableUpload()
setFaceScanMinimumMatchLevel(n) · setFaceReadMinimumMatchLevel(n) · enableAgeVerification(minAge)
disableUserDataReview()  // @deprecated, ignored
enablePhotoQualityDetection()  // @deprecated, ignored, on by default
build()
```

### `FacialRecognitionConfigurationBuilder` [OBSERVED 3.8.0+1 lines 214–297]

```dart
enrollFace() · setScanMinimumMatchLevel(n) · setReadMinimumMatchLevel(n)
setLookupMinimumMatchLevel(n) · setMaxAttempts(int)   // 1..3, default 3
allowClosedEyes() · enableAuditTrailImageObfuscation(ObfuscationType)
enableOneToNVerification() · enableActiveLiveness([LivenessGesture?]) · build()
```

### `FaceSessionConfigurationBuilder` [OBSERVED 3.8.0+1 lines 694–805]

```dart
setToken(token) · setSessionId(sessionId) · setNonce(nonce) · setUserIdentifier(userIdentifier)
setMinimumMatchLevel(value) · setMaxAttempts(int) · allowClosedEyes()
enableActiveLiveness([LivenessGesture?]) · enableAuditTrailImageObfuscation(ObfuscationType)
enableRootedDeviceUsage() · disableSecureWindow() · returnDataForIncompleteSession()
setAppearanceMode(AppearanceMode) · build()
```

Wire shape from `FaceSessionConfiguration.g.dart` [OBSERVED]: `token`, `sessionId`, `nonce`, `userIdentifier`, `isRootedDeviceAllowed`, `isSecuredWindowsDisabled`, `minimumMatchLevel`, `maxAttempts`, `isReturnDataForIncompleteSession`, `allowClosedEyes`, `appearanceMode`, `obfuscationType`, `enableActiveLiveness`, `disableLivenessGesture`.

Note `FaceSessionConfigurationBuilder` uses `setMinimumMatchLevel`, singular — unlike `FacialRecognitionConfigurationBuilder`'s three scan/read/lookup variants. Defaults are the sentinel `-1` for all match levels and `maxAttempts` [OBSERVED lines 701–702].

### Enums [OBSERVED 3.8.0+1 lines 13–98]

`AppearanceMode { SYSTEM, LIGHT, DARK }` · `ObfuscationType { FILLED, BLURRED, FILLED_WHITE }` · `LivenessGesture { FACE_MOVE, FACE_TILT, FACE_TURN }` · `BackgroundCheckType { RDC, DOW_JONES }`.

### Configuration we must and must not set

| Setting | Decision | Why |
|---|---|---|
| `DocumentBuilder.disableExpiryValidation()` | **MUST call** | Journey stage 9: "Expiry is not checked — RESOLVED. A genuine but expired document is accepted." Without this the SDK rejects an expired document and that resolution silently fails. |
| `enableAgeVerification(n)` | **MUST NOT call** | No minimum-age rule exists in this journey. FIB sets it [OBSERVED `../FIB/mobile/lib/core/uqudo/uqudo_service.dart` line 115] — do not copy. |
| `allowNonPhysicalDocuments()` | **MUST NOT call** | Leaving it off keeps print/screen rejection active (`idPrintDetection` / `idScreenDetection` reject above score 50). |
| `disableSecureWindow()` | **MUST NOT call** | Default blocks screenshots and screen recording. This is the client-side PII protection AD-001 was decided on. |
| `enableRootedDeviceUsage()` | **MUST NOT call** | Default `false` blocks rooted devices. |
| `EnrollmentBuilder.enableFacialRecognition()` | **MUST NOT call at stage 8** | Would capture a selfie during the scan, before stage 9's review screen and before stage 10's preparation screen. Face capture belongs at stage 10. |
| `FaceSessionConfigurationBuilder.setMinimumMatchLevel()` | **MUST NOT call** | See §4 — setting it destroys the signed face-match-failure artifact. |
| `setMaxAttempts(3)` on the face session | Call, value server-supplied | This is the SDK's *internal* retry count within one launch, distinct from the journey's budget of 5 launches. |
| `enableActiveLiveness()` | **Do not enable in v1** | Uqudo: "We recommend enabling this feature only if required for regulatory or compliance purposes, as it may significantly impact user conversion rates" [DOC android/face-session-flow.md]. Revisit if OQ-001 answers otherwise. |
| `UqudoIdPlugin.setLocale('ar')` | Call before every launch | The only lever we have on R-002. |
| `setAppearanceMode(AppearanceMode.LIGHT)` | Call | Deterministic rendering; do not inherit device dark mode into an Arabic RTL capture UI. |

---

## 4. How face matching against the scanned document actually works

**It is not a parameter and it is not a backend comparison. It is the definition of a Face Session.**

> "A face session allows you to upload an image (e.g. id photo) and match it with a selfie taken during the face session flow… The returning session id must be used in the uqudo SDK to initiate the Face Session Flow to perform **liveness check and face matching against the provided image**." [DOC https://docs.uqudo.com/docs/kyc/uqudo-api/face.md]

The chain is:

1. Backend calls `POST {apiBase}/api/v1/face`, uploading the reference image → receives a **face session id** [DOC face.md].
2. That id goes to the app, into `FaceSessionConfigurationBuilder.setSessionId(...)` — "the session id returned by the Face Session API" [DOC android/face-session-flow.md].
3. The SDK runs liveness and 1:1 matching against the uploaded image, and returns a JWS.
4. **"The session and related image will be automatically deleted after 10 minutes."** [DOC face.md]

**The reference image must be the document portrait, `documents[0].scan.faceImageId`** — "The id of the image of the face of the user in the document" [DOC scan-object.md]. Not `frontImageId`. FIB uploads the *front page* image instead [OBSERVED `../FIB/mobile/lib/features/liveness/liveness_screen.dart` lines 45–51, `apiService.getSessionIdWithImage(frontImageId)`, and `../FIB/docs/API_REFERENCE.md` §5 "Create Uqudo face session"]. Whether Uqudo crops a face out of a full ID card server-side is **[UNVERIFIED]**; using the portrait Uqudo already extracted removes the question entirely.

### What comes back

Face Session flow `data` claim top-level keys: `nonce`, `sessionId`, `face`, `deviceAttestation` [DOC common-object.md].

Face object [DOC face-object.md]:

| Field | Type | Meaning |
|---|---|---|
| `auditTrailImageId` | string | The live selfie, for manual review — an **id**, not bytes |
| `auditTrailImageIdChecksum` | string | `sha256:<digest>` |
| `matchLevel` | integer 1–5 | 1 = worst, 5 = best |
| `match` | boolean | "The facial recognition matches the id photo of the document" |
| `oneToNVerificationId` | string | Only if 1:N enabled (permissioned) |
| `oneToNVerificationMatch` | boolean | Only if 1:N enabled |

A real payload fragment published by Uqudo: `"face": { "auditTrailImageId": "5eabf184827ca836b8e28cf0", "matchLevel": 5, "match": true }` [DOC common-object.md, via docs search].

### The liveness finding — this contradicts R-016 and stage 10

**There is no liveness field and no liveness score anywhere in the JWS.** I checked face-object, common-object, verification-object and the data-structure index [DOC, all four fetched 2026-08-21], and this matches an independent investigation of the same pages in June [OBSERVED `../FIB/docs/research/2026-06-10-uqudo-sdk-research.md` lines 116–147: "`face` object … `matchLevel`, `match`, `oneToNVerificationId`, `oneToNVerificationMatch`" and nothing else].

Liveness is a **gate inside the SDK**, not a reported result:

- A liveness failure raises an analytics event `FACE_LIVENESS_FAILED` and the SDK re-prompts the user within the same launch [OBSERVED FIB research line 295, cross-referencing the changelog].
- Exhausting `maxAttempts` (1–3, default 3) throws `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` [DOC android/face-session-flow.md].
- **No JWS is produced.** There is nothing to sign and nothing to record beyond the error code.

So the two failure modes reach us through **different channels**, not through two scored fields:

| Journey outcome | How it actually arrives | Score available |
|---|---|---|
| Face-match failure — the fraud signal | **A successful `faceSession()` call** returning a JWS with `face.match == false` and a low `face.matchLevel` | Yes: `matchLevel` 1–5 |
| Liveness failure | A thrown `PlatformException`; after retries, `…FACE_RECOGNITION_TOO_MANY_ATTEMPTS` | **No** |
| Cancel | `PlatformException` with `USER_CANCEL` | n/a |

This still satisfies what R-016 exists to protect: face-match failure is distinguishable, scored, signed, and can never be absorbed into a generic retry — provided we do not set `setMinimumMatchLevel()`. **If we set it, the SDK enforces the threshold, treats the low match as a failed attempt, retries the user, and terminates with `TOO_MANY_ATTEMPTS` — indistinguishable from a liveness failure and with no signed artifact.** [DOC android/face-session-flow.md "defines the minimum match level that the facial recognition has to meet" + "Set the max failed facial recognition attempts before dropping the session"; the precise interaction is **[UNVERIFIED]** and must be proven at S1-02 by presenting a face that does not match the scanned document, once with the threshold set and once without.]

### Three routes to face matching, evaluated

| Route | How | Against our constraints |
|---|---|---|
| **A. `enroll()` + `enableFacialRecognition()`** | Selfie and match happen inside the stage-8 scan session; result lands in `documents[0].face` and `verifications[0].biometric.matchLevel` (0–5) [DOC verification-object.md] | Fewest moving parts, no 10-minute window, no Face API call. **But** it captures the face at stage 8 — before the stage 9 review screen and before stage 10's preparation screen, which the journey says "materially affects success rates". It also makes the scan retry budget and the face retry budget the same budget, which the journey deliberately separates (3/6 vs 5). Rejected on journey fidelity. |
| **B. `faceSession()` against a backend-created Face Session** | As specified above | Matches the journey exactly. Costs one extra backend→Uqudo call and imposes a 600s window. **Recommended.** |
| **C. Backend-only `POST /api/v1/face/match`** | Backend compares two images it already holds, no SDK face step [DOC face.md lists this endpoint] | **Provides no liveness at all.** A stored photo of a photo would pass. Not viable alone; usable only as a secondary offline check. Rejected. |

**Recommendation: B.** It flips to A if the product owner ever collapses stages 8–10 into one capture flow, or if S1-02 shows the 600-second face-session window causes real abandonment in Sudanese network conditions.

---

## 5. The mobile↔backend contract

### 5.1 Three candidate shapes

**Option A — one session resource with attempt sub-resources.** `POST /api/v1/sessions` at stage 1b; everything after lives under `/api/v1/sessions/{sid}/…`; each scan or face launch is a server-created `attempt` sub-resource that the app later `PUT`s a result onto.

**Option B — discrete stateless endpoints.** `POST /api/v1/uqudo/scan-token`, `POST /api/v1/uqudo/scan-result`, `GET /api/v1/registry-review`, etc., correlated by a session id in the body or a header.

**Option C — single action-dispatch endpoint.** `POST /api/v1/action` with `{ "type": "REQUEST_SCAN_TOKEN", ... }`. (For accuracy: FIB does *not* do this — FIB uses RPC-style verb-named endpoints, `GET /GetSessionIDWithUqudoImage/{imageId}`, `POST /OpenCIFData`, `GET /token` [OBSERVED `../FIB/mobile/lib/core/network/api_service.dart` lines 115–142 and `../FIB/backend server fib/utility/src/main/java/com/aztech/utility/controller/UqudoController.java` lines 17–25]. That sits between B and C.)

| Constraint | A | B | C |
|---|---|---|---|
| **Resume (stage 0/12)** — "the backend alone decides whether the session is still open" | `GET /sessions/{sid}` *is* the resume call. Natural. | Needs a bolted-on status endpoint that isn't part of the shape. | Needs a dispatch type that is really a GET, losing caching and readability. |
| **Idempotency** — "a dropped connection must not create a duplicate scan or submission" | Strongest: the attempt id is created before the SDK launches, so `PUT …/{attemptId}/result` is idempotent **by URL**, with no header protocol to get wrong. Retrying a dropped upload is literally the same request. | Requires an `Idempotency-Key` header plus a server-side key store on every mutating call, and correctness depends on the client generating and *persisting* the key across an app kill. | Same as B, and the key must cover a polymorphic body — the hardest case to reason about. |
| **App stays blind to identity data** | Enforced by resource design: the app reads `/identity/review`, a curated display payload, and image URLs that are our own paths. It never sees a Uqudo image id. | Achievable, but nothing structurally stops a later endpoint from returning raw parsed fields. | Same, and the dispatch body encourages "just add a field". |
| **Attempt budgets held server-side** | The attempt resource is the counter. Cancels are reported onto the same resource, so a cancel counts without the app owning arithmetic. | Counters live in a side table keyed by session; workable but the wiring is invisible in the API. | Same as B. |
| **Auditability** — every event carries session id + monotonic sequence | Session id and attempt id are in the URL of every request. Trivial to correlate. | Correlation depends on a body field being present and correct. | Same as B. |
| **Cost if wrong** | Some URL churn. Low. | Low. | Low. |

**Recommendation: Option A.** The deciding factor is idempotency. The journey requires that a dropped connection never produces a duplicate scan record, and Option A gets that from the URL rather than from a header contract the client must implement correctly under app-kill conditions. It flips to B if the backend is ever split so that identity handling lives in a service that does not own the session record — then a session-rooted URL would be a lie about ownership.

`Idempotency-Key` is still carried on the genuinely creating calls (`POST …/scan-attempts`, `POST …/face-attempts`, `POST …/submission`), per the journey's blanket rule. It is redundant on the `PUT …/result` calls, which are idempotent by construction.

### 5.2 Endpoint by endpoint

All bodies below are **proposed** — this is a design recommendation, not an observed API. Marked accordingly.

---

**Stage 0 / 12 — resume** `GET /api/v1/sessions/{sid}`

```json
{ "status": "in_progress",
  "resumePoint": "face",
  "blockedUntil": null,
  "profileStatus": "in_progress",
  "listVersions": { "occupations": 3, "branches": 1, "divisions": 2 } }
```
`status` ∈ `in_progress | complete | blocked | abandoned | submitted | approved | rejected`. When `blocked`, `blockedUntil` is an ISO-8601 UTC instant and `resumePoint` names the blocked stage. The app obeys this before touching local state. [Proposed]

---

**Stage 7 Next — persist entered data** `PUT /api/v1/sessions/{sid}/profile`

Header `Idempotency-Key`. Body is the collected stages 3–7 data plus `identityType: "national_id" | "passport"` and the reference-list versions used. Response `200 { "accepted": true, "resumePoint": "scan" }`. **No Uqudo call happens here** — journey stage 7 is explicit. [Proposed]

---

**Stage 8, on tap to begin — scan attempt** `POST /api/v1/sessions/{sid}/identity/scan-attempts`

Request, header `Idempotency-Key: <uuid>`:
```json
{ "identityType": "national_id" }
```

Response `201`:
```json
{ "attemptId": "8f2c…",
  "uqudoSessionId": "0d7a4e2c-…-4b11",
  "nonce": "b3f9…",
  "accessToken": "eyJraWQiOi…",
  "accessTokenExpiresAt": "2026-08-21T09:31:07Z",
  "sdkDocumentType": "SDN_ID",
  "sdkOptions": { "disableExpiryValidation": true, "appearanceMode": "LIGHT", "locale": "ar" },
  "attemptsRemaining": { "thisDocumentType": 2, "thisSession": 5 } }
```

Four deliberate choices:

- **`sdkDocumentType` is server-supplied**, not derived in the app from `identityType`. The app never contains the string `SDN_ID`. Adding `SDN_DL` or swapping to a new Uqudo enum becomes a config change, consistent with the "no hardcoded reference lists" rule and with R-007's tenant swap.
- **`uqudoSessionId` and `nonce` are minted by the backend** and stored against `attemptId`. The app passes them to `setSessionId` / `setNonce` and nothing else. Uqudo: the nonce "should be generated server side" [DOC android/enrolment-flow.md].
- **`attemptsRemaining` is informational.** The backend, not the app, enforces the 3-per-type / 6-per-session budgets.
- The token is fetched at this moment, seconds before the SDK launch, exactly as the journey requires.

Response `409` with `{ "reason": "BLOCKED", "blockedUntil": "…" }` when a budget is already exhausted.

---

**Stage 8, after the SDK returns — submit the JWS** `PUT /api/v1/sessions/{sid}/identity/scan-attempts/{attemptId}/result`

Exactly one of two bodies:
```json
{ "jws": "eyJraWQiOiJ…<untouched compact string>…" ,
  "capturedAtDeviceClock": "2026-08-21T09:24:51+02:00" }
```
```json
{ "sdkError": { "code": "USER_CANCEL", "task": "SCAN", "message": null } }
```

The second is how a cancel "counts as a failed attempt" without the app keeping a counter. `capturedAtDeviceClock` is recorded as a *claimed* value, explicitly unverified, per the audit trail's server-authoritative-time rule.

Response `200`, accepted:
```json
{ "outcome": "accepted", "next": "registry_review" }
```
```json
{ "outcome": "accepted", "next": "registry_pending" }
```
Response `200`, not accepted:
```json
{ "outcome": "rejected",
  "reason": "ARTIFACT_EXPIRED",
  "countsAgainstBudget": false,
  "attemptsRemaining": { "thisDocumentType": 2, "thisSession": 5 } }
```

`reason` ∈ `VERIFICATION_FAILED | ARTIFACT_EXPIRED | IMAGES_UNAVAILABLE | SCAN_QUALITY_REJECTED | CANCELLED | CONNECTIVITY`. These are coarse on purpose; the precise failure lives in audit. `countsAgainstBudget` is the backend's decision and the app renders it, never computes it.

Note there is **no `documentType` in the response, no name, no national number**. The app learns nothing about the customer's identity here.

---

**Stage 9 — the review display payload** `GET /api/v1/sessions/{sid}/identity/review`

```json
{ "state": "ready",
  "nationalNumber": "…",
  "registry": { "fullNameAr": "…", "fullNameEn": "…", "motherName": "…",
                "citizenship": "…", "birthPlace": { "country": "…", "state": "…", "city": "…" } },
  "images": { "documentFront": "/api/v1/sessions/{sid}/identity/images/document-front",
              "portrait":      "/api/v1/sessions/{sid}/identity/images/portrait" } }
```
`state` ∈ `ready | pending_registry | unavailable`. `pending_registry` is R-011's distinct paused state — the app renders "service unavailable, progress intact" and re-polls; no rescan, no new token.

**Image URLs are ours.** They resolve against our own storage and our own auth. A Uqudo image id and a Uqudo access token must never reach the app for image retrieval — that would hand the device a handle into the tenant's object store.

**Stage 9 action** `POST /api/v1/sessions/{sid}/identity/review-decision`, header `Idempotency-Key`, body `{ "decision": "accept" | "national_number_wrong" | "details_wrong" }`. `national_number_wrong` decrements the stage-8 budget server-side and returns `{ "next": "scan", "attemptsRemaining": {…} }`; `details_wrong` returns `{ "next": "terminal_registry_mismatch" }` and sets profile status `terminated_registry_mismatch`.

---

**Stage 10, on tap to begin — face attempt** `POST /api/v1/sessions/{sid}/identity/face-attempts`

Header `Idempotency-Key`. Body `{}`.

Backend, in order: ensure an access token → read the stored document portrait bytes → `POST {apiBase}/api/v1/face` uploading them → receive the face session id → mint a nonce → respond.

Response `201`:
```json
{ "attemptId": "c41b…",
  "uqudoFaceSessionId": "5eabf184827ca836b8e28cf0",
  "nonce": "77c2…",
  "accessToken": "eyJraWQiOi…",
  "accessTokenExpiresAt": "2026-08-21T09:58:12Z",
  "faceSessionExpiresAt": "2026-08-21T09:38:12Z",
  "sdkOptions": { "maxAttempts": 3, "appearanceMode": "LIGHT", "locale": "ar" },
  "attemptsRemaining": 4 }
```

`faceSessionExpiresAt` is `now + 600s` [DOC face.md]. It is in the contract so the app can abandon and re-request rather than launching the SDK against a dead session. **`minimumMatchLevel` is deliberately absent** — see §4.

---

**Stage 10, after the SDK returns** `PUT /api/v1/sessions/{sid}/identity/face-attempts/{attemptId}/result`

Body: `{ "jws": "…" }` or `{ "sdkError": { "code": "…", "task": "FACE", "message": "…" } }`.

Response `200`:
```json
{ "outcome": "passed", "next": "completion" }
```
```json
{ "outcome": "failed", "attemptsRemaining": 3 }
```
```json
{ "outcome": "blocked", "blockedUntil": "2026-08-22T09:38:12Z" }
```

**`outcome: "failed"` is returned identically whether liveness failed or the face did not match.** The journey is explicit: "the customer experience must not differentiate, or it tells a fraudster which control fired." The distinction — `face.match == false` with its `matchLevel`, versus `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` — is written to the profile and the audit trail and surfaced to operators. It is never in this response body.

---

**Stage 11 — completion** `POST /api/v1/sessions/{sid}/submission`, header `Idempotency-Key`.

```json
{ "referenceNumber": "FR-2026-0084213",
  "status": "submitted",
  "notifiedChannels": ["sms", "whatsapp"] }
```
A repeat call with the same key returns the identical body including the same reference number. The app clears local state only after a `2xx` — journey stage 11's strict ordering.

---

### 5.3 What the app is allowed to parse

The app decodes **exactly one** JSON structure that originates from Uqudo: the error envelope in `PlatformException.code`. That is the SDK's error channel, not the signed result, and decoding it is required to distinguish a cancel from a camera-permission denial. The app never touches the JWS. FIB already implements the error decode correctly [OBSERVED `../FIB/mobile/lib/core/uqudo/uqudo_service.dart` lines 55–67] — that part is worth copying; lines 34–44 of the same file, which base64-decode the JWS payload client-side, are the part that must not be (R-004).

---

## 6. Server-side JWS verification

**Library recommendation: `com.nimbusds:nimbus-jose-jwt`, at whatever version `spring-boot-dependencies` manages for our Spring Boot line.** Rationale under our constraints: it is already on the classpath transitively via `spring-security-oauth2-jose`, so it adds zero new supply-chain surface to a bank backend; it ships `JWKSourceBuilder` with caching, refresh-ahead and outbound rate limiting built in; and `JWSVerificationKeySelector` pins the algorithm as a first-class constructor argument rather than as a check you might forget.

Uqudo's own sample uses `io.jsonwebtoken` (jjwt) plus `com.auth0`'s JWK provider [DOC validation-and-parsing.md]. That is a legitimate second option and has the advantage of matching vendor sample code, at the cost of two additional direct dependencies. Third option — hand-rolled verification with `java.security` — rejected: nobody should implement `kid` resolution and algorithm pinning by hand in a bank.

I have deliberately **not** stated version numbers for these libraries; pin them against Maven Central at implementation time. [UNVERIFIED — no version claim made]

**JWKS endpoint:** `https://id.uqudo.io/api/.well-known/jwks.json` [DOC validation-and-parsing.md]. **Configuration, never a constant** (`uqudo.jwks-uri`) — R-007.

**Algorithm:** RS256, and RS256 only [DOC validation-and-parsing.md, header `alg`]. Construct the key selector with an explicit `JWSAlgorithm.RS256` allow-list so `none`, `HS256` and RSA/EC confusion are structurally impossible.

**Key rotation and caching:** Uqudo's guidance is to "cache the list of keys in memory and refresh the cache only if the `kid` is not found" [DOC validation-and-parsing.md]. Configure explicitly rather than relying on library defaults (whose exact values are **[UNVERIFIED]** and change between versions): cache TTL 15 minutes, refresh-ahead enabled, outbound JWKS fetches rate-limited to at most one per 30 seconds. On an unknown `kid`: one forced refresh, then fail closed. Never fetch JWKS per request — that makes Uqudo's availability a hard dependency of every verification, including the verification of an artifact captured hours earlier.

**Claim validation, all mandatory, all failures fatal:**

| Claim | Rule |
|---|---|
| `alg` (header) | `RS256` exactly |
| `kid` (header) | Resolves in the cached JWKS |
| `iss` | Exact match against configured issuer |
| `aud` | Exact match against our client id [DOC validation-and-parsing.md lists `aud` as the client id] |
| `exp` | Not in the past, ±60s skew |
| `iat` | Not in the future, ±60s skew |
| `jti` | **Equals the `uqudoSessionId` we issued for this `attemptId`** — Uqudo: "Create a unique sessionId for each SDK initiation and verify it matches the `jti` property in the JWS result" [DOC security-and-best-practices.md], corroborated by the Info API: "The session id is the value of the `jti` property of the JWS returned by the uqudo SDK" [DOC info.md] |
| `data.nonce` | Equals the nonce we issued for this `attemptId` [DOC security-and-best-practices.md] |
| `data.documents[0].documentType` | Equals the `sdkDocumentType` we issued [DOC security-and-best-practices.md] |
| `data.sessionId` (face flow) | Equals the Uqudo face session id we created |
| `jti` (global) | Not previously accepted for **any** attempt — replay guard across sessions, not just within one |

**Clock skew:** 60 seconds, applied to `exp` and `iat` only. The device clock is never used in verification; it is recorded as a claimed value.

**What a verification failure looks like:** one exception type, one audit event carrying the failure category (`SIGNATURE`, `KID_UNKNOWN`, `ISS`, `AUD`, `EXPIRED`, `JTI_MISMATCH`, `JTI_REPLAY`, `NONCE_MISMATCH`, `DOCTYPE_MISMATCH`) plus the raw JWS stored byte-identical, and one coarse `VERIFICATION_FAILED` to the app. **Never echo the JWS, a decoded fragment, or the failure category to the client.** Per journey stage 8, repeated verification failure on one session is a system fault and must raise an operator signal rather than loop the customer.

**A note on `aud`.** `aud` is documented as the client id [DOC], and the tenant changes before production (OQ-010). The expected `aud` must therefore be configuration alongside the client credentials, not a constant. If it were hardcoded, the FIB→own-tenant swap would fail at verification time with a signature-looking error — the worst possible place to discover a config problem.

---

## 7. Token lifecycle

**The call** [DOC https://docs.uqudo.com/docs/kyc/uqudo-api/authorisation.md]:

```
POST https://auth.uqudo.io/api/oauth/token
Content-Type: application/x-www-form-urlencoded
grant_type=client_credentials&client_id=…&client_secret=…
```

Response: `access_token` (itself a JWS), `token_type: "bearer"`, **`expires_in: 1800`**, `scope`, `jti`. The docs state this operation must happen only from a backend, never in a mobile app [DOC].

FIB's implementation matches this shape [OBSERVED `../FIB/backend server fib/utility/src/main/java/com/aztech/utility/service/Impl/UqudoServiceImpl.java` lines 46–73: form-encoded `grant_type=client_credentials` with `client_id`/`client_secret`, posted to `{uqudo.url}/oauth/token`, cached in Redis under one key with TTL `expires_in − uqudo.ttl`]. Note that FIB's DTO types `expires_in` as a **String** and parses it with `Long.parseLong` [OBSERVED same file line 58] — type it defensively; a numeric-vs-string change would throw at token refresh, i.e. everywhere at once.

**Is the token per-customer or global?** Global. It is a `client_credentials` token — it represents the tenant, not a user. There is no documented user, session or enrolment scope parameter on the token request [DOC authorisation.md: only `grant_type`, `client_id`, `client_secret`]. **[UNVERIFIED]** whether Uqudo supports a narrower `scope` request parameter or a short-lived SDK-specific token; this is worth one question to Uqudo alongside OQ-010, because the answer would materially improve the posture described next.

**The consequence, stated plainly.** The SDK requires an access token on the device (`setToken`). That token is tenant-scoped and lives 1800 seconds, and there is no documented way to shorten or narrow it. So the constraint "Uqudo client credentials live only in the backend" holds — the *credentials* never leave the backend — but a tenant-scoped bearer token does transit to and reside on the device for up to 30 minutes, and during that window it can in principle call the Info API for arbitrary resource ids. This is inherent to the SDK design, not to our contract. Mitigations available to us: mint the token at point of use so its residual life is maximal-but-bounded and it exists on the device only across one capture; never persist it to disk on the device; drop it from memory as soon as the SDK call returns; and do not reuse the token value handed to devices for backend-only calls if minting is cheap.

**Caching strategy — recommended:**

- One cached token per backend instance (or one in a shared cache), key `uqudo:access_token`.
- Refresh when **less than 300 seconds** of life remain, not on expiry. A token minted at T+1799s is useless to a customer about to launch a 60-second capture.
- Refresh on any `401` from a Uqudo API call, once, then fail.
- Single-flight the refresh so a burst of concurrent stage-8 taps produces one token request, not many.
- **Never** hand a token to a device with fewer than 300 seconds remaining; mint a fresh one instead. The contract already returns `accessTokenExpiresAt` so the app can fail fast rather than launching the SDK into `SESSION_EXPIRED`.

**Rate limits on the token endpoint are not documented** [DOC authorisation.md contains no rate-limit statement]. **[UNVERIFIED]** — this is the one thing that could make "mint fresh per device handoff" unworkable. Measure at S1-02 or ask the vendor.

---

## 8. Image retrieval

### Which ids appear

**From `enroll()`** — `data.documents[0].scan` [DOC scan-object.md]:

| Field | Content |
|---|---|
| `frontImageId` | Front page of the document |
| `backImageId` | Back page — absent for a passport |
| `frontFrameImageId` | Full frame of the front capture (Mobile SDK 3.3.0+) |
| `backFrameImageId` | Full frame of the back capture (Mobile SDK 3.3.0+) |
| `faceImageId` | **The portrait extracted from the document** — the Face Session reference image |

Each has a sibling `…Checksum` in the form `sha256:<digest>` [DOC scan-object.md]. The docs are explicit that the face image "is not included directly in the result but as a reference" [DOC scan-object.md].

**From `faceSession()`** — `data.face` [DOC face-object.md]: `auditTrailImageId` + `auditTrailImageIdChecksum` — the live selfie.

That is five from the scan plus one from the face session: exactly the six AD-004 sizes for.

### The download call

```
GET {apiBase}/api/v1/info/img/{id}
Authorization: Bearer <access_token>
```
"This endpoint allows you to download the image based on the resource id provided in the SDK result and returns directly the binary in the response." [DOC info.md]

FIB's equivalent is `GET {uqudo.imageUrl}/info/img/{imageId}` with a bearer header [OBSERVED `UqudoServiceImpl.java` lines 76–108] — implying their configured `imageUrl` already carries the `/api/v1` prefix. **`{apiBase}` is portal-supplied per tenant** — the Scan API page states the base URL comes from the Customer Portal under Development → Credentials [DOC scan.md]. It is configuration. R-007 again.

Related endpoints on the same API [DOC info.md]: `GET /api/v1/info/{sessionId}` retrieves the final SDK session result server-to-server, where `sessionId` is the JWS `jti`; `DELETE /api/v1/info/{sessionId}` purges cached session data "without waiting for the automatic deletion". The GET is a useful belt-and-braces fallback if a JWS is lost in transit but the session completed. The DELETE is a privacy control we should exercise once our own copies are stored and checksummed.

### When in the chain it runs

**Immediately, inside the same server-side transaction as JWS verification** — journey stage 9's step 3, before the Civil Registry call. Not lazily, not on first operator view.

The reason is retention. Uqudo caches session data and deletes it automatically on an **undocumented** schedule; the image download can return "Resource not found or expired" [DOC info.md, via docs search]. Every hour we defer is an hour of risk that the only copy of an identity document is gone. The stage-8 chain must therefore be:

1. Verify the JWS (§6).
2. Download all five scan images by id, in parallel.
3. Verify each against its `sha256:` checksum. A mismatch is a hard failure — an image that does not match its signed digest is not evidence.
4. Store originals **byte-identical, never re-encoded** (journey audit rules; AD-004 owns the technology).
5. Extract the national number; call the Civil Registry.
6. `DELETE /api/v1/info/{jti}` once 3 and 4 have committed.

Face session: same, for `auditTrailImageId`, at stage 10 step 5.

**Storage technology is AD-004 and I make no recommendation on it.**

---

## 9. Error, cancel and timeout paths

### How the SDK reports

Both native plugins put a JSON string `{code, message, task, data}` into `PlatformException.code` and leave `.message` `null` (Android) / `""` (iOS) [OBSERVED `../FIB/docs/research/2026-06-10-uqudo-sdk-research.md` lines 206–231, quoting `UqudoIdPlugin.kt` `sendError()` and `UqudoIdPlugin.m` `sendPluginError:` in the 3.8.0+1 package]. `task` ∈ `SCAN | READING | FACE | BACKGROUND_CHECK | LOOKUP`.

**Any error mapping written against `PlatformException.message` cannot work.** FIB's `document_scan_screen.dart` `_mapErrorMessage()` matches substrings `'tamper'`, `'print'`, `'screen'`, `'age'`, `'expired'` against a string that is always the generic fallback [OBSERVED `../FIB/mobile/lib/features/document_scan/document_scan_screen.dart` lines 122–130]. Every branch is dead. Do not copy it.

### Codes → journey outcomes

| SessionStatusCode | Journey outcome (stage 8 / 10) | Counts against budget? |
|---|---|---|
| `USER_CANCEL` | "Customer cancels out of the SDK" → back to preparation | **Yes** — journey is explicit |
| `SESSION_EXPIRED` | Token or session dead before capture | **No** — connectivity class; re-request the attempt |
| `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` | Stage 10 "Fails" (liveness, in practice) | **Yes** — one journey attempt; the SDK's internal 1–3 is a sub-budget |
| `SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED` | Not a capture failure — permissions screen | **No** |
| `SESSION_INVALIDATED_CAMERA_NOT_AVAILABLE` | Device fault | **No** |
| `REQUEST_TIMEOUT` | Slow network during the SDK's own upload | **No** — connectivity class |
| `UNEXPECTED_ERROR` | Generic | **No**, but audited and rate-limited; repeated occurrences raise an operator signal |
| `SESSION_INVALIDATED_READING_*` (4 codes) | Unreachable — NFC is not used | n/a |
| `SESSION_INVALIDATED_OTP_TOO_MANY_ATTEMPTS` | Unreachable — Lookup not used | n/a |

Sources: `USER_CANCEL`, `SESSION_EXPIRED`, `UNEXPECTED_ERROR`, the four `READING_*`, `FACE_RECOGNITION_TOO_MANY_ATTEMPTS`, both `CAMERA_*` [DOC android/enrolment-flow.md and android/face-session-flow.md]; `SESSION_INVALIDATED_OTP_TOO_MANY_ATTEMPTS` [OBSERVED FIB research line 275]; **`REQUEST_TIMEOUT` is new in 3.9.0** — "Introduced a new REQUEST_TIMEOUT status code" for slow-network scenarios [DOC pub.dev changelog 3.9.0]. It is not in the 3.8.0-era lists and must be handled.

Note the web SDK (4.x line) uses a **different** code set — `UNAUTHORIZED`, `FORBIDDEN`, `SESSION_EXPIRED_OR_NOT_FOUND`, `MEDIA_*`, `INVALID_CONFIG`, `WASM_NOT_SUPPORTED` [DOC web/operation-error.md]. Do not map mobile codes from the web page.

### The correction to the journey's mental model

Journey stage 8 lists "**Scan fails — the SDK could not read the document**" as a distinct outcome. In practice that outcome mostly does not arrive as an exception:

| Real-world scan problem | How it surfaces |
|---|---|
| Printed copy | `verifications[0].idPrintDetection.score`; rejected in-SDK above 50 unless `allowNonPhysicalDocuments()` [DOC verification-object.md; OBSERVED FIB research lines 289–294] |
| Photo of a screen | `verifications[0].idScreenDetection.score`, same threshold |
| Tampered ID photo | `verifications[0].idPhotoTamperingDetection.score` in a **successful** result |
| Expired document | In-SDK rejection unless `disableExpiryValidation()` — which we must set |
| Poor light / blur / glare | The SDK re-prompts inside its own UI; no exception |

So a customer who cannot get a good capture will typically **cancel** (`USER_CANCEL`), or will produce a JWS whose verification scores the backend then judges. `SCAN_QUALITY_REJECTED` in §5.2 is the backend's verdict on the verification object, not an SDK error code. The four journey outcomes still map cleanly onto our contract — scan failed → `outcome: rejected, reason: SCAN_QUALITY_REJECTED`; cancelled → `sdkError: USER_CANCEL`; upload failed → nothing sent, app retries the same `PUT`; backend rejected the JWS → `reason: VERIFICATION_FAILED` — but the *cause distribution* is not what the stage-8 text implies, and the copy on the failure screen should be written accordingly.

**Timeouts on our own contract:** the `PUT …/result` call must have a generous client timeout and unlimited retries, because the JWS is irreplaceable and the retry is free (idempotent by URL). The `POST …/attempts` call must have a short timeout, because a slow token is better abandoned than launched into.

---

## 10. The stale-artifact case (R-012)

**The question as posed rests on a wrong premise, and the real answer is worse in one way and better in another.**

**The 1800s access token is irrelevant to whether the JWS verifies.** The token authenticates the *SDK session* to Uqudo; the JWS is signed with Uqudo's own key and validated against JWKS. Verification of an hours-old JWS is a pure cryptographic operation plus claim checks, and the access token plays no part [DOC validation-and-parsing.md]. So "the backend rejects it because the token expired" is not a mechanism that exists.

**Three real clocks exist instead:**

1. **`exp` in the JWS.** The claim is documented as present [DOC validation-and-parsing.md]. **Its value is not documented anywhere.** I checked validation-and-parsing, sdk-result, security-and-best-practices and the API pages [DOC, all fetched 2026-08-21]; security-and-best-practices does not mention JWS expiry at all. **[UNVERIFIED]** — this is the single number R-012 turns on and it must be measured, not guessed.
2. **Uqudo-side resource retention.** Session data is cached and "automatically deleted"; the Info API offers a DELETE to purge early [DOC info.md]; image download can return "Resource not found or expired" [DOC info.md, via docs search]. **The window is not documented.** **[UNVERIFIED]**
3. **The face session's 600 seconds** [DOC face.md] — but this expires *before* the SDK runs, so it cannot produce a stale artifact. It produces a failed launch instead.

**Design that does not depend on knowing (1) or (2).** The backend must not decide staleness by a timer. It attempts the chain and reports what actually happened:

| What the backend observes | `reason` | Counts against budget |
|---|---|---|
| `exp` in the past | `ARTIFACT_EXPIRED` | No |
| Signature and claims fine, one or more image ids return 404/410 | `IMAGES_UNAVAILABLE` | No |
| Everything succeeds | `accepted` | n/a |

Both failure reasons produce the same customer-facing message — *this capture can no longer be used; please scan again* — which is exactly the distinction journey stage 12 demands: "the app must distinguish *retrying an upload* from *the artifact is too old to use*, and tell the customer plainly which has happened." Neither counts against the retry budget, because neither is the customer's fault.

**Note the asymmetry this creates.** A JWS can verify perfectly and still be worthless, because the images behind it are gone. Verification success is therefore *not* sufficient grounds to accept a scan — the accept decision must come after the image download and checksum step, not before. That is a real ordering constraint on the stage-8 chain and it is easy to get wrong.

**Add to S1-02, cheap and decisive:** from one real device run, (a) log `exp − iat` from the JWS; (b) attempt `GET /api/v1/info/img/{id}` at T+30 min, T+2 h and T+24 h and record the status codes; (c) attempt `PUT …/result` with the same JWS at T+2 h and record the outcome. Three data points close R-012 permanently.

---

## 11. `SDN_ID`, `SDN_DL`, `SDN_VL` at the current version

**Confirmed present at 3.10.0.** All three appear in the `DocumentType` enum of the dartdoc generated for uqudosdk_flutter 3.10.0 [DOC https://pub.dev/documentation/uqudosdk_flutter/latest/uqudosdk_flutter/DocumentType.html, fetched 2026-08-21]. The 3.10.0 enum has 81 values versus 78 at 3.8.0+1; the additions are `OMN_ID_NATIONAL_MRZ`, `OMN_ID_INVESTOR`, `PASSPORT_OMN`, consistent with the 3.9.0 changelog entry "New Document: Omani Investor ID card support" [DOC pub.dev changelog]. No document types were removed.

Also confirmed at 3.8.0+1 in local source: `SDN_ID`, `SDN_DL`, `SDN_VL` at `lib/uqudosdk_flutter.dart` lines 28–30 [OBSERVED].

**Caveat that matters more than the enum:** the enum value existing in the SDK is not the same as the document type being *enabled on our tenant*. FIB uses `SDN_ID` and `PASSPORT` in production for Sudan [OBSERVED `../FIB/mobile/lib/core/uqudo/uqudo_service.dart` lines 17–26], which proves the tenant-level capability exists at Uqudo — but on FIB's tenant. Whether it is enabled on ours is OQ-010. `UqudoIdPlugin.isEnrollmentSupported(DocumentType.SDN_ID)` gives a runtime answer and should be called once during the S1-02 spike.

The card's open item "Verify `SDN_ID`/`SDN_DL`/`SDN_VL` still present at 3.10.0" can be closed. The tenant-enablement item cannot.

---

## 12. What I could not determine

- **The value of `exp` on a result JWS.** Not in any Uqudo page I fetched. Blocks a definitive R-012 answer. Measurable at S1-02.
- **Uqudo's retention window for session data and images.** Documented only as "automatic deletion" with no period, plus an "expired" error string. Blocks the same.
- **The exact request body of `POST /api/v1/face`** — whether the reference image is multipart binary, base64, or an existing Uqudo image id. The page states only "upload an image (e.g. id photo)" [DOC face.md]. FIB's endpoint name `GetSessionIDWithUqudoImage/{frontImageId}` implies their backend downloads by id and re-uploads bytes, but their controller for it is not in the reference clone and I could not read the implementation. **This is on the critical path for stage 10** and is answered by the OpenAPI spec (below) or by one call during S1-02.
- **The exact response field name for the face session id.** Documented only as "the returning session id".
- **Whether `jti` on a face-session JWS equals the Uqudo face session id or a separate value.** The common object shows `data.sessionId` = the Face API session id [DOC common-object.md]; the Info API says `jti` is the session id [DOC info.md]. Whether these coincide for the face flow is not stated. Affects the replay guard in §6 — for the face flow, fall back to `nonce` binding until proven.
- **Whether `setMinimumMatchLevel()` suppresses the `match:false` JWS.** Inferred from two documented behaviours, not observed. The single most consequential unverified item in this report.
- **Whether the token endpoint supports a narrower `scope` request parameter**, or any shorter-lived SDK token. Not documented.
- **Rate limits on the token endpoint.** Not documented.
- **The `scan.front` / `scan.back` OCR field names for `SDN_ID` and `PASSPORT`.** Uqudo defers to a per-document reference; the Sudan pages were not reachable in the index I retrieved. The backend's parser cannot be finished without one real JWS. S1-02 already commits to logging one.
- **Whether the SDK UI ships Arabic** — R-002, unchanged; device-run only.
- **The OpenAPI/Swagger specification location.** Several pages say "the full YAML or JSON swagger documentation linked in this page" [DOC face.md, info.md, scan.md], but the link is not present in the markdown variants I fetched. It is almost certainly reachable from the rendered HTML pages or from the Customer Portal. **Worth 10 minutes of someone's time with portal access** — it would close four of the gaps above at once.

**Machine-readable documentation worth recording for future sessions:** `https://docs.uqudo.com/llms.txt` is a complete index [DOC]. Appending `.md` to any docs URL yields a markdown variant [DOC]. Unlike the position recorded in the current component card, **the Flutter integration pages now carry real per-flow content** (`integration/flutter/enrolment-flow.md`, `integration/flutter/face-session-flow.md`), though they remain thinner than `integration/android/*`, which is still where the technical detail lives. `pub.dev/documentation/uqudosdk_flutter/latest/` gives version-stamped dartdoc for the current release — the cheapest way to check an enum or signature without downloading the package.

---

## 13. Risks

| If this is wrong | What breaks | Cost to reverse |
|---|---|---|
| **`setMinimumMatchLevel` does not behave as inferred** (§4) | If the SDK returns `match:false` regardless, we lose nothing by omitting it. If it *does* suppress the artifact and we had set it, R-016's fraud signal is silently absent — and nothing fails visibly. This is the asymmetry that decides it: **omitting is safe under both outcomes.** | Zero, if we omit. High if we set it and discover late. |
| **The face reference image must be `frontImageId`, not `faceImageId`** | Face matching either fails wholesale or degrades | One line in the backend. Low. |
| **The 600-second face session window is too short for Sudanese network conditions** | Stage 10 abandonment | Moderate: fall back to route A (`enroll()` + `enableFacialRecognition()`), which has no such window — but that restructures stages 8–10 and moves the face capture before the review screen. High if discovered after the journey is built. |
| **`exp` is short** (say 300s) | The stage-12 upload-retry branch becomes near-useless; almost every reconnection forces a rescan | Low to reverse (the contract already returns `ARTIFACT_EXPIRED`), but it changes the customer experience materially and the journey copy would need rewriting. Measure at S1-02 before writing that copy. |
| **Option A (session sub-resources) is the wrong contract shape** | URL churn across mobile and backend | Low, if caught before the app ships. The idempotency semantics are the part that would hurt to change later, not the paths. |
| **Uqudo rate-limits token issuance** | "Mint fresh per attempt" fails under load | Low: fall back to the shared cached token with early refresh. The contract does not change. |
| **The tenant swap surfaces a hardcoded value** | Production failure at the worst moment (R-007) | Low if every one of `apiBase`, `authBase`, `jwksUri`, `issuer`, `audience`, `clientId`, `clientSecret` and the `identityType → DocumentType` map is configuration from day one. High otherwise. |

---

## 14. Card updates — `docs/components/uqudo-sdk.md`

### Correct these existing lines

**Line 3** — replace the header:
```
Status: researched, not integrated · Last verified: 2026-08-21 · Sources:
docs/sessions/2026-08-19-research-ad-001-stack.md, docs/sessions/2026-08-21-research-ad-002a-uqudo.md
```

**Lines 13–16** — the Flutter-docs caveat is now partly stale:
```
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
```

**Line 48–50, Sudan section** — replace with:
```
`DocumentType` includes `SDN_ID`, `SDN_DL`, `SDN_VL` at **3.10.0** (81 enum values;
3.9.0 added OMN_ID_NATIONAL_MRZ, OMN_ID_INVESTOR, PASSPORT_OMN; nothing removed).
[DOC pub.dev dartdoc 3.10.0, verified 2026-08-21] Also present at 3.8.0+1
[OBSERVED lib/uqudosdk_flutter.dart lines 28-30]. Enum presence is NOT tenant
enablement — call `UqudoIdPlugin.isEnrollmentSupported(DocumentType.SDN_ID)` at
S1-02. FIB uses SDN_ID + PASSPORT in production on ITS tenant. [OBSERVED]
```

**Lines 74–79, Backend surface** — replace with:
```
## Backend surface (framework-agnostic, plain HTTPS)
- Token: `POST https://auth.uqudo.io/api/oauth/token`, form-encoded,
  `grant_type=client_credentials` + client_id + client_secret. Returns access_token
  (itself a JWS), token_type=bearer, `expires_in` **1800**, scope, jti. Backend only —
  the docs forbid this call from a mobile app. [DOC uqudo-api/authorisation.md]
  Corroborated [OBSERVED ../FIB/.../UqudoServiceImpl.java lines 46-73].
- The token is TENANT-scoped, not per-customer. No documented per-user or per-session
  scope. [DOC] It must nevertheless be handed to the device — the SDK requires it —
  so a tenant-scoped bearer token lives on the handset for up to 1800s. Inherent to
  the SDK. Mint at point of use, never persist to disk, drop after the call.
- Image: `GET {apiBase}/api/v1/info/img/{id}`, `Authorization: Bearer …`, returns raw
  binary. [DOC uqudo-api/info.md] Can return "Resource not found or expired". [DOC]
- Session result server-to-server: `GET {apiBase}/api/v1/info/{jti}`.
  Purge early: `DELETE {apiBase}/api/v1/info/{jti}`. [DOC uqudo-api/info.md]
- Face session: `POST {apiBase}/api/v1/face` uploading a reference image, returns a
  session id for the SDK. **Session and image auto-deleted after 10 minutes.**
  [DOC uqudo-api/face.md] Request body format UNVERIFIED.
- Also on that API: `POST /api/v1/face/match` (server-side 1:1, NO liveness),
  `POST /api/v1/face/one-to-n/{search,insert}`, `DELETE …/search/{id}`. [DOC]
- `{apiBase}` is portal-supplied per tenant — configuration, never a constant. [DOC
  uqudo-api/scan.md]
- Client credentials live ONLY in the backend. Never in the app.
```

### Add these new sections

```
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

## ⚠️ Do NOT call FaceSessionConfigurationBuilder.setMinimumMatchLevel()
If the SDK enforces the threshold it consumes a low match as a failed attempt, retries
the user, and terminates with TOO_MANY_ATTEMPTS — indistinguishable from a liveness
failure, with NO signed match:false artifact. R-016's fraud signal would be destroyed
silently. Leave it at the -1 default; enforce the threshold server-side from the JWS.
[UNVERIFIED — inferred from two documented behaviours; prove at S1-02 by presenting a
non-matching face, once with the threshold set and once without.]

## Result validity and the stale-artifact case (R-012)  [2026-08-21]
The 1800s ACCESS TOKEN is irrelevant to whether a JWS verifies — verification is
JWKS-based and the token plays no part. [DOC validation-and-parsing.md] Three other
clocks matter:
- `exp` in the JWS: claim documented as present, **value NOT documented anywhere**.
  [UNVERIFIED — measure exp-iat at S1-02]
- Uqudo resource retention: "automatic deletion", period undocumented; image download
  can return "Resource not found or expired". [DOC info.md] [UNVERIFIED]
- Face session + its image: **10 minutes**. [DOC face.md] Expires before the SDK runs.

Design rule: never decide staleness by a timer. Verify → download images → checksum.
`exp` past → ARTIFACT_EXPIRED. Images 404 → IMAGES_UNAVAILABLE. Neither counts against
the retry budget. **A JWS can verify perfectly and still be worthless because the
images are gone — so the accept decision must come AFTER the image download, not
after verification.**

## JWS verification  [DOC 2026-08-21]
- JWKS `https://id.uqudo.io/api/.well-known/jwks.json` — CONFIG, never a constant (R-007)
- Pin `alg` to RS256 only. Reject none/HS*/EC.
- Cache JWKS 15 min, refresh-ahead, rate-limit outbound fetches. On unknown `kid`:
  one forced refresh, then fail closed. Never fetch per request. [DOC guidance]
- Validate: iss, aud (= our client id, CONFIG — the tenant changes), exp, iat (±60s),
  `jti` == the sessionId we passed to setSessionId [DOC security-and-best-practices.md
  + info.md], `data.nonce` == the nonce we issued, `documentType` == what we requested,
  and `jti` not previously accepted (global replay guard).
- Library: `com.nimbusds:nimbus-jose-jwt` at the version Spring Boot manages — already
  on the classpath via spring-security-oauth2-jose, and JWKSourceBuilder gives caching,
  refresh-ahead and rate limiting. Uqudo's own sample uses io.jsonwebtoken + com.auth0
  jwks-rsa [DOC]; equally valid, two more direct dependencies.
- Failure: one exception type, one audit event with a category, raw JWS stored
  byte-identical, coarse VERIFICATION_FAILED to the app. Never echo the JWS.

## Required SDK configuration for THIS journey
MUST set: `DocumentBuilder.disableExpiryValidation()` — the journey accepts genuine
expired documents (stage 9, RESOLVED); without it the SDK rejects them.
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
never re-encoded. Then DELETE /api/v1/info/{jti}. Storage tech is AD-004.
```

### Replace the Open items block (lines 90–97)

```
## Open items
- [ ] S1-02: verify scan + liveness at runtime under targetSdk 36, physical arm64
- [ ] S1-02: record `exp - iat` from a real JWS  (closes R-012 clock 1)
- [ ] S1-02: attempt image download at T+30m / T+2h / T+24h  (closes R-012 clock 2)
- [ ] S1-02: present a NON-MATCHING face — with and without setMinimumMatchLevel —
      and record whether a match:false JWS is issued  (the §4 decision rests on this)
- [ ] S1-02: `isEnrollmentSupported(SDN_ID)` on the live tenant
- [ ] S1-02: confirm the SDK UI language, Arabic in particular (R-002)
- [ ] Determine the POST /api/v1/face request body format — image id vs bytes
- [ ] Determine whether `jti` on a face-session JWS equals the Face API session id
- [ ] Locate the OpenAPI/Swagger spec via the Customer Portal
- [ ] Ask Uqudo: any narrower token scope, and token-endpoint rate limits
- [ ] Obtain our own tenant + credentials for Sudan document types (OQ-010)
- [x] SDN_ID / SDN_DL / SDN_VL present at 3.10.0 — CLOSED 2026-08-21 [DOC]
```

### Also needs correcting outside the card

- **`RISKS.md` R-016**, mitigation column: "Uqudo returns both results with confidence figures" is **false**. Replace with: *Uqudo returns face match as `match` (bool) + `matchLevel` (1–5) in a signed JWS. Liveness has no result field and no score — it is a gate inside the SDK, and a liveness failure produces an exception, not a JWS. The two remain cleanly distinguishable and both are recorded, but only face match carries a confidence figure. The signal survives only if `setMinimumMatchLevel()` is NOT set on the face session.* [DOC 2026-08-21]
- **`RISKS.md` R-012**: the 1800s token is not the mechanism. Replace with the three-clocks framing in §10.
- **`docs/journeys/customer.md` stage 10**, the line "Uqudo returns both results with confidence figures. Both are recorded." — same correction. The stage's *design* is unaffected; only this sentence is wrong.
- **`docs/journeys/customer.md` stage 10**, the point-of-use token rule: add that the Uqudo **face session** expires in 600 seconds, and that this, not the 1800s token, is the binding constraint at this stage.

---

## 15. Noticed in passing

Not investigated further; recorded so it is not rediscovered.

- `../FIB/backend server fib/utility/src/main/java/com/aztech/utility/service/Impl/UqudoServiceImpl.java` `getImageById()` (lines 76–108) base64-encodes the downloaded image, **logs it**, and then returns `""` on every path — the method can never return an image. It also recurses into itself after a token refresh with no depth guard. FIB's problem, not ours; do not port the shape.
- The same file logs the full token response body at INFO (line 53). Their tree.
- The FIB clone contains a `utility - Copy/` directory duplicating the whole backend source. Anyone grepping `../FIB` will get every hit twice.
- `../FIB/mobile/lib/core/network/api_service.dart` has backslashes where escapes were intended — `\ TODO(...)` at line 119 and `'\OpenCIFData'` at line 136. Reading FIB as a syntax reference is unsafe.
- The FIB backend's Redis token cache with TTL `expires_in − configurable_margin` is a sound pattern and worth reusing in shape, if not in code.
- FIB's `GET /token` endpoint returns the Uqudo access token *and* uses the same value as the app's own bearer auth token [OBSERVED `../FIB/docs/API_REFERENCE.md` line 36]. Conflating a third-party tenant token with our own session authentication is not something to reproduce.
- `../FIB/backend server fib/utility/src/main/resources/application.yml` binds `uqudo.url`, `uqudo.client`, `uqudo.secret`, `uqudo.ttl` and `uqudo.imageUrl` via `UqudoReaderConfig.java`. Live values for another bank's tenant are present in that file. **No value from it is reproduced anywhere in this report**, and none should be copied into this repository, a prompt, a log or a session report.

---

**Sources:**
- [Uqudo docs index (llms.txt)](https://docs.uqudo.com/llms.txt)
- [Validation and Parsing](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/validation-and-parsing.md)
- [Security & Best Practices](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/security-and-best-practices.md)
- [Common Object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/common-object.md)
- [Scan Object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/scan-object.md)
- [Face Object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/face-object.md)
- [Verification Object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/verification-object.md)
- [Face API](https://docs.uqudo.com/docs/kyc/uqudo-api/face.md)
- [Info API](https://docs.uqudo.com/docs/kyc/uqudo-api/info.md)
- [Scan API](https://docs.uqudo.com/docs/kyc/uqudo-api/scan.md)
- [Authorisation API](https://docs.uqudo.com/docs/kyc/uqudo-api/authorisation.md)
- [Android Enrolment Flow](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/enrolment-flow.md)
- [Android Face Session Flow](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/face-session-flow.md)
- [Flutter Enrolment Flow](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/flutter/enrolment-flow.md)
- [Flutter Face Session Flow](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/flutter/face-session-flow.md)
- [Web Operation Error (4.x line — different code set)](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/web/operation-error.md)
- [uqudosdk_flutter changelog (pub.dev)](https://pub.dev/packages/uqudosdk_flutter/changelog)
- [DocumentType enum, dartdoc 3.10.0](https://pub.dev/documentation/uqudosdk_flutter/latest/uqudosdk_flutter/DocumentType.html)

---

## 16. Commit/push proof

```
$ git log --oneline -1
768453d docs: file AD-002a Uqudo integration research and reconcile plan files

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
</content>
