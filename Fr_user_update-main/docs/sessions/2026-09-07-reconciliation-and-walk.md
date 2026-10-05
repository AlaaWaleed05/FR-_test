# 2026-09-07 — Pre-build reconciliation, and the guided journey walk

**Read-only on application code. This session changed no application code and built nothing.**
Its output is the current-state picture below, which is the input to the next (build) session.

No real PII appears in this report. The seeded account numbers below are synthetic stub values
(`fru.core-banking.stub.accounts`), not live account numbers.

---

## Job 1 — ground truth, read from source and from the live deployment

### 1.1 The mobile theme — what `app_theme.dart` actually contains now

`mobile/lib/core/theme/app_theme.dart` is 82 lines and is the app's entire visual definition.
It is applied once, at `mobile/lib/core/app.dart:29` (`theme: AppTheme.light()`). There is no
second `ThemeData` anywhere in `mobile/lib`.

**What it sets:**

| Property | Value |
|---|---|
| `useMaterial3` | `true` |
| `brightness` | `Brightness.light` |
| `colorScheme` | `ColorScheme.fromSeed(seedColor: #105097)` with `primary` and `onPrimary` **pinned** to `#105097` / white |
| `fontFamily` | `IBMPlexSansArabic`, `fontFamilyFallback: ['sans-serif']` |
| `textTheme` | 15 styles, all `letterSpacing: 0`; body 16 sp / `height` 1.6; `bodySmall` + `labelMedium` + `labelSmall` 14 sp; `title*` `w600` |
| `pageTransitionsTheme` | `{ TargetPlatform.android: FadeUpwardsPageTransitionsBuilder() }` — Android only |

**What it does NOT set — this is the answer the pending S1 build needs:**

- no `scaffoldBackgroundColor`
- no `InputDecorationTheme` / `InputDecorationThemeData`
- no `CardTheme` / `CardThemeData`, no `ListTileTheme`
- no `surfaceContainer*` / `surface*` overrides on the scheme (only `primary`/`onPrimary` are pinned)
- no `darkTheme`, no `themeMode`

Consequence, stated precisely because S1 is defined as a delta against it: `scaffoldBackgroundColor`
is unset, so Flutter resolves it to the Material 3 default, `colorScheme.surface` — which is exactly
the value S1 proposes to move off, onto `surfaceContainerLow`. Text fields are therefore default M3
(underline, `filled: false`) and cards are default M3.

**So S1 lands on a clean slate. There is nothing to fight and nothing to double-apply.** Every
surface property S1 touches is currently unset. What S1 must *not* clobber, all three of which are
load-bearing and carry their reasoning in comments:

1. the pinned `primary`/`onPrimary` — D3.2: `fromSeed` maps the seed through a tonal palette and
   returns a lighter, less saturated `primary`, so seeding with `#105097` does **not** give
   `#105097` back;
2. `letterSpacing: 0` on every text style — Material's Latin-tuned positive tracking breaks the
   joins of a connected script;
3. the Android `FadeUpwardsPageTransitionsBuilder` — D6.1: its tween is `Offset(0, 0.25) → zero`,
   so x is zero and the motion is direction-neutral *by construction*. No built-in Material page
   transition mirrors for RTL, so replacing this with a horizontal slide silently reintroduces
   Latin-direction navigation in an Arabic app, with every test still green.

**One stale comment, worth knowing before the next session goes looking for a file.**
`app_theme.dart:23-25` says "the Android `values-night` launch theme is made identical to `values`".
There is no `values-night` directory — `mobile/android/app/src/main/res/` holds `values` and
`values-v31` only. `git show 23231d7 --stat` shows `values-night/styles.xml` was **deleted** (14
lines) in that commit. The intent the comment describes holds (the OS dark setting cannot put a dark
window behind a light app), but the mechanism is deletion, not an identical file.

### 1.2 The splash — current state

Two halves, both live:

**Native launch window.** `drawable/launch_background.xml` and `drawable-v21/launch_background.xml`
(kept identical; `drawable-v21/` is the variant that actually applies at minSdk 24) both resolve to
`@color/brand_blue` = `#105097` from `values/colors.xml`. `values/styles.xml` sets `LaunchTheme`'s
`windowBackground` to that drawable and `NormalTheme`'s to the colour directly. `values-v31/styles.xml`
re-declares the same blue through the Android 12+ API, which ignores `windowBackground` entirely:
`windowSplashScreenBackground` = brand blue, `windowSplashScreenAnimatedIcon` =
`@mipmap/ic_launcher_foreground`. Both paths are live at minSdk 24 / targetSdk 36.

**Flutter `LaunchScreen`** (`mobile/lib/features/entry/launch_screen.dart`, 330 lines). A brand-blue
`Scaffold` and **one** `AnimationController` at 1800 ms, sliced into overlapping sub-intervals so the
sequence stays a single timeline:

| Element | Interval | Motion |
|---|---|---|
| SFB emblem | 0.00–0.42 | fade + scale 0.92 → 1.00 (up, deliberately not a zoom-out — see below) |
| «الفرنسي بياناتي» | 0.20–0.52 | fade + 12 px rise, in a `FittedBox` |
| «لؤلؤة المصارف» | 0.42–0.78 | `ShaderMask` gradient sweep, **right-to-left** |
| hairline rule | 0.62–0.84 | grows outward from centre to 44 px |
| «خطوات بسيطة لتحديث بياناتك» | 0.72–1.00 | fade + 12 px rise |
| spinner | 0.84–1.00 | fade + rise |

`MediaQuery.disableAnimationsOf(context)` jumps the controller to `1` (D6.5). The choreography is
fire-and-forget: `_navigate` runs the instant the launch decision resolves and never awaits the
controller, so the splash fills a wait that already exists rather than manufacturing one. The scale
goes *up* from slightly small on purpose — the API-28 reference device runs the legacy renderer and
pays first-run shader compilation, and large-to-small is the variant that shows that cost as a
visible stutter.

**There is no visual tiering in the code.** `grep -rni "lowEnd|deviceTier|visualTier|fallbackTier|
reducedEffects|isLowEnd" mobile/lib` returns nothing. Tiering is a *proposal* in
`docs/sessions/2026-09-07-research-splash-and-surface.md` §8 (an `SDK_INT ≥ 29` boundary), and it is
proposed for the **splash motion options P1/P2/P3 only**.

This corrects the framing in the session brief, and it matters for how the PO's comments are read:
the PO is **not** seeing a fallback visual tier. There is only one tier today, and it renders the
same on every device. Two consequences:

- A "not fancy enough" comment about the splash is a comment on the **current shipped splash**, not
  on a degraded version of P2. P2 does not exist yet on any device.
- **S1 — the priority item — needs no tiering at all.** The research is explicit that its rich and
  fallback versions are identical: flat fills cost the same on Impeller and on the legacy renderer,
  there is no animation, and nothing new compiles. So an S1 judgement made on this Huawei is a valid
  judgement of what S1 will look like everywhere. That is a genuine advantage of doing S1 first, and
  it is the one part of the pending work this device can assess without caveat.

### 1.3 Stage 1b channel-selection defaults — BL-086 traced end to end

**Traced, because the brief asks whether this is a mobile-only fix. It is not — and BL-086's own
text is wrong on one material point.**

*Mobile.* `contact_channels_screen.dart:27-29` — `_smsSelected`, `_whatsappSelected` and
`_emailSelected` all default to `true`. The Drift column backing the draft has the same default
(`session_database.dart:41`: `boolean().withDefault(const Constant(true))`), so the draft-restore
path at `:82` re-asserts `true` for a resuming customer rather than merely preserving a choice.

*Wire.* The flag is sent, not merely displayed — `submitContactChannels(..., whatsapp: _whatsappSelected)`.

*Backend.* `ContactChannelsRequest.whatsappSelected()` returns `whatsapp == null || whatsapp`, so a
**null also means selected**. `ContactChannelsService:183-192` passes it into
`ChannelSelection.resolve(...)`, whose `stateFor(selected, enabled)` returns `UNVERIFIED` (to be
challenged) only when `selected && enabled`, else `DECLINED`. A challenged channel gets an
`otp_challenge` row and a billed dispatch.

**So the flag drives challenge creation, and flipping the mobile default alone is a behaviour change
made from the client.** That much of BL-086 is confirmed.

**Where BL-086 is wrong:** it states that "D9.1 also assumes a feature flag that does not exist in
the codebase." That flag exists. `fru.messaging.<channel>.enabled` is read by
`ContactChannelsService.isEnabled` (`:487-490`) via `MessageSenderConfiguration.enabledProperty`
(`:93-94`), defaulting to `true` when unset. Setting `fru.messaging.whatsapp.enabled=false` makes
`stateFor` return `DECLINED` for WhatsApp regardless of what the phone sends — no challenge, no
dispatch, and the channel is recorded as `DECLINED`, which `ChannelSelection`'s own class comment
calls the honest record of "the customer wanted it but we couldn't". That is precisely BL-086's
"refused at the backend" option, already built and already reachable from configuration alone.

**What is genuinely missing is the other half:** nothing tells the phone which channels a deployment
can actually verify. No endpoint exposes enablement — `grep` for `enabledProperty|availableChannels|
whatsappEnabled` across `backend/src/main/java` finds only the three sites inside
`MessageSenderConfiguration` itself, and no mobile code reads any enablement field. That is **R-042**,
still 🔴, and it is the real open piece: the UI cannot follow a flag it has no way to read. So the
work splits cleanly — the backend refusal is a config change available today; the visible-but-disabled
row D9.1 wants needs a wire field first.

**And it is live on staging right now.** Task definition 8 sets no `FRU_MESSAGING_*_ENABLED`
variable, so `whatsapp.enabled` defaults to `true`; a WhatsApp challenge **is** created and
dispatched to `FRU_MESSAGING_WHATSAPP_PROVIDER=stub`, so nothing is delivered. This is exactly
R-042's predicted failure mode, in production configuration, today. It does not block the journey —
Stage 2's `_onNext` requires only `_anyPhoneVerified` and shows a confirmation dialog listing the
unverified channels (`channel_verification_screen.dart:302-308`) — but it does mean any walk on this
build meets an extra dialog and a permanently unverified WhatsApp row. See §2.3.

### 1.4 Backend — which client selectors are live on the deployed AWS stack

Read from the **live** service, not from a provisioning script. Cluster `fru-staging`, service
`fru-staging-backend`, 1/1 running, task definition **`fru-staging-backend:8`**, image
`0.0.1-20260907t1848`.

| Selector | Deployed value | Real or stub |
|---|---|---|
| `FRU_CORE_BANKING_CLIENT` | `stub` | **stub** |
| `FRU_UQUDO_CLIENT` | `http` | real |
| `FRU_CIVIL_REGISTRY_CLIENT` | `http` | real |
| `FRU_MESSAGING_SMS_PROVIDER` | `http` | real (Airtel Sudan) |
| `FRU_MESSAGING_WHATSAPP_PROVIDER` | `stub` | **stub** |
| `FRU_MESSAGING_EMAIL_PROVIDER` | `stub` | **stub** |

Secrets present and wired: `UQUDO_CLIENT_ID`, `UQUDO_CLIENT_SECRET`, `UQUDO_AUTH_URL`,
`UQUDO_API_BASE`, `UQUDO_JWKS_URL`, `UQUDO_ISSUER`, `FRU_CIVIL_REGISTRY_ENDPOINT`,
`FRU_CORE_BANKING_ENDPOINT`, `FRU_MESSAGING_SMS_{ENDPOINT,SENDER_ID,USERNAME,PASSWORD}`,
`FRU_APP_PASSWORD`. No value was read.

**BL-089 is unchanged and still open.** `FRU_CORE_BANKING_ENDPOINT` is provisioned and unused: the
real middleware endpoint is sitting there behind a selector still set to `stub`. This remains a
one-variable change plus a task-definition revision.

### 1.5 The thirty seeded stub accounts — confirmed present *and* usable

`backend/src/main/resources/application.properties` carries `0000000001=1`, `0000000002=-1`, and
`0000001001` through `0000001030`, all `=1` (30 accounts, lines 78-107).

Confirmed against the **deployed** stack rather than the file — a live probe of the public endpoint:

```
POST https://d12k860j1xg6zy.cloudfront.net/api/v1/account-check
     {"branch":"001","accountNumber":"0000001001"}
→ HTTP 200
  {"outcome":"ACTIVE","continuation":"PROCEED","requestId":"0e218136-…","blockedUntil":null}
```

`ACTIVE` / `PROCEED`, so the block is deployed and walkable. Account-check creates no profile, so
this probe did not consume the account. Per BL-089 the accounts are **single-use** — a profile is
keyed on the account number alone and is never deleted by design, so thirty accounts mean thirty
complete walks, not thirty testers.

### 1.6 Back office — and one gap that was not on any list

The BL-076 items **are in the repo**, verified at source:

- em-dash and the «واجهة الموظفين» rename — `layout/AppShell.tsx:41`
- `DD/MM/YYYY HH:mm` via dayjs — `profiles/ProfileDetailPage.tsx:70-71`, used at `:212`, `:323-325`,
  `:461`, `:496`, `:535`, and at the profile list
- the status-history full sentence replacing the bare `(من <status>)` calque —
  `profiles/ProfileDetailPage.tsx:193-200`

**But none of it was deployed.** The S3 bundle behind the CloudFront distribution was
`assets/index-DeNJ15ee.js`, dated **2026-09-06 21:48** — before the 2026-09-07 UI work. Fetched from
the live distribution, it still contained the old calque «الواجهة الخلفية» (1 occurrence) and
**zero** occurrences of `DD/MM/YYYY`. The entire 2026-09-07 back-office pass was committed and
undeployed, so an operator opening staging saw the pre-BL-076 UI.

This was on no backlog row, and it mattered for the same reason BL-085 gave when it forced a
redeploy: staff test against the deployed stack, so a repo-only fix leaves every staff test looking
at the old screen.

**RESOLVED this session, by product-owner instruction.** Gates first, since deploying is
consequential and the tier had not been re-gated since the UI pass: `npm run lint` → warnings only,
all pre-existing (`react(set-state-in-effect)`, `react(globals)`, `react(only-export-components)`);
`npm run test:coverage` → **18 files, 140 tests passed, exit 0**, statements 97.09%, branches 86.25%,
functions 97.46%, **lines 98.9%** — every Vitest threshold cleared.

Then `npm run build` (new bundle `assets/index-C4jkaBcS.js`), and the upload performed with exactly
the two commands and cache rules `infra/aws/09-backoffice-cloudfront.sh` uses — `s3 sync --delete`
with `public,max-age=31536000,immutable` for the fingerprinted assets, then `index.html` separately
with `no-cache` — rather than re-running the whole script, which would also have re-walked the VPC
origin wait and the ALB security-group narrowing for no reason. CloudFront invalidation
`I151KRLPXMS5JLX7M7BKAN77R4` on `/index.html`.

Verified **live from CloudFront**, not from the bucket, using the same check that proved it stale:

```
index.html now references  → assets/index-C4jkaBcS.js
«واجهة الموظفين»           → 1   (was 0)
DD/MM/YYYY                 → 1   (was 0)
«تغيّرت الحالة من»          → 1   (was 0)
```

Two notes for whoever reads this next. The old calque «الواجهة الخلفية» still returns one hit in the
new bundle — that is `AdminHomePage.tsx:36`, a different sentence using the phrase in prose, not the
header, and it is not a missed fix. And the superseded `index-DeNJ15ee.js` still answers 200 from the
edge cache although it is deleted from S3; nothing references it any more, so it will age out
harmlessly.

**This deploy does not make the back office usable for a real review.** It fixes wording and date
formatting only. **BL-075 is untouched** — the operator still sees placeholder avatars and a
metadata-only attachments table, because that is blocked on the **R-046** decision, not on a deploy.

Still open, unchanged:

- **BL-075** — exactly as filed. `ProfileDetailPage.tsx:586-606` renders `PORTRAIT_PLACEHOLDER_SRC`
  for both portraits, with on-screen Arabic text naming R-046 as the reason, plus a metadata-only
  attachments table (label / MIME / size / checksum) at `:608-624`. Blocked on **R-046**, which is
  a decision, not an implementation.
- **BL-078** (neither operator cookie states `SameSite`) and **BL-001** (statistics dashboard,
  deferred by design) — untouched.

### 1.7 `strartup.mp4` — flagged, and it is not as safe as "untracked" sounds

Present at the repository root, 2,855,498 bytes, modified 2026-09-07 16:35.

- **Untracked** — `git ls-files --error-unmatch strartup.mp4` → *did not match any file(s) known to git*.
- **NOT gitignored** — `git check-ignore -v strartup.mp4` returns nothing, and `.gitignore` has no
  `mp4`/video rule.

So it appears in `git status` as `?? strartup.mp4` and **a `git add -A` would commit it**. That is
the whole reason the brief forbids `git add -A`. This report was committed by explicit path. If the
file is meant to stay around, the durable fix is a `.gitignore` entry rather than repeated care.

---

## Job 2 — the guided journey walk

**Status: COMPLETE. The walk ran 2026-09-07 ~20:50-22:00, product-owner driven, end to end on
account 0000001003, which submitted successfully.** Every comment is recorded verbatim in §2.5,
unfiltered and untriaged, exactly as given, and read into twelve bundles in §2.7. The
preparation record below is kept because it is what the run rests on.

**One aborted first attempt, and what it cost.** A first walk on account `0000001002` reached the
Stage 2 OTP screen and ended when the laptop lost power. On restart that account answers
`continuation=TERMINAL` — it had answered `PROCEED` twenty minutes earlier — so it is **burned and
not resumable**, and the run moved to `0000001003`. This is unexplained and possibly a real defect (now tracked as BL-093):
`ExistingProfile.terminal` is `app.status_code.is_terminal`, true for exactly `submitted`,
`approved`, `rejected` and `terminated_registry_mismatch`, and a profile abandoned at Stage 2 should
be none of those. Settling it needs the profile's actual status read from the database, which this
session had no access to. **If it reproduces, an interrupted customer is permanently locked out of
their one allowed update** — which would be considerably more serious than a lost test account.
Recorded here rather than filed, because one unexplained observation is not yet a diagnosis.

### 2.1 What was verified ready

| Item | State |
|---|---|
| Device | Huawei **AMN-LX9**, Android **9** (API 28), 2 GB — the S1-02/S5-07/S5-08 handset, connected over USB, `adb devices` → `device` |
| Installed app | `com.example.mobile`, `versionName=1.0.0`, installed 2026-09-03, last updated **2026-09-07 18:06** |
| Installed app's backend | **`https://d12k860j1xg6zy.cloudfront.net`** — AWS staging, confirmed |
| Installed app's code | **current** — carries `FieldErrorState`, a file that did not exist before the newest mobile commit `23231d7`; no uncommitted changes under `mobile/` |
| Deployed backend | task definition 8, 1/1 running, answering `account-check` over HTTPS (§1.5) |
| Seeded account | `0000001001` live, `ACTIVE`/`PROCEED`, unconsumed |

**Correction, stated because it changed the conclusion.** I first reported the installed APK as
pointing at `localhost`. That was wrong: I had grepped the `.apk` as a zip, where the Dart string
lives inside a compressed entry and does not match. Pulling the installed APK off the device
(`adb pull`) and grepping the *uncompressed* `assets/flutter_assets/kernel_blob.bin` shows
`https://d12k860j1xg6zy.cloudfront.net` present. The installed build was already correct and
current; **no reinstall was needed and none was done.** A fresh debug APK was built against AWS
before that check completed (`Running Gradle task 'assembleDebug'… 185.7s`, exit 0, at
`mobile/build/app/outputs/flutter-apk/app-debug.apk`, verified to carry the same AWS URL). It is
equivalent to what is installed and is available if a clean reinstall is wanted.

### 2.2 The OTP step — the brief's premise does not hold, and the real procedure is easier

The brief says to "recover the code from the backend/DB as prior runs did". **That is not possible
on this stack, and it is not necessary.**

- Not possible: `V0007__app_channels_and_otp.sql:22` stores `sha256(salt || code)` with the comment
  *"the code is NEVER stored"*. `StubMessageSender` deliberately does not retain the rendered body
  either — its own comment explains that keeping the payload would violate that rule — and its
  in-memory `recordedSends` list is gated behind `fru.messaging.stub.record-sends`, which is set
  nowhere and is unreachable over the wire regardless.
- Not necessary: **SMS is real on staging** (`FRU_MESSAGING_SMS_PROVIDER=http`, the Airtel Sudan
  adapter, proven delivering to a real handset at S8-02). The OTP arrives as an actual SMS on the
  phone number entered at Stage 1b. The PO reads it off their own handset.

So Stage 1b must be given a **real, reachable Sudanese mobile number** — the PO's own. A synthetic
number will simply never receive a code.

### 2.3 Blockers and expected friction, found during preparation

Recorded now so they are not mistaken during the walk for defects the walk discovered:

1. **WhatsApp will be selected by default and will never verify** (§1.3). Its provider is `stub` and
   its `enabled` flag defaults true, so a challenge is created and delivered nowhere. Stage 2 will
   show an extra confirmation dialog naming WhatsApp as unverified before it lets the journey
   continue. This is expected, it is BL-086 / R-042, and it is not a walk failure. The PO can either
   deselect WhatsApp at 1b or continue through the dialog — both are worth observing, but the
   dialog's wording is the interesting part.
2. **Email is also `stub`** and will behave the same way if an address is entered. S7-12 never
   exercised the email channel.
3. **The account is spent by the walk.** One complete journey consumes `0000001001` permanently.
   Twenty-nine remain.
4. **The back office the PO would review the submission in is the stale bundle** (§1.6) — pre-BL-076
   wording and ISO dates. Any back-office comment collected today is a comment on the *old* UI, and
   would need re-checking after a deploy.
5. **Logcat will not carry Flutter logs.** BL-055, confirmed twice on this exact ROM (zero
   `I/flutter` lines in 77,336 captured at S5-08, over Wi-Fi, so it is the ROM and not the USB
   path). Observation must come from the backend audit chain, which is the stronger evidence anyway.
   Per BL-069, prefer `adb tcpip 5555` over USB so a re-enumeration cannot truncate a capture.
6. **Uqudo SDK screens are in English.** R-002 / BL-071 — the SDK ships no `values-<lang>` folder at
   all. Expected; not a defect to record afresh.
7. **Real integrations mean real data.** Uqudo and Civil Registry are both `http`, so the scan and
   registry stages put the PO's actual passport and actual national record through the live own
   tenant, exactly as at S7-12. Nothing about that is to be captured into this repo.

### 2.5 The product owner's comments — VERBATIM

Recorded as given, unedited and untriaged. Nothing here is a decision; §2.6 adds only
observed evidence where it bears on a comment, and never a disposition.

The product owner's own numbering is preserved exactly, including where it is irregular: general
point 6 carries a sub-point `a` about question/answer separation that is not about country lists,
and screen items 13 and 14 are two halves of one comment about «ارسال الطلب».

#### General — apply across all screens

> 1. I want to see a progress line on top for all screens, and it should get filled gradually with
>    each screen. The line will have small points one per each screen and big points or circles for
>    each logical section for the journey, you should define the logical section but something like
>    bank account, personal data, identity, signature, submit
> 2. Back navigation is now text, it should be an icon, also sweep back should be usable
> 3. The title of the screen should be with background and style that differentiates it from the rest
>    of the screen evidently
> 4. The action button Next is now transitioning the user from a field to the next in the same screen
>    and also from screen to another. This action button should be only for the screen to screen
>    transition, and next button in the keyboard should be transitioning the user between fields
> 5. Action button is not always having the same location and size, and it should. For example its
>    smaller in the screen of selection of the communication channels and the screen of personal and
>    social data, and maybe other screens.
> 6. For all Country_List: Sudan should be the first option in the list, the the rest can be ordered
>    as is done now, because almost all users are Sudanese.
>    a. Every category/question should be evidently distinctive from its answer choices. And
>       categories or questions should be visibly separated from each other, maybe by boxing every
>       question or grades or shades or something I don't know what

#### 1. Splash

> a. Should be called بياناتي with ـــ to fit or exceed the width of the logo, and it should be
>    relatively bigger size than the other text and maybe with glow or something distinctive
> b. Give the splash some second for the user to see it and read the text

#### 2. Error screen for not being able to retrieve the lists

> a. It shouldn't say which list, it should just say تعذر الاتصال، الرجاء التأكد من الاتصال بالإنترنت ثم أعد المحاولة
> b. This is an error message, should be shown with emphasis and icon for no connectivity and in a
>    pop-up or so
> c. This error should not be possible and at the same time the field of the account number is
>    available as in the case I got. If any list is not retrievable then the whole screen should be
>    just the error message.

#### 3. Screen the branch and account

> a. This screen doesn't have much details, make use of the screen and don't push them both on top
>    and leave the rest of the screen empty

#### 4. Screen of communication channels selection

> a. Screen title should be اختيار قنوات الاتصال
> b. First line from top should be explanatory text for what this screen is about: اختر قنوات الاتصال التي تود اعتمادها في التواصل مع البنك
> c. Use icons for each option (whatsapp logo, sms, email icon)
> d. The resultant text at the button can be deleted, or change with this text: سيتم ارسال رموز التحقق لاعتماد: then the icons of selected channels

#### 5. OTP Screen

> a. Screen title should be التحقق من قنوات الاتصال
> b. Make sure the text sizes doesn't make the screen title overflow, the current screen title
>    overflows and reads at the last word letters …
> c. Again consider using the icons in addition to the text
> d. The text رقم الهاتف غير صحيح؟ العودة لتعديله should be an action button and lighter than Next
>    button but next to it just like the السابق button in some other screens but this with different
>    text. But in my scenario this text should not be seen if I successfully verified any of the
>    phone number OTP, if I verified either sms or whatsapp then I should not be able to go back
>    change the number and hence this action nutton should disappear the momen I do successful OTP
>    validation of a phone cahnnel

#### 6. Screen of Personal and social data

> a. Marital status, Education and other questions should be drop down instead of lists, this makes
>    things more compact. The same for the gender and the income source

#### 7. Screen for selection of document

> a. Since we have only two options here, we should have them as cards, each with picture for
>    generic (passport for the passport option, and national ID for the national ID option). This
>    card will have the image and the text and the card overall is the action button

#### 8. Screen of Document scan instruction before uqudo

> a. Consider adding icons to each instruction
> b. The text is red is meaningless, delete it

#### 9. First uqudo screen

> a. Find way to hide this screen totally, it only contains the uqudo name and start action button,
>    we don't want the name of uqudo to be seen by the user, and it has no informative value

#### 10. Screen of review of CR retrieved data

> a. Screen title should be مراجعة نتائج المسح
> b. The text بيانات السجل المدني should be deleted, the user doesn't care about the source of the
>    data and he should not know
> c. Every segment of theis screen should be emphasized as a section, I mean for example the word
>    الصور should be different than the name data, and so on

#### 11. Screen of instructions for liveness

> a. Screen title should be التحقق الشخصي
> b. The text تحقق سريع من هويتك should be deleted

#### 12. Signature screen

> a. The two options should be evident as two options: draw, upload (image or file)

#### 13/14. Screen ارسال الطلب

> This screen is meaningless now as it has only one action possible, but the sentences are good to
> be used somewhere else

#### 15. Screen of تم الارسال

> a. Reference number still starts with FRU and it should start with SFB, maybe it's a backend thing
>    but if so then make sure you log it clearly for the backend next session
> b. The app should ask the user to save this number
> c. When I click the action button انهاء it takes me back to the initial splash screen, it should
>    maybe have the same splash with close action button or the انهاء action button itself should
>    close the application

### 2.6 Evidence gathered alongside the comments

Observation only. No comment above is triaged, accepted or rejected here.

- **On the list-retrieval error (comment 2c) — the failure was genuine and device-side, not a
  backend outage.** Probed during the run, `GET /api/v1/reference/manifest` answered **HTTP 200 in
  0.259 s** with a well-formed catalogue (`admin_division` 151 items, `branch` 25, catalog hash
  present). The capture shows the handset in Wi-Fi settings at 20:57:37–39 with
  `detailedState = OBTAINING_IPADDR`, so connectivity was genuinely absent or re-establishing when
  the fetch failed. This does not weaken the comment — it sharpens it: a real connectivity failure
  is precisely the case where the screen must not continue to offer the account-number field.
- **Stage 1a screenshot captured** at 21:04 (`screen1.png`, scratchpad, not committed): branch
  prefilled «الجمهوريه», account field focused and empty, «التالي» disabled until input. It
  corroborates comment 3a directly — both controls sit in the top third with the lower two-thirds
  empty above the keyboard.
- **BL-055 is narrower than filed, on this run.** That row records *zero* `I/flutter` lines on this
  ROM. This capture does contain `I/flutter` lines (the Impeller manifest notice, three times at
  20:53, 20:57 and 21:03). They carry no application logging, so the row's practical conclusion
  stands — but "zero I/flutter lines" is not literally true here and the row should say "no
  application-level Flutter logging" instead.
- **Four device photographs supplied by the product owner corroborate four comments directly.** They
  are not committed (they show the handset and, on one, part of a real phone number). What they
  establish, described without reproducing any personal data:
  - *OTP screen* — the title renders as «التحقق من وسائل ال...», **visibly truncated with an
    ellipsis**. Comment 5b is a reproducible layout defect, not a preference. The same photo shows
    «التراجع عن الجلسة» rendered as a plain text link (comment G2), and «رقم الهاتف غير صحيح؟ العودة
    لتعديله» still on screen *below an already-verified SMS row* reading «موثقة — سيتم حفظها في
    ملفك», which is exactly the state comment 5d says it must disappear in. Only the SMS row is
    present — the product owner had deselected WhatsApp at Stage 1b.
  - *First Uqudo screen* — carries the **uqudo wordmark** at the top, then "Scan Passport" / "Fit
    Passport to the frame" / "Start". Comment 9a is confirmed, and the screen is also entirely in
    English, which is R-002 / BL-071 on the same surface.
  - *«إرسال الطلب»* — a tick, «اكتملت جميع الخطوات», one explanatory line, an information box
    «بعد الإرسال لا يمكن التعديل من التطبيق.» and a single button. Comment 13/14 is confirmed: the
    screen offers exactly one action and shows no data.
- **The walk completed.** Account `0000001003` submitted successfully and now answers
  `continuation=TERMINAL`, which is correct — `submitted` is a terminal status. Two of the thirty
  seeded accounts are now spent (`0000001002` aborted, `0000001003` completed); 28 remain.

### 2.7 The comments, filed — bundles, causes, owners and conflicts

The verbatim record in §2.5 is the evidence and does not change. This section is the *reading* of it:
thirty-odd comments are not thirty jobs. They group into twelve bundles by underlying cause, and the
grouping matters because several of them are the same fix and two pairs fight each other.

Each bundle names the comments it absorbs, the cause, the tier that owns it, and — where it exists —
the prior decision or backlog row it collides with. **Nothing here is scheduled or approved; this is
filing, not a plan.**

| # | Bundle | Absorbs | Owner | Status |
|---|---|---|---|---|
| W-1 | No visual hierarchy anywhere | G3, G5, G6a, 3a, 10c | mobile, theme | buildable |
| W-2 | No shared journey frame | G1, G2, G5 | mobile, every screen | one conflict |
| W-3 | Screen-title overflow | 5b | mobile | defect, buildable |
| W-4 | Copy and titles | 4a, 4b, 4d, 5a, 8b, 10a, 10b, 11a, 11b | mobile | one policy check |
| W-5 | Input components | 6a, 7a, 12a | mobile | buildable |
| W-6 | Channel iconography | 4c, 5c | mobile | buildable |
| W-7 | Keyboard vs action button | G4 | mobile, every data-entry screen | buildable |
| W-8 | Error presentation | 2a, 2b, 2c | mobile | 2c is a defect |
| W-9 | Splash identity and timing | 1a, 1b | mobile | conflicts with D7.3 |
| W-10 | Journey-logic changes | 5d, 13/14, 15c | **journey decision, not UI** | blocked on decisions |
| W-11 | Reference number prefix | 15a | **backend** | buildable, two sites |
| W-12 | Country-list ordering | G6 | **backend / reference data** | buildable, fiddly to publish |

#### W-1 — No visual hierarchy anywhere *(the largest bundle, and the cheapest)*

Five comments, one cause. §1.1 of this report establishes it from source: `app_theme.dart` sets no
`scaffoldBackgroundColor`, no input theme, no card theme and no surface overrides, so every screen
renders in stock Material defaults. Nothing has an edge, so nothing separates from anything else —
which is precisely what G6a ("boxing every question"), G3 (titles indistinct), 10c (sections blur)
and 3a (screen reads as empty) each describe from a different screen.

**This is the S1 treatment the design research already recommended, arrived at independently.** That
research's own argument for S1 — a tinted canvas with white containers — is the mechanism that
produces the separation these comments ask for. Two facts from §1.1 and §1.2 make it the right thing
to do first: it lands on a clean slate with nothing to double-apply, and it renders identically on
the below-floor Huawei and on a mid-range device, so a judgement made on this walk transfers.

G5 (button size and position) is partly here — a button theme fixes size and shape — and partly W-2,
since *position* needs the shared frame.

#### W-2 — No shared journey frame

G1 (progress line), G2 (back as icon, swipe-back) and G5's positional half all need one wrapper every
screen sits inside. Today each screen builds its own chrome, which is why the action button drifts.

**Section grouping for G1, as requested.** Five sections over the twelve stages: **الحساب** (account
entry, channel selection) · **التحقق** (OTP) · **بياناتك** (the five data-entry stages) · **الهوية**
(document selection, scan, registry review, liveness) · **التوقيع والإرسال** (signature, submit).
Twelve small points, five large ones. Liveness is folded into identity rather than standing alone —
four screens under one marker is already the widest section, but splitting the face check out would
make a five-dot row into six for a step the customer experiences as part of proving who they are.

⚠ **Conflict — swipe-back reopens a settled transition decision.** `app_theme.dart:40-59` deliberately
pins Android to `FadeUpwardsPageTransitionsBuilder` *instead of* `PredictiveBackPageTransitionsBuilder`,
and the comment explains why: predictive back falls back to `FadeForwards`, whose slide direction is
hardcoded LTR, so it animates an Arabic app in the Latin direction. Android's system swipe-back
gesture is the predictive-back mechanism. **So "make swipe back usable" cannot simply be switched on
without re-entering the exact RTL defect that choice was made to avoid.** Either a bespoke
direction-aware transition is written (the file's own comment says any bespoke transition must pass
`Directionality.of(context)` to `SlideTransition`), or swipe-back is delivered without adopting the
predictive-back builder. This needs deciding before it is built, not during.

#### W-3 — Screen-title overflow *(a defect, not a preference)*

Comment 5b, confirmed in the device photograph: the OTP title truncates to «التحقق من وسائل ال...».
Filed separately from W-4 because it is a layout bug that will recur on any long title at a larger
text scale, and fixing the words alone would mask it rather than fix it. Note that comment 5a
*renames* this same screen, so the two must be applied together or the rename will appear to have
fixed the overflow when it has only shortened the string.

#### W-4 — Copy and titles

Four renames (4a, 5a, 10a, 11a), one addition (4b), one button-text change (4d) and three deletions
(8b, 10b, 11b).

⚠ **One policy check inside an otherwise trivial bundle.** Comment 10b deletes «بيانات السجل المدني»
on the grounds that "the user doesn't care about the source of the data and he should not know."
That is a change to what the journey discloses about provenance, not a wording preference — the
review screen currently names the Civil Registry as the source of what it is showing. Whether the
customer is entitled to know which authority supplied the data they are being asked to confirm is a
journey question. Cheap to change, but it should be answered rather than assumed.

#### W-5 / W-6 / W-7 — Components, icons, focus

W-5: marital status, education, gender and income source become dropdowns (6a); the two document
types become picture cards that are themselves the action (7a); the signature's draw-versus-upload
choice is made visibly a choice (12a). W-6: channel icons on both the selection and OTP screens (4c,
5c). W-7: the action button stops advancing between fields, and the keyboard's own next key takes
that role (G4) — the widest-reaching of the three, since it is focus handling repeated on every
data-entry screen.

#### W-8 — Error presentation

2a (do not name which list failed), 2b (emphasis, connectivity icon, pop-up) and 2c.

**2c is a defect, and §2.6 sharpens it rather than weakening it.** The reference endpoint answered
HTTP 200 in 0.259 s throughout; the failure was the handset's own connectivity, caught in the capture
as `OBTAINING_IPADDR`. So this is not a rare server fault — it is the ordinary condition of a customer
on poor connectivity, which is most of this market. The screen nonetheless left the account-number
field interactive behind a failure message, which is the half-usable state 2c objects to. Note the
launch screen already solved the sibling problem (D7.5/D8.3 stopped raw exception text reaching the
customer); this is the same class of issue one screen later.

#### W-9 — Splash identity and timing

1a is presentation — «بياناتي» set larger, extended with kashida to the logo's width, given a glow or
similar distinction — and sits naturally with the P2 splash work.

⚠ **Conflict — 1b reverses a stated design decision.** `launch_screen.dart:15-19` records D7.3
explicitly: *"there is no minimum display time: this screen fills a wait that already exists and must
never manufacture one"*, and the navigation is fire-and-forget for that reason. Comment 1b asks for
a deliberate hold so the text can be read. Both positions are defensible — brand landing versus not
taxing the customer's time — and the decision is the product owner's, but it must be recorded as a
reversal rather than slipped in. A first-run-only hold was suggested verbally as a middle path and
is **not** an approved decision.

#### W-10 — Journey-logic changes wearing UI clothing

The three comments in this bundle change behaviour, not appearance, and are filed together for that
reason — the same trap BL-086 exists to name.

- **5d** — hide «رقم الهاتف غير صحيح؟ العودة لتعديله» once any phone channel verifies. The device
  photo confirms it currently shows beside an already-verified SMS row, so the observation is right.
  But customer.md Stage 2 "Corrections" lists *"Phone number wrong → Back to 1b"* as an available
  repair, and this closes it once a code has been proven. Defensible and probably correct — a number
  that has demonstrably received a code is settled — but it contradicts a written journey rule and
  CLAUDE.md forbids settling that in passing.
- **13/14** — «إرسال الطلب» offers one action and shows no data. **This already has an owner:
  BL-087**, which records that the screen was intended to carry a read-only summary of the
  customer's entered data and was built without one because no backend read returns it. So the
  answer is to give the screen its missing content, not to delete it: it is the last checkpoint
  before an irreversible submit. BL-087 also fixes the honesty hazard — a summary sourced from the
  local draft could show values the backend never received.
- **15c** — «إنهاء» currently returns to the splash. The product owner wants it to close the app or
  reach a genuine end state. Navigation-on-completion, small but a behaviour choice.

#### W-11 — Reference number prefix *(backend)*

15a, and the product owner explicitly asked for this to be logged clearly for the backend session.
Confirmed at source: `'FRU-' || lpad(nextval('app.reference_number_seq')::text, 9, '0')` appears in
**two** places — `JdbcSubmissionRepository:52` (customer submission) and
`JdbcManualCompletionRepository:35` (operator manual completion). `V0045__app_reference_number_seq.sql`
states the format is a **placeholder**: *"No format is specified anywhere in the journey documents or
by the bank"*. **Both sites must change together**, or manually-completed profiles carry a different
prefix from customer-submitted ones — a difference an operator would eventually have to explain.
Same misattribution family as BL-081 and BL-085 (the product is not «FRU» to a customer any more than
it is the central bank).

#### W-12 — Country-list ordering *(backend / reference data)*

G6 — Sudan first in every country list. Not a UI change: order is served data (`sort_ordinal`), and
per this project's reference-document model the served document carries a `content_hash`. Changing
the order therefore means a migration **plus** re-running the publication step, which R-033 records
as a documented manual step with a silent-skip failure mode. Small change, careful delivery.
Unaffected by R-014 (that risk is the administrative-divisions dataset, a different list).

## What this session did not do

- **No application-code change in any tier**, which was the brief's binding constraint and held.
  No design build: S1, P2 and the BL-086 *implementation* are all still unstarted and belong to the
  next session. The back-office deploy shipped already-committed code and changed no source.
- **Nothing the walk surfaced was fixed.** Every comment in §2.5 is recorded and filed, none acted
  on — deliberately, since the walk was data collection.
- The three conflicts named in §2.7 (W-2 swipe-back, W-9 splash timing, W-10 phone-correction) were
  **not** settled. Each contradicts a written decision, and CLAUDE.md forbids settling those in
  passing.

**Scope note.** The brief said "commit only this report" and "this session builds nothing". Both
were widened during the session by explicit product-owner instruction, not by drift, and the walk
itself was in the brief from the start:

| Item | Disposition |
|---|---|
| §1.6 undeployed back-office bundle | **Resolved** — gated, built, deployed to staging, verified live from CloudFront |
| §1.3 BL-086's incorrect "flag does not exist" claim | **Filed** — BACKLOG.md row corrected, with the config-vs-wire split and a three-step sequencing recommendation |
| §2.5 walk comments | **Recorded verbatim and filed** into twelve bundles (§2.7); tracked forward as BL-090 to BL-093 |

**Where the walk's output is tracked.** The bundles in §2.7 are the reading, but a session report is
a record rather than a work list, so each bundle group also has a BACKLOG row pointing back here:
**BL-090** (mobile presentation, W-1 to W-9), **BL-091** (the three behaviour changes needing journey
decisions, W-10), **BL-092** (backend and reference data, W-11 and W-12), **BL-093** (the
`0000001002` terminal anomaly). Two existing rows were also updated from this run: **BL-069** (its
own "if a third device run confirms it" condition is now met) and **BL-055** (narrowed — see §2.6).

Gates: **backoffice only**, since it is the only tier whose artifact was rebuilt and shipped — lint
warnings-only, `test:coverage` 140/140 passing at 98.9% lines, output in §1.6. Backend and mobile
source were untouched, so their gates were not run; `git status` reports no modification under
`backend/` or `mobile/`. The mobile `flutter build apk --debug` was a packaging step only and
produced no source change.
