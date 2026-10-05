# Uqudo device test — a script for the handset

The Uqudo SDK is arm-only and runs on no emulator, so **nothing in this repository's automated
gates exercises the scan or the liveness path**. Every `UqudoScanner` and `UqudoFaceScanner` test
runs against a fake. This document is the substitute: the steps a person performs on a real
device, what each should produce, and what a failure looks like.

Written 2026-09-12 (S8-16) from live source and `docs/components/uqudo-sdk.md`.

---

## Read this first — which parts are evidence and which are expectation

Every step below is tagged. The tags are the point of the document: they say which of your own
expectations are guesses before you are standing there holding a phone.

| Tag | Means |
|---|---|
| **[OBSERVED]** | Someone ran this on a real device and recorded the result in this repository. The session report is named. |
| **[ARTIFACT]** | Read out of the shipped bytecode, the AAR or the built APK. Stronger than source-reading — it is what actually ships — but nobody watched it happen. |
| **[UNVERIFIED]** | Derived from reading source. Nobody has watched it happen and nothing was inspected. If it behaves differently, **the document is wrong, not necessarily the app.** |

**There are TWO real device runs in this repository, and they cover different things.**

1. **S1-02** (2026-09-03, `docs/sessions/2026-09-03-s1-02-device-spike.md`) — a **spike build on
   FIB's borrowed tenant**, not this app and not this backend, long before the screens you will
   be looking at existed. Its [OBSERVED] facts are about the *shape* of the SDK's output — the
   JWS layout, lifetimes, the error envelope — which hold regardless of tenant.
2. **S8-09's device confirmation** (2026-09-08,
   `docs/sessions/2026-09-08-uqudo-prescan-fixes.md` §9) — the release APK on the **pilot
   handset**. It confirms on hardware that the SDK's intro screen does not appear and no uqudo
   logo is seen, and that the SDK's own screens render in Arabic. Those two are settled; **do not
   spend your pass re-proving them.**

**What is still unproven is the rest.** No recorded run takes a document scan or a liveness check
end to end against the deployed backend on the current build, and as of 2026-09-12 the scan has
not been seen to succeed on it at all. Every step below tagged [UNVERIFIED] is genuinely open.

---

## Before you start

1. **Install the release APK, having uninstalled any previous build first.** A reinstall over the
   top keeps `session.db`, so the app resumes into a profile created by a different build and you
   will be testing two things at once.
2. **Use a fresh test account** — the seeded range is `0000001001`–`0000001030`, branch
   `16`, which the branch list seeds as «الخرطوم» (V0015 — there is no "main branch" entry, so do not
   look for one). Check with the operator or the account-check endpoint which are
   still `PROCEED`; each is consumed by a completed journey.
3. **Attach logcat if you can.** The app's Stage 8 catch-all discards the exception and
   `debugPrint` is compiled out of a release build, so **when something unexpected fails the app
   itself can tell you nothing**. The SDK's own native logging is the only remaining witness:
   ```
   adb logcat -c && adb logcat | grep -iE "uqudo|flutter|AndroidRuntime"
   ```
4. Work through stages 1a–7 normally to reach Stage 8. Stage 7 is the document-type choice and is
   the last freely revisitable stage.

---

## 1 — Document scan, happy path

### 1.1 The pre-scan screen  [UNVERIFIED]

**You do:** arrive at Stage 8 after choosing a document type at Stage 7.

**Expect:** our own preparation screen — navy banner with the pearl mark, «مسح وثيقة الهوية» as
the title, and a start control. This is *our* screen, not the SDK's.

**Note what it does NOT say.** There is deliberately no sentence about what an attempt costs;
S8-09 removed it by product-owner decision. If you see attempt-cost copy here, you are on an old
build.

**Failure looks like:** any of the seven end screens in §7 appearing before you tap anything.

### 1.2 The SDK opens  [OBSERVED for the intro screen; UNVERIFIED for the rest]

**You do:** tap to start the scan.

**Expect:** the app requests a scan token from the backend, then the SDK takes over the whole
screen with its own camera UI. The transition should be quick — the token call is a single HTTPS
round trip.

**Expect NOT to see:** an intro/help page reading "Scan your passport / Fit the document to the
frame / Start". `disableHelpPage()` is set on the document builder specifically to remove it, and
it is **the only screen in this journey that carries the uqudo logo**
(`uq_logo_icon` in `uq_core_toolbar.xml`). **[OBSERVED on the pilot handset, 2026-09-08** —
S8-09 §9: "the SDK's intro screen does not appear; the camera opens directly and no uqudo logo is
seen." Backed by [ARTIFACT]: the flag reaches `DocumentBuilder.disableHelpPage()` and both
`ScannerActivity` and `CameraFragment` branch on it.] **If you see that page, report it** — it is
a regression against a confirmed behaviour, not an open question.

**Failure looks like:** «تعذر الاتصال» with body «تعذر الاتصال.» — the token call failed or
returned a 5xx. Or «تعذر الاتصال» with body «حدث خطأ غير متوقع.» — an unexpected error inside the
plugin; see §8.

### 1.3 Capture the document  [UNVERIFIED]

**You do:** hold the document in the frame as the SDK directs.

**Expect:** the SDK captures, validates and closes itself, returning to our screen, which then
uploads. Expiry is **not** checked — a genuine but expired document is accepted by design.

**Expect the SDK to reject, in its own UI and without telling us:** a photocopy, a document shown
on a screen, or a tampered one. These are **not** error codes — they surface as
`verifications[0].{idPrintDetection, idScreenDetection, idPhotoTamperingDetection}` scores inside a
*successful* result and are rejected in-SDK above score 50. [DOC verification-object.md] So a
refusal here is the SDK doing its job, not our bug.

### 1.4 Landing on Stage 9  [UNVERIFIED]

**Expect:** the review screen with the data read from the document, and the Civil Registry result.

**Worth knowing:** Uqudo transcribes the document faithfully — including formatting quirks. At
S7-12 a passport number came back exactly as printed, and the apparent mismatch was the *reader*,
not the OCR. [OBSERVED, `docs/sessions/2026-09-07-s7-12-acceptance-walk.md`]

---

## 2 — The Arabic inside the SDK's own screens

**This is the only place these strings can be read.** They are **134 override keys** in
`mobile/android/app/src/main/res/values-ar/strings.xml`, all provisional and all part of the
pending native-Arabic review. Nothing in the app renders them; only the SDK does.

Uqudo ships **no translations at all** — the resolved AAR carries no `values-<lang>` folder of any
kind. [ARTIFACT, 2026-09-08] So **every Arabic word you see inside the SDK is ours**, and any
English word you see is a key we missed.

**The mechanism is already confirmed on hardware** [OBSERVED, S8-09 §9, 2026-09-08]: the SDK's own
screens render in Arabic on the pilot handset. What is NOT confirmed is the **wording** — it was
drafted in that session, has never been through the native-Arabic review, and 129 of the 134 keys
were verified present in the release APK by `aapt2 dump resources` rather than read on a screen.
**So you are reviewing the words, not the plumbing.**

**You do:** read every SDK screen you pass through, and photograph anything that reads oddly.

| Group | Keys | Where you will see them |
|---|---|---|
| `uq_scan_*` | 29 | The document-scan camera UI, its instructions and its progress |
| `uq_face_*` | 51 | The liveness UI — §6 |
| `uq_error_*` | 36 | Any SDK error dialog |
| the rest | 18 | Buttons, confirm/cancel, page labels, document names |

**Report specifically:**
- **Any English string.** That is a missing override, not a translation quality issue.
- **Broken letter joining or reversed word order.** The SDK's RTL has been fixed repeatedly in
  Uqudo's own changelog, so a layout fault here may be theirs.
- **Text that is cut off or overlapping.** Arabic runs longer than English and the SDK's layouts
  were measured for English.

**How the Arabic gets there, so you can tell a wiring failure from a wording one**  [ARTIFACT,
2026-09-08]: `setLocale('ar')` writes `key_locale` into the SDK's own SharedPreferences;
every SDK activity reads it back in `attachBaseContext` and re-resolves its resources under `ar`.
**`init()` erases that key, so `setLocale` must come after it** — our wrapper does this in
`_ensureInitialised()`. If EVERY string is English, suspect that ordering. If SOME are English,
they are simply missing from our override file.

---

## 3 — Denying the camera permission

**This is the path that produced BL-114, and it is the most important negative test in this
document.**

**You do:** on a fresh install, start a scan and **deny** the camera permission at the OS prompt.
(If you already granted it, revoke it in Android Settings → Apps → permissions first.)

**Expect on screen** [UNVERIFIED]: our own screen, not the SDK's —

> **لا يمكن الوصول إلى الكاميرا**
> يحتاج مسح الوثيقة إلى إذن استخدام الكاميرا. امنح التطبيق هذا الإذن من إعدادات الجهاز ثم أعد المحاولة. لم يتم احتساب أي محاولة.

**What actually happened underneath, and why it matters**  [verified at source, BL-114(a)]:

- The SDK returns `SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED` inside the JSON envelope in
  `PlatformException.code`. Our wrapper maps it, and Stage 8 treats it as a permission problem
  rather than a scan failure.
- **No scan attempt is spent.** The screen's claim «لم يتم احتساب أي محاولة» is true of the
  per-document budget of 5, and `/cancel` is deliberately not called.
- **But a lifetime token mint WAS spent.** `identity_scan_repository.dart:96` calls `issueToken`
  — which increments the per-profile lifetime counter — *before* `enroll` at `:107`. The camera
  never opened, and the profile is one mint of **20** closer to a permanent block.
- **Nothing on the screen says so, and nothing can**: the app is never told the counter's value.

**So the test to actually run:** deny the permission **three times in a row**. Expect the same
screen each time and no visible change. That is correct behaviour *as built* — and it is also
BL-114(a) demonstrating itself. Twenty denials would cap the profile forever without a single
document ever being photographed.

**What the block screen says once a cap or a budget block does fire** [UNVERIFIED]: the shared
`BlockedView`, which describes what was observed and never why — «يمكنك المحاولة مرة أخرى بعد …»
with a live countdown when a deadline is known, «هذا الإجراء غير متاح حاليًا. يرجى زيارة أقرب فرع»
when there is none. **A capped profile's deadline is a real 24-hour one**, reused rather than given
a new code or screen. If you ever see the block screen offer a retry button while telling you the
wait is over, that is BL-114(b) — but it was fixed at S8-14, so report it.

---

## 4 — Cancelling mid-scan

**You do:** start a scan, let the SDK open, then back out of it — the SDK's own cancel/back
control, not the app's.

**Expect** [UNVERIFIED]: the SDK returns `USER_CANCEL`, and our screen shows

> **لم ينجح مسح الوثيقة**
> تم احتساب محاولة. …

**An attempt IS spent, and the screen says so.** This is deliberate and it is the rule to check:
customer.md is explicit that *a launched SDK session consumes a real Uqudo operation whether or not
a document was captured*. The app files the abandoned session with the backend so the attempt is
recorded.

**Failure looks like:** the screen claiming no attempt was counted. That would be the app telling
you something false about your own budget.

**Also worth doing:** cancel **five times** on the same document type. Expect the fifth to move you
to the other document type with a fresh budget («SCAN_TYPE_EXHAUSTED»), *not* straight to a
24-hour block. The per-type limit is 5 and the total is 10, derived as exactly twice the per-type
limit so the document-switch offer is real rather than nominal.

---

## 5 — Losing the network between a successful scan and the upload

**This is BL-034's whole reason for existing.**

**You do:** complete a scan successfully, and put the phone into flight mode **the instant the SDK
closes**, before the upload finishes. (Timing is awkward; several attempts may be needed.)

**Expect** [UNVERIFIED]:

> **تم مسح الوثيقة، ولم يكتمل الإرسال**

with a retry control. **The capture is retained in memory** and the retry re-sends the same one —
you should NOT be asked to photograph the document again.

**Then turn the network back on and retry.** Expect it to complete and land on Stage 9.

**Two limits to know before you judge this:**
- The capture is held **in memory only, for the life of the process**. Kill the app and it is gone
  — a deliberate decision, not a bug, because a JWS is a PII-bearing identity artifact.
- **Uqudo deletes the session images 2 hours after the scan completes** — the JWS lifetime is
  7200 s and images 404 one second past `exp`. [OBSERVED S1-02: 200 at +25/+31/+35/+45/+90 min,
  404 at +120 min] So a retry more than two hours later cannot succeed, and should tell you the
  capture can no longer be used («لم يعد بالإمكان استخدام المسح السابق، ويلزم مسح الوثيقة من جديد»)
  rather than offering a retry that will fail.

---

## 6 — Liveness, happy path

**You do:** reach Stage 10 and run the face check.

**Expect** [UNVERIFIED]: our own screen, then the SDK's face UI (the 51 `uq_face_*` strings), then
back to ours.

**Things that are NOT bugs:**
- **Uqudo returns no liveness score.** The result is a match decision, not a number. If you are
  looking for a confidence percentage, there isn't one.
- **The SDK gates the match internally.** A failed match is most likely unreachable on the success
  path — the AAR carries a `uq_face_dialog_face_not_match` string that the SDK shows itself.
- The face session is short-lived: `exp − iat` = **600 s** [OBSERVED S1-02], against 7200 s for the
  document scan. A face capture left sitting expires far sooner than a document one.

**Budget:** liveness has its own 5-attempt budget and its own 20-mint lifetime cap, separate from
the scan's, and BL-114(a) applies identically — `liveness_repository.dart:64` mints before `:78`
launches the SDK, so a camera denial here also costs a mint.

---

## 7 — Was an attempt spent? What you can tell from the app alone

Stage 8 has **seven** end states. **The screen you land on is the only information the app gives
you** — there is no counter anywhere in the UI.

| Screen | Attempt spent? | Lifetime mint spent? |
|---|---|---|
| «لم ينجح مسح الوثيقة» — scan failed / cancelled | **Yes**, and the screen says so | Yes |
| «تم مسح الوثيقة، ولم يكتمل الإرسال» — upload failed | No — the scan succeeded | Yes |
| «لا يمكن الوصول إلى الكاميرا» — permission denied | **No**, and the screen says so | **Yes — invisible.** BL-114(a) |
| «تعذر الاتصال» — connectivity | **No**, and the screen says so | Only if the token call succeeded |
| «لم يعد بالإمكان استخدام المسح السابق» — capture expired | No | Already spent, earlier |
| «تعذر مسح [الوثيقة]» + «يمكنك استخدام وثيقة أخرى» — **this document's 5 are gone** | All 5, spent | **5 or more** — denials and post-token connectivity failures each cost a mint and no attempt |
| The block screen (`BlockedView`) — a 24-hour block | All 10, or the 20-mint cap | Up to 20 |

**The two bottom rows are where §4's "cancel five times" instruction lands**, so expect them —
they are the budget working, not a fault.

**The honest summary: you cannot tell from the app how many of the 20 lifetime mints are gone.**
The only symptom is the 24-hour block arriving without an obvious cause. If you want to know
during testing, ask for a read of the profile's `scan_tokens_minted` — the app will never show it.

---

## 8 — What the SDK does that our screens cannot control

Do not report these as app bugs.

- **The whole camera UI.** Layout, framing guides, capture timing, progress, retry prompts, the
  document-not-recognised dialog — all Uqudo's, all drawn full-screen over ours. **Our banner
  cannot appear there**; we do not own the view tree.
- **In-SDK rejection of photocopies, screens and tampered documents** — §1.3.
- **In-SDK face-match gating** — §6.
- **The permission prompt itself** is Android's, not ours.
- **Anything after the SDK closes is ours**, and is fair game to report.

### The one failure mode where the app cannot help you

If Stage 8 shows «تعذر الاتصال» with the body **«حدث خطأ غير متوقع.»**, that is
`stage8_screen.dart`'s catch-all: an exception that matched none of the typed handlers. The most
likely cause is the plugin returning `null` from `enroll` — `UqudoIdPlugin.enroll` declares
`final String result = await _channel.invokeMethod(...)`, so a null native result throws a
`TypeError`, which is **not** a `PlatformException` and therefore escapes the wrapper's only catch.

**The app discards that exception and a release build logs nothing.** logcat is the only witness;
see the setup step. If you hit this, capture the log before doing anything else — retrying will
produce the identical screen and no new information.
