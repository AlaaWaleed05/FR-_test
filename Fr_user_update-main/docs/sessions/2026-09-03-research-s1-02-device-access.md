# Research Report — S1-02: can the Uqudo Android device spike be run without owning a physical Android device?

**Date:** 2026-09-03
**Task:** S1-02 environment selection (EXECUTION_PLAN.md Sprint 1)
**Author:** `@agent-researcher` (read-only; no code written, no spike attempted). Saved verbatim by the parent session.

---

## 0. Reading taken, and the reading of the question I pursued

Read before investigating: `PROJECT_PLAN.md`, `EXECUTION_PLAN.md` (Sprint 1 table), `RISKS.md`, `docs/components/uqudo-sdk.md`.

**Ambiguity resolved:** "without the team owning a physical Android device" could mean (a) *no physical Android hardware in the developer's hands at all*, or (b) *no newly-purchased device — borrowing counts*. I investigated **(a)** as the primary reading, because that is the one that would actually change the critical path in R-008, and I cover (b) as its own candidate. I did not pursue a third reading — "can S1-02 be descoped so no device is needed" — because the task explicitly forbids forcing a workaround narrative, and because the eight measurements are runtime observations by construction.

**Out of scope, not touched:** the Flutter/Uqudo architecture choice (AD-001/AD-002a), iOS device access (AD-003), and running the spike itself.

---

## 1. Executive answer

**No. S1-02 cannot be performed without a physical Android device.** Not "awkwardly", not "with caveats" — the two constraints that matter (a live human face and a real physical document reaching the SDK's camera in real time, on ARM) are jointly satisfied by no service I could find, and Uqudo's own current documentation rules out the one class of environment that does support live webcam passthrough.

Three independent findings each close the door on their own:

1. **Uqudo's current Android documentation states outright that the SDK does not run on emulators**, and specifically that laptop cameras are inadequate for testing it. That is a vendor statement dated to the live docs site, not an inference. [DOC]
2. **Every real-device cloud I checked replaces the camera with an uploaded still image, not a live webcam feed** — and BrowserStack states its device cameras are *physically taped over*. A still image is exactly what the constraint disqualifies, and it is also what Uqudo's own anti-spoofing (`idScreenDetection`, `idPrintDetection`, and the liveness gate) exists to reject. [DOC]
3. **The one environment that does support live host-webcam passthrough on ARM64 — Genymotion SaaS — is a virtual device**, which lands squarely inside Uqudo's emulator exclusion and would raise the `emulated` flag in Uqudo's own device-attestation object. [DOC]

The practical answer is the boring one: **the device already being procured (EXECUTION_PLAN.md S1-02 note: "Android device arriving") is the answer**, and the useful output of this research is not an alternative environment but a *specification* for that device — because one requirement in S1-02 has been quietly under-specified and would otherwise produce a spike that measures seven things and silently fails to retire R-006.

**The under-specified requirement:** `targetSdk 36` behaviour changes are applied by the *platform*, and only by a platform at API 36 or above. A budget handset running Android 14 or 15 will return a completely valid real JWS and answer measurements 1–6 and 8 — but it cannot exercise a single Android 16 behaviour change, so **R-006 would remain open after a "successful" S1-02**. The device must run **Android 16 (API 36) or higher**, not merely be arm64. [DOC developer.android.com — Android 16 = API 36; behaviour changes are gated on "apps targeting Android 16 **or higher**", which the platform only enforces from API 36 onward]

---

## 2. The gate every candidate has to pass (re-verified against current Uqudo docs)

The task required re-verifying this project's dated ABI belief rather than trusting it. I did, and the belief holds — with a *stronger* current source than the one the card cites.

### 2.1 Emulators are excluded by name, in the current docs

> "The Uqudo SDK is heavily dependent on the mobile camera for scanning capabilities; therefore, it is not supported on Android emulators."

The same page adds that "the quality of laptop camera scanning is inadequate for proper testing" and recommends testing on physical devices rather than through Android Studio emulation.
[DOC https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android.md — fetched 2026-09-03]

This is **new evidence this project did not previously hold.** `docs/components/uqudo-sdk.md` sources the physical-device requirement to CHANGELOG 2.5.0 (an ABI note); the live integration page states it directly as a support boundary. The card should cite this page, because a changelog entry from 2023 is a weaker citation than a current support statement.

It also independently pre-empts the "just point a laptop webcam at it" family of ideas — Uqudo names laptop cameras as inadequate, which is precisely the capture hardware every webcam-passthrough proposal in this report depends on.

### 2.2 The ABI restriction is still current, and still says the same thing

The current mobile SDK changelog still carries, at 2.5.0 (2023-03-03):

> "For Android we did a small change to make sure your application can build and run on x86_64 architecture. This will allow you to test your application on x86_64 emulator but the SDK part, armeabi-v7a and arm64-v8a remain the only supported architectures"

I checked the changelog forward from there to the current head (**3.10.0, 2026-08-10**) for any ABI, NDK, architecture or emulator entry. There is none — nothing supersedes, relaxes or extends 2.5.0.
[DOC https://docs.uqudo.com/docs/kyc/uqudo-sdk/changelog/mobile-sdk.md — fetched 2026-09-03]

So: **x86_64 builds, x86_64 does not work.** The card's claim survives re-verification. Note precisely what the sentence says, because it is the source of a common misreading — it grants *build* compatibility on x86_64 so your app doesn't fail to assemble, and explicitly withholds *SDK* support. The card's phrasing ("x86_64 builds but the SDK does not function") is a fair rendering of it.

I could not find a dedicated requirements/prerequisites page listing ABIs; `prepare-environment.md` contains no ABI, emulator, minSdk, compileSdk or `abiFilters` statement at all — its only environment constraint is Java 17 source/target compatibility. The changelog is the only place Uqudo states the ABI restriction. [OBSERVED — checked https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/prepare-environment.md and the full `llms.txt` index, 2026-09-03]

### 2.3 A third gate this project had not yet recorded: device attestation

Uqudo's SDK result carries a **device attestation object** with risk flags including:

`debugging` · `hooking` · `deviceMasked` · **`emulated`** · `rooted` · `proxy` · `gpsSpoofers` · **`screenSharing`** · `vpnRunning` · `suspiciousFactoryReset` · `sdkConnectionIntercepted` · `payloadTampered`

Uqudo names a high-risk subset: `applicationStore`, `debugging`, `hooking`, `emulated`, `rooted`, `proxy`, `sdkConnectionIntercepted`, `payloadTampered` — with the note that "each flag should be assessed according to your specific risk model".
[DOC https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/device-attestation.md — fetched 2026-09-03]

Three of these bear directly on this question:

- **`emulated`** — any virtual device (Genymotion, AVD, Corellium-class) trips it. Even if the SDK ran, the JWS logged for the parser would be a JWS from a device the vendor flags as emulated. That is not a clean reference artifact to build R-034's parser against.
- **`hooking` / `payloadTampered`** — every real-device cloud's camera injection works by instrumenting or repackaging the app to intercept the camera API (Sauce Labs requires "a debuggable and non-obfuscated version"; LambdaTest describes "injecting LambdaTest's proprietary camera module into your application and emulating or superseding the native Android/iOS camera SDK"; BrowserStack refuses obfuscated apps). That is textbook hooking of the exact API the attestation watches. [UNVERIFIED whether Uqudo's specific detector fires on these specific implementations — but the mechanism is the one the flag names.]
- **`screenSharing`** — any remote-controlled device is, by definition, streaming its screen. [UNVERIFIED whether device-farm screen capture trips Uqudo's detector; it is implemented below the MediaProjection API on most farms, which is the likeliest detection target.]

**This is a finding worth recording independently of the device question:** `docs/components/uqudo-sdk.md` does not currently mention the device-attestation object at all, and the backend has no handling for it. Filed under "Card updates" and "Noticed in passing".

---

## 3. Candidate environments

Each is assessed on: **live laptop-webcam passthrough** (the binary constraint), **genuine ARM64 Android**, **cost in USD**, **ability to install a locally-produced debug build**, **what leaves our control**, and a **verdict**.

---

### 3.1 Candidate A — Buy a physical arm64 Android phone (the honest baseline)

**Live webcam passthrough:** Not applicable — the device *is* the camera. A real human face and a real physical document are held in front of a real camera sensor. This is the only candidate where the constraint is satisfied trivially rather than approximated. ✅

**ARM64:** Every shipping Android handset is arm64-v8a. Satisfied by construction. [DOC — Uqudo's supported set is exactly the physical-handset set]

**Cost:**
- **I could not obtain a reliable Sudan retail price.** No Sudanese retail source was reachable through the tools available to me, and Sudan's FX situation makes any USD conversion I'd quote unreliable. **Flagged as an estimate, not a figure to quote to the bank.**
- Regional/international estimate, **[UNVERIFIED]**: a new budget arm64 handset (Redmi A-series, Infinix Smart, Tecno Spark Go, Samsung Galaxy A0x class) runs roughly **USD 70–120** new in East African and Gulf markets; Sudan retail with import margin plausibly **USD 100–200**. A second-hand device is cheaper still.
- **The critical cost caveat:** the cheapest tier of the market is exactly where Android 16 does *not* ship. Devices in this bracket commonly ship Android 14 or 15 and may never receive 16. To exercise API 36 runtime behaviour (§1), the device must actually run **Android 16 or higher**, which realistically means either a 2026-model device that ships with it or a mid-range/flagship-lineage device with a current update — plausibly **USD 200–400** rather than USD 100. **[UNVERIFIED — I did not survey which specific 2026 budget models ship Android 16 in Sudan-available channels; that is a shopping question, not a research one, but it must be checked before purchase or R-006 stays open.]**
- Recurring cost: **zero**. The device is reusable for S1-07 mobile-coverage work, every future SDK upgrade re-test, the whole testing phase, and the Arabic RTL layout review that no emulator screenshot will settle honestly.

**Install a locally-produced debug build:** Yes. `fvm flutter build apk --debug` plus `adb install`, or `flutter run` over USB. No store listing, no signing ceremony, no third-party upload. This is the *only* candidate with zero friction here. ✅

**What leaves our control:** Nothing beyond what the journey already requires. The document image and face capture go from the device to Uqudo (FIB's tenant, R-001) and to our backend. **No additional third party is introduced.** This is the minimum-exposure option and the only one that adds nothing to R-001's existing surface.

**Verdict: VIABLE. The comparison point.** Fastest, cheapest over any horizon longer than about a week of cloud time, and the only one that satisfies the constraints as written rather than as approximated.

---

### 3.2 Candidate B — Borrow a physical arm64 Android phone

**Live webcam passthrough:** N/A — same as A. ✅
**ARM64:** ✅

**Cost:** USD 0, plus social cost and scheduling friction.

**Install a locally-produced debug build:** Yes, with one real obstacle: S1-02 requires **repeated** runs across a T+30min / T+2h / T+24h window (measurement 2) and a same-JWS resubmission at T+2h (measurement 3). Measurements 2 and 3 are *backend-side* — once the JWS is logged, the image-download and resubmission checks need no device at all — so a borrowed device only needs to be in hand for the capture itself, not for 24 hours. **That materially reduces the borrowing burden and is worth stating in the S1-02 notes.** But measurement 4 (the `setMinimumMatchLevel` A/B against a non-matching face) needs *two* people present — the account holder's document and a second person's face — which is a scheduling constraint, not a technical one.

**What leaves our control:** Same as A — nothing extra. One added consideration: the borrowed device will hold a tenant-scoped Uqudo bearer token for up to 1800s during the run (an inherent SDK property, per the component card), and a debug build with logging. Wipe the app and its data before returning it. Do not use a device you cannot uninstall from.

**Specific sources worth trying, in order:** the developer's own handset (if it is arm64 and can be spared for a session — it is); FIB, which already runs Uqudo in production on Android and therefore certainly has test devices, and with whom a tenant-sharing agreement already exists (R-001) so a device-sharing ask is a small marginal request; the bank's own IT/branch estate.

**Verdict: VIABLE — and it may already be moot.** EXECUTION_PLAN.md's own S1-02 note reads "**Android device arriving.**" [OBSERVED `EXECUTION_PLAN.md` line 12]. If that is accurate, the procurement half of R-008's Android leg is already in motion and this whole question resolves to "verify the arriving device's OS version".

---

### 3.3 Candidate C — BrowserStack App Live (real-device cloud, interactive sessions)

**Live webcam passthrough: NO — and emphatically so.** BrowserStack's own native-device-features documentation states:

> "Access the device camera on BrowserStack real devices. However, **all our device cameras are taped**, and accessing the camera displays a black device screen."

The only camera input mechanism is **Image Injection**: an uploaded file, used "as input to the real device camera to simulate the action of capturing an image".
[DOC https://www.browserstack.com/docs/app-live/native-device-features and https://www.browserstack.com/docs/app-live/media/image-injection — fetched 2026-09-03]

Even taken on its own terms, the injection path is unusable here: **maximum 288 pixels in height and width**, `.jpg`/`.jpeg`/`.png` only, and **apps using ProGuard/obfuscation are not supported and must have obfuscation disabled**. A 288-pixel image cannot carry a Sudanese ID card's MRZ, let alone OCR fields — and Uqudo's Android release-build requirements mandate R8/ProGuard configuration, so the injection prerequisite is in direct tension with the SDK's own build requirements. [DOC BrowserStack image-injection page; DOC uqudo-sdk.md "Release-build requirements"]

**ARM64:** Yes — real physical handsets, therefore arm64-v8a. Not the failing criterion.

**Cost:** App Live from roughly **USD 39–49/user/month** on published plans, subject to change. Not the deciding factor.

**Install a locally-produced debug build:** Yes — upload an APK; store listing not required. ✅

**What leaves our control:** Would have been significant — a real ID document image and a real face capture traversing a US-headquartered vendor's device fleet and session recordings. Moot given the disqualification.

**Verdict: DISQUALIFIED.** No live webcam passthrough; cameras physically taped; injection is a static ≤288px file and requires disabling obfuscation. Per the task's own instruction, this is disqualified rather than proposed as a partial answer.

---

### 3.4 Candidate D — Sauce Labs Real Device Cloud

**Live webcam passthrough: NO.** Sauce Labs' Camera Image Injection "allows you to replace the image captured by the device camera with an image that you **upload** through Sauce Labs during a live test" — "upload a photo of your choice, which is then fed to your app, mimicking the use of that device's camera". Prerequisites include "a **debuggable and non-obfuscated** version of the application" uploaded to Sauce's Mobile App Storage.
[DOC https://docs.saucelabs.com/mobile-apps/features/camera-image-injection/ — via vendor documentation, fetched 2026-09-03]

Uploaded still image, not a live feed. Same disqualification as BrowserStack, and the debuggable/non-obfuscated prerequisite carries the same conflict with Uqudo's R8 requirement and the same `hooking`/`payloadTampered` attestation exposure.

**ARM64:** Yes — real Android devices, public and private clouds.
**Cost:** Enterprise/quote-based; well above the other options and above what a solo developer with no QA budget should be signing.
**Install a locally-produced debug build:** Yes, via Mobile App Storage. ✅
**What leaves our control:** Document image and face capture into a US vendor's cloud plus session artifacts. Moot.

**Verdict: DISQUALIFIED.** Static uploaded image only; no live webcam passthrough documented anywhere in the feature.

---

### 3.5 Candidate E — LambdaTest Real Device (Media Injection)

**Live webcam passthrough: NO.** LambdaTest's Media Injection covers "Image Injection and Video Injection" — but the source is **uploaded media**: "choose any uploaded image from the Upload Media screen to simulate camera input during test runs". The mechanism is stated plainly: it "leverage[s] Sensor Instrumentation technology, **injecting LambdaTest's proprietary camera module into your application** and emulating or superseding the native Android/iOS camera SDK."
[DOC https://www.lambdatest.com/support/docs/camera-image-injection-on-real-devices/ — via vendor documentation, fetched 2026-09-03]

Note that *video* injection exists — but it is an uploaded video file, not a live webcam stream. A pre-recorded video of a face would not satisfy "a genuine human face in real time" and is precisely the attack Uqudo's liveness gate exists to defeat.

Worse for this specific SDK: the mechanism is described as injecting a module into the app to supersede the native camera SDK. That is app modification of the class Uqudo's `hooking` and `payloadTampered` attestation flags describe.

**ARM64:** Yes — real devices.
**Cost:** Lower than Sauce; real-device plans commonly in the **USD 25–100/month** range depending on tier. Not the deciding factor.
**Install a locally-produced debug build:** Yes. ✅
**What leaves our control:** Same class as C and D. Moot.

**Verdict: DISQUALIFIED.** Uploaded media only; the injection mechanism is app instrumentation, which is independently hostile to an anti-tamper eKYC SDK.

---

### 3.6 Candidate F — AWS Device Farm (remote access / manual sessions)

**Live webcam passthrough: NO — the feature does not exist.** AWS documents the complete supported feature set for remote access sessions:

> App(s) upload · Appium Endpoint · Orientation change · Network shaping · Location mocking · Screenshot · Video recording · Logs

**Camera is not on that list at all** — there is no camera feature, no image injection, and no webcam capability of any kind in remote access.
[DOC https://docs.aws.amazon.com/devicefarm/latest/developerguide/remote-access.html — fetched 2026-09-03]

AWS additionally warns: "For security reasons, we recommend that you avoid providing or entering sensitive information ... during a remote access session", and states that Device Farm "captures video of each remote access session and generates logs of activity" including "any information you provide during a session." For a session whose entire purpose is holding an identity document up to a camera, that is a direct conflict with this project's PII posture even if the camera worked.

**ARM64:** Yes — "real, physical phones and tablets".
**Cost:** ~USD 0.17/device-minute pay-as-you-go, or an unmetered monthly plan. Irrelevant given no camera.
**Install a locally-produced debug build:** Yes — `.apk` upload. ✅
**What leaves our control:** Would be substantial — AWS records video of the session by their own statement. Moot.

**Verdict: DISQUALIFIED.** No camera capability documented at all in interactive sessions.

---

### 3.7 Candidate G — Samsung Remote Test Lab (free, real Samsung hardware)

Worth checking specifically because it is free, offers genuine Samsung ARM64 handsets, and permits installing your own APK — which would have made it the ideal low-cost answer.

**Live webcam passthrough: NO. The camera is not supported at all.** Samsung RTL's documented limitations state that **audio, additional peripherals, multi-touch, and camera are not supported.** [DOC Samsung developer / Remote Test Lab documentation, corroborated across multiple independent secondary sources — fetched 2026-09-03. **[UNVERIFIED]** on one point: I read this via secondary sources reporting Samsung's own limitation list rather than fetching Samsung's page directly, because the RTL documentation sits behind a developer sign-in. The claim is consistent across sources and consistent with RTL's architecture, but treat the exact wording as second-hand.]

The absence of multi-touch alone would also make the SDK's capture UI difficult to drive.

**ARM64:** Yes — real Galaxy handsets.
**Cost:** **Free** — 20 credits/day, up to ~5 hours/day. The best cost profile of any cloud option, and it does not matter.
**Install a locally-produced debug build:** Yes — remote APK installation is supported. ✅
**What leaves our control:** Moot.

**Verdict: DISQUALIFIED.** Camera explicitly unsupported. The cheapest option is also the most completely disqualified.

---

### 3.8 Candidate H — Genymotion SaaS, ARM64 virtual devices with host-webcam streaming

**This is the only candidate that satisfies the webcam constraint, and it fails on a different one. It deserves the most careful treatment, because it is the option someone will otherwise propose.**

**Live webcam passthrough: YES — genuinely.** Genymotion documents that "you can also use the video stream from a real physical webcam connected or integrated into your computer", delivered through the Media Injection widget, and for the browser-based products you "allow your web browser to access your host webcam and microphone when prompted." The Camera and Microphone feature is documented as available in **Genymotion Desktop, Genymotion SaaS and Genymotion PaaS**.
[DOC https://docs.genymotion.com/features/camera/ — fetched 2026-09-03]

Genymotion further documents that OBS Virtual Camera works with **SaaS** (though not Desktop), with the camera API limited to 720p — which means an arbitrary composited live feed can be routed in. [DOC Genymotion support articles on OBS Virtual Camera, fetched 2026-09-03]

**ARM64: YES, at the instruction-set level.** Genymotion SaaS "has transitioned to exclusively support arm64 architecture for all Android virtual devices" (support article dated 2026-08-13; the x86 retirement date reported as 2026-03-30). Their stated rationale is that arm64 VMs mirror the processor architecture of real phones.
[DOC https://support.genymotion.com/hc/en-us/articles/30419556610333 — fetched 2026-09-03]

So on paper this clears both hard constraints: live webcam in, arm64-v8a out.

**Why it is nevertheless disqualified — three reasons, any one sufficient:**

1. **Uqudo says no.** Genymotion's own documentation calls these "Android **virtual devices**" and a "virtual environment", explicitly distinguishing them from physical phones. Uqudo's current Android page says the SDK "is not supported on Android emulators." [DOC both, above] This project's standing rule is that Uqudo's documentation is authoritative. Running the spike here would mean running the SDK in an environment the vendor states it does not support, and then treating whatever happened as evidence about production behaviour. If it works, we've learned nothing certain; if it fails, we cannot tell an SDK defect from an unsupported-environment artifact. **That is the exact failure mode S1-02 exists to avoid.**
2. **The JWS would carry `emulated: true`.** The primary deliverable of S1-02 is *one complete real JWS logged for the parser* (measurement 8), against which R-034's quarantined parser gets rewritten. A JWS produced on a device Uqudo's own attestation flags as emulated is not a clean reference artifact — and since `emulated` sits in Uqudo's named high-risk set, tenant-side policy could plausibly reject or alter the flow. [DOC device-attestation.md]
3. **The webcam relay is a screen-of-a-document, in effect.** Even setting 1 and 2 aside, what reaches the SDK is a re-encoded 720p-capped video stream, not photons off a physical card. Uqudo's `verifications[0].idScreenDetection` / `idPrintDetection` scores exist specifically to reject non-physical document presentations, and the card records that the SDK rejects above score 50 unless `allowNonPhysicalDocuments()` is set — which this project has forbidden. **[UNVERIFIED whether a webcam relay specifically trips it — but if it does not, that is a finding about Uqudo's anti-spoofing, not a licence to test this way; and if it does, the spike fails.]** Uqudo also states laptop camera quality is inadequate for its scanning, and a laptop webcam is exactly the capture device here. [DOC android.md]

**Cost:** Pay-as-you-go around **USD 0.06/minute/device (~USD 0.60/hour)**; unlimited plans around **USD 179–219/month/device**. [DOC Genymotion pricing pages, fetched 2026-09-03] Cheap enough that cost is not the objection.

**Install a locally-produced debug build:** Yes — ADB/drag-drop APK install into the virtual device. ✅

**What leaves our control:** A live video stream of a real human face and a real identity document, from a laptop webcam, through a browser, into a French vendor's (Genymobile SAS) cloud VM. This is *more* exposure than the device-farm options, not less — it is a continuous live feed rather than a single uploaded still.

**Verdict: DISQUALIFIED — on the emulator exclusion, not on webcam or ABI.** State this precisely in any follow-up: Genymotion is the only environment found that *would* satisfy live passthrough on ARM, and it is ruled out by Uqudo's own support boundary and by its own attestation flag.

---

### 3.9 Candidate I — Local Android emulator (AVD) with arm64 system image and `-camera-back webcam0`

Included because it is free, obvious, and will be proposed if not addressed.

**Live webcam passthrough:** Yes, in principle — the Android emulator can map a host webcam to the virtual device's camera. ✅
**ARM64:** Only on an ARM host. The developer's machine is **win32 / x86_64** [OBSERVED environment: Windows 10 Pro, win32]. An arm64-v8a system image on an x86_64 Windows host runs under full instruction emulation — unusably slow for real-time camera processing even if it booted, and the ML-heavy scan/liveness pipeline is the worst possible workload for it. There is no ARM host available. ❌
**Cost:** Free.
**Install a locally-produced debug build:** Yes. ✅
**What leaves our control:** Nothing — this is the only cloud-free alternative and it leaks nothing.
**Verdict: DISQUALIFIED** — twice over: no ARM host available, and it is an emulator, which Uqudo excludes by name. [DOC android.md]

---

### 3.10 Candidate J — Firebase Test Lab

**Live webcam passthrough: NO.** Firebase Test Lab is an automated-test service (Robo crawls and instrumented test runs against physical and virtual devices). There is no interactive human-in-the-loop session with camera input, which is the core requirement here. Its physical devices are real ARM hardware, and its virtual devices are emulators.
**Verdict: DISQUALIFIED** — no interactive session, no camera feed. Not a partial answer.

---

## 4. Cross-cutting: Sudan payment and sanctions friction

This bears on **every** paid cloud option, and it is realistic rather than theoretical:

- **BrowserStack, Sauce Labs, LambdaTest, AWS and Genymotion all bill by international card or invoice in USD/EUR.** Sudan-issued cards do not clear on international rails, and Sudan is subject to ongoing US sanctions measures targeting the Government of Sudan (the CBW Act measures already flagged at OQ-018). The pattern PROJECT_PLAN.md records for messaging providers applies identically here: **"the obstacle for international options is de-risking rather than legal prohibition — whether the payment clears, not whether the provider will sell"** (OQ-020). **[UNVERIFIED for these five specific vendors — I did not obtain a vendor-by-vendor sanctions/AUP statement, and I would not rely on one without reading it.]**
- **AWS specifically** applies account-level geographic restrictions and KYC on account creation; an AWS account opened from Sudan is a real risk of being closed mid-spike.
- Practical consequence: any paid cloud option would likely need a card and billing address outside Sudan, which introduces a personal-liability and expense-reimbursement problem for a solo developer, on top of the technical disqualifications above.

**This strengthens the recommendation but is not load-bearing for it** — every cloud candidate is already disqualified on technical grounds before payment is reached. Recorded so nobody re-opens the question on cost and discovers payment friction late.

---

## 5. PII posture — what would leave our control, and whether it matters

The task asked for this per candidate; here is the consolidated position, because it is the same argument each time.

**Under the physical-device options (A and B), nothing additional leaves our control.** The document and face go to Uqudo (FIB's tenant — R-001, already accepted and tracked) and to our backend. No new party.

**Under every cloud option, a real identity document image and a real face capture would traverse a third party's infrastructure** — and in AWS's case, be recorded on video by the vendor's own documented behaviour. Even for a spike using a developer's own or a synthetic document rather than a customer's (OQ-011 is explicit that Sprint 1 spikes need no customer document), this matters for three reasons:

1. **It is a developer's own biometric and identity document.** OQ-011 governs *customers'* documents; it says nothing about the developer consenting to their own face and ID being processed by an unvetted foreign vendor. That is a personal decision nobody has been asked to make, and it should be made explicitly rather than by default.
2. **It contradicts the posture the project has already taken elsewhere.** This project calls `DELETE /api/v1/info/{jti}` deliberately, described in PROJECT_PLAN.md as "a deliberate privacy control, not housekeeping ... purging minimises how much customer identity data sits in another bank's account." A project that goes to that length to minimise exposure inside a *partner bank's* tenant should not casually add a commercial device farm with session video recording to the path.
3. **It creates a precedent.** The spike environment tends to become the regression-test environment. Whatever is acceptable for one document becomes the default for every SDK-upgrade re-test thereafter.

**Conclusion: the PII argument points the same way as the technical argument.** That is worth noting explicitly — where two independent lines of reasoning agree, the recommendation is more robust than either alone.

---

## 6. GitHub corroboration (as corroboration only, never as a source of truth)

Per the task's constraint, public repositories were checked **only** for evidence of what environments people have actually got Uqudo scan/liveness working in — never as an integration or architecture source, and never permitted to override Uqudo's documentation.

**What exists:** Uqudo maintains first-party sample apps under the `uqudo-com` GitHub organisation — `sample-app-flutter`, `sample-app-android-kotlin`, `sample-app-ios-swift`, `sample-app-react`, `sample-app-cordova`, `sample-app-xamarin`, `sample-app-web-javascript`. The Flutter sample "demonstrates the usage of the Uqudo SDK for passport onboarding with facial recognition"; its README's prerequisites are Flutter, an IDE, and pasting an access token into `main.dart`. [OBSERVED https://github.com/uqudo-com — search results, 2026-09-03]

**What I found — and did not find:**
- **No public repository, issue, or README documents anyone running Uqudo scan or liveness on a cloud device farm, an emulator, or any virtualised Android.** [OBSERVED — absence, from the searches performed]
- Uqudo's own sample-app READMEs describe a plain local device workflow and do not mention emulators or device clouds as an option.

**Weight of this evidence: low, and only confirmatory.** Absence of a public example is not proof of impossibility, and I did not exhaustively search. It does not change any conclusion above — those rest on Uqudo's own documentation and the device farms' own documentation. It is recorded because the task asked for it, and because it is consistent with, rather than contradicting, the vendor documentation. I did not read any sample repository's integration code, per the standing rule that Uqudo's documentation is authoritative and reference implementations have already been found wrong in this project (the FIB precedent).

---

## 7. Summary table

| Candidate | Live laptop-webcam → device camera | Genuine ARM64 Android | Install local debug build | Recurring cost (USD) | Verdict |
|---|---|---|---|---|---|
| **A. Buy a physical arm64 phone** | N/A — real camera ✅ | ✅ real handset | ✅ adb | $0 (one-off ~$100–400) | **VIABLE — baseline** |
| **B. Borrow a physical arm64 phone** | N/A — real camera ✅ | ✅ real handset | ✅ adb | $0 | **VIABLE** |
| C. BrowserStack App Live | ❌ cameras taped; static ≤288px upload | ✅ | ✅ | ~$39–49/mo | DISQUALIFIED |
| D. Sauce Labs RDC | ❌ uploaded still only | ✅ | ✅ | quote-based | DISQUALIFIED |
| E. LambdaTest Media Injection | ❌ uploaded media only; app instrumented | ✅ | ✅ | ~$25–100/mo | DISQUALIFIED |
| F. AWS Device Farm remote access | ❌ no camera feature at all | ✅ | ✅ | ~$0.17/dev-min | DISQUALIFIED |
| G. Samsung Remote Test Lab | ❌ camera unsupported | ✅ | ✅ | **free** | DISQUALIFIED |
| H. Genymotion SaaS arm64 | **✅ yes, genuinely** | ⚠️ arm64 **virtual device** | ✅ | ~$0.60/hr | DISQUALIFIED — Uqudo excludes emulators |
| I. Local AVD arm64 + webcam | ✅ in principle | ❌ no ARM host; emulator | ✅ | free | DISQUALIFIED |
| J. Firebase Test Lab | ❌ no interactive camera session | ✅ (physical tier) | ✅ | free tier | DISQUALIFIED |

---

## 8. The single recommendation

**Run S1-02 on a physical Android handset the team holds — the one already in procurement (EXECUTION_PLAN.md: "Android device arriving"), or a borrowed one, whichever is in hand first. Do not purchase cloud device time for this task.**

Two conditions attach to that recommendation, and they are the actual deliverable of this research:

**(1) Verify the device runs Android 16 (API 36) or higher before booking the session.** This is the finding that would otherwise be missed. `targetSdk 36` behaviour changes are enforced by the platform, and a platform below API 36 does not contain them. A device on Android 14 or 15 will produce a completely valid real JWS and answer measurements 1, 3, 4, 5, 6 and 8 — and will **not** exercise a single Android 16 runtime behaviour change, so **R-006 would remain open after an apparently successful spike**. If the arriving device is below API 36, the spike is still worth running immediately for the seven answerable measurements, but R-006 must be explicitly recorded as *not retired*, and a second run on an API-36+ device scheduled. Do not let a green spike report close R-006 by implication.

**(2) Plan the run knowing that measurements 2 and 3 do not need the device.** The T+30min / T+2h / T+24h image-download checks and the T+2h JWS resubmission are backend-side operations against a logged JWS. Only the capture itself needs the handset. This materially reduces how long a *borrowed* device must be held — from a day to under an hour — and should be stated in the S1-02 notes so the borrowing ask is small.

**A third, smaller point:** measurement 4 (the `setMinimumMatchLevel` A/B) requires a **second person's face** presented against the first person's document. Arrange that before the session, not during it.

### Conditions under which this recommendation flips

- **If no physical device materialises within the sprint window and the schedule cannot absorb the delay** → the recommendation does **not** flip to a cloud environment. It flips to **escalating R-008 as the live blocker it is** and running the spike late. Every cloud environment examined is disqualified on the vendor's own documentation; substituting one would produce a spike report that reads as evidence and is not, which is worse for R-034 than having no spike at all.
- **If Uqudo removes the emulator exclusion from `integration/android.md`, or states in writing that Genymotion SaaS arm64 is supported** → Genymotion SaaS becomes viable and the recommendation flips to it for *repeat* runs (SDK-upgrade regression testing), though not for the first one — the reference JWS for R-034's parser should still come from real hardware. Re-check that page at each SDK upgrade; it is one fetch.
- **If a device farm ships genuine live-webcam-to-device-camera passthrough on real ARM hardware** (none does today, per §3) **and does it without instrumenting the app under test** → re-evaluate. The app-instrumentation requirement is the second, independent disqualifier for the injection-based farms and would have to fall too.
- **If the arriving device turns out to be below API 36 and no API-36+ device is obtainable** → the recommendation is still "run it on that device", but R-006 must stay 🟡 Watching with its mitigation text corrected to say so, and the purchase of an API-36+ device becomes its own procurement line under R-008.
- **Explicitly, as required:** **if no environment satisfies live webcam passthrough, the recommendation is to buy a physical device, not to force a workaround.** That is precisely the situation this report found. No environment satisfies it. Buy or borrow the device.

---

## 9. What I could not determine

Stated explicitly rather than filled with plausible guesses.

1. **A reliable Sudan retail price for an arm64 Android handset.** No Sudanese retail source was reachable. The USD 70–200 (budget) / USD 200–400 (Android 16-capable) figures are regional/international estimates, **[UNVERIFIED]**, and must not be quoted to the bank as costed.
2. **Which specific 2026 handset models shipping in Sudan-available channels run Android 16.** This is a shopping question I could not answer from here, and it is the one that decides whether R-006 gets retired.
3. **Whether Uqudo's `hooking` / `payloadTampered` detectors actually fire on BrowserStack's, Sauce Labs' or LambdaTest's specific camera-injection implementations.** The mechanism matches what the flags describe, but I have no observation. Moot — those options are disqualified on webcam grounds first.
4. **Whether device-farm screen streaming trips Uqudo's `screenSharing` flag.** Unknown. Would matter if a farm ever gained webcam passthrough.
5. **Whether a webcam-relayed document trips `idScreenDetection` / `idPrintDetection`.** Unknown, and untestable without running the very spike this report is scoping. Recorded as a reason not to trust a Genymotion result even if one were obtained.
6. **Vendor-by-vendor sanctions and payment position for Sudan** at BrowserStack, Sauce Labs, LambdaTest, AWS and Genymotion. I did not obtain any vendor's own statement. The general de-risking pattern is documented in this project already (OQ-020); the specific application to these five is inference.
7. **Samsung Remote Test Lab's camera limitation read from Samsung's own page.** Read via consistent secondary sources reporting Samsung's limitation list; Samsung's documentation sits behind a developer sign-in. Treat the exact wording as second-hand.
8. **Whether Corellium (ARM-native virtualisation of Android) offers host-webcam passthrough.** I did not reach a verdict from its documentation. It is almost certainly moot: it is a virtual device (so inside Uqudo's emulator exclusion), it is a US company with export-controlled software, and its pricing is enterprise-scale — three independent reasons it fails this project's constraints before the webcam question is reached.

---

## 10. Risks if this recommendation is wrong

| If wrong about… | What breaks | Cost to reverse |
|---|---|---|
| The emulator exclusion being decisive (i.e. Genymotion would in fact have worked) | We spent ~USD 100–400 on hardware we did not strictly need — but which remains useful for every future SDK upgrade, RTL layout review and the whole testing phase | **Near zero.** The device is not wasted under any outcome. This is the cheapest possible way to be wrong. |
| The API-36 device requirement (i.e. behaviour changes somehow observable below API 36) | We over-specified the device and paid more than necessary | Low. Over-specifying costs money once; under-specifying leaves R-006 open and produces a spike report that falsely reads as retiring it. **Asymmetric — err toward the higher API level.** |
| Cloud device farms all lacking webcam passthrough (i.e. one has it undocumented) | We passed over a working remote option | Low. Re-checkable in one session at any time; the flip condition is written above. |
| Measurements 2 and 3 being backend-only (i.e. they do need the device) | A borrowed device must be held ~24h instead of ~1h | Low, and detectable at planning time. The claim follows from the backend owning image download and JWS verification (PROJECT_PLAN Architecture), which is settled. |
| The device actually arriving | S1-02 slips, and with it every parser task depending on it (R-034 🔴 Live, "Blocks all parser work") | **This is the real risk, and it is R-008's, not this report's.** Nothing in this report mitigates it; the report's contribution is establishing that no substitute exists, which makes R-008's Android leg strictly a procurement problem with no engineering escape. |

---

## 11. Card and plan-file updates

### 11.1 `docs/components/uqudo-sdk.md` — "Platform floors" section

**Correct** the ABI/device line to cite the current, stronger source alongside the changelog:

```
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
```

**Add** to the `targetSdk 36` bullet:

```
  ⚠️ Android 16 behaviour changes are enforced by the PLATFORM, so a device below API 36
  cannot exercise them at all. S1-02 on an Android 14/15 handset answers every other
  measurement and leaves R-006 untouched. The spike device must run API 36+. [DOC
  developer.android.com/about/versions/16/behavior-changes-16, 2026-09-03]
```

### 11.2 `docs/components/uqudo-sdk.md` — NEW section (this card has no coverage of it today)

```
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
exact JSON nesting inside the JWS is not confirmed by any source in this repo.]
```

### 11.3 `RISKS.md` — R-006, mitigation column

Replace *"Verified on a physical arm64 device in S1-02."* with:

> Verified on a physical arm64 device in S1-02. **The device must run Android 16 (API 36) or higher** — API 36 behaviour changes are enforced by the platform, so a handset on Android 14/15 answers every other S1-02 measurement and leaves this risk entirely untouched (2026-09-03 research). If the spike runs on a sub-36 device, this row stays 🟡 and must say so explicitly rather than being closed by implication. **No substitute environment exists**: Uqudo's current docs state the SDK "is not supported on Android emulators" [DOC integration/android.md, 2026-09-03], and no real-device cloud offers live webcam passthrough — see docs/sessions/2026-09-03-research-s1-02-device-access.md.

### 11.4 `RISKS.md` — R-008, mitigation column

Append:

> **2026-09-03 research: the Android leg has no engineering escape hatch.** Ten environments were assessed against "live laptop webcam → remote device camera, on ARM64, interactive". BrowserStack tapes its device cameras and injects only ≤288px stills; Sauce Labs and LambdaTest inject uploaded media and require a debuggable, non-obfuscated app (in tension with Uqudo's own R8 requirement); AWS Device Farm remote access documents no camera feature at all and records session video; Samsung RTL (free, real Galaxy hardware) does not support the camera; Firebase Test Lab has no interactive camera session. Genymotion SaaS is the **only** environment that genuinely supports live host-webcam passthrough on arm64 — and it is a *virtual* device, inside Uqudo's documented emulator exclusion and its own `emulated` attestation flag. **Conclusion: buy or borrow a physical handset; there is nothing to trade money for here.** Sanctions/payment friction from Sudan would in any case obstruct every paid cloud option (same de-risking pattern as OQ-020), though that is not the deciding factor. Full assessment: docs/sessions/2026-09-03-research-s1-02-device-access.md.

### 11.5 `EXECUTION_PLAN.md` — S1-02 notes

Append:

> **Device specification (2026-09-03 research):** physical arm64-v8a handset running **Android 16 / API 36 or higher** — a sub-36 device answers measurements 1, 3, 4, 5, 6 and 8 but cannot exercise any API 36 behaviour change, so R-006 would not be retired. No remote/cloud environment substitutes (see the session report). **Measurements 2 and 3 are backend-side** — once the JWS is logged, the T+30min/T+2h/T+24h image-download checks and the T+2h resubmission need no device, so a borrowed handset is needed for under an hour, not a day. **Measurement 4 needs a second person present** (a non-matching face against the first person's document) — arrange before the session. Measurement 7 (SDK UI language / R-002) is off this list per `docs/components/uqudo-sdk.md`; measurement 5 (R-024) is confirmatory only, closed by documentation. **Add:** capture the device-attestation object from the real JWS — the component card now documents it and nothing in the backend reads it.

---

## 12. Noticed in passing

Not expanded, not acted on — recorded so they are not lost.

1. **`docs/components/uqudo-sdk.md` has no device-attestation section, and the backend reads none of it.** Drafted above. This is the larger of the two, because the object contains named fraud signals (`rooted`, `hooking`, `emulated`, `payloadTampered`) that the journey's operator-judges-the-claim design principle would plausibly want surfaced, and no BL item tracks it.
2. **Uqudo's own docs give a second, independent reason not to trust a browser/webcam capture path** — "the quality of laptop camera scanning is inadequate for proper testing" — which is worth remembering if the Web SDK (the separate 4.x line) is ever floated as a fallback for any platform.
3. **BrowserStack's image-injection prerequisite (obfuscation must be disabled) directly conflicts with Uqudo's release-build requirement (R8/ProGuard config mandatory).** Worth noting generally: any future tooling that requires a non-obfuscated build cannot be used against a Uqudo release build, only a debug one.
4. **`docs/components/uqudo-sdk.md`'s ABI claim was sourced to a 2023 changelog entry and is now corroborated by a current support page.** Worth a general habit: where a card cites only a changelog, check whether the live docs now say it more directly — the citation upgrade is usually one fetch.

---

**Sources**

- [Uqudo — Android integration (emulator exclusion, laptop camera statement)](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android.md)
- [Uqudo — Android Prepare Environment](https://docs.uqudo.com/docs/kyc/uqudo-sdk/integration/android/prepare-environment.md)
- [Uqudo — Mobile SDK changelog (ABI statement at 2.5.0; head 3.10.0, 2026-08-10)](https://docs.uqudo.com/docs/kyc/uqudo-sdk/changelog/mobile-sdk.md)
- [Uqudo — Device Attestation](https://docs.uqudo.com/docs/kyc/uqudo-sdk/sdk-result/data-structure/device-attestation.md)
- [Uqudo — docs index (llms.txt)](https://docs.uqudo.com/llms.txt)
- [BrowserStack — App Live native device features (cameras taped)](https://www.browserstack.com/docs/app-live/native-device-features)
- [BrowserStack — App Live image injection (≤288px, no obfuscation)](https://www.browserstack.com/docs/app-live/media/image-injection)
- [Sauce Labs — Camera Image Injection](https://docs.saucelabs.com/mobile-apps/features/camera-image-injection/)
- [LambdaTest — Media Injection on Real Devices](https://www.lambdatest.com/support/docs/camera-image-injection-on-real-devices/)
- [AWS — Device Farm remote access feature list](https://docs.aws.amazon.com/devicefarm/latest/developerguide/remote-access.html)
- [Samsung — App Testing / Remote Test Lab](https://developer.samsung.com/mobile/app-testing.html)
- [Genymotion — Camera and Microphone (host webcam, Desktop/SaaS/PaaS)](https://docs.genymotion.com/features/camera/)
- [Genymotion — x86 retirement, arm64-only SaaS](https://support.genymotion.com/hc/en-us/articles/30419556610333-What-is-happening-to-x86-devices-in-Genymotion-SaaS)
- [Genymotion — pricing](https://www.genymotion.com/pricing/)
- [Android — Behavior changes: apps targeting Android 16 (API 36)](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Uqudo — first-party sample apps (GitHub org)](https://github.com/uqudo-com)

---

## Addendum (parent session, 2026-09-03) — a device is in hand; what that changes

Written after the report above was delivered. **The developer has a physical Android
handset next to the laptop.** Identified from its About-phone screen (the screenshot itself
is not filed — it shows the device's IMEIs):

| | |
|---|---|
| Device | Huawei Y5 2019, model **AMN-LX9** |
| SoC | MediaTek **MT6761** (Helio A22), ARM Cortex-A53 — ARM, as Uqudo requires |
| ABI | 64-bit silicon, but 2 GB Helio A22 handsets commonly ship a **32-bit (armeabi-v7a) userspace**. **[UNVERIFIED until the spike app prints `Build.SUPPORTED_ABIS`]** — moot for viability, since Uqudo supports armeabi-v7a explicitly (§2.2) |
| Android | **9 / API 28**, EMUI 9.1.0, build 9.1.0.202, security patch 5 July 2019 — no further updates expected |
| RAM / storage | **2.0 GB** / 32 GB (24.56 GB free) |
| Display | 1520 × 720; **glass cracked**, mostly lower-left |
| Cameras | 13 MP autofocus rear (document scan); 5 MP fixed-focus front (liveness) |
| Google services | Pre-ban 2019 device → GMS present. Irrelevant to sideloading a debug APK either way |
| Condition at time of writing | 15 % battery |

### What this changes in the report's conclusions

1. **R-008's Android leg no longer blocks S1-02.** §8's first flip condition ("if no physical device materialises…") is moot. The spike can be scheduled as the next session. The only remaining Android item under R-008 is the *second* device — see 3.
2. **The environment is Candidate A/B's best case: same Wi-Fi as the laptop, no USB.** The backend runs on the laptop; the spike app points at its LAN IP; token fetch, JWS upload and the 10-minute Face-Session-id handoff are all automatic. Nothing leaves the local network. This is arrangement 1 of the three described to the developer (same Wi-Fi / hand-carried paste / tunnel), and the only one recommended.
3. **This device cannot retire R-006.** API 28 is eight platform versions below the API 36 the report identifies as required (§1, §8 condition 1). A run on this handset answers measurements **1, 2, 3, 4, 5, 6 and 8** — including the real logged JWS that unblocks R-034's parser rewrite ("blocks all parser work") — and exercises **zero** Android 16 behaviour changes. **A green S1-02 report from this device must not be read as closing R-006.** R-006 stays 🟡 Watching with its text corrected (done this session), and an API 36+ handset becomes a separate, shorter *second run*, not a repeat of S1-02.
4. **It adds a measurement the report did not ask for, and the Sudan market cares about more than API 36:** does scan + liveness complete at all on a **2 GB, Android 9, fixed-focus-front-camera** handset, and how long does it take? Uqudo publishes no RAM minimum; an OOM kill or a multi-minute liveness on this device is a real finding about the low end of the customer base, not a test artifact. Recorded as measurement **(9)** in S1-02's notes. If it works here, it works for most customers; if it fails here, that is exactly the kind of thing to learn before launch rather than after.
5. **Pre-flight checklist for the session** (developer-side, before an APK is built): charge fully; confirm the phone joins the same Wi-Fi as the laptop; enable *Install unknown apps* for whatever transfers the APK; verify touch works across the cracked lower-left region (Settings → Developer options → *Show taps*) because the SDK's capture controls sit at the bottom of the screen; have a **second person** present for measurement 4; FIB Uqudo credentials ready to go into the local gitignored config only. USB debugging is optional — useful for `adb logcat`, not required.

### Net position for whoever plans the next session

**S1-02 is runnable now**, on this device, with the R-006 carve-out stated up front. Expected yield: the real JWS (→ R-034 parser rewrite becomes unblocked), the `exp − iat` value (R-021), the image-retention and resubmission behaviour (R-012), the `setMinimumMatchLevel` A/B (the AD-002a face-matching decision rests on it), `isEnrollmentSupported(SDN_ID)` on FIB's tenant (OQ-010 partial), the device-attestation object's real shape (new — §2.3), and a low-end viability/performance floor. Not yielded: R-006. Procure an API 36+ handset separately for that; it needs under an hour with the phone once the spike tooling exists.

## Parent-session disposition

Applied this session, per the task's instruction ("updates RISKS.md R-008 and EXECUTION_PLAN.md's S1-02 notes"):
- §11.4 → RISKS.md R-008 mitigation column, appended.
- §11.5 → EXECUTION_PLAN.md S1-02 notes, appended.

**Recommended but NOT applied** — outside the task's named scope, left for a deliberate decision:
- §11.1 and §11.2 — `docs/components/uqudo-sdk.md` citation upgrade and the new device-attestation section. The attestation gap (§12.1 — the backend reads none of the SDK's fraud flags, and no BL item tracks it) is the substantive one.
- §11.3 — R-006 mitigation rewrite: initially not applied. **Reversed once the in-hand device turned out to be API 28** (see Addendum): R-006's old text "Verified on a physical arm64 device in S1-02" would then be actively false for the S1-02 run about to happen, so a minimal correction was applied — the row now states the in-hand device cannot verify it and names the API 36+ second run. Still 🟡 Watching.
