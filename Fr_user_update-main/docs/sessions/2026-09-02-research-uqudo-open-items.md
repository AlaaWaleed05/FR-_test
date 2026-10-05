# Research report — closing two open items on `docs/components/uqudo-sdk.md`

**Scoped reading taken:** "which applies to the Flutter SDK result" = the payload of the JWS string returned by `UqudoIdPlugin.enroll()`, as distinct from the JSON body returned by the server-side Scan API `POST /api/v1/scan`. Readings not pursued: whether the *Scan API* is ever used by this project (it is not — we use the SDK), and whether any other Uqudo surface returns portrait bytes.

Read first: `PROJECT_PLAN.md`, `docs/components/uqudo-sdk.md`, `docs/components/uqudo-api-findings.md`. `../FIB` not read, per instruction. Nothing below contradicts a decision recorded in PROJECT_PLAN.md; item 1's answer **confirms** AD-002a's existing "images are ids, not bytes" line rather than overturning it.

---

## Item 1 — `faceImage` bytes vs `faceImageId` reference

### Answer

**The SDK result carries `faceImageId`, a reference. Not base64.** Uqudo documents this explicitly, and documents it as the *single named exception* to the rule that the SDK result's OCR fields are the same fields the Scan API pages describe. The two pages are not inconsistent: the country-specific field pages sit under the **Scan API** (`POST /api/v1/scan`) documentation tree and describe *that API's* JSON response, where `faceImage` is base64 and is controlled by a query parameter that does not exist in the SDK. The SDK's `scan.front` / `scan.back` reuse those same field definitions **minus** the face image.

**This is answerable by reading, and it is now answered.** It is not a runtime-only fact. The Uqudo↔backend round trip (`GET /api/v1/info/img/{faceImageId}` → re-upload bytes to `POST /api/v1/face`) is real and unavoidable. **Stage 10 as implemented is correct and needs no rework.**

### Evidence

- [DOC] `scan-object`, SDK result data structure — the `front` attribute description reads verbatim: *"See API Documentation for the Scan / OCR fields. The only exception is the face image that is not included directly in the result but as a reference as per the common fields described above"*. The `back` attribute description is the same sentence ending *"...as a reference as per the `faceImageId` described above"*. https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/scan-object.md (undated page; current as fetched 2026-09-02).
- [DOC] Same page states it describes the SDK result, and lists the image attributes as `faceImageId` / `faceImageIdChecksum`, `frontImageId` / `frontImageIdChecksum`, `frontFrameImageId` / `frontFrameImageIdChecksum` (SDK 3.3.0+), `backImageId` / `backImageIdChecksum`, `backFrameImageId` / `backFrameImageIdChecksum`. `faceImageId` = *"The id of the image of the face of the user in the document"*. No `faceImage` field appears anywhere on it.
- [DOC] The base64 field belongs to the Scan API. `POST <Api base url>/api/v1/scan` is a multipart upload API taking `frontImage`/`backImage` binaries, with query parameter **`exportFaceImage` (boolean, default `true`) — "Enable or disable face detection in the document"** — and a response whose sample shows `"faceImage": "<base64 encoded face image>"`. The page also carries the instruction *"Utilize this API within your backend and do not make direct calls within your frontend application."* https://docs.uqudo.com/docs/kyc/uqudo-api/scan.md
- [DOC] The Sudan field page is a child of that Scan API tree — `docs/kyc/uqudo-api/scan/country-specific-ids/sdn_id-sudan-id`. On it, `faceImage` is *"Base64 encoded face image of the user. The format is the same as per the image uploaded, e.g. png or jpg"*, identically in both card versions, and **`faceImageId` does not appear on that page at all**. The phrase "the same as per the image uploaded" is itself a tell: it presumes a caller-uploaded image, which only the Scan API has.
- [DOC] `sdk-result/download-a-resource` lists what an SDK result gives you as downloadable resources: *"Images of scanned ID Documents, Face images extracted from ID Documents, and Selfies captured during face recognition"*, retrieved via the Info image API. A face image the SDK returned inline would not be listed as a downloadable resource. https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/download-a-resource.md
- [OBSERVED] There is no Flutter-specific divergence, because the Flutter plugin is a pass-through and performs no result transformation. `C:\Users\DELL\AppData\Local\Pub\Cache\hosted\pub.dev\uqudosdk_flutter-3.8.0+1\android\src\main\kotlin\io\flutter\plugin\uqudo\uqudosdk_flutter\UqudoIdPlugin.kt` line 866 returns the native result verbatim: `pendingResult?.success(data!!.getStringExtra("data"))`. Dart side, `UqudoIdPlugin.enroll()` is typed `Future<String>` and returns the channel result unmodified (`lib/UqudoIdPlugin.dart` lines 24–28). The plugin has no parsing, no JSON handling on the success path, and no image code.
- [OBSERVED] Uqudo's own documentation is structured to make the platform question moot: there is one `sdk-result/*` tree for all platforms and no per-platform result documentation. Confirmed against the full index at https://docs.uqudo.com/llms.txt (fetched 2026-09-02) — `integration/{android,ios,flutter,react-native,capacitor,cordova,web}` exist, but `sdk-result/data-structure/*` appears once, unqualified.
- [UNVERIFIED] The one residual: the pass-through argument establishes that Flutter cannot differ *from the native Android SDK*, and the doc tree establishes that Uqudo does not document a per-platform result difference. It does not exclude a native-SDK-vs-docs discrepancy on a live tenant. That residue is what S1-02 confirms, not the design question.
- [OBSERVED] The implementation already matches this answer, so nothing is at stake in reworking it: `backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java` line 370 comments *"Stage 10 (S3-13) needs documents[0].scan.faceImageId's bytes to create a Uqudo…"*, and `identityscan/domain/IdentityScanRepository.java` lines 96–97 stash already-downloaded, checksum-verified portrait bytes into `app.scan_result.face_reference_image`. The download-then-re-upload round trip is built.

---

## Item 2 — does the SDK's own UI ship Arabic?

### Answer

**Uqudo does not publish this.** Not in the SDK docs, not in the API docs, not in the changelog, not in the marketing FAQ. Every localisation page documents only the *override* mechanism; none states a shipped language set, none gives a default, and no changelog entry in the package's entire history announces adding or changing a UI language.

**But this is not a device-spike question either.** It is a property of the shipped *artifact*, not of a running build: the Android AAR either contains `res/values-<lang>/` folders or it does not, and the iOS pod either contains `.lproj` bundles or it does not. That is answerable by unzipping a file. It becomes answerable the moment `uqudosdk_flutter` is added to `mobile/pubspec.yaml` and Gradle resolves `io.uqudo.sdk:Uqudo:3.10.0` — **no device, no tenant, no credentials, no arm64 hardware**. It should be pulled *out* of S1-02's device checklist and done at dependency-add time, which is far earlier and far cheaper.

I could not do it here: the AAR is not on this machine, and I have no tool that can fetch a binary.

**Budget recommendation:** budget as if we supply Arabic ourselves. Two reasons beyond the unknown. First, the string set we actually need is much smaller than the documented total. Second, even a shipped Arabic translation would be Uqudo's wording, not the bank's — for the most visible screens in an Arabic-first journey, an override is likely wanted regardless. Treat a shipped Arabic set as a bonus that reduces the work to review-and-adjust, not as the thing the budget rests on.

### Evidence

- [DOC] Android override mechanism, and the absence of a language list: *"For supporting localisation in the application you can add your string resources and even can override the current resource as per your application."* That is the entire prose on the page; the rest is the string table. No languages named. https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/ui-customisation/text-and-language/general-strings.md
- [DOC] iOS override mechanism, likewise silent on what ships: *"To support localization, you can create the text configuration file 'uq-text-[language code].plist' with the two letter language code, e.g. 'uq-text-ar.plist'."* and *"Only the keys that need to be changed can be copied to the localized version of the configuration file."* https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/ios/ui-customisation/text-and-language.md — Uqudo's own worked example is Arabic, which is suggestive but is not a statement that Arabic ships. Do not read it as one.
- [DOC] Flutter locale API, documented without an accepted-value list: *"Use the below method for setting the locale of the application: `UqudoIdPlugin.setLocale(<your locale eg. fr, en etc.>);`"*. https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/flutter/enrolment-flow.md
- [OBSERVED] `setLocale` does not imply built-in translations. `UqudoIdPlugin.kt` lines 175–179 forward it straight to `UqudoSDK.setLocale(context, localeString)`. On Android this selects a resource qualifier — which resolves against the *merged* resource set, i.e. our app's `values-ar/strings.xml` overriding the library's `uq_*` keys. So the mechanism works with zero Uqudo-supplied translations. `C:\Users\DELL\AppData\Local\Pub\Cache\hosted\pub.dev\uqudosdk_flutter-3.8.0+1\android\src\main\kotlin\...\UqudoIdPlugin.kt`
- [OBSERVED] Nothing in the package's own distribution says anything either: the plugin `README.md` contains no match for `locale|language|arabic` (grep, 3.8.0+1 in the local pub cache).
- [OBSERVED] No changelog entry, ever, adds or names a UI language. The only language-adjacent entries in the whole file are RTL *layout fixes*: 3.1.3 *"iOS: Corrected RTL layout issues when using the Kurdish language."*, 3.4.1 *"Improved RTL layout support so that right-to-left settings apply only to SDK views."*, 3.6.0 (the `semanticContentAttribute` fix). `CHANGELOG.md` in the local pub cache, corroborated at https://pub.dev/packages/uqudosdk_flutter/changelog for 3.9.0 and 3.10.0 (neither mentions language). The Kurdish entry proves the SDK *renders* an RTL language correctly; it does not prove Uqudo supplies the strings — a customer supplying `uq-text-ku.plist` and reporting a layout bug produces exactly the same changelog line.
- [DOC] **Trap, flagged so nobody re-treads it:** Uqudo's public FAQ answers *"What languages do you support?"* with *"We support a total of 98 languages including English, Arabic, and Latin."* This is about **document OCR**, not the SDK UI — the same page frames it as reading and extracting identity data from documents. Do not cite it as evidence that the UI ships Arabic. https://uqudo.com/faq/
- [DOC] Checked and silent, so nobody re-checks: `integration/android/ui-customisation` (index only), `integration/web/ui-customisation/text-and-language`, `no-code-kyc/text-settings`, `readme/integration-options/uqudo-sdk`. None names a language.
- [DOC, counts machine-extracted — treat as approximate] Scope of the worst case, from the Android string tables. General strings ≈158 entries, but a large share are per-document-type labels (`uq_uae_id_description`, `uq_som_id_description`, `uq_mar_id_description`, `uq_idn_id_description`…) of which we need only the Sudan ID and passport ones. Scanning ≈33 entries, none document-specific. Face recognition ≈51 entries. **Reading (NFC), Lookup and Background Check are separate pages and all three flows are unused by this journey**, so their strings are out of scope. Realistic translate-and-review surface: roughly 90–120 strings, not 158+.
- [UNVERIFIED] My own read of that scope against the task's stated 2–4 day figure: 90–120 short UI strings, done once, plus a re-diff at each SDK upgrade, looks like the low end of that range rather than the high end — but I have not seen the strings' length or the review process, so treat the existing budget as the safe number.

### The check to run, when the dependency is added

Not implementation, just where to look. After Gradle resolves the SDK, the AAR lands at
`C:\Users\DELL\.gradle\caches\modules-2\files-2.1\io.uqudo.sdk\Uqudo\3.10.0\<hash>\Uqudo-3.10.0.aar`
— an AAR is a zip; the presence or absence of `res/values-ar/` (and any other `values-<lang>/`) inside it is the whole answer for Android. [OBSERVED] It is **not** there today: `C:\Users\DELL\.gradle\caches\modules-2\metadata-2.107\descriptors\io.uqudo.sdk\Uqudo\3.8.0\...\descriptor.bin` exists (stale metadata from an earlier resolution) but `modules-2\files-2.1\io.uqudo.sdk\` does not exist at all, and the only `values-ar` anywhere in the Gradle cache belongs to `androidx.core:core:1.13.1`. For iOS the equivalent is `.lproj` folders inside the `UqudoSDK` pod's resource bundle, which needs a `pod install` — later, and gated on AD-003.

---

## What I could not determine

- Whether the Uqudo SDK ships **any** UI translations at all, in any language. Unpublished; artifact-inspection question (above).
- Whether a real SDN_ID JWS from a live tenant matches the documented `scan-object` shape. Documentation is now unambiguous; live confirmation still belongs to S1-02.
- Whether the Scan API's country field pages and the SDK result agree on **every** field name, or only on the set minus the face image. Uqudo asserts the former ("the only exception"). I have not seen a real JWS to confirm it.
- The Scan API's OpenAPI/Swagger specification. I looked again as instructed: **it is not in `llms.txt`.** The index carries Face, Info, Lookup, Background Check, Authorisation and Scan as prose pages only, and no `.yaml`/`.json` spec URL appears anywhere in it. The two specs already in the repo were obtained via the Customer Portal, and that remains the only known route. [OBSERVED https://docs.uqudo.com/llms.txt, 2026-09-02]

---

## Risks

**Item 1 — low, and now lower than before.** The recommendation confirms what is already built, so being wrong would mean the *reverse* discovery at S1-02: a JWS that also carries `faceImage` base64. Cost of that would be an *optimisation* opportunity (skip one download), not a rework — `IdentityScanService` would gain a branch preferring inline bytes, and `app.scan_result.face_reference_image` would be populated from a different source. Nothing structural. Reversal cost: hours. The genuinely expensive direction — building against base64 and discovering ids — is now closed off by an explicit vendor sentence.

**Item 2 — bounded by construction.** If we budget the Arabic strings and Uqudo ships them, we overspent 2–4 days once. If we budget nothing and Uqudo ships nothing, the most visible screens in an Arabic-first journey render in English and the gap surfaces at S1-02 with no slack. Asymmetric; budget the strings.

**One risk worth naming that is not about either item:** the SDK's Arabic can only be verified by an Arabic-speaking human looking at the screen, and the same is true of an override we write. No test catches a bad Arabic string or a mirrored layout. That inspection belongs in S1-02's checklist explicitly, whatever the answer turns out to be.

---

## Card updates — `docs/components/uqudo-sdk.md`

**1. `## Results — read this before writing any parsing code`** — replace the "Images are references, not bytes" bullet's citation with the stronger one:

```
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
```

**2. `## Language / RTL`** — replace the first bullet:

```
- SDK renders its own UI. **Which languages ship built in — including whether Arabic is
  among them — is NOT PUBLISHED BY UQUDO.** Checked 2026-09-02: android + ios + web
  `ui-customisation/text-and-language`, `general-strings`, `no-code-kyc/text-settings`,
  `readme/integration-options/uqudo-sdk`, the full pub.dev changelog, and the plugin
  README — every one documents only the override mechanism, none names a shipped language
  or a default. [DOC — absence, not silence in one place]
- ⚠️ Uqudo's public FAQ says *"We support a total of 98 languages including English, Arabic,
  and Latin"* — that is **document OCR, not the SDK UI**. Do not cite it for this question.
  [DOC https://uqudo.com/faq/]
- **This is an artifact property, not a runtime one.** The Android AAR's `res/values-<lang>/`
  folders answer it by unzipping a file — no device, no tenant, no arm64 hardware. Do it when
  `io.uqudo.sdk:Uqudo:3.10.0` is first resolved into the Gradle cache, not at S1-02.
  Not on this machine today: `modules-2\files-2.1\io.uqudo.sdk\` does not exist.
  [OBSERVED local Gradle cache, 2026-09-02]
- Worst-case budget scope: general ≈158 strings (most of them per-document-type labels we
  don't need), scanning ≈33, face-recognition ≈51; Reading/Lookup/Background-Check pages are
  out of scope, those flows being unused. Realistic surface ≈90–120 strings.
  [DOC android text-and-language pages; counts machine-extracted, approximate]
- `setLocale` implies nothing about built-in translations — it forwards to
  `UqudoSDK.setLocale(context, locale)`, which resolves against the MERGED resource set, so
  our own `values-ar/strings.xml` overriding the `uq_*` keys is the delivery mechanism either
  way. [OBSERVED UqudoIdPlugin.kt lines 175-179; DOC general-strings.md]
```

**3. `## Open items`** — replace two lines:

```
- [x] Check whether the real JWS carries `faceImage` as base64 or `faceImageId` as a
      reference — CLOSED 2026-09-02 by documentation, R-024 retired: `faceImageId`, a
      reference. `scan-object`'s "the only exception is the face image" sentence. No
      code change; stage 10 already built this way. [DOC]
- [ ] SDK UI language, Arabic in particular (R-002) — **Uqudo does not publish it.** MOVED
      OFF the S1-02 device list: answerable by inspecting the resolved AAR's
      `res/values-<lang>/` at dependency-add time. Budget the Arabic strings regardless.
```

**4. `## Docs — machine-readable`** — add:

```
- The country-specific OCR field pages under `uqudo-api/scan/*` are SHARED documentation:
  they define both the Scan API response and the SDK result's `scan.front`/`scan.back`,
  differing only in the face image. That is why they read as Scan-API-shaped. [DOC
  scan-object, 2026-09-02]
- Re-checked 2026-09-02: the **Scan API OpenAPI spec is NOT in `llms.txt`** — the index has
  Scan/Face/Info/Lookup/Background-Check/Authorisation as prose pages only, no spec URL
  anywhere. The Customer Portal remains the only known route. [OBSERVED llms.txt]
```

---

## Noticed in passing — not investigated, not expanded

Three things I saw while reading `scan-object` for item 1. All bear on R-034's quarantined parser; none was in scope and I did not chase any of them.

1. **[DOC] The OCR fields nest under `scan.front` and `scan.back`, not directly under `scan`.** `scan-object` lists `front` and `back` as attributes carrying the extracted data. The S3-12 stub's `[UNVERIFIED] Payload structure` note assumes `data.documents[0].scan.{fields}` flat. If that matters before S1-02, it is a cheap correction; if not, S1-02 rewrites `verifyAndParse` wholesale anyway.
2. **[DOC] The checksum key is `faceImageIdChecksum`, not `faceImageChecksum`.** Same for `frontImageIdChecksum`, `frontFrameImageIdChecksum`, `backImageIdChecksum`, `backFrameImageIdChecksum`. The card records the stub as having *invented* `<x>Checksum`; the real names are published on `scan-object` and are `<x>IdChecksum`. `StubUqudoClient.java` lines 155 and 326 use `"faceImageChecksum"`.
3. **[OBSERVED] `FaceSessionConfigurationBuilder` does have a nonce.** `UqudoIdPlugin.kt` lines 442–444 read `if (json.has("nonce")) { faceSession.setNonce(json.getString("nonce")) }` — one of the S3-13 stub's open `[UNVERIFIED]` items ("whether the real `FaceSessionConfigurationBuilder` exposes a nonce-like binding at all") is answerable from the plugin source, not only from a device. Observed at 3.8.0+1; not checked against 3.10.0.

Sources:
- [Uqudo — SDK result, scan object](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/scan-object.md)
- [Uqudo — Scan API](https://docs.uqudo.com/docs/kyc/uqudo-api/scan.md)
- [Uqudo — SDN_ID (Sudan ID) fields](https://docs.uqudo.com/docs/kyc/uqudo-api/scan/country-specific-ids/sdn_id-sudan-id.md)
- [Uqudo — Download a resource](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/download-a-resource.md)
- [Uqudo — Android general strings](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/ui-customisation/text-and-language/general-strings.md)
- [Uqudo — iOS text & language](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/ios/ui-customisation/text-and-language.md)
- [Uqudo — Flutter enrolment flow](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/flutter/enrolment-flow.md)
- [Uqudo — docs index (llms.txt)](https://docs.uqudo.com/llms.txt)
- [Uqudo — public FAQ](https://uqudo.com/faq/)
- [pub.dev — uqudosdk_flutter changelog](https://pub.dev/packages/uqudosdk_flutter/changelog)

---

## Commit/push proof

Note: an unrelated, pre-existing uncommitted backend refactor (artifact-storage changes to
`identityscan`/`liveness` repositories and services, plus migrations V0053–V0056) was already
present in the working tree when this session started. Per CLAUDE.md ("Do NOT change any code")
and this task's read-only scope, those files were left untouched and unstaged — only the two
files this task produced were added and committed.

```
$ git commit -m "docs: close R-024 (Uqudo faceImageId) and unblock SDK-Arabic item from S1-02" ...
[main 2617848] docs: close R-024 (Uqudo faceImageId) and unblock SDK-Arabic item from S1-02
 2 files changed, 230 insertions(+), 7 deletions(-)
 create mode 100644 docs/sessions/2026-09-02-research-uqudo-open-items.md

$ git push
To https://github.com/Osmantou/Fr_user_update
   285b5b2..2617848  main -> main

$ git log --oneline -1
2617848 docs: close R-024 (Uqudo faceImageId) and unblock SDK-Arabic item from S1-02

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/domain/IdentityScanRepository.java
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/jdbc/JdbcIdentityScanRepository.java
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/identityscan/service/IdentityScanService.java
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/liveness/domain/LivenessRepository.java
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/liveness/jdbc/JdbcLivenessRepository.java
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/liveness/service/LivenessService.java

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	backend/src/main/resources/db/migration/V0053__app_artifact_ref_body_and_derivatives.sql
	backend/src/main/resources/db/migration/V0054__app_artifact_read_function.sql
	backend/src/main/resources/db/migration/V0055__app_purge_abandoned_artifacts.sql
	backend/src/main/resources/db/migration/V0056__app_drop_face_reference_image.sql

no changes added to commit (use "git add" and/or "git commit -a")
```
