# S1-02 — Uqudo Android device spike (live, 2026-09-03)

Device: Huawei Y5 2019 (AMN-LX9), MediaTek MT6761, **Android 9 / API 28**, 2 GB RAM, cracked
lower-left glass. SDK: `uqudosdk_flutter` **3.10.0** (`io.uqudo.sdk:sdk-bundle-Uqudo:3.10.0`),
FIB's tenant. Document: the developer's own **passport** — no Sudanese ID card was available, so
**SDN_ID was not scanned**. Second person: **not available** — measurement 4's D/E runs were not
taken. Times below are UTC unless marked local (UTC+2). All raw material (JWS, payloads, images)
stays in `backend/target/uqudo-spike/` (gitignored); only redacted *shapes* appear here.

## What this session does NOT close

This device runs Android 9 (API 28) — eight versions below the API 36 that targetSdk 36 behaviour
changes need to be observable. **R-006 stays 🟡 Watching regardless of this report.** A second run
on an API 36+ handset is still required; nothing here may be read as closing it.

## Setup that actually worked (and what didn't)

- Router `gigacube-D3FE` isolates its Wi-Fi clients: phone (192.168.0.7) could not reach the laptop
  (192.168.0.5) on 8000/8080 even after an inbound firewall rule. USB (MTP and adb) did not work on
  this phone. **What worked: an iPhone hotspot with both devices on it** — laptop 172.20.10.3, backend
  reached at `http://172.20.10.3:8080`, APK served by `python -m http.server 8000`. Firewall rule
  `fru-spike-lan-8000-8080` (inbound TCP 8000,8080, all profiles) added by UAC elevation.
- Credentials: `UQUDO_CLIENT_ID` / `UQUDO_CLIENT_SECRET` / `UQUDO_SPIKE_KEY` in the root `.env`
  (gitignored, `git check-ignore .env` → `.env`), read by the `uqudo-spike` Spring profile only.
- Laptop-side proof before any phone involvement — `GET /api/v1/spike/uqudo/ping?mint=true`:
  `{"tokenMinted":true,"expiresIn":1859,"tokenLength":938,"mintLatencyMs":854,"jwksKeyCount":1,
  "kids":["426efc44b703495e9b14351cd25e7d5b"]}`; without the key → 403.
- Two spike-code defects fixed live: (1) the phone keyboard auto-inserted a space after `172.` in
  the URL field; (2) `POST /face-session` 500'd with `ClassNotFoundException:
  org.reactivestreams.Publisher` — `MultipartBodyBuilder` references Reactive Streams, absent from a
  WebMVC-only classpath; replaced with a `LinkedMultiValueMap` + `ByteArrayResource` part. One
  throwaway Face Session (`aa924ff5…`) was created from the laptop to verify the fix and never used.

## Measurements — every one, answered or not

| # | Measurement | Result |
|---|---|---|
| 1 | `exp − iat` | **Enrolment JWS: 7200 s.** Face JWS: **600 s** (both runs). `iat` is stamped at scan completion, not at tap (`iatMinusTapAt` = 52 s = the scan's duration). |
| 2 | Image availability over time | T+0: all three passport images **200**, checksums match. **T0+25, +31, +35, +45 min: still 200** (each with a fresh Uqudo `Date` header) — the documented "kept only for the duration of the session, 30 minutes" is **not observed** counted from `iat`; **T0+90 min (45 min after the previous access): still 200** — a 30-minute last-access TTL is excluded as well. **T0+120 min — one second after `exp` — all three images 404** (`responseDate 22:52:42Z`, `iat` 20:52:39Z, `exp` 22:52:39Z). **Answered: images live exactly as long as the enrolment JWS, 2 hours from `iat`; the documented "30 minutes" is wrong.** T0+125 redundant; T+24 h dropped by decision. |
| 3 | Resubmission at T+2h | **Answered** at T0+120: `signatureValid: true`, `kidFoundInJwks: true`, `expExpired: true`, every image **404**. A JWS re-presented after `exp` still passes RS256/JWKS verification — **our verifier must enforce `exp` itself** (`ARTIFACT_EXPIRED`), because Uqudo's only "enforcement" is that the images are gone. Definition: Uqudo has no resubmission endpoint; the spike computes `expExpired`, never enforces it. |
| 4 | `setMinimumMatchLevel` A/B | **NOT TAKEN** — no second person, and none before finalisation (product-owner decision). Baseline C (own face, no threshold) taken twice: `match=true, matchLevel=5` both times. **Closed by evidence on 2026-09-04 — see the section below**: the mobile SDK gates on match internally (AAR string `uq_face_dialog_face_not_match`), so R-016's `match:false` channel is most likely unreachable on the success path; decision BL-028 (`returnDataForIncompleteSession`). Residual: verify at the first two-person test. |
| 5 | `faceImage` vs `faceImageId` | **References.** Keys observed: `faceImageId`, `frontImageId`, `frontFrameImageId` (+ `backImageId: null` for a passport), checksum siblings named `<key>Checksum` → `faceImageIdChecksum` etc. `largeStrings=[]` — no inline base64 anywhere. R-024 confirmed live. |
| 6 | `isEnrollmentSupported(SDN_ID)` | SDK half: `true` for SDN_ID and PASSPORT — but this is `DocumentType.valueOf(x).enrollmentSupported` (`UqudoIdPlugin.kt:717`), a compile-time enum property, **not a tenant check**. Tenant half: a **PASSPORT** enrolment succeeded on FIB's tenant; **SDN_ID tenant licensing not tested** (no card). |
| 7 | SDK UI language | **English**, with `setLocale('ar')` called before the first `enroll()`. Cause settled from the artifact: `sdk-bundle-Uqudo-3.10.0.aar` contains **only `res/values/`** — no `values-ar`, no language folder at all. Uqudo ships no translations. Override surface (`uq_*` strings in the flows we use): `face` 63, `error` 51, `scan` 47, `button` 9, `help` 6 ≈ **176**; `read` 205 / `lookup` 106 are unused flows. R-002 confirmed. |
| 8 | One complete real JWS logged | **Yes** — `backend/target/uqudo-spike/sessions/1969a39d-…/enrolment.jws` (6319 chars) plus two face JWS (1005 / 1062 chars). Redacted shapes below. R-034's parser rewrite is unblocked. |
| 9 | Works at all on 2 GB / API 28 / fixed-focus front camera; timing | **Yes, no OOM, no crash.** Scan: SDK screen visible **3.5 s** after tap; total **52 s** including one `SCAN_DOCUMENT_BLUR_DETECTED` retry prompted by the SDK. Face: screen visible **0.9 s** after tap; **49 s** and **42 s** end to end, with `FACE_BLUR_DETECTED` / `FACE_INCORRECT_DISTANCE_DETECTED` / `FACE_INCORRECT_POSITION_DETECTED` guidance events before COMPLETE (guidance, not attempts — one attempt each). Backend-side verify + 3 image downloads: ~1.3 s. |
| + | Device attestation | **Absent from every JWS** (`deviceAttestationShape: "absent"` on enrolment and both face results). What IS present is `data.source` = `{sdkType, sdkVersion, sourceIp, devicePlatform, deviceVersion, deviceManufacturer, deviceModel}` — device info, no risk flags. Whether attestation is a tenant-enabled feature is unknown; BL-027 stands. |

## Findings that change the backend (each observed, not inferred)

1. **Enrolment `jti` == the `sessionId` we minted** (`jtiEqualsSessionId: true`, a UUID) and
   `data.nonce` echoes our nonce. AD-002a's server-minted session/nonce contract holds as designed.
2. **Face-session binding is `data.sessionId`, NOT `jti`.** `jti` is a separate UUID
   (`344dee19…` vs face session `d2186cf1…`; `jtiEqualsFaceSessionId: false`). `StubUqudoClient
   .verifyAndParseFaceSession` validates `jti == expectedFaceSessionId` — it would reject every real
   result. **Face sessions DO echo a nonce**: with `setNonce` on the builder, `data.nonce` appeared
   and matched (`nonceEchoed: true`; `dataKeys: [face, sessionId, nonce]`). Without it: `[face,
   sessionId]`.
3. **`DELETE /api/v1/info/{id}` returns 204 for any UUID** — with the face JWS's `jti` it returned
   204 and the audit image was **still 200** afterwards (6.25 min after `iat`); with the face
   **session id** it returned 204 and the image went **404** immediately (confirmed on a second
   session). `LivenessService.purgeSession(parsed.jti())` for face sessions is therefore a **silent
   no-op**; the correct id is `data.sessionId`. For enrolments `jti == sessionId`, so that path is
   unaffected.
4. **Image retention is the JWS lifetime — 2 hours — not the documented 30 minutes**: 200 at
   +25/+31/+35/+45/+90 min, 404 at +120 min (one second past `exp`). R-012's 30-minute retry
   window was wrong in the safe direction; Stage 13 copy can say "within 2 hours".
5. **Real payload shape vs the S3-12/S3-13 stub** — the stub is wrong in every structural
   assumption it marked `[UNVERIFIED]`: no top-level `documentType` (it is
   `data.documents[i].documentType`); `data` has `source` and `verifications` siblings; anti-spoof
   scores live in `verifications[i].idScreenDetection.score` etc. (observed 17.1 / 10.54 / 0.68),
   not inside `scan`; OCR fields sit under `scan.front` (passport: 30 fields incl. `identityNumber`
   13 chars, `fullName`, `fullNameArabic`, `mrzVerified: true`, `chipAvailable: true`) with
   `scan.back: {}`; the face object is `{match, matchLevel, error, falseAcceptRate,
   auditTrailImageId, auditTrailImageIdChecksum}`. Header: `alg RS256`, `kid`, no `typ`.
6. **Token `expires_in` is 1859 s, not 1800**; scope `KYC_SCAN_API KYC_FACE_SEARCH_SDK
   KYC_FACE_FLOW KYC_INFO_SDK KYC_SCAN_SDK KYC_EVENT_SDK`. `aud` is the tenant client id —
   identity-bearing, redacted everywhere (the spike's shape whitelist was corrected live).
7. **Image sizes**: portrait 155 KB, front 618 KB, front frame 1.77 MB, face audit trail 414/422 KB
   — a passport scan is ~2.5 MB of JPEG before the frame exclusion AD-004 already applies.

## Redacted shapes (keys, types, lengths — no values)

Enrolment (`enrolment.shape.json`, trimmed to structure):
```
{ iss: "https://id.uqudo.io", aud: <string 36 — tenant client id, redacted>, iat, exp, jti: <uuid>,
  data: {
    source: { sdkType, sdkVersion, sourceIp, devicePlatform, deviceVersion, deviceManufacturer, deviceModel },
    nonce: <our nonce, echoed>,
    documents: [ { documentType: "PASSPORT",
      scan: { front: { secondaryId, primaryId, fullName, fullNameArabic, dateOfBirth(+Formatted),
                       dateOfBirthFull(+Formatted), placeOfBirth, issueDate(+Formatted),
                       dateOfExpiry(+Formatted), dateOfExpiryFull(+Formatted), documentNumber,
                       identityNumber, passportNumber, passportCountryCode, passportType, nationality,
                       issuer, placeOfIssue, sex, gender, documentCode, mrzText, mrzVerified: true,
                       opt1, chipAvailable: true },
              back: {},
              faceImageId, faceImageIdChecksum, frontImageId, frontImageIdChecksum,
              frontFrameImageId, frontFrameImageIdChecksum, backImageId: null, backImageIdChecksum: null },
      reading: null, face: null, lookup: null } ],
    verifications: [ { documentType: "PASSPORT",
      dataConsistencyCheck: { enabled, fields: [ { name, match: MATCH|MATCH_PARTIALLY, sources: [ { source, documentSide, name, value } ] } ] },
      sourceDetection: { enabled, allowNonPhysicalDocuments: false, selectedResolution, optimalResolution: true },
      idScreenDetection: { enabled, score: 17.1 }, idPrintDetection: { enabled, score: 10.54 },
      idPhotoTamperingDetection: { enabled, score: 0.68 },
      mrzChecksum: { enabled, finalCheckDigit, valid: true, checkDigits: [ { fieldName, fieldValue, checkDigit, valid } ] },
      reading: { enabled: false }, biometric: { enabled: false }, lookup: { enabled: false } } ] } }
```
Face (`face-result.shape.json`, nonce run):
```
{ iss, aud: <redacted>, iat, exp (= iat + 600), jti: <uuid, ≠ sessionId>,
  data: { face: { match: true, matchLevel: 5, error: null, falseAcceptRate: null,
                  auditTrailImageId, auditTrailImageIdChecksum },
          sessionId: <the id from POST /api/v1/face>, nonce: <ours, echoed> } }
```

## Timeline (spike.log, local UTC+2)

- 22:50:45 phone ping 200 · 22:51:00 `isEnrollmentSupported` SDN_ID/PASSPORT true.
- 22:51:47.8 `enroll(PASSPORT)` tap → 22:51:51.3 SDK VIEW → 22:52:15.4 BLUR_DETECTED → 22:52:32.8
  FRONT_PROCESSED → 22:52:39.8 FINISH; backend verified 22:52:40.4Z(+2h); images 200 at 22:52:41.
- 23:14:23 face session C created (201, 0.6 s) → 23:15:13 FINISH, `match=true/5`.
- 23:21 purge by face jti → 204, image 200; purge by face sessionId → 204, image 404.
- 23:24:34 face session C2 (nonce on) → 23:25:16 FINISH, `match=true/5`, nonce echoed; purged.
- 23:17:39 / 23:23:40 / 23:27:40 scheduled rechecks: all images 200, signature valid.

## Closing the untaken measurements by evidence (2026-09-04, same session)

Product-owner constraint: no Sudanese ID card and no second person will be available before
the solution is finalised, and no API 36 device will be bought. Each gap was therefore closed
by documentation + reasoning + the one production Uqudo integration on this laptop, with the
residual stated. Two corrections first:

- **FIB's mobile app was never tested in production** (product-owner statement) — every
  `[OBSERVED ../FIB/mobile]` citation about SDK behaviour is downgraded to "what FIB wrote".
  FIB's **web app** (`../FIB/Web_Code`, Uqudo Web SDK 4.x, source maps) IS in production on the
  same tenant and is valid evidence for the platform-independent **result shape** only.
- **Every `docs.uqudo.com` URL in the card 404s** — the docs moved to `/docs/kyc/…`; the whole
  corpus is now one file (`/docs/llms-full.txt`), which is how the absence claims below were made.

**SDN_ID (measurement 6's tenant half, and the parser contract) — closed by evidence.**
- Tonight's passport JWS proved Uqudo's field pages are a *subset guarantee*: every documented
  field appeared, spelled as documented, plus extras. The SDN_ID page (two versions, front/back
  tables) is the same page family → the parser is written from those tables, tolerating extras.
- Side placement: `scan-object` gives no rule; the country tables are the authority,
  corroborated by a two-sided UAE_ID sample in Uqudo's Scan API docs and by FIB's production web
  app, which for National ID reads `scan.front.name` (Arabic) and the English name from
  `scan.back` (`back.fullName` in the code; the docs say `back.name` — accept either), spreads
  `...scan.front, ...scan.back`, reads `frontImageId`/`backImageId`, and gates on
  `verifications[0].{idScreenDetection,idPrintDetection,idPhotoTamperingDetection}.score` — the
  exact structure observed tonight.
- Corrections to the stub: the English name is NOT `nameEn` (a key that exists nowhere); the
  card-version discriminator is `scan.front.bloodType`, not the name. `identityNumber` is
  fail-closed. FIB's tenant is licensed for SDN_ID (production web).
- **Residual:** front/back placement never observed for SDN_ID; a smudged blood-type line
  misclassifies a new card as old (harmless for provenance). Verified at the bank's UAT.

**Face mismatch (measurement 4) — premise contradicted; design made robust to either answer.**
- Facts: the plugin never invokes the native `setMinimumMatchLevel` unless > 0 (we run in the
  SDK's default state) [OBSERVED plugin source]; `POST /api/v1/face` has **no threshold
  parameter** [OBSERVED OpenAPI]; no Uqudo page states when a `match:false` JWS is issued or what
  "failed facial recognition attempt" means [DOC — absence, whole corpus]; the AAR ships
  **`uq_face_dialog_face_not_match = "Your face didn't match"`** beside `uq_face_try_again`
  [OBSERVED AAR]; FIB's production web integration sets no threshold and never reads `match`
  [OBSERVED source maps]; the web line has the same `FACE_LIVENESS_FAILED` / `FACE_NO_MATCH`
  split [DOC].
- Most defensible reading: the SDK shows the customer "didn't match", retries, and after
  `maxAttempts` terminates with `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` — the
  same channel as a liveness failure. AD-002a's *premise* "a mismatch arrives as a signed
  `match:false` JWS" is most likely wrong; its *decision* (two invocations, backend-minted Face
  Session) is unaffected. Both S1-02 runs (`match:true/5`) are consistent with either reading.
- Consequence in plain terms: an impostor and an honest customer with a bad camera moment look
  identical to the backend (both "face failed, 3 attempts"). Nothing breaks — S3-13 handles both
  channels — but the fraud signal R-016 exists for is silently absent.
- **Decision (product owner): Option B — enable `returnDataForIncompleteSession()` on the face
  session, provided the cost stays "one builder call + one optional field"; BL-028.** It is the
  only documented route to a signed artifact from a terminated session (delivered in the error
  object's `data`). Whether that artifact carries `match:false` is `[UNVERIFIED]`; worst case it
  is an audit record without the match detail. Fallback to Option A recorded in BL-028.
- R-016 reopened 🔴; PROJECT_PLAN's AD-002a premise marked *contradicted*, not rewritten; the
  "threshold 3" wording corrected (3 is ours, borrowed from `/face/match`; Uqudo applies none).
- **Residual:** closable only by a second person's face — the first two-person test (UAT).

**R-006 (API 36) — accepted Phase 1 residual**, verified at UAT device coverage.

**Retention bound and T+24h** — the T+24h recheck is **dropped by decision** (DELETE returns
204 for any id, and a 2-hour-lived image is certainly gone by then). The T0+90/+120/+125 checks
ran automatically; results in the measurement-2 row.

**Other items surfaced by the same evidence pass**, recorded in the card: `face.error`,
`face.falseAcceptRate` and `data.source` are undocumented vendor fields (quarantine applies);
`deviceAttestation` is documented as standard for both flows with no enablement model, so its
absence is unexplained ("Ask Uqudo"); anti-spoof thresholds are 50/50/**70**, not uniformly 50;
Uqudo documents a Government DB Lookup for the Sudan ID (noted under AD-002b).

## Card and plan-file updates made

R-021 retired (7200 s / 600 s). R-024 retired (confirmatory). R-002 confirmed — no translations in
the AAR. R-034: parser rewrite unblocked, not done. R-012: PENDING update. R-006: untouched, pointer
added. `docs/components/uqudo-sdk.md`: new "S1-02 observed" section flipping every settled
`[UNVERIFIED]`. EXECUTION_PLAN S1-02 → 🔵 with the per-measurement table above. OQ-010: FIB tenant
confirmed for PASSPORT enrolment + face session; own tenant still open.

## Gates

Backend, `./mvnw verify -Pdb-integration-test` (spike package excluded from JaCoCo by design):
```
[INFO] Tests run: 639, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 392 files clean - 0 needs changes to be clean, 0 were already clean, 392 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```
JaCoCo line ratio from `target/site/jacoco/jacoco.xml`: 4783 / 5199 = **92.00 %**. Re-run after the
last hygiene edit (aud no longer logged): `./mvnw verify -Pdb-integration-test` → `Tests run: 639, Failures: 0, Errors: 0, Skipped: 0` · Spotless clean · `All coverage checks have been met` · `BUILD SUCCESS`.

Mobile, `fvm flutter analyze`: `No issues found!` (ran twice, before and after the spike-key field).
`fvm dart run tool/check_coverage.dart`:
```
01:58 +225: All tests passed!
Line coverage: 85.03% (2181/2565 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```
Unchanged from S5-05 (2181/2565) — the unimported `lib/spike/` files do not appear in lcov, as
predicted. No coverage gate applies to spike code (task tail item 2). Debug APK: `unzip -l` shows
Uqudo's `libscanning-native-lib.so`, `libscanning-native-jni-lib.so`, `libopencv_java4.so` under
`lib/arm64-v8a/` and `lib/armeabi-v7a/` and absent from `lib/x86_64/` — consistent with the
documented ABI restriction.

iOS support check at addition (hard rule): `uqudosdk_flutter-3.10.0/ios/uqudosdk_flutter.podspec`
pins `UqudoSDK '3.10.0'` and `OpenSSL-Universal '3.3.3001'` exactly, `:ios, '12.0'` — R-003 unchanged.

## Commit proof

```
$ git log --oneline -1
6be9f8b S1-02: Uqudo device spike -- first real JWS, face session, retention and purge semantics

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Follow-up commits: the T0+90 / T0+120 / T0+125 rechecks and the T+24h recheck land as a dated
addendum to this report (R-012's bound), with their own commit proof.
