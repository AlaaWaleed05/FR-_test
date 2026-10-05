# 2026-09-08 — S8-09: pre-scan copy, the Uqudo intro screen, and Arabic SDK strings

Three items from the colleague hardware test. One was a deletion in our own code; two were
investigations that could legitimately have come back "not possible". Both came back possible.

**Outcomes, stated up front:**

| Item | Outcome |
|---|---|
| 1 — delete the attempt-cost sentence on our pre-scan screen | **Done.** Deleted, with both tests now asserting its absence. |
| 2 — do not show the SDK's own intro screen | **SKIPPED, not forced.** `DocumentBuilder.disableHelpPage()`. Removes the uqudo logo too. No backlog row needed. |
| 3 — the SDK's own UI in Arabic | **ACHIEVED through real localisation**, not an overlay. 134 override strings shipped; **wording provisional** pending native review + device pass. BL-071 rescoped, deliberately not closed. |

---

## 1. The investigation, and the method that mattered

CLAUDE.md forbids integrating an SDK from documentation alone, and this session is why that
rule earns its keep: **R-002 was half wrong, and only the artifact could show it.**

Evidence base: `sdk-bundle-Uqudo-3.10.0.aar` from the local Gradle cache — its `res/` tree and
its `classes.jar` under `javap` — plus the `uqudosdk_flutter-3.10.0` plugin source from the pub
cache. No web fetch, no doc citation used as proof.

### A method error worth recording, because it nearly produced a false finding

Extracting `classes.jar` on Windows silently loses classes to case-insensitive filename
collisions: **647 entries in the jar, 380 on disk** (`C.class` loses to `c.class`). My first
pass grepped that truncated tree, found no reader of `key_locale`, and concluded `setLocale`
was stored but never applied — which would have made item 3 an overlay-or-nothing decision.
Re-scanning every entry straight from the jar stream (`unzip -p` per entry) found the reader
immediately: `io/uqudo/sdk/C.class`, one of the 267 lost files.

**Rule for any future session decompiling an AAR on this machine: never grep an extracted
tree, stream the entries.**

---

## 2. Item 1 — the sentence is gone

`mobile/lib/features/identityscan/stage8_screen.dart`. Removed the `Text` widget, the 11-line
comment above it that argued for keeping it, and the `SizedBox(height: 16)` that preceded it
and would otherwise have left a visible gap. `_instruction` carries its own `bottom: 8` padding,
so the last instruction still spaces correctly before the `Spacer()`.

Two tests updated. `stage8_screen_test.dart`'s test name — "warns that cancelling costs an
attempt" — had become a lie and was renamed. Both tests now assert the sentence's **absence**
rather than simply dropping the check, and `identity_scan_navigation_test.dart` re-anchors on
`'قبل أن تبدأ:'`, which is a real widget on the screen under test.

**The consequence, recorded not argued.** That sentence was the only place the journey told the
customer that backing out of the camera spends one of BL-039's five per-type attempts. Nothing
says it now, so the 24-hour block at the cap arrives with no prior warning anywhere in the app.
The product owner asked for the deletion twice — once as walk comment 8b, which S8-05 declined
and restyled instead, and again on this hardware test. That is their call and it is made. Filed
on BL-090; `stage8_screen.dart`'s class doc carries the same note so a later session cannot
quietly reinstate it.

---

## 3. Item 2 — the intro screen is the SDK's HelpActivity, and it is skippable

**Identification.** The screen the product owner photographed reads "Scan Passport / Fit
Passport to the frame / Start". Those are three AAR strings — `uq_scan_help_page_title`
(`"Scan %1$s"`), `uq_scan_help_page_description` (`"Fit %1$s to the frame"`) and `uq_start`
(`"Start"`) — rendered by `res/layout/uq_core_fragment_help_info_text.xml`, a page of the
ViewPager in `uq_core_activity_help.xml`.

**The flag is honoured, not cosmetic.** The chain, each link observed:

| Link | Evidence |
|---|---|
| `DocumentBuilder.disableHelpPage()` exists in Dart | `uqudosdk_flutter.dart:353` |
| it is serialised over the channel | `Document.g.dart` → `'isHelpPageDisabled'` |
| the plugin forwards it natively | `UqudoIdPlugin.kt:297-298` |
| the native API is public | `javap io.uqudo.sdk.core.DocumentBuilder` → `disableHelpPage()` |
| the SDK branches on it at runtime | `ScannerActivity` and `CameraFragment` both call `Document.isHelpPageVisible()`; the help destination is bypassed when false |

**It also removes the logo, which was the other half of the ask.** `uq_core_toolbar.xml` carries
`uq_logo_icon`, and in this journey's scan path only `uq_core_activity_help.xml` includes that
toolbar — `uq_scan_fragment_camera.xml`, `uq_core_fragment_custom_progress_dialog.xml` and
`uq_scan_fragment_output.xml` do not. One flag, both halves.

**The face session has no intro screen at all** — `uq_face_activity_facial_recognition.xml`
includes no toolbar and there is no face help fragment — so nothing was missed on the liveness
side and no second flag exists to set.

**No test can assert this, by design.** `UqudoScanner` exposes `enroll(...)`, not the builder;
the seam a fake could observe does not carry SDK configuration. Device proof only.

---

## 4. Item 3 — R-002's premise was wrong, and that changed the answer

R-002 recorded that S1-02 called `setLocale('ar')` and the UI still rendered English. The
inference drawn from that — that the locale flag does nothing — was wrong.

**What the bytecode says.** `UqudoSDK.setLocale(context, locale)` does one thing: writes
`key_locale` into the `uqudo_preferences` SharedPreferences. The SDK's base Activity
`io.uqudo.sdk.C.attachBaseContext` reads it back, constructs `Locale(that)`, calls
`LocaleList.setDefault` + `Configuration.setLocales` (API ≥ 24; plain `Configuration.locale`
below that), and returns `createConfigurationContext(...)`.

**So every SDK activity has been resolving its resources under locale `ar` since S1-02.** The
English text was never a dead flag — it was the missing values, and nothing else. The AAR carries no
`values-<lang>` folder of any kind — `res/` has `values` and `values-night-v8` beside the usual
`drawable*`, `font`, `layout`, `navigation`, `raw` and 27 screen-width `values-sw*dp-v13` buckets,
and not one language bucket — confirming on the artifact what R-002 had inferred.

That makes the delivery path ordinary Android resource merging — app module wins over the
library — which is Uqudo's own documented override mechanism. **Not an overlay, not a
workaround, nothing subclassed or reflected into.** The last-resort option in the brief was
never needed.

**One ordering constraint, easy to break later:** `UqudoSDK.init()` **removes** `key_locale`.
`setLocale` must follow `init()`. Both plugin wrappers already do this and now say why.

### R-002's string counts were also wrong

They were scraped from Uqudo's doc pages. Measured in the artifact's own `values.xml`:

| prefix | R-002 claimed | actually in 3.10.0 |
|---|---|---|
| `uq_face_*` | 63 | **52** |
| `uq_error_*` | 51 | **51** |
| `uq_scan_*` | 47 | **38** |
| `uq_button_*` | 9 | **1** |
| `uq_help_*` | 6 | **0** — those keys are dimens and styles, not strings |

Shipped set: **134 keys**, after excluding the NFC/reading (`uq_read_*`, 208), lookup/OTP
(`uq_lookup_*`, 72), background-check and PDF-upload flows — none of which this journey
enables — and the document names of countries other than Sudan.

### The AAB trap, guarded before anyone hits it

Because the Arabic lives in the app module, an Android App Bundle would let Play deliver only
the language splits matching the **device** locale. A handset set to English would install
without the `ar` split and the SDK would fall back to English **silently** — no error, nothing
in a log. `android.bundle.language.enableSplit=false` prevents it — expressed in the
`android { bundle { language { enableSplit = false } } }` DSL in
`mobile/android/app/build.gradle.kts`.

**It was first written as an `android.bundle.language.enableSplit=false` line in
`gradle.properties`, which does nothing at all.** The reviewer checked AGP 8.11.1 rather than
taking the property name on trust: `BooleanOption` carries no such key, `enableSplit` exists only
as a DSL member, and an unknown `android.*` property is silently ignored rather than rejected. The
guard looked present and was absent. Now in the DSL, where AGP actually reads it. The pilot ships
an APK and is unaffected either way; this is for the first person who builds a bundle.

### The wording is provisional, and the file says so

Drafted this session, **not** through the product owner's native-Arabic review and **not** seen
on a device. Entries most likely to break carry a `RISK (length)` / `RISK (newline)` /
`RISK (bidi)` comment so the review and the device pass know where to look hardest:

- **`uq_face_detection_alert_description`** — the longest string in the file, HTML in a dialog
  on the smallest screen, bold Latin digits inside RTL markup. Look here first.
- **`uq_face_hints`**, **`uq_face_position`**, **`uq_scan_face_visible`** — embedded `\n`, and
  Arabic runs longer than English, so a two-line string may wrap to three.
- **`uq_error_camera_permission_denied_description`**, **`uq_error_too_many_requests`**,
  **`uq_scan_info_id`**, **`uq_scan_id_photo_tampering_detected`**,
  **`uq_scan_source_detection_failed`** — long copy on a 5-inch display.
- **`uq_page_number`** (`صفحة %1$d - %2$d`) and
  **`uq_error_status_face_max_failed_attempt_reached`** — digits inside RTL text, the classic
  mirroring trap.
- **`uq_face_facial_recognition_face_turn_left` / `_right`** — in an RTL layout the customer's
  left is still their left. The instruction must match the on-screen avatar, not mirror with it.

`uq_passport_description` and `uq_sdn_id_description` are matched to the wording Stage 8 already
uses (`stage8_screen.dart`'s `_documentLabel`: «جواز السفر», «بطاقة الرقم الوطني») so the
customer does not meet two names for the same document in one journey.

---

## 5. Live proof — the RELEASE APK, not a passing test and not a debug build

A green test could not show any of this: nothing in the Dart suite touches Android resource
merging, so the override could have been silently dropped and every gate would still pass.

The first attempt at this proof used `--debug` and was wrong for the reason section 7 records —
`lintVital` is release-only, so the debug build could not have caught the blocker. Redone on the
build type the pilot actually ships.

**Release build, after the `ExtraTranslation` fix:**

```
Running Gradle task 'assembleRelease'...                          678.1s
√ Built build\app\outputs\flutter-apk\app-release.apk (108.2MB)
```

That is the blocker's proof: the same task graph that failed with 133 `ExtraTranslation` errors
now completes.

**A false start worth recording, because the notification lied.** The first release attempt
reported "completed (exit code 0)" while Gradle had actually failed — that was the shell
wrapper's status, not Gradle's. The real error was
`java.io.IOException: There is not enough space on the disk`, with the machine at **130 MB free**.
Freed 3.3 GB (the 4.3 GB regenerable `mobile/build` tree and the scratch copy of the extracted
AAR) and re-ran. **Read the log, not the exit code.**

**`aapt2 dump resources` on the release APK:**

```
uq_* strings carrying an (ar) value in the RELEASE APK: 129
uq_api_base_url overridden? False
uq_face_active_liveness overridden? False
uq_scan_error_document_identify present? True
```

Each key carries its bundled English default and our Arabic under the `ar` qualifier — the merge
behaving as intended — and the two `translatable="false"` keys (configuration, not copy) are
correctly untouched.

**129, not 134, and the gap is explained rather than waved past.** Five keys — `uq_confirm`,
`uq_error_permission_denied`, `uq_error_retry`, `uq_error_forbidden`,
`uq_error_payload_too_large` — are missing. The dump shows they are absent from the release
resource table **entirely, English defaults included**, so this is the release resource shrinker
removing SDK resources nothing references, **not an override failing to merge**. No language
regression follows: there is no string of any language behind those five in a release build, and
the code path that would have shown them went with them. They stay in the file because they are
correct and cost nothing. **Falsifiable claim for the device pass: if any of those five messages
appears in English on a handset, this analysis is wrong.**

## 6. Gates — verbatim

`fvm flutter analyze`:

```
Analyzing mobile...
No issues found! (ran in 86.9s)
```

`fvm flutter test`:

```
02:05 +515: All tests passed!
```

`fvm dart run tool/check_coverage.dart`:

```
03:08 +515: All tests passed!
Line coverage: 84.65% (3788/4475 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

**Not re-run after the review fixes, deliberately.** Those fixes touched
`values-ar/strings.xml`, `build.gradle.kts` and `gradle.properties` only — no Dart source and no
test changed after the run above, so a repeat would prove nothing the release build has not
already proven better. The Android-side verification is section 5's release build and `aapt2` dump.

---

## 7. Review

`@agent-reviewer` against the diff. **Four findings, all four accepted and fixed.** The first
was a genuine blocker my own proof had missed.

### BLOCKER — the release build was broken, and my proof could not have caught it

`ExtraTranslation` is a **FATAL** lint issue, and `lintVitalRelease` sits in the release assembly
graph. Every key in `values-ar` is defined only there — its default lives in the Uqudo AAR, and
lint does not count a library's defaults — so the release build failed with one error per key:

```
> Task :app:lintVitalRelease FAILED
strings.xml:37: Error: "uq_button_title_continue" is translated here but not found in default locale [ExtraTranslation]
...
133 errors
FAILURE: Build failed with an exception.
> Lint found fatal errors while assembling a release target.
```

**My section-5 proof used `--debug`, which never runs `lintVital`.** `flutter build apk` defaults
to release, and the pilot APK is a release build — so the one path that mattered was the one path
I had not exercised. The lesson is narrower than "run more builds": a build-type-gated check is
invisible to any proof taken on the other build type, and I chose the cheaper build type without
asking what it skipped.

**Fixed** by `tools:ignore="ExtraTranslation"` on the `<resources>` root, with the reasoning in the
file so it is not mistaken for tidying. The alternative — copying 133 English defaults into our
own `values/strings.xml` — would fork the SDK's copy into this repo and let it drift at the next
upgrade. Release build re-run as proof, in section 5.

### SHOULD FIX — the AAB guard was a no-op

`android.bundle.language.enableSplit=false` in `gradle.properties` is not an AGP project option.
The reviewer checked AGP 8.11.1 instead of trusting the name: no such key in `BooleanOption`,
`enableSplit` exists only as a DSL member, and an unknown `android.*` property is silently ignored
rather than rejected. **The guard looked present and did nothing** — the worst kind of wrong,
because the comment above it asserted protection that did not exist. **Fixed** by moving it to
`android { bundle { language { enableSplit = false } } }` in `app/build.gradle.kts`;
`gradle.properties` is back to its original contents and is no longer in the diff.

### SHOULD FIX — one reachable string had no Arabic

`uq_scan_error_document_identify` was missed. It is the dialog body `CameraFragment` shows on
`SCAN_DOCUMENT_NOT_RECOGNIZED` — a different key from `uq_error_scan_document_not_recognized`,
which is only the coded label, and my exclusion list did not cover it. **Fixed**, mirroring the
SDK's markup exactly (CDATA, escaped newlines, `<b>` tags — the fragment rewrites those newlines
to `<br>` before HTML-rendering, so the form is load-bearing). Set is now **134**.

The reviewer also confirmed the remaining non-overridden keys are genuinely unreachable:
`uq_scan_upload_*` are `UploadFragment` only, `uq_scan_error_age_verification` needs
`enableAgeVerification` (deliberately not called), and `uq_scan_message_mrz` fires only for
`DocumentType.MRZ`, never `PASSPORT` or `SDN_ID`.

### NOTE — a false observation, tagged [OBSERVED], in three places

The file header, R-002 and the component card all said the AAR's `res/` holds `values` and
`values-night-v8` **only**. It does not — it also ships `drawable*`, `font`, `layout`,
`navigation`, `raw` and 27 `values-sw*dp-v13` buckets. The load-bearing conclusion (no
`values-<lang>` of any kind, so zero shipped translations) is correct and the reviewer
independently confirmed it, but the stated observation was wrong, and a later session would have
re-read it as fact. **Fixed** in all three places.

### Fixing the fix

Applying the missing string with a scripted edit turned the intended literal `
` escapes into
real newlines and left a stale four-line fragment behind, and the word `--debug` inside the new
XML comment is illegal (`--` cannot appear in an XML comment). All three were caught by
re-validating rather than by assuming the edit landed — which is the same discipline the blocker
above shows I owed the release build.

Post-fix re-validation of the whole file against the SDK originals:

```
parsed OK, strings: 134 | duplicates: []
dead keys: []
drift vs SDK originals: []
uq_api_base_url present? False
uq_face_active_liveness present? False
```

### Checked clean by the reviewer, for the record

Item 1's deletion complete with no orphaned spacer and no stale references; both test anchors
byte-match `const Text('قبل أن تبدأ:')`, which occurs exactly once in the file; `..disableHelpPage()`
is inside the `DocumentBuilder` cascade and `.build()` still produces the same `Document`, with the
"Deliberately NOT called" list still accurate; **iOS forwards the flag too**
(`ios/Classes/UqudoIdPlugin.m:228-229`), which I had not checked; the `attachBaseContext` and
`isHelpPageVisible` findings independently reproduced; the RISKS prefix counts (52/51/38/1/0)
exact. No generated file edited, no secret, no JWS decoded, no hardcoded tenant or endpoint.

---

## 8. What the product owner must check on the phone

All three need real arm hardware — the SDK's screens do not appear on the emulator.

1. **Stage 8, «قبل أن تبدأ»** — the sentence «يُحتسب الخروج من الكاميرا…» is gone, and there is
   no empty gap where it used to be.
2. **Tap «ابدأ المسح»** — the camera opens **directly**. No "Scan Passport / Fit Passport to the
   frame / Start" screen, and no uqudo logo anywhere in the scan flow. If that screen appears,
   item 2 has regressed.
3. **During the scan and the liveness step** — the SDK's own prompts and errors render in
   Arabic. This is the one to read critically rather than tick: the wording is a draft. Check
   the numbered hint lists read in the right order, the two long dialogs are not truncated on
   the 5-inch handset, and «أدر وجهك قليلًا نحو اليسار» points the same way the avatar does.

---

## 9. Device confirmation — 2026-09-08, after the session's commits

The product owner installed the release APK on the pilot handset and reported **"all went
perfect"**. That is the on-device proof section 8 asked for, and it lands all three items:

- **Item 1** — the sentence is gone, with no gap left behind.
- **Item 2** — the SDK's intro screen does not appear; the camera opens directly and no uqudo
  logo is seen. The `disableHelpPage()` finding is confirmed on hardware, not just in bytecode.
- **Item 3** — the SDK's own screens render in Arabic. The `values-ar` mechanism works end to end
  on a real device, which is what R-002 had been open on since S1-02.

**One correction en route, which is why this needed a second APK.** The first release APK was
built with a bare `flutter build apk --release` — no `--dart-define` — so it carried
`app_config.dart`'s `http://localhost:8080` default and could not reach the backend at all
(«تعذر الاتصال» on Stage 1a). It had been built to prove `lintVital` and the resource merge, not
to be installed, and was handed over without that being said. Rebuilt with
`--dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net`, then verified by
grepping the AOT binary rather than the APK zip: the CloudFront host is present in
`lib/arm64-v8a/libapp.so` and `localhost:8080` is **absent entirely** — the meaningful half,
since a `const String.fromEnvironment` default is compile-time replaced, so its absence is what
proves the define took. **Any APK that goes to a device needs that define; a build made only to
prove a gate is not a distributable.**

**What this does NOT establish, and BL-071 stays open for it.** "All went perfect" is a
successful journey run, not a line-by-line reading of 134 provisional Arabic strings. Still owed:

- The product owner's **native-Arabic wording review**. The strings remain provisional.
- A **deliberate look at the flagged RISK entries** — the numbered hint lists, the two long dialog
  bodies, the left/right face instructions. A happy-path run may never surface them, because most
  are error and correction states.
- The **five shrunk strings** (`uq_confirm`, `uq_error_permission_denied`, `uq_error_retry`,
  `uq_error_forbidden`, `uq_error_payload_too_large`). A clean run exercises none of them, so the
  falsifiable claim in section 5 is untested rather than confirmed.

This was also the **first release build this app has ever had** — every earlier pilot APK was
`--debug`. R8 and resource shrinking ran for the first time and the journey completed, which is
a real result worth recording on its own.

---

## 10. Owed, and not glossed

- **The attempt-cost warning is gone with nothing replacing it.** BL-090 carries it. If the
  block turns out to confuse pilot customers, the fix is a new screen at the point of blocking,
  not a reinstated sentence on the pre-scan screen.
- **Item 2 has no automated guard.** If a future session rewrites the builder and drops
  `disableHelpPage()`, only a device run will notice. The Arabic overrides for
  `uq_scan_help_page_title`/`_description` are deliberately kept as a tell: if those two strings
  ever appear on a device, the flag has been lost.
- **iOS is untouched and unverified.** The `values-ar` mechanism is Android-only; the iOS
  equivalent is the `uq-text.plist` override in the `UqudoSDK` pod, which is gated on AD-003 and
  was not attempted. No Flutter package was added this session, so there is no new iOS support
  check to report.
- **BL-071 stays open** — native-Arabic review and an on-device truncation/RTL pass.
- **SDN_ID remains unexercised.** `uq_sdn_id_description` is translated against the documented
  contract, like the rest of the national-ID path; it is not evidence the card scans.

---

## 11. Commit

```
994ada5 S8-09: pre-scan copy, the Uqudo intro screen, and Arabic SDK strings
 11 files changed, 731 insertions(+), 32 deletions(-)
```

Pushed straight to `main` (`be892b8..994ada5`), which is this project's norm. Every path was
staged explicitly, never `git add -A`. Working tree fully clean afterwards — note that
`strartup.mp4`, which the brief asked to keep untracked, is **not present in the repo at all**
this session, so nothing had to be held back.

---

## 12. Files

| File | Change |
|---|---|
| `mobile/lib/features/identityscan/stage8_screen.dart` | Item 1 — sentence, comment and orphaned spacer deleted; class doc records the decision |
| `mobile/test/features/identityscan/stage8_screen_test.dart` | Test renamed; asserts absence + a real anchor |
| `mobile/test/features/identityscan/identity_scan_navigation_test.dart` | Re-anchored on `'قبل أن تبدأ:'` |
| `mobile/lib/core/identityscan/plugin_uqudo_scanner.dart` | Item 2 — `..disableHelpPage()` with the bytecode evidence |
| `mobile/android/app/src/main/res/values-ar/strings.xml` | **New.** Item 3 — 134 provisional Arabic overrides, plus the `ExtraTranslation` suppression the release build needs |
| `mobile/android/app/build.gradle.kts` | Item 3 — the language-split guard, in the DSL where AGP reads it |
| `docs/components/uqudo-sdk.md` | Locale mechanism, real string counts, AAB caveat, `disableHelpPage` in required config; one open item closed |
| `RISKS.md` | R-002 corrected — mechanism and counts |
| `BACKLOG.md` | BL-071 rescoped not closed; BL-090 records 8b actioned |
| `EXECUTION_PLAN.md` | S8-09 row |
