# Research — UI/UX & Arabic wording design plan (pre-pilot)

**Date:** 2026-09-07
**Author:** `@agent-researcher` (investigation only — no code was written, nothing was changed)
**Scope:** The **customer mobile app** (`mobile/`) ahead of a bank-staff pilot: presentation and wording only. Back-office wording is included only where BL-076 and the brief name a specific defect. **Out of scope:** journey logic, validation, error contracts, backend, iOS, the Uqudo SDK's own screen strings (BL-071), and the permanent package identifier (BL-079/BL-081).

**Ambiguity and the reading taken.** The brief asks for a plan for "the customer mobile app" but its Arabic strand names back-office defects. I have taken the defensible reading: **the house style, digit rule and date rule are cross-tier; the build work is mobile-first, with the three named back-office strings included as a small, cheap rider.** Readings not pursued: a full back-office redesign; a bilingual (ar/en) product; anything touching Uqudo chrome.

### What was read (local disk only)

| File | Why |
|---|---|
| `CLAUDE.md` | Arabic-first constraint, agreement hazard, E.164 rule, gate rules |
| `PROJECT_PLAN.md` | AD-001/AD-006 constraints, UX benchmark, module map |
| `docs/sessions/2026-09-07-s7-12-acceptance-walk.md` §7 | the catalogued cosmetic findings (BL-076) |
| `docs/road-to-production.md` (§2.1–2.3, §3.4, §4.4, §4.6) | sequencing and dependencies |
| `BACKLOG.md` BL-071, BL-076, BL-079, BL-081, BL-082 | exact wording of the filed rows |
| `mobile/lib/core/app.dart`, `main.dart`, `core/router/app_router.dart` | theme, locale, Directionality, routing/transitions |
| `mobile/lib/features/entry/{launch,account_entry,contact_channels,channel_verification}_screen.dart` | stages 0–2, the phone/OTP fields |
| `mobile/lib/features/dataentry/stage{3,4,5,6,7}_screen.dart`, `reference_item_picker.dart` | the data-entry screens being polished |
| `mobile/lib/features/identityscan/stage9_screen.dart`, `signature/stage11_screen.dart`, `submission/stage12_screen.dart`, `submission/confirmation_screen.dart` | the S7-12 findings' actual sites |
| `mobile/lib/core/text/{arabic_noun_agreement,arabic_digit_input_formatter}.dart` | the existing Arabic helpers |
| `mobile/pubspec.yaml`, `android/app/src/main/AndroidManifest.xml`, `res/values/styles.xml`, `res/drawable/launch_background.xml`, `res/` listing | fonts, label, splash, icons |
| `docs/components/mobile-packages.md` | the existing "four LTR islands" house rule |
| `backoffice/src/profiles/ProfileDetailPage.tsx`, `layout/AppShell.tsx` | the three named back-office strings |
| `C:\Users\DELL\fvm\versions\3.47.0\packages\flutter\lib\src\widgets\{transitions,editable_text,focus_traversal}.dart` | verified three framework behaviours against the **pinned** SDK rather than from memory |

**Method.** Grounded read of source first; framework claims verified against the pinned Flutter 3.47.0 SDK on disk; vendor docs second; secondary sources named and marked as such. Nothing was fetched from the GitHub API and no repo content came from the web.

### Provenance markers

- `[REPO path:line]` — read from this repository on local disk, this session
- `[SDK path:line]` — read from the pinned Flutter 3.47.0 SDK on local disk, this session
- `[DOC url]` — official vendor documentation
- `[SOURCE url]` — secondary source, named, quality noted
- `[INFERRED]` — my reasoning from the marked facts above
- `[JUDGEMENT]` — my design opinion; not a sourced claim
- `[UNVERIFIED]` — assumed; must be confirmed before anyone relies on it

---

## 0. Decisions taken with the product owner

Recorded as the review discussion proceeds, so the build session reads the settled
positions before the reasoning that produced them. Each entry names the topic it came from
and the section that carries the argument.

### D1 — The bank's name, the slogan, and the app's name (topic 1, 2026-09-07)

**Supplied by the PO as the formal, authoritative forms:**

| | |
|---|---|
| Bank, Arabic | **«البنك السوداني الفرنسي»** |
| Bank, English | **Sudanese French Bank** |
| Slogan | **«لؤلؤة المصارف»** |
| App name | **«الفرنسي بياناتي»** (Latin, internal only: "Alfaransi Bayanati") |

**Decisions:**

1. **BL-081's proposed store subtitle «بنك السودان الفرنسي» is wrong and is corrected** to
   **«بياناتي — البنك السوداني الفرنسي»**. «بنك السودان» names the central bank, so the
   original phrasing misattributed the app to the regulator — the opposite of what a bank
   identity flow needs. → `BACKLOG.md` BL-081 to be amended. See §2.1.
2. **Launcher label is «بياناتي» alone**; the full lockup «الفرنسي بياناتي» carries the
   splash, the brand line and the store listing. Truncation width on the reference device's
   EMUI launcher is still `[UNVERIFIED]` — cheap to confirm during build.
3. **`android:label` becomes a string resource**, replacing the literal `"mobile"`.
4. **The Latin transliteration appears nowhere in the UI** — keystore alias, Play developer
   notes and internal conversation only.
5. **The slogan «لؤلؤة المصارف» appears on the splash screen only**, as the brand line
   beneath the wordmark. Not in any AppBar, not on the confirmation screen, not repeated
   anywhere in the journey. Reasoning in §2.5.
6. **Mobile branding is NOT gated on the backend package rename.**
   `docs/road-to-production.md` §3.4's "depends on 2.1" line binds the backend tier only;
   Android resources live under `res/` and survive an `applicationId` change. → that
   dependency line to be amended. See §2.1.

**Plan-file amendments this decision creates:** `BACKLOG.md` BL-081 (bank name);
`docs/road-to-production.md` §3.4 (dependency scope).

### D2 — The logo asset and the brand colour (topic 2, 2026-09-07)

**Brand master supplied by the PO** and stored at **`docs/brand/sfb-logo-master-2048.jpg`**.

| | |
|---|---|
| **Brand blue** | **`#105097`** — measured from the master (36.5 % of its pixels) |
| White on that blue | **8.03 : 1** — WCAG AAA for body text `[computed this session]` |
| Master format | 2048 × 2048 **JPEG** — a raster, not a vector, and sufficient |
| Emblem crop | x 684–1357, y 192–963 (673 × 771) |

**Decisions:**

1. **`#105097` is the single brand blue**, used for the splash background, the adaptive-icon background layer and the theme's pinned `primary`. Shades for pressed states and containers are derived from the Material 3 tonal palette, not hand-picked.
2. **This supersedes the `#2F5AAE` / `#1A4D8F` pair approved earlier in topic 2.** Those came from sampling the bank's *website*; the supplied master is the authoritative artwork and disagrees with it. Superseding is a correction to a factual input, not a change of design opinion — see §2.2 for the full evidence trail, kept because it documents that the bank's public assets are internally inconsistent.
3. **The splash background is exactly `#105097`** so the master's own blue field merges invisibly into it. This is why no separate darker "brand surface" shade is wanted.
4. **The launcher icon is the emblem only, no text** — the English wordmark scales to under 2 dp at a 48 dp icon.
5. **The bilingual lockup is never altered.** D1's no-Latin rule governs the app name, not the bank's registered mark.
6. **A vector master is still worth obtaining** from the bank for print and large-format use, but **does not block the pilot** — every app rendition is a downsample from 2048 px.

**Consequence for the build list:** items 1, 3, 4 and 17 are **unblocked**. The brand-colour dependency is discharged.

### D3 — Theme, font and Arabic text metrics (topic 3, 2026-09-07)

**The PO confirms the bank mandates no Arabic typeface.** §4.4's flip condition is therefore closed and the recommendation stands.

| | |
|---|---|
| Font | **IBM Plex Sans Arabic**, Regular 400 + SemiBold 600 only |
| Subsetting | by **Unicode range**, never by scanned strings — see below |
| `primary` | **`#105097`, pinned explicitly** after seeding |
| `letterSpacing` | **0** across the whole text theme |
| `height` | **1.6** for body |
| Body size | **16 sp** minimum; nothing customer-facing below 14 sp |
| Weights | nothing below 400; no italic |
| Themes | **light only** for the pilot |

**Decisions:**

1. **IBM Plex Sans Arabic is adopted**, two weights, bundled. Its matched Latin is the deciding factor: nearly every screen sets Arabic labels beside Latin digits, `FRU-` reference numbers and scanned names, and an unmatched pair produces a visible family jump mid-line. §3.3's rejection of Noto Kufi for body text stands.
2. **`ColorScheme.fromSeed` alone is not sufficient** — it maps the seed into a tonal palette and returns a lighter, less saturated `primary`, so seeding with `#105097` does **not** produce `#105097`. Seed from it for a coherent palette, then **pin `primary: #105097` and `onPrimary: white` explicitly.** Without this, "we set the brand colour" silently becomes "we set something adjacent to it". `[INFERRED from Material 3's tonal-palette model — confirm the returned value when the theme is written]`
3. **Subset by Unicode range, never by the characters the app's own strings use.** Customer names arrive from the Uqudo scan and the Civil Registry, not from our source, and can contain characters no literal in the repo contains. A string-scanned subset would render a customer's own name as missing-glyph boxes. Set `fontFamilyFallback` to the device font so anything outside the range degrades gracefully rather than to tofu.
4. **Light theme only.** Also closes the latent mismatch in "Noticed in passing" item 5: `values-night/styles.xml` gives a dark launch window behind what will now be an explicitly light app.

**Still to measure, not assumed:** the real subsetted font size (§4.4's 150–250 KB is an estimate), and whether `letterSpacing: 0` / `height: 1.6` look right for this face on the reference device.

### D4 — Digits, dates and alignment (topic 4, 2026-09-07)

**Both confirmed by the PO. §4.3's recommendation is adopted in full, and its one `[UNVERIFIED]` premise — that Sudanese retail customers expect Latin digits — is now settled by the PO rather than assumed.**

1. **Latin digits `0123456789` everywhere**, display and storage, no exceptions. Arabic-Indic input continues to be accepted and silently transliterated by the existing `ArabicDigitInputFormatter`. The deciding argument is Stage 9: its sole purpose is comparing the extracted national number against a physical document printed in Latin digits, and rendering Arabic-Indic there would impose a ten-digit mental transliteration at the exact moment the customer is being asked to verify.
2. **Dates `DD/MM/YYYY`, times `HH:mm` 24-hour, Latin digits, `/` separators. Never ISO on any customer or operator screen** — mobile and back office alike.
3. **Format at the presentation boundary only.** Wire types and stored values are unchanged: the backend continues to render `LocalDate.toString()`. This is the project's existing asymmetric rule — canonicalise what you send, present differently.
4. **The alignment rule (§4.3, three cases) is adopted**, and the `LtrValue` widget is the mechanism: one widget in `mobile/lib/core/text/`, used at all seven islands, rather than seven ad-hoc property lists. Treated as a defect fix, not a preference — today the five numeric inputs set direction with no alignment (label at the right edge, its own digits at the left), and `confirmation_screen.dart` sets neither despite `FRU-` opening with a Latin run.

**Not reopened:** if the digit rule ever flips it flips for **display only**, never storage, and it is not a partial change — every `LtrValue` site and the date helper would need a shaping layer and the stored/displayed split becomes real and testable. Recorded so a future session does not do it by halves.

### D5 — Focus flow (PO item 3; topic 5, 2026-09-07)

**The item is approved and the diagnosis is restated: this is not an RTL focus-*order* defect.** Traversal is already RTL-correct — `Directionality` is `rtl` app-wide and Flutter's default `ReadingOrderTraversalPolicy` orders by it — and is simply never invoked, because the app holds one `FocusNode` and sets `textInputAction` on nothing. §2.3 carries the evidence.

**Decisions:**

1. **`textInputAction` across every data-entry run** — `next` on all but the last mounted field, `done` on the last. This is the bulk of the value and needs no explicit `FocusNode`.
2. **Do NOT install `OrderedTraversalPolicy`.** Stage 3 mounts fields conditionally (spouse name, children count), so a hand-maintained order list would break silently on every future branch. The default policy sees only mounted nodes and needs no maintenance.
3. **The OTP screen never uses `next`.** Three independent codes, one per channel, each with its own verify button — advancing between them invites entering the WhatsApp code in the SMS row. Each gets `done`.
4. **Keyboard dismissed at the sixth OTP digit**, to reveal the button that enables at exactly six and is otherwise covered on a 5-inch screen. **No auto-submit — APPROVED by the PO:** a mid-paste or mid-correction transient would spend one of a limited number of wrong-code attempts, and that cost far exceeds one extra tap.
5. **No autofocus on data-entry screens — CONFIRMED.** The keyboard would cover half the form, the section headings and the offline banner before the customer has oriented. The reference picker's existing autofocus stays as the correct exception.
6. **Per-field error text replaces the below-the-fold message — APPROVED by the PO.** Errors attach to the offending field, focus moves to it and it is scrolled into view; the bottom message is kept only as a summary. Account entry's existing `InputDecoration.errorText` becomes the house pattern. **This is the one change in the item that alters what the customer sees on failure** — no validation rule or error contract changes, the same errors fire at the same times, so it stays inside the presentation-only scope.
7. **`textAlign: TextAlign.right` on the five LTR input islands** — carried from D4; the label currently sits at the right edge while its own digits sit at the left.

**Not disturbed:** the primary button's placement outside the scrolling area, which makes it ride above the keyboard rather than under it. That is already right (§1.2).

### D6 — Screen transitions (PO item 4; topic 6, 2026-09-07)

**Two PO decisions reframed this item, and a source finding resolved it.**

**PO decisions:** (a) **no performance measurement on the reference device**; (b) **the 2019 Huawei is no longer treated as the binding floor** — it may reasonably lose transition elegance provided it still works. Asked for the lowest-effort next-best option, the pinned SDK was traced instead of the device.

**The finding (verified against Flutter 3.47.0 on disk, not from memory):**

| Fact | Evidence |
|---|---|
| No `pageTransitionsTheme` is set, so the Android default applies | `[REPO mobile/lib/core/app.dart:28]` |
| The Android default is `PredictiveBackPageTransitionsBuilder` | `[SDK material/page_transitions_theme.dart:766]` |
| Predictive back needs Android 13+ **and** a manifest opt-in the app lacks, so it falls back | `[SDK material/predictive_back_page_transitions_builder.dart:95-97]` |
| The fallback is `FadeForwardsPageTransitionsBuilder`: 450 ms, horizontal ±0.25 slide, **LTR semantics hardcoded** — its own comment reads *"The previous page slides from right to left as the current page appears"* | `[SDK page_transitions_theme.dart:470, 490-499]` |
| **No built-in Material page transition mirrors for RTL** — `grep -c textDirection` over both transition files returns **0** | `[SDK page_transitions_theme.dart; widgets/page_transitions_builder.dart]` |

**So the Arabic app animates forward navigation in the Latin direction today, on every screen and every device.** Not a stutter — a wrongness, invisible because nothing tests for it.

**Decisions:**

1. **Set `pageTransitionsTheme` → `FadeUpwardsPageTransitionsBuilder` for `TargetPlatform.android`.** One line in `app.dart`, no route touched. Its tween is `Offset(0.0, 0.25) → Offset.zero` `[SDK widgets/page_transitions_builder.dart:103-108]` — **x is zero**, so the motion is purely vertical and direction-neutral *by construction*, immune to the framework never mirroring.
2. **Item 4 is no longer deferred.** It folds into build item 1 (the theme), same file, same change. Build item 13 — measure, then maybe act — is **deleted**, along with the performance-overlay task and the bespoke shared-axis build.
3. **Kept, but reframed as general hygiene rather than old-device accommodation:** no shared-element/Hero transitions on images, and cap image decode with `cacheWidth`. Animating a 232 KB signature image mid-decode is wrong on any phone, and uncapped full-resolution decode is a memory-safety issue on modern hardware too.
4. **Softened:** the blur ban. Nothing in the design calls for blur, so it is simply outside the vocabulary rather than a prohibition.
5. **Kept:** honour the OS "remove animations" setting (`MediaQuery.disableAnimations` → `Duration.zero`).

**Rejected alternative, recorded:** `ZoomPageTransitionsBuilder` — also direction-neutral, but the heaviest built-in option and it uses snapshotting.

**A note on the reversal.** The original §2.4 verdict ranked this item last and gated it on measurement, on the argument that it was the only item that could regress the pilot. That argument was sound given what was then known and is now void: the item is not adding motion to a working default, it is replacing a defective one, and the replacement is cheaper than what it replaces. The PO's refusal to measure is what forced the source trace that found it.

### D7 — The landing / splash screen (PO item 5; topic 7, 2026-09-07)

**Wording supplied by the PO: «بياناتي - تحديث بيانات حسابك».** Adopted with two adjustments (§2.5): **em-dash** for the separator — «بياناتي — تحديث بيانات حسابك» — and on the splash the **short form «تحديث بيانات حسابك»** only, since the app name already sits on the line above. The full string is the **tagline**, used in the store listing and wherever it stands without the logo.

**The final splash stack:** logo lockup → «الفرنسي بياناتي» → «لؤلؤة المصارف» → «تحديث بيانات حسابك» → progress indicator. Flat `#105097`, all text white.

**Decisions:**

1. **Three layers, all built — CONFIRMED.** (a) the Android window launch theme, (b) a separate `values-v31/styles.xml` for Android 12+, which ignores the custom `windowBackground` and uses the OS SplashScreen API `[DOC docs.flutter.dev/platform-integration/android/splash-screen]` — with `minSdk 24` and `targetSdk 36` **both paths are live**, and testing only one is how this gets missed; (c) the Flutter brand screen replacing the bare spinner.
2. **Continuous blue.** The native launch background and the Flutter splash are both `#105097`, so the customer sees one unbroken blue screen from tap to first content with no white flash and no visible native→Flutter handoff. `values-night/styles.xml` is made **identical to `values/`**, closing the light-theme-behind-dark-window mismatch (§D3.4, "Noticed in passing" 5).
3. **No minimum display time.** The screen is Stage 0 and already awaits a real backend launch check `[REPO launch_screen.dart:18-39]`; there is a genuine wait to fill and no reason to manufacture one. When the decision resolves, navigate.
4. **No `flutter_native_splash`.** Three XML files by hand versus a package that triggers an AD-006 evaluation, CLAUDE.md's per-package iOS-support check, and generated files the project's rules then forbid hand-editing.
5. **The error path is fixed here, not deferred.** `'تعذر بدء التطبيق: $error'` `[REPO launch_screen.dart:34]` renders an English Dart exception to a Sudanese retail customer, and a network exception's text can carry the request URI and response content — a UX defect and a hygiene defect with one fix. Replaced by a fixed Arabic sentence stating the next action; detail logged, never displayed. The three sibling sites (build item 5) are fixed in the same pass.

**Open, and now known to be unanswerable from the bank:** the PO confirms **the bank has no approved wording** for *why* customers must re-verify. Consequences: (a) do **not** invent a justification or an authority claim in customer copy — the splash states what the app does, not why the bank requires it; (b) the "what happens to your data" line that §3.5 recommends and **BL-082's Play Data-safety declaration requires** still has to be authored, and it needs PO and bank sign-off rather than being written in passing. Carried as open question 13.

### D8 — Arabic copy: the approved rewrite specification (topic 8, 2026-09-07)

**The blocking dependency is discharged. The PO is the native Arabic reviewer and reviewed this copy in session on 2026-09-07.** §5's "hard dependency" on an external reviewer is closed; it becomes a review step the PO owns.

#### D8.1 — What a full scan of the mobile source actually found

Measured this session over `mobile/lib/**/*.dart`: **393 Arabic string literals across 27 files.**

| House rule | Violations found | Verdict |
|---|---|---|
| R1 labels are nouns | **1** (`stage4_screen.dart:322`) | **Already satisfied.** Every other `labelText` is a noun — «رقم الهاتف», «اسم الزوجة», «المدينة», «رقم الحساب», «رمز التحقق». The rule was already the app's practice. |
| R2 errors take a verb + next action | 0 | **Already satisfied.** The 41 imperative strings a naive scan flags are all inside `_validationError` getters — they are *error messages*, where an imperative is correct. Flagging them would have been a false positive. |
| R4 no computer words | **13** | The largest real defect. §D8.2 |
| R5 no interpolated exception | **10** | Larger than §4.1's table said (4). §D8.3 |
| R6 tatweel / `--` | **0** | Clean. Both were back-office-only defects. |
| Arabic-Indic digits in literals | **0** | Clean. |
| ISO date format literals | **0** | Clean — Stage 9's ISO date is a *value* from the backend, not a format string. |
| R7 Latin parenthetical | **1** | «الرسائل النصية (SMS)». The other three a regex flags are false positives — it matched Dart code inside `${…}`, not Arabic text. |

**The correction this forces to §4.1:** the mobile copy is in materially better shape than the section implied. The wording work is **two systematic patterns and three one-offs**, not a rewrite.

#### D8.2 — «الخادم» in customer copy: 13 sites — APPROVED

**Canonical replacement, used wherever the current string is the generic one:**

> **`تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.`**

It drops «الخادم» (R4) *and* gains a next action (R2) — «يرجى المحاولة مرة أخرى» told the customer nothing they did not already know.

Sites taking the canonical form: `account_entry_screen.dart:149`, `channel_verification_screen.dart:185,242`, `contact_channels_screen.dart:196`, `stage9_screen.dart:106,154,231`, `stage10_screen.dart:362` (short form «تعذر الاتصال»), `stage11_screen.dart:209`, `stage12_screen.dart:88`.

Three sites keep a context clause that carries real information:

| Site | Approved string |
|---|---|
| `offline_banner.dart:17` | `لا يوجد اتصال بالإنترنت. يتم عرض بياناتك المحفوظة، وبعض الإجراءات غير متاحة حتى يعود الاتصال.` |
| `final_stages_gate_screen.dart:86` | `تعذر الاتصال. يحتاج استكمال الطلب إلى اتصال بالإنترنت.` |
| `stage12_screen.dart:139` | `تعذر الاتصال. لم يتم الإرسال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.` |

#### D8.3 — Interpolated exceptions: 10 sites — APPROVED

Three of these interpolate a `PlatformException` message, i.e. **English text from Android shown to a Sudanese retail customer**. All ten are also a hygiene concern: a network exception's `toString()` can carry the request URI and response content. Detail is logged, never rendered.

| Site | Approved string |
|---|---|
| `launch_screen.dart:34` | `تعذر بدء التطبيق. تأكد من اتصالك بالإنترنت ثم أعد فتح التطبيق.` |
| `account_entry_screen.dart:120,185` | `تعذر تحميل قائمة الفروع. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.` |
| `stage3_screen.dart:498` | `تعذر تحميل قائمة المستوى التعليمي. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.` |
| `stage4_screen.dart:302` | `تعذر تحميل قائمة مصادر الدخل. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.` |
| `salary_certificate_field.dart:47` | `تعذر فتح الكاميرا. تأكد من السماح للتطبيق باستخدام الكاميرا من إعدادات الهاتف.` |
| `salary_certificate_field.dart:71` | `تعذر فتح الملفات. أعد المحاولة.` |
| `stage11_screen.dart:109` | `تعذر فتح الكاميرا أو المعرض. تأكد من السماح للتطبيق بالوصول إليهما من إعدادات الهاتف.` |
| `occupation_demo_screen.dart:21,24` | **Not translated — delete the route.** See below. |

**Design note on the camera strings:** the underlying failure is almost always a denied permission, so naming the remedy is more useful than any exception text. `[JUDGEMENT — approved by the PO]`

**`/reference-demo` is to be deleted, not translated.** Two of the ten sites live in a leftover demo screen that is still a mounted route `[REPO app_router.dart:127-130]`. Removing the route removes the strings, one route and one screen file. This closes "Noticed in passing" item 4.

#### D8.4 — The three one-offs — APPROVED

| Site | Before | After |
|---|---|---|
| `stage4_screen.dart:322` | `حدد مصدر الدخل` (imperative as a label — the only R1 violation in the app) | **`مصادر الدخل`** — noun, and plural because the field is multi-select |
| `stage3_screen.dart:416` | `عدد الأطفال: ${ArabicNounAgreement.children.phrase(n)}` | **`عدد الأطفال: 4`** — the bare number |
| `contact_channels_screen.dart:298` | `الرسائل النصية (SMS)` | **`الرسائل النصية`** — the PO's reasoning: the term is universally understood, so the gloss adds nothing |

**The Stage 3 change pays twice.** Deleting the agreeing phrase removes the agreement hazard from the screen (§4.2 Rule 1) *and* removes the reason the screen calls `setState` on every keystroke of five controllers `[REPO stage3_screen.dart:67-82]` — a live UI-thread cost on a 2 GB device, and per §2.4 more likely to be *felt* than any transition. One deletion, two defects.

#### D8.5 — Back office: three strings — APPROVED

| Site | Before | After |
|---|---|---|
| `ProfileDetailPage.tsx:189-194` | `بانتظار السجل المدني (من قيد التنفيذ)` | **`تغيّرت الحالة من «قيد التنفيذ» إلى «بانتظار السجل المدني»`**; first row, where `fromStatus` is null: **`بدأت الحالة: قيد التنفيذ`** |
| `AppShell.tsx:39` | `الواجهة الخلفية -- تحديث بيانات العملاء` | **`تحديث بيانات العملاء — واجهة الموظفين`** — real em-dash; **«واجهة الموظفين» confirmed by the PO** as what SFB staff call it, replacing the calque «الواجهة الخلفية» |
| `ProfileDetailPage.tsx:66-68` | `dayjs(value).format('YYYY-MM-DD HH:mm')` | **`DD/MM/YYYY HH:mm`** (D4). Keep the existing `<bdi>` wrapper `[REPO ibid.:62-64]` — it is the right primitive |

The status-history rewrite reads as a sentence and is **grammatically safe by construction**: «تغيّرت» agrees with the fixed «الحالة», never with an interpolated value, and both insertions are noun phrases. No arrow glyph — an arrow in RTL is its own trap.

#### D8.6 — Structural decisions

1. **No ARB / `gen_l10n` for the pilot — APPROVED** (§4.1's flip conditions stand: a second locale, or copy changes without a rebuild).
2. **No string extraction into per-feature const files for the pilot — APPROVED.** The PO reviews the copy in place. Build item 18 stays deferred; its stated cost (breaking ~106 assertions) is not paid for a benefit the PO does not need.
3. **The copy-lint test (§4.2 Rule 7) is still built.** It is what stops the above from decaying, and D8.1 proves its value: every rule it enforces corresponds to a defect actually found, and the four it would catch in mobile are currently at zero — so it locks in a clean state rather than chasing a dirty one.
4. **Gate cost, expected not surprising:** ~23 string changes against **106 `find.text('<Arabic literal>')` assertions across 16 test files** `[REPO mobile/test]`. Budget for the test churn.

### D9 — The remaining additions (topic 9, 2026-09-07)

#### D9.1 — WhatsApp at Stage 1b (A5 / R-042) — the row stays, but disabled for the pilot

**The PO's decision: do not hide the row; default it to deselected; and add a note near WhatsApp naming the number the code will arrive from.** The note is a good trust signal — an unknown WhatsApp sender is otherwise indistinguishable from spam.

**A conflict was raised and resolved.** `_whatsappSelected = true` today `[REPO contact_channels_screen.dart:29]`, and the release is **SMS-only** `[REPO BACKLOG.md BL-080 — PO decision 2026-09-07]`, so WhatsApp cannot deliver during the pilot. Deselecting by default fixes the *default* path but not the path of anyone who selects it — and a note reading *"your code will arrive from our number"* would turn a silent dead end into an **explicit false promise**, which is worse.

**RESOLVED — the row is visible but DISABLED for the pilot, carrying this string:**

> **«الرسائل عبر واتساب غير متاحة حالياً. اختر الرسائل النصية لاستلام رمز التحقق.»**

This satisfies every constraint at once: the channel is not hidden, so customers see it is coming; no one can enter a dead end; there is no false promise; and **no placeholder number ships in front of a customer** — a "not yet available" line needs no number, so nothing renders as `xxxx`.

**The PO's live-version note is built at the same time, behind the same flag**, ready for when WhatsApp goes live:

> **«سيصلك رمز التحقق على واتساب من رقم البنك: ‹الرقم›. احتفظ بالرقم لديك لمراسلات البنك لاحقًا.»**

with the number as a placeholder constant to be filled when the bank's WhatsApp Business number is known. Both strings exist; which one renders is one flag. `[JUDGEMENT — approved by the PO]`

Note «سيصلك» and «احتفظ» are gender-safe in writing, consistent with §4.1 R2.

#### D9.2 — Journey progress (A6) — CONFIRMED

Four sections, **labelled by section and never by stage number**: stage counts vary with the optional email channel and with blocked paths, and the backend owns stage truth — a "step 7 of 12" that sometimes reads 13 is worse than none.

> **«بياناتك» · «عنوانك» · «إثبات هويتك» · «الإرسال»**

Rendered as a thin `LinearProgressIndicator` plus the section name beneath the AppBar. **This is the largest-surface item in the plan** — it touches every screen's `Scaffold` — so it is the first thing to cut if the schedule tightens, not for lack of value but because its value is spread thinly across many files.

#### D9.3 — Stage 12 "check your answers" (build item 15) — APPROVED, **data only, no images**

The GOV.UK listing pattern `[SOURCE designnotes.blog.gov.uk]`, **read-only, without edit links** — by Stage 12 the earlier stages are past their submission points, so an edit link would promise what the journey cannot deliver (§3.2).

**The PO's constraint — no photos or document images redisplayed — is adopted, and it is the stronger design for two independent reasons:**

1. **Privacy.** Redisplaying the identity document and portrait would put the customer's identity documents on screen in whatever room, queue or branch they are standing in, at the moment they are least attentive. A shoulder-surfing exposure with no compensating benefit.
2. **Performance.** It avoids decoding two large images — Stage 11's uploaded signature measured **231,967 bytes** at S7-12 — on the most important screen in the journey, on a 2 GB device (§2.4).

**Artifacts are acknowledged textually instead of shown:** «تم مسح وثيقة الهوية», «تم إرفاق التوقيع». The customer confirms the step happened without the image being redisplayed.

#### D9.4 — Applied without further discussion

| Item | Why it needs no decision |
|---|---|
| **Text-scale pass at 1.0× and 1.3×** | No `textScaler` handling exists; the fixed 140 px label row `[REPO stage9_screen.dart:464]` and the two-button nav rows will overflow. Older users running larger fonts are explicitly in the audience. Finding it before the pilot costs an hour. |
| **`cacheWidth` on every `Image.memory`** | Full-resolution camera decode is tens of MB of ARGB on a 2 GB device. One argument each; a house rule, not an optimisation (§2.4, D6.3). |
| **Signature preview aspect ratio** | An uploaded portrait photo letterboxes into a narrow strip inside a fixed 180 px landscape band `[REPO stage11_screen.dart:271-297]` — BL-076's finding. |
| **`AutofillHints.oneTimeCode` — SKIPPED** | Requires the SMS sender format to match, which is not settled. Decoration that may never fire; revisit when the SMS provider is fixed (couple to BL-080). |

### D10 — The "what happens to your data" notice (topic 10, 2026-09-07)

**The PO confirms the bank has no approved wording and asked for a best-practice recommendation.** Drafted below and adopted for the pilot. Also resolves open question 11: **pilot testers WILL be told up front** that the Uqudo scan and liveness screens are English with a different typeface (D10.4).

#### D10.1 — Shape: a layered, just-in-time notice, not a privacy wall

**Best practice is a short line at the point sensitive data is captured**, not a paragraph at Stage 0 that the customer taps past before knowing what the app does — the ICO/GOV.UK position on collection notices, and the pattern §3.5's national digital-ID apps follow (the disclosure sits immediately before identity capture). `[SOURCE — the layered/just-in-time notice pattern; see §3.2, §3.5]`

For a mixed-literacy audience this matters twice: a wall of text at the start is dismissed; one sentence as the camera is about to open is read. **Two placements, both short.**

#### D10.2 — Placement 1: account entry (Stage 1a), one line

> **«تُستخدم بياناتك لتحديث سجلك لدى البنك السوداني الفرنسي، ويراجعها موظفو البنك المختصون.»**

Sets the frame: this goes to your bank, and a person there will look at it. The second clause is deliberate — customers assume automation, and a human reviewer is both **true** (the back office's approve/reject is the system's only write action) and reassuring.

#### D10.3 — Placement 2: immediately before identity capture (Stage 7), three sentences

> **«لإثبات هويتك سيطلب التطبيق تصوير وثيقة هويتك وتصوير وجهك.**
> **تُفحص الصور عبر مزوّد خدمة متخصص، ويُتحقق من رقمك الوطني لدى السجل المدني.**
> **تُحفظ بياناتك لدى البنك السوداني الفرنسي ويراجعها موظفو البنك المختصون.»**

What is captured · who it passes through and why · where it ends up. Each sentence ~10 words. Gender-safe throughout.

#### D10.4 — The governing rule: state facts, never promise rights

**Deliberately absent, and to stay absent:** any retention period, "your data will never be shared", "you can delete it at any time", or "used only for this purpose". Each reads well and is a **commitment the bank has not made** — either untrue, or true only until someone decides otherwise. Every clause in D10.2–D10.3 is something the system verifiably does.

**The vendor is not named.** «مزوّد خدمة متخصص» rather than "Uqudo": the name means nothing to a Sudanese retail customer, and naming it makes a copy change (and a re-review) a consequence of any vendor change. The third-party relationship is still disclosed in the Play declaration, which is where it belongs. `[JUDGEMENT]`

#### D10.5 — What is left for the bank: exactly one question

BL-082's Play **Data safety** form can be filled from what the app demonstrably does: personal details, address, financial information, photos, a government identity document and a face image; purpose account management and identity verification; shared with a third-party processor; encrypted in transit.

**The single field needing a bank answer: whether customers may request deletion of their data, and by what route.** Play asks it directly and it cannot be inferred, and it is the only part of the notice requiring a channel (a branch, a phone line) that only the bank can commit to. **Taken to the bank as part of the store-listing work — it blocks nothing in the build list.**

---

---

## 1. What's there now (grounded read of the current app)

### 1.1 Routing and transitions

`goRouterProvider` declares 18 routes, every one a plain `builder:` with **no `pageBuilder` and no `CustomTransitionPage` anywhere** `[REPO mobile/lib/core/router/app_router.dart:27-133]`. go_router therefore wraps each in the platform default page, so screen-to-screen motion is **whatever `ThemeData`'s `pageTransitionsTheme` resolves to for Android** — and no `pageTransitionsTheme` is set `[REPO mobile/lib/core/app.dart:28]`. **There is no bespoke transition code in the app at all.** `[INFERRED]` The PO's item 4 is therefore an addition, not a correction.

Navigation is `context.go(...)` (replace) throughout, not `push` — e.g. `[REPO mobile/lib/features/dataentry/stage3_screen.dart:229]`. Whatever transition is chosen must read correctly for a *replace*, not a stack push. `[INFERRED]`

### 1.2 Focus handling on the data-entry screens

This is the significant finding of the read.

- **The whole app contains exactly one `FocusNode`**, on the reference-picker's search field `[REPO mobile/lib/features/dataentry/reference_item_picker.dart:68,76,85,109]`. It autofocuses on a post-frame callback — correct for a screen whose only purpose is typing a query.
- **`textInputAction` appears nowhere in `mobile/lib`.** Neither does `onSubmitted`, `onEditingComplete`, `FocusScope`, `FocusTraversalGroup` or any traversal policy. A repository-wide search for all of these returns only the four picker lines above. `[REPO mobile/lib — grep over `textInputAction|FocusNode|FocusScope|autofocus|onSubmitted|onEditingComplete|FocusTraversal|requestFocus|unfocus`]`
- Consequence: every `TextField` in the journey presents the IME's default single-line action (a "done" tick) that **dismisses the keyboard**. Stage 5 has five consecutive text fields — المدينة، المنطقة، الشارع، المربع، رقم المنزل `[REPO mobile/lib/features/dataentry/stage5_screen.dart:240-263]` — so the customer must dismiss the keyboard and tap each field individually, five times. Stage 6 has the same shape `[REPO .../stage6_screen.dart:247-262]`. `[INFERRED]`
- **The app does not have an RTL focus-*order* bug.** `FruApp` wraps the entire widget tree in `Directionality(textDirection: TextDirection.rtl)` at the `MaterialApp.router` `builder` level `[REPO mobile/lib/core/app.dart:26-27]`, and Flutter's default traversal policy is `ReadingOrderTraversalPolicy()` `[SDK focus_traversal.dart:63, 458, 2049]`, which orders by the ambient `Directionality`. Traversal is already RTL-correct; it is simply **never invoked**, because nothing requests it. `[INFERRED from the two facts]` This matters: the fix is *not* to install a custom policy.
- Validation errors are rendered as a single message at the **bottom of the scroll view** `[REPO .../stage3_screen.dart:457-463; .../stage5_screen.dart:264-270]`, with no focus move and no scroll. On a 5-inch screen a failure on the first field puts the explanation below the fold. The one screen that does it properly is account entry, which uses `InputDecoration.errorText` `[REPO mobile/lib/features/entry/account_entry_screen.dart:138-141]`.
- `android:windowSoftInputMode="adjustResize"` is set `[REPO mobile/android/app/src/main/AndroidManifest.xml:19]`, and every data-entry screen puts its primary button in a fixed `Padding` **outside** the `Expanded(SingleChildScrollView)` `[REPO .../stage3_screen.dart:302-481]`, so the button rides above the keyboard rather than under it. **This is right and should not be disturbed.** `[INFERRED]`

### 1.3 Theme and typography

```
theme: ThemeData(useMaterial3: true)
```
`[REPO mobile/lib/core/app.dart:28]` — no `colorSchemeSeed`, no `fontFamily`, no `textTheme`, no `pageTransitionsTheme`, no dark theme. The app is stock Material 3 baseline purple.

`mobile/pubspec.yaml`'s `fonts:` block is **entirely commented out** `[REPO mobile/pubspec.yaml:123-141]` and there is no `assets/` directory declared `[REPO mobile/pubspec.yaml:112-121]`. **No font is bundled**, so Arabic renders in whatever face the device ships — on a 2019 Huawei that is EMUI's own Arabic font, which is not what the developer's machine renders. `[INFERRED]`

Localisation is the three `Global*Localizations` delegates only, `locale: Locale('ar')`, `supportedLocales: [Locale('ar')]` `[REPO mobile/lib/core/app.dart:19-25]`. **There is no `AppLocalizations`, no `l10n/` directory and no `.arb` file anywhere in `mobile/`** `[REPO — glob over mobile/lib/**/*.dart returns no l10n files]`. Every customer-facing Arabic string is a literal inside a widget. The FIB reference *does* carry `core/config/l10n/app_ar.arb` `[REPO PROJECT_PLAN.md:123]`; we do not.

The cost of that, quantified: **106 `find.text('<Arabic literal>')` assertions across 16 mobile test files** `[REPO mobile/test — grep count]`. Any wording change breaks tests in proportion.

### 1.4 Existing Arabic infrastructure (which is better than expected)

- `ArabicNounAgreement` implements the 1 / 2 / 3–10 / 11+ agreement rule with three named constants and is used at three call sites `[REPO mobile/lib/core/text/arabic_noun_agreement.dart:11-61; scan_blocked_view.dart:108,111; channel_verification_screen.dart:269; stage3_screen.dart:416]`.
- `ArabicDigitInputFormatter` transliterates U+0660–0669 and U+06F0–06F9 to ASCII before `FilteringTextInputFormatter.digitsOnly` `[REPO mobile/lib/core/text/arabic_digit_input_formatter.dart:12-43]`.
- The gendering discipline is real: Stage 3 refuses to render any gendered label until sex is chosen, with a comment explaining why `[REPO .../stage3_screen.dart:338-346]`.
- Arabic punctuation is used correctly in places: `، ` as list separator and `و` prefixed without a following space `[REPO .../contact_channels_screen.dart:222,224]`, `؟` in «هل لديك أطفال؟» `[REPO .../stage3_screen.dart:381]`.
- `docs/components/mobile-packages.md` already codifies the rule as **"RTL — the four LTR islands"**: account number, phone, OTP, monthly expenses `[REPO docs/components/mobile-packages.md:120-125]`.

**Correction to that card:** there are now **five** input islands — Stage 3's children-count field was added with the same treatment `[REPO .../stage3_screen.dart:399-406]` — plus at least two **display-only** islands the card does not cover: Stage 9's national number `[REPO .../stage9_screen.dart:377-384]` and the confirmation reference number `[REPO .../confirmation_screen.dart:131-135]`. The reference number is the one place the rule is **not** applied: `SelectableText(widget.args.referenceNumber)` carries no `textDirection`, and `FRU-000000001` starts with a Latin run. Whether that visibly misorders under an RTL paragraph is `[UNVERIFIED — needs a device check]`.

### 1.5 The S7-12 defects, located exactly

| BL-076 finding | Exact site |
|---|---|
| ISO date on mobile Stage 9 | `_field('تاريخ الميلاد', display.dateOfBirth)` `[REPO .../stage9_screen.dart:403]`, where `dateOfBirth` is documented as "ISO `uuuu-MM-dd` as the backend renders `LocalDate.toString()`" `[REPO mobile/lib/core/identityscan/identity_scan_models.dart:215-216]` |
| ISO date in back office | `dayjs(value).format('YYYY-MM-DD HH:mm')` `[REPO backoffice/src/profiles/ProfileDetailPage.tsx:66-68]` |
| Fixed 140 px label / Latin field misaligned | `SizedBox(width: 140, child: Text(label))` + `Expanded(child: Text(value))`, no `textAlign`, no `textDirection` `[REPO .../stage9_screen.dart:457-468]` |
| `(من <status>)` | `(من {STATUS_LABELS_AR[h.fromStatus] ?? h.fromStatus})` `[REPO backoffice/src/profiles/ProfileDetailPage.tsx:190-194]` |
| Literal `--` for an em-dash | `الواجهة الخلفية -- تحديث بيانات العملاء` `[REPO backoffice/src/layout/AppShell.tsx:39]` |
| Sparse Stage 12 | a title, one sentence and a button `[REPO mobile/lib/features/submission/stage12_screen.dart:215-243]` |
| Signature preview shape | draw pad is `height: 180` full-width (landscape); the **uploaded** preview is `Image.memory(bytes, height: 180, fit: BoxFit.contain)` `[REPO mobile/lib/features/signature/stage11_screen.dart:271-297]`, so a portrait phone photo letterboxes to a narrow strip inside a 180 px band. `[INFERRED — this is the more likely referent of BL-076's "portrait-shaped preview"; the draw pad itself is landscape]` |

### 1.6 Splash, launch config and display name

- `android:label="mobile"` `[REPO mobile/android/app/src/main/AndroidManifest.xml:8]` — hardcoded, not a string resource.
- `launch_background.xml` is a layer-list containing **a single white rectangle**, with Flutter's template comment for the image still commented out `[REPO mobile/android/app/src/main/res/drawable/launch_background.xml:3-12]`. The first frame the customer ever sees is a blank white screen.
- `LaunchTheme`/`NormalTheme` exist in `values/` and `values-night/` only — **there is no `values-v31/`** `[REPO mobile/android/app/src/main/res/ listing]`, so on Android 12+ the OS SplashScreen API takes over with unstyled defaults.
- Launcher icons are Flutter's default `ic_launcher.png` at five densities; **there is no `mipmap-anydpi-v26/`**, so there is no adaptive icon `[REPO mobile/android/app/src/main/res/ listing]`.
- `LaunchScreen` — which is the app's Stage 0 and genuinely does network work — renders **a bare `CircularProgressIndicator`** while it waits `[REPO mobile/lib/features/entry/launch_screen.dart:28-39]`, and on failure interpolates the raw Dart exception into Arabic customer copy: `Text('تعذر بدء التطبيق: $error')` `[REPO .../launch_screen.dart:34]`. The same pattern appears at `[REPO .../account_entry_screen.dart:120,185]` and `[REPO .../stage3_screen.dart:498]`.

### 1.7 Rendering baseline of the reference device

The pilot/test device is a 2019 Huawei, 2 GB RAM, **Android 9 = API 28**, 5-inch. Flutter's Impeller renderer is "available and enabled by default on Android **API 29+**. On devices running lower versions of Android or don't support Vulkan, Impeller falls back to the legacy OpenGL renderer." `[DOC https://docs.flutter.dev/perf/impeller]`

**Therefore the reference device runs the legacy Skia/OpenGL path, and runtime shader-compilation jank is live on it.** Flutter's own performance page describes the symptom precisely — "noticeable jank on your mobile app, but only on the first run of an animation" — and its only stated mitigation is *use Impeller*, which this device cannot `[DOC https://docs.flutter.dev/perf/shader]`. `[INFERRED]` The practical consequence dominates §2.4: **a newer test phone will not reproduce the jank**, so any motion work must be measured on the Android 9 device or it is not measured at all.

---

## 2. Reaction to the PO's five items

### 2.1 App name / branding — «الفرنسي بياناتي» / "Alfaransi Bayanati"

**VERDICT: Approve with adjustment.**

The name is good: two words, the distinctive product noun («بياناتي» — "my data") carries the meaning, the bank shorthand supplies the authority. It survives the service outliving the re-verification campaign. The naming decision is the PO's and I am not reopening it.

Three adjustments, each with a reason:

**(a) Split the launcher label from the store/brand name.** Android home-screen labels truncate at roughly 10–12 characters on most launchers; «الفرنسي بياناتي» is 15 including the space and would most likely truncate to «الفرنسي…» on a 5-inch device — losing exactly the distinctive word `[JUDGEMENT; truncation width is launcher-dependent and is UNVERIFIED on the reference device]`. Recommend: **launcher label «بياناتي»** (7 characters, cannot truncate), **full lockup «الفرنسي بياناتي»** on the splash, the brand line and the Play listing. Play's app title limit is 30 characters, so the full name fits comfortably `[UNVERIFIED — confirm the current Play Console limit at submission]`. **DECIDED (topic 1) — accepted as recommended.**

**(b) Set the label as a string resource, not a literal.** `android:label="@string/app_name"` with `values/strings.xml` (Arabic) and optionally `values-en/strings.xml`, replacing the hardcoded `"mobile"` `[REPO AndroidManifest.xml:8]`. Same effort, and a device set to English then gets a sensible label instead of Arabic-in-a-Latin-launcher. **DECIDED (topic 1) — accepted.**

**(c) Do not show the Latin transliteration anywhere in the UI.** "Alfaransi Bayanati" is useful for a keystore alias, Play developer notes and internal conversation. Putting it on screen creates a bilingual brand the project then has to maintain in an Arabic-first product. `[JUDGEMENT]` **DECIDED (topic 1) — accepted; the transliteration appears nowhere in the UI.**

**(d) The slogan «لؤلؤة المصارف» — supplied by the PO at topic 1 — belongs on the splash screen and nowhere else.** `[JUDGEMENT]`

It earns its place there for the same reason the logo does: the customer's first question in a mandated identity flow is *"is this really from my bank?"*, and a slogan they have seen on branch signage and printed material is an **authenticity marker** — it is recognised, not read. That is a different job from persuasion, and it is the job the splash screen has.

It should not travel further into the journey. «لؤلؤة المصارف» is marketing register — a claim about the bank's standing — and §3.1's Revolut analysis is precisely about why persuasion tone works against trust in a flow the customer was *told* to complete rather than chose. Once past the splash, every screen should be doing work. Specifically: **not** in any AppBar (the title carries the stage name, the customer's only orientation cue in a 12-stage journey — §2.2), and **not** on the confirmation screen, where the customer wants their reference number and what happens next, not a brand claim.

Typographically it is subordinate to the wordmark, not competing with it: smaller, lighter in colour, never larger than the app name. See §2.5 for the splash stack.

**⚠ A naming error to catch before any store listing exists.** The bank's own public site gives the Arabic name as **«البنك السوداني الفرنسي»** `[SOURCE https://www.sfbank-sd.com — fetched this session]`. BL-081 proposes the store listing "بياناتي — بنك السودان الفرنسي" `[REPO BACKLOG.md BL-081]`. **«بنك السودان» is the central bank of Sudan.** «بنك السودان الفرنسي» therefore reads as "the Bank of Sudan, the French one" and misattributes the app to the regulator. Recommended subtitle: **«بياناتي — البنك السوداني الفرنسي»**. This contradicts a phrasing already recorded in BACKLOG.md; flagged here rather than corrected in passing, as CLAUDE.md requires.

**RESOLVED (topic 1).** The PO confirms the formal forms as **«البنك السوداني الفرنسي»** / **"Sudanese French Bank"**, with the slogan **«لؤلؤة المصارف»**. The store subtitle becomes **«بياناتي — البنك السوداني الفرنسي»** and BL-081 is to be amended. Every occurrence of the bank's name in customer or operator copy uses this form — including §2.5's launch sentence and §4.1's back-office header rewrite.

**Scheduling finding, contradicting a recorded plan line.** `docs/road-to-production.md` §3.4 says the UI polish "**Depends on** 2.1 — do not add branded assets under the old package names" `[REPO docs/road-to-production.md:273]`, and §2.1 is the **backend Java package rename** `sd.gov.bank.* → sd.sfbank.*` `[REPO docs/road-to-production.md:186-198]`. For the **mobile** tier that dependency does not bind: Android resources live under `res/`, not under the package path, and survive an `applicationId` change untouched; only `MainActivity.kt` sits under `com/example/mobile/`. **The mobile branding and polish work is not blocked by 2.1.** `[INFERRED]` This is a sequencing observation for the PO to confirm, not a decision taken here. **CONFIRMED by the PO (topic 1): the mobile branding and polish work is not gated on the backend rename.** `docs/road-to-production.md` §3.4's dependency line should be amended to say it binds the backend tier only.

### 2.2 App logo (asset supplied by the PO)

**VERDICT: Approve with conditions on the asset, and one adjustment on placement.**

The app currently has no brand mark of any kind. This is the single most-noticed absence in a bank identity flow, where the customer's first question is "is this really from my bank?" `[JUDGEMENT, supported by the national-ID-app pattern in §3.5]`

**ASSET SUPPLIED (topic 2, 2026-09-07).** The PO supplied the brand master, now stored at **`docs/brand/sfb-logo-master-2048.jpg`** (relocated into the repo with the PO's agreement). Everything below is measured from that file this session, not requested.

| Property | Measured value |
|---|---|
| Format | **JPEG** (`FF D8 FF E0`, JFIF), despite the supplied `.jfif` extension — **not a vector** |
| Dimensions | **2048 × 2048**, RGB, 1.09 MB |
| Brand blue | **`#105097`** — 36.5 % of all pixels; the blue family covers 92.9 % of the image |
| Composition | White content on a flat blue field, horizontally centred (left margin 322 px = right margin 322 px) |
| Emblem (octagon + camel) | bbox **x 684–1357, y 192–963** → **673 × 771**, centred at x = 1020 vs image centre 1024 |
| Arabic wordmark | bbox y 1127–1444 → 1405 × 317 |
| English wordmark | bbox y 1504–1583 → **1401 × 79** — 3.9 % of image height |

**It is a raster, and that is acceptable.** At 2048 × 2048 every rendition the app needs is a *downsample*, never an upscale: the adaptive-icon foreground layer is 432 px and the emblem source is 771 px; a 3× splash logo is a few hundred pixels. JPEG ringing around the hard white edges averages out under downsampling at these ratios. `[INFERRED]` A true vector master remains worth obtaining from the bank for print and large-format use, but **it does not block the pilot** and the build session should not wait for it.

**The four variants are all derived from this one file** — no second round trip to the PO:

1. **Full lockup** — the file as supplied, for the splash. Its blue field is `#105097`, so on a `#105097` splash background the edges vanish and it reads as one composition (§2.5).
2. **Emblem only, white on transparent** — crop `x 684–1357, y 192–963`, key out the blue to alpha. This is the adaptive-icon **foreground** layer and the only variant that needs an alpha channel (JPEG has none, so this cut must be made). The emblem is effectively square and self-centring, so it sits inside the adaptive icon's guaranteed-visible inner 66/108 dp without redesign.
3. **Flat `#105097`** — the adaptive-icon **background** layer. No artwork needed.
4. **Small emblem** for the confirmation screen, downsampled from (2).

**The launcher icon carries no text — DECIDED (topic 2).** The English wordmark measures 79 px in a 2048 px canvas; scaled to a 48 dp launcher icon it is **under 2 dp tall**, and the Arabic line is barely better. Both would render as an unreadable smear, and the launcher's circular mask would slice them off regardless. Emblem only.

**The bilingual lockup is not altered.** The master carries both «البنك السوداني الفرنسي» and "SUDANESE FRENCH BANK". This does **not** conflict with D1's rule against Latin in the UI — that rule governs transliterating the *app* name ("Alfaransi Bayanati"), not the bank's own registered mark. Nobody should strip the English line from the lockup. `[JUDGEMENT]`

**Contrast, measured.** White on `#105097` computes to **8.03 : 1** — clears WCAG AA (4.5:1) and AAA (7:1) for body text. `[computed this session from the WCAG 2.2 relative-luminance formula]` The PO has confirmed white as the on-brand text colour, and the headroom means later tonal adjustment cannot silently drop it under AA. On a low-cost 5-inch LCD used outdoors in Sudan that margin is not a formality.

**Flutter still consumes PNG, not SVG.** Flutter has no built-in SVG renderer; `flutter_svg` is present in the tree **only as a transitive dependency of `signature`** `[REPO mobile/pubspec.yaml:70-84]`, and promoting it to a direct dependency is an AD-006 decision that buys nothing here — the logo never animates and never scales continuously. Export the variants above to PNG at 1×/2×/3× and let Flutter's resolution-aware asset mechanism pick. `[INFERRED]`

**Adjustment on placement.** Do **not** put the logo in every AppBar. On a 5-inch screen the AppBar title carries the stage name — «عنوان السكن», «البيانات الشخصية والاجتماعية» `[REPO .../stage5_screen.dart:200; .../stage3_screen.dart:299]` — which is the customer's only orientation cue in a 12-stage journey. Recommend the logo appears on: the Android launch drawable, the Flutter launch/brand screen, and the confirmation screen. Nowhere else. `[JUDGEMENT]` **DECIDED (topic 2) — accepted, four locations: launcher icon, native launch drawable, Flutter splash, confirmation screen. No AppBar logo.**

### 2.3 Smoother field-to-field cursor/focus flow, and RTL focus order specifically

**VERDICT: Approve — and the diagnosis is different from the framing.**

The PO is right that the flow is wrong; it is wrong for a simpler reason than "LTR traversal". As §1.2 establishes, traversal is **already RTL-correct and simply never runs**.

#### What "next" correctly means in an RTL form

Focus order is a **reading-order** property, not a left-to-right visual one. In an Arabic RTL form:

- **Down a single column:** "next" = the next field below. Identical to LTR. This is 100% of the current app's data-entry layout `[REPO stage3/4/5/6/7_screen.dart — all single-column `Column`s]`.
- **Across a multi-column row:** "next" = the **right-hand** cell first, then the left-hand cell, then down to the next row. The mirror of LTR.
- **A field whose *content* is LTR** (a phone number, an account number, an OTP) does **not** change its position in the traversal order. Content direction and traversal direction are independent properties. Forcing `textDirection: TextDirection.ltr` on a `TextField` affects only how that field lays out its own glyphs `[INFERRED]`.

#### The mechanism, verified against the pinned SDK

1. **`textInputAction: TextInputAction.next` is sufficient to advance focus.** `EditableText` handles it by calling `widget.focusNode.nextFocus()` `[SDK packages/flutter/lib/src/widgets/editable_text.dart:3948-3949]`. No explicit `FocusNode` is required for advancement.
2. **`nextFocus()` resolves through `ReadingOrderTraversalPolicy` by default** `[SDK packages/flutter/lib/src/widgets/focus_traversal.dart:63, 458, 2049]`, which sorts by the ambient `Directionality` — already `rtl` app-wide `[REPO mobile/lib/core/app.dart:26-27]`.
3. **Therefore: do not install `OrderedTraversalPolicy`.** Beyond being unnecessary, it is actively wrong here: Stage 3 mounts and unmounts fields conditionally (spouse name only when married, children count only when `_hasChildren == true`) `[REPO .../stage3_screen.dart:370-422]`, and a hardcoded index list would have to be maintained against every conditional branch. `ReadingOrderTraversalPolicy` only ever sees mounted nodes and needs no maintenance. `[INFERRED]`

#### The concrete recommendation

**(a) Keyboard action types, field by field.**

| Screen | Field | Action |
|---|---|---|
| 1a account | account number (only field) | `done` → submit |
| 1b contact | phone | `next` |
| 1b contact | email | `done` |
| 2 verification | each OTP code (3 independent rows, each with its own verify button) | `done` — **never `next`**; the three codes are unrelated and advancing between them invites entering the WhatsApp code in the SMS row |
| 3 personal | ethnicity → (spouse name) → (children count) → (birth state text) → birth city | `next` on all but the last mounted one; `done` on the last |
| 4 financial | monthly expenses (only field) | `done` |
| 5 home address | المدينة، المنطقة، الشارع، المربع | `next` |
| 5 home address | رقم المنزل | `done` |
| 6 work address | same shape | same |
| 7 | per the run present | same rule |

**(b) The caret and keyboard.**

- **Do not autofocus the first field of a data-entry screen.** On a 5-inch panel the keyboard immediately covers half the form, the offline banner and the section headings, and a mandated flow needs orientation before typing. The picker's existing autofocus `[REPO reference_item_picker.dart:76]` is the correct exception — that screen exists only to type. `[JUDGEMENT]`
- **On validation failure, move focus to the first offending field and `Scrollable.ensureVisible` it**, in addition to the existing message. Better still: adopt account entry's per-field `InputDecoration.errorText` pattern `[REPO account_entry_screen.dart:138-141]` as the house rule, and keep the bottom message only as a summary. This removes a real below-the-fold failure. `[JUDGEMENT, GOV.UK error-summary pattern — §3.2]`
- **In the OTP rows, dismiss the keyboard once six digits are entered.** The verify button already enables at exactly six `[REPO channel_verification_screen.dart:443]`, but on a 5-inch screen the keyboard covers it. `FocusScope.of(context).unfocus()` when `length == 6` reveals the enabled button. Deliberately **not** auto-submitting: auto-submit spends a wrong-code attempt on a mid-paste transient. `[JUDGEMENT]`

**ALL OF §2.3 DECIDED (topic 5).** The PO approved the per-field error change, confirmed no auto-submit at six digits, and confirmed no autofocus on data-entry screens. Full record at §0 D5.

**(c) The LTR islands' alignment, which is the RTL half the PO is pointing at.**

Today the five numeric fields set `textDirection: TextDirection.ltr` with **no `textAlign`** `[REPO account_entry_screen.dart:132; contact_channels_screen.dart:265; channel_verification_screen.dart:486; stage4_screen.dart:228; stage3_screen.dart:401]`. Result: the Arabic `labelText` is positioned at the **right** edge by the ambient RTL, while the digits sit at the **left** edge of the field. On a 328 px-wide content column the label and its own value are at opposite ends of the screen. `[INFERRED — visual, needs a device confirmation]`

Recommendation: add **`textAlign: TextAlign.right`** to those five. `textDirection: ltr` keeps the digit *order* correct; `textAlign: right` puts the digits under their Arabic label and lets the number grow leftward as it is typed, which is standard for numbers in RTL forms. `[JUDGEMENT]`

**(d) Multi-column rows.** There are currently **no multi-column text-input rows**; the only two-column `Row`s hold buttons `[REPO stage5_screen.dart:277-298; stage11_screen.dart:300-318; salary_certificate_field.dart:151-165]`. The nav rows put «السابق» first (rendering right) and «التالي» second (rendering left) — **correct RTL, since forward is leftward** `[DOC https://material.io/archive/guidelines/usability/bidirectionality.html: "The passage of time is depicted as left to right for LTR languages, and right to left for RTL languages"]`. **Do not disturb this.** The rule for a future multi-column input row: wrap it in a `FocusTraversalGroup` and leave the default policy — right cell then left cell falls out automatically.

### 2.4 Elegant screen-to-screen transitions, bounded by the device

**ORIGINAL VERDICT: Adjust — approve the intent, invert the order of work, and cap the vocabulary.**

> ### ⚠ SUPERSEDED AT TOPIC 6 — VERDICT NOW: **Approve. This is a defect fix, not an enhancement, and it costs one line.**
>
> **The PO declined device measurement and raised the device floor**, on the reasoning that the 2019 Huawei should not bind the design and may reasonably lose transition elegance while still working. Asked for the lowest-effort next-best option, the pinned SDK was traced instead — and it produced a finding that removes the whole dilemma.
>
> **The app ships a transition that is wrong for RTL today.** Setting no `pageTransitionsTheme` `[REPO mobile/lib/core/app.dart:28]` resolves to the Android default `PredictiveBackPageTransitionsBuilder` `[SDK material/page_transitions_theme.dart:766]`. Predictive back is Android 13+ **and** requires a manifest opt-in this app does not have, so on every pilot device it **falls back to `FadeForwardsPageTransitionsBuilder`** `[SDK material/predictive_back_page_transitions_builder.dart:95-97]` — a 450 ms horizontal ±0.25 slide `[SDK page_transitions_theme.dart:470, 490-499]` whose own source comments state LTR semantics verbatim: *"The previous page slides from right to left as the current page appears."*
>
> **No built-in Material page transition mirrors itself for RTL.** `grep -c textDirection` over both transition source files returns **0** `[SDK material/page_transitions_theme.dart; widgets/page_transitions_builder.dart — verified this session]`. §2.4's closing note anticipated this trap for *bespoke* transitions; it is in fact live in the *framework default*.
>
> **DECIDED: set `pageTransitionsTheme` to `FadeUpwardsPageTransitionsBuilder` for `TargetPlatform.android`.** Its tween is `Offset(0.0, 0.25) → Offset.zero` `[SDK widgets/page_transitions_builder.dart:103-108]` — **the horizontal component is zero**, so the motion is purely vertical and therefore *direction-neutral by construction*. It does not fix the RTL bug so much as make it structurally impossible.
>
> Four consequences, all favourable:
> - **No measurement and no device testing.** Cheaper than what ships today: no snapshotting (unlike `ZoomPageTransitionsBuilder`), shorter, and a vertical translate + fade on an already-rasterised layer is the cheapest item on the "smooth" list below.
> - **Uniform behaviour on every device.** Today's behaviour varies by Android version through the predictive-back fallback; pinning it removes that divergence — which is what the PO's no-device-testing decision requires.
> - **It suits the product.** A calm upward fade fits a bank identity flow better than a horizontal push, which carries a "browsing forward" feel this journey does not want. `[JUDGEMENT]`
> - On the 2019 Huawei the worst case is a first-run hitch, then smooth — the graceful degradation the PO accepted.
>
> **Everything below about measuring first, and the bespoke shared-axis vocabulary, is superseded.** It is retained because the *hardware* analysis still governs what may be added later, and because the RTL-mirror note is what led to the finding above. The rejected alternative, recorded: `ZoomPageTransitionsBuilder` is also direction-neutral but is the heaviest built-in option and uses snapshotting.

The original reasoning follows. Three facts bounded it:

1. The reference device is **API 28**, below Impeller's threshold, so it runs the legacy renderer and **pays runtime shader-compilation cost the first time each new effect appears** `[DOC docs.flutter.dev/perf/impeller; docs.flutter.dev/perf/shader]`.
2. **A newer test phone will not reproduce that jank** `[INFERRED]`. So "it looks smooth on my phone" is not evidence here, and neither is a green widget test.
3. There is **currently no bespoke transition at all** `[REPO app_router.dart:27-133; app.dart:28]`, so the platform default is what ships. Replacing a default with something "more elegant" is the only way this item can *regress* the pilot.

**Therefore the first task is measurement, not design.** Run the current build on the Android 9 device with the performance overlay and record whether the existing default page transition drops frames. If it does not, the cheapest correct answer to item 4 is **change nothing about transitions** and spend the budget on typography and the splash, which every customer sees on every screen.

#### What is smooth on a 2 GB / Android 9 phone, and what stutters

**Smooth — transform and opacity applied to an already-rasterised layer, one animated property, no new offscreen buffer:**

- `SlideTransition` — a translation on a layer; the subtree is not repainted.
- `FadeTransition` / `AnimatedOpacity` on a **leaf** widget or a small subtree.
- A semi-transparent `Color` on a `Container` (draws directly; no offscreen buffer).
- `AnimatedContainer` animating colour or a small size.
- The framework's own page transitions, which snapshot the outgoing route where the platform allows it.
- Material's ink ripple on button press (already free).

**Stutters — anything forcing `saveLayer()`, a second render pass, or a large decode mid-animation:**

- **`BackdropFilter` / `ImageFilter.blur`.** Full-screen blur is multiple render passes on GLES. **Ban outright.**
- **`Opacity` wrapping a non-trivial subtree.** "The `Opacity` widget is expensive because it often forces an off-screen layer… avoid using the `Opacity` widget, and particularly avoid it in an animation" `[DOC https://docs.flutter.dev/perf/best-practices]`. Use `AnimatedOpacity`/`FadeTransition`, or a semi-transparent colour.
- **Clipping during animation.** "Avoid `Clip.antiAliasWithSaveLayer`… avoid clipping in an animation" `[DOC ibid.]`. `ClipRRect` on an animating widget is the common accidental case; prefer `BorderRadius` on the decoration.
- **`BoxShadow` with a large `blurRadius`, or elevation on many simultaneous surfaces** — each is a blurred draw per frame. The app's `Card` at `[REPO stage9_screen.dart:369]` is one, static — fine. A list of twelve elevated cards animating in is not.
- **Hero / shared-element on an image.** It drives layout and paint every frame while the image decode competes on the raster/IO thread. Directly relevant here: Stage 9 renders `Image.memory` for registry and portrait images `[REPO stage9_screen.dart:413]` and Stage 11 renders an uploaded signature measured at **231,967 bytes** at S7-12 `[REPO docs/sessions/2026-09-07-s7-12-acceptance-walk.md §4]`. **Ban Hero on images.**
- **Large image decode.** `Image.memory` with no `cacheWidth` decodes at full source resolution. A full-resolution camera photo decodes to tens of megabytes of ARGB on a device with 2 GB total. Adding `cacheWidth` sized to the display box is a one-argument fix and should be treated as a house rule, not an optimisation.
- **UI-thread vs raster-thread:** blur, clip and saveLayer land on the **raster** thread; rebuilding large widget trees per frame lands on the **UI** thread. Stage 3 calls `setState(() {})` on every keystroke of five controllers `[REPO stage3_screen.dart:67-82]`, rebuilding the entire screen per character. That is a UI-thread cost that already exists and is worth measuring on the reference device — it is more likely to be felt than any transition. `[INFERRED]`
- **First-run shader compilation.** Because SkSL warm-up is no longer the recommended path and Impeller is unavailable on API 28, the only practical mitigation is **to use few distinct effects, and to reuse the same ones everywhere so they compile once and early.** `[INFERRED from DOC docs.flutter.dev/perf/impeller + perf/shader]` This turns the constraint into the design rule: a small vocabulary is not a compromise here, it is the mitigation.

#### The recommended bounded motion vocabulary — SUPERSEDED at topic 6

*(Replaced by the single `FadeUpwardsPageTransitionsBuilder` line. Retained as the specification for any future motion work, and because its "Banned" row survives — see the reframing at §0 D6.)*

| Use | Pattern | Duration | Curve | Mechanism |
|---|---|---|---|---|
| Stage → stage (forward/back) | **Shared-axis X**, mirrored for RTL: new page enters from the **left**, old exits **right** | 250–300 ms | `easeOutCubic` in / `easeInCubic` out | `SlideTransition` + `FadeTransition` in a `CustomTransitionPage`, **passing `textDirection: Directionality.of(context)`** |
| Within-stage view swap (Stage 9 review → confirmation, error → retry) | **Fade-through**, no scale | 90 ms out, 210 ms in | `easeIn` / `easeOut` | two `FadeTransition`s |
| Conditional field appearing (Stage 3) | **None** | 0 | — | render/remove directly |
| Feedback | ripple + `CircularProgressIndicator` (already present) | — | — | Material defaults |
| **Banned** | Hero on images; `BackdropFilter`/blur; parallax; staggered list entrances; container transform; any vector-animation package; anything > 400 ms | | | |

Two implementation notes that matter more than the table:

- **The RTL mirror is the one thing most likely to be got wrong.** `SlideTransition` takes a `textDirection` and, when it is `TextDirection.rtl`, applies the x offset in the opposite direction `[SDK packages/flutter/lib/src/widgets/transitions.dart:197, 205-219, 237]`. A hardcoded `Offset(1.0, 0.0)` with no `textDirection` slides forward navigation the wrong way in an RTL app and every test still passes. Pass the directionality.
- **The cheapest possible version of this item is one line.** Setting `ThemeData.pageTransitionsTheme` to `FadeUpwardsPageTransitionsBuilder` for `TargetPlatform.android` in `app.dart` changes every route at once, touches no route definition, uses only slide-up + fade (no zoom, no snapshot), and is **direction-neutral — so it carries zero RTL risk**. If measurement shows the current default stutters, do this first and only build the bespoke shared-axis if it is still wanted afterwards. `[JUDGEMENT]`
- **Honour the OS "remove animations" setting.** `MediaQuery.of(context).disableAnimations` → duration `Duration.zero`. Custom transitions do not do this for free. `[SOURCE https://designsystem.gov.ae/guidelines/mobile-applications: "follow accessibility guidelines in terms of animations and the ability to detect motion settings from the device's settings"]`

**Explicitly cut from this item:** animating conditional form fields in and out. On a 5-inch screen with the keyboard up, a field that fades in below the caret is disorienting for a mixed-literacy audience under mild stress. `[JUDGEMENT]`

### 2.5 Landing / splash screen — logo + brand line + one sentence

**VERDICT: Approve, and it is better value than it looks.**

`LaunchScreen` is not a decorative delay — it is Stage 0 and it performs real work, awaiting `launchDecisionProvider` which runs the backend launch check `[REPO mobile/lib/features/entry/launch_screen.dart:18-39]`. There is already a wait. Filling it with brand and purpose costs nothing in time.

**Three layers, all needed, in this order:**

1. **The Android window launch theme.** `launch_background.xml` is currently a plain white rectangle `[REPO res/drawable/launch_background.xml:3-12]` — the customer's genuine first frame is a blank screen that reads as "nothing is happening". Put the mark and the brand background colour there. Flutter's official method is the manual theme approach `[DOC https://docs.flutter.dev/platform-integration/android/splash-screen]`.
2. **A `values-v31/styles.xml`.** Android 12+ ignores the custom `windowBackground` and uses the SplashScreen API with `windowSplashScreenBackground` / `windowSplashScreenAnimatedIcon`; Flutter's own doc warns "If your Android app supports releases earlier than Android 12 *and* post-Android 12 releases, consider using two different resources in your `styles.xml` file" `[DOC ibid.]`. With `minSdk 24` and `targetSdk 36` `[REPO PROJECT_PLAN.md:15-18]` **both paths are in scope.** A change tested only on the Android 9 reference device will look wrong on a modern phone, and vice versa — worth stating because that is exactly how this gets missed.
3. **The Flutter brand screen**, replacing the bare spinner.

**The splash stack, top to bottom** (updated at topic 1 for the slogan):

Background: **flat `#105097`**, the master's own blue, so the supplied lockup's field merges into the screen with no visible edge (D2). All text white.

| Line | Content | Weight |
|---|---|---|
| 1 | Logo mark / full lockup | the visual anchor |
| 2 | **«الفرنسي بياناتي»** — the app name | primary, largest text |
| 3 | **«لؤلؤة المصارف»** — the bank's slogan | subordinate: smaller, muted colour, never larger than line 2 |
| 4 | One sentence saying what the app is for | body size |
| 5 | A small progress indicator | minimal |

Lines 2 and 3 do different jobs and must not compete. Line 2 identifies the *app*; line 3 is an **authenticity marker** for the *bank* — recognised from branch signage rather than read as a claim (§2.1(d)). If the supplied logo asset already contains the slogan inside the lockup, **drop line 3** rather than printing it twice.

~~**Proposed sentence**~~ **DECIDED (topic 7) — the PO supplied the wording: «بياناتي - تحديث بيانات حسابك».** My longer proposal («تحديث بيانات حسابك لدى البنك السوداني الفرنسي.») is dropped.

**Why the PO's phrasing is the better one, and not only because it is theirs.** «تحديث» is a verbal noun (masdar), not an imperative — so it satisfies house rule R1 (§4.1) without effort and **sidesteps the gendered-command problem entirely**: an imperative would have forced «حدّث» / «حدّثي» and therefore a sex the app does not know until Stage 3. «حسابك» carries a second-person possessive that is written identically for both genders. The register is also plainer and shorter than mine, which is the COGA principle (§3.7). `[JUDGEMENT]`

**Two adjustments applied:**

1. **Em-dash, not hyphen: «بياناتي — تحديث بيانات حسابك».** House rule R6 (§4.1) and consistency with the store subtitle set at D1. This is the same defect class S7-12 caught in the back office, where a literal `--` stood in for a dash.
2. **On the splash, line 4 renders the short form «تحديث بيانات حسابك» only.** The app name is line 2 directly above, so the full string would print «بياناتي» twice on one screen. **The full «بياناتي — تحديث بيانات حسابك» is the tagline** — correct for the store listing and anywhere it stands alone without the logo above it.

**Do not add a timed minimum display.** A mandated one-time chore should not be made to wait for a logo. When the launch decision resolves, navigate. `[JUDGEMENT]`

**Do not add `flutter_native_splash`.** It is three XML files by hand; a package means an AD-006 evaluation and CLAUDE.md's per-package iOS-support check for something that generates files we would then be forbidden from hand-editing. `[INFERRED from CLAUDE.md hard rules + PROJECT_PLAN AD-006]`

**Adjacent and required by BL-082 anyway:** an adaptive launcher icon (`mipmap-anydpi-v26/ic_launcher.xml` with foreground + background layers). None exists today `[REPO res/ listing]`.

### 2.6 Missing for a pilot-ready first impression (additions), and anything to cut

**Nothing in the PO's five should be cut.** If one must be dropped for schedule, it is item 4 (transitions) — the only one whose absence no customer will notice and whose presence carries the sole regression risk on the target device.

**Seven additions, ordered by value.**

**A1 — A theme. This is the largest gap in the list and it is not on it.** `ThemeData(useMaterial3: true)` with no seed colour `[REPO app.dart:28]` means the pilot ships in stock Material baseline purple with no relationship to the bank. A `ColorScheme.fromSeed(seedColor: <bank hex>)` plus a `textTheme` is roughly fifteen lines in one file and restyles every screen at once. **Highest value per line available.** Depends on the brand colours from §2.2. Ship **light theme only** for the pilot: one fewer theme to test, better outdoor legibility on a cheap LCD, and the bank's identity is white-on-blue, which is a light-mode identity. `[JUDGEMENT]` **DECIDED (topic 3) — accepted; the theme is the highest-priority build item.**

**The `fromSeed` trap, which decides whether this item actually delivers the brand.** `ColorScheme.fromSeed(seedColor: Color(0xFF105097))` does **not** yield a `primary` of `#105097`. It treats the argument as a *seed*, derives a full tonal palette from it, and selects a fixed tone for `primary` — which for a mid-toned blue returns visibly lighter and less saturated than the input. The failure mode is quiet: the app looks broadly blue, nobody checks the hex, and the bank notices. **Seed from `#105097` for the harmonised surfaces, containers and states, then override `primary` to exactly `#105097` and `onPrimary` to white.** `[INFERRED from Material 3's tonal-palette model — the returned value should be confirmed when the theme is written, not trusted from this note]`

**A2 — Bundle a font.** See §4.4. Without it the pilot device and the review device render different products, and the letter-spacing defect in A3 cannot be fixed.

**A3 — Fix Material's Latin-tuned text metrics for Arabic.** Material 3's default text styles carry positive `letterSpacing` (e.g. `bodyMedium` 0.25). **Positive tracking on a connected script visually breaks the joins between letters** and reads like tatweel padding. Because no `textTheme` is set, this is live today on every string in the app. Set `letterSpacing: 0` across the text theme, `height: 1.6` for body, and raise body from Material's 14 sp default to **16 sp**. `[INFERRED from how connected scripts render + Material's default styles; SOURCE https://designsystem.gov.ae/guidelines/typography for the ≥1.5 line-height rule — verify on the device]`

**A4 — Never interpolate a raw exception into customer copy.** `'تعذر بدء التطبيق: $error'` `[REPO launch_screen.dart:34]`, `'تعذر تحميل قائمة الفروع: $error'` `[REPO account_entry_screen.dart:120,185]`, `'تعذر تحميل قائمة المستوى التعليمي: $e'` `[REPO stage3_screen.dart:498]`. A Sudanese retail customer is shown an English Dart exception. It is also a hygiene concern: a `DioException`'s `toString()` can include the request URI and, depending on the error, response content. **Two problems, one fix.** Replace with a fixed Arabic sentence that says what to do next; log the detail, never render it.

**A5 — The WhatsApp default (R-042, road-to-production §4.4).** `_whatsappSelected = true` `[REPO contact_channels_screen.dart:29]`. With SMS-only at release `[REPO BACKLOG.md BL-080 — product-owner decision 2026-09-07]`, every pilot user who leaves the default watches a WhatsApp row that can never resolve, with resend buttons that do nothing. **This is the single worst first impression in the app and it is one line.** The choice between "deselect by default" and "hide" is the PO's; both are presentation-only. Listed here because it lands in the same file family as the polish work and would be absurd to leave for a later session.

**A6 — Journey progress.** There is no "where am I" indicator anywhere in a twelve-stage flow `[REPO — no stepper or progress widget in any screen]`. For a mandated, once-ever, mildly stressful task, knowing how much remains is the strongest anxiety reducer available. **Recommend labelling by *section*, not by stage number** — stage counts vary with the optional email channel and the blocked paths, and the backend owns stage truth. Four sections: «بياناتك» · «عنوانك» · «إثبات هويتك» · «الإرسال», rendered as a thin `LinearProgressIndicator` plus the section name under the AppBar. `[JUDGEMENT]`

**A7 — Text-scale and touch-target pass.** The app has no `textScaler` clamp. A customer with the system font size at 1.3× — common among older users, and the audience is explicitly all ages — will overflow the fixed 140 px label row `[REPO stage9_screen.dart:464]` and the two-button nav rows. Test at 1.0× and 1.3×, allow wrapping, and confirm the 48 dp minimum touch target on the `TextButton`s. This finds overflow before the pilot does. `[JUDGEMENT]`

---

## 3. Design references with transfer analysis

### 3.1 Revolut (PO-named)

**What it is.** A consumer neobank app; LTR, English-first, designed for young affluent daily users on flagship hardware. Its polish is widely cited: restrained near-monochrome product UI with one accent, one dominant action per screen, numbers as the visual hero, and marketing-led onboarding storytelling `[SOURCE https://craftinnovations.global/revolut-onboarding-flow-analysis/ and https://getdesign.md/revolut/design-md — both secondary UX analyses; I did not have access to Revolut's own design documentation]`.

**Borrow.**
- **One primary action per screen, bottom-anchored, full-width.** Our app already does exactly this `[REPO stage3_screen.dart:468-480]`. Revolut's example is the argument for *keeping* it under pressure to add more.
- **Numbers as the hero.** A single large number, centred, with nothing competing. Stage 9's national number already does this `[REPO stage9_screen.dart:369-393]`; the confirmation reference number should do it more strongly.
- **Colour restraint.** Largely neutral surfaces with one accent reserved for the primary action. This is also the cheapest thing to render.
- **"Say what happens next" confirmations** rather than a bare success tick.

**Where it does NOT transfer.**
- **LTR/English visual rhythm.** Its grid, its type scale and its icon-to-text relationships all assume Latin. Mirroring the layout does not mirror the typography.
- **Register.** Revolut's onboarding is *persuasion* — it is selling a choice. Our customer has been **told by their bank** to do this. Excitement, celebratory motion and marketing tone read as *less* trustworthy in a mandated identity flow and can trigger the "is this a scam?" reflex. This is the most important non-transfer.
- **Hardware.** Layered translucency, blur, animated cards and full-bleed dark/light transitions are precisely the effects §2.4 bans on a 2 GB API-28 device.
- **Daily-use design.** Dense home dashboards and discoverable-by-repetition gestures assume the user returns. Nothing in our app is learnable — every screen is seen once.
- **Dark mode.** Revolut leans dark; we should ship light-only for the pilot.
- **Audience.** Young-affluent tone and English loanwords do not transfer to Sudanese retail across all ages and literacies.

### 3.2 GOV.UK Service Manual / GOV.UK Design System

**What it is.** The UK government's public service design standard, built around **"one thing per page"**: split questions onto separate pages because "low-confidence users find them easier to use, they work well on mobile devices, and they're better at handling things like errors, branches, loops and saving progress" `[SOURCE https://designnotes.blog.gov.uk/2015/07/03/one-thing-per-page/ — GDS's own design blog]`.

**Borrow.**
- **One question (or one tight group) per page.** Our stage split already approximates this. Stages 5 and 6 (five text fields each) are the ones that most exceed it and are the honest candidates for a split — flagged, not recommended, because splitting screens edges toward journey change.
- **The error-summary pattern** — say what is wrong at the top, linked to the field. Directly fixes the below-the-fold error message in §2.3(b).
- **Plain-language content design.** Feeds §4.1 directly.
- **"Check your answers before you send"** — GOV.UK's version lists every answer before the irreversible action. This is exactly what Stage 12 is and is currently near-empty `[REPO stage12_screen.dart:215-243]`, which is BL-076's "sparse Stage 12" finding.

**Where it does NOT transfer.**
- It is a **browser** system with a Latin, LTR type scale, and its generous whitespace assumes a viewport taller than a 5-inch 720p panel.
- Its "back link" is browser back; ours is device navigation with `context.go` replace semantics.
- Its services assume a user can leave and return via a saved link; ours is device-bound with a distinct resume model (customer.md stage 13).
- **"Check your answers with edit links" cannot be copied wholesale** — by Stage 12 the earlier stages are past their submission points. Borrow the *listing*; do not borrow the *edit links*.

### 3.3 UAE Design System (designsystem.gov.ae) and Dubai Design System

**What it is.** A government-published, **Arabic-first, bilingual** design system with explicit mobile-app and typography guidance — one of the very few authoritative RTL sources.

**Borrow.**
- **The Arabic-first header order**, given explicitly as `[User Profile/Avatar] [Notifications Icon] [Page Title] [Back Button (if applicable)]` — i.e. the back control on the leading (right) edge `[SOURCE https://designsystem.gov.ae/guidelines/mobile-applications]`.
- **Line-height ≥ 1.5 for Arabic body text**, base 16 px `[SOURCE https://designsystem.gov.ae/guidelines/typography]`. Feeds §4.4 and A3.
- **Honour the device's motion settings** `[SOURCE ibid., mobile-applications]`. Feeds §2.4.
- **"Micro-interactions should be subtle and purposeful, not distracting or excessive"** as a *stated bound* — the same discipline §2.4 arrives at from a hardware argument.
- **The practice itself:** a design system that *names* an Arabic base font rather than letting devices choose. That is the argument for A2.

**Where it does NOT transfer.**
- It names **Noto Kufi Arabic** as the primary Arabic base font `[SOURCE ibid.]`. **Reject that for body text here:** Kufi is geometric with low stroke contrast, and at 14–16 sp on a low-DPI 5-inch panel it is materially less legible for continuous reading than a naskh face, for an audience that includes low-literacy readers. Acceptable for a wordmark. `[JUDGEMENT]`
- It is a **Gulf government brand system**, not a Sudanese commercial bank's, and its component set is web-first with a browser-oriented responsive type scale.
- Its audience assumption is a high-smartphone-penetration, high-bandwidth market. Nothing in it is tuned for a 2 GB device.

### 3.4 Saudi DGA "Platforms Code" national design system

**What it is.** Saudi Arabia's national design system for government digital platforms — Arabic-first, full RTL, standardised on **IBM Plex Sans Arabic** `[SOURCE https://dga-registry.vercel.app/ and https://www.ijjad.com/case-study-saudi-national-design-system — secondary vendor/agency pages; I did not reach the DGA's own site, so treat any claim beyond the typeface as UNVERIFIED]`.

**Borrow.**
- **The typeface decision, and its rationale:** a naskh-based UI face shipping a *matched Latin*. Our content mixes Arabic labels with Latin digits, `FRU-` reference numbers and English names on nearly every screen, so a matched pair avoids a visible family jump mid-line. This is the primary input to §4.4.
- **The discipline of one family and few weights** across a whole government estate.

**Where it does NOT transfer.** It is a web component library, not Flutter; its component visuals carry Saudi government brand; and its accessibility conformance is not inheritable — we still have to test ours.

### 3.5 UAE PASS / Saudi Nafath (national digital-ID apps)

**What they are.** The closest *functional* analogues available: government identity apps, Arabic-first, used infrequently by an all-ages population, trust-critical. UAE PASS is described as "the first national digital identity and signature solution that enables users to identify themselves to service providers" `[SOURCE https://u.ae/en/about-the-uae/digital-uae/digital-transformation/platforms-and-apps/the-uae-pass-app]`; Nafath acts as the "secure front door" for cross-government login `[SOURCE https://makitsol.com/digital-identity-in-gcc-how-uae-pass-is-rewriting-access/ — secondary]`.

**Borrow.**
- **The front door states the authority.** The issuing body's name and mark are the first thing on screen, because the user's first question is legitimacy. This is the whole justification for items 2 and 5 in combination.
- **Short, explicit step sequences with a visible position** — feeds A6.
- **Plain confirmation screens rather than dashboards** — matches our once-ever model.
- **An explicit "what happens to your data" line before identity capture.** We need this anyway: BL-082 requires an accurate Play **Data safety** declaration for government identity documents, a face biometric and a national number `[REPO BACKLOG.md BL-082]`. Saying it in-app and declaring it in the Console should be the same sentence, written once.

**Where they do NOT transfer.**
- They **authenticate an already-enrolled identity**; we **create a claim from a scan**. Their flows are far shorter and can lean on biometric unlock; ours cannot.
- GCC device baselines are far higher than a 2019 2 GB Huawei.
- They sit on a state identity system that pre-fills nearly everything. Our paper-form parity means roughly forty fields `[REPO PROJECT_PLAN.md:67-71]`. **What transfers is their sectioning and progress model, not their brevity.**
- State-emblem branding (gold/green, official seals) is not a commercial bank's language.

**Provenance caution.** I did not install or inspect either app. Every UI-specific claim above is `[UNVERIFIED]` and rests on official descriptions and secondary analyses.

### 3.6 Material Design 3 motion + Flutter performance documentation (the platform's own guidance)

**Borrow.** The transition *taxonomy* — shared axis for a spatial/navigational relationship, fade-through for elements without a strong relationship, ≈300 ms for transitions and 100–300 ms for component animation `[SOURCE https://m3.material.io/styles/motion/transitions and https://m3.material.io/styles/motion/easing-and-duration/tokens-specs — the direct fetch returned no body, so these figures come from the search summary and Google's own implementation article https://medium.com/google-design/implementing-motion-9f2839002016; treat the exact millisecond values as indicative]`. And the explicit list of expensive operations `[DOC https://docs.flutter.dev/perf/best-practices]`.

**Where it does NOT transfer.** M3's motion spec — and especially its newer "expressive" direction — assumes hardware where emphasised easing and **container transform** (a morphing, clipped, elevation-animating shape) are affordable. On API 28 with the legacy renderer, container transform is precisely the pattern to avoid. M3 also **does not address which direction shared-axis X should run in RTL** — you must mirror it yourself, which is §2.4's `textDirection` note.

### 3.7 W3C WAI COGA / plain language (the mixed-literacy strand)

**Borrow.** Use common, clear words; remove or explain acronyms, abbreviations and jargon; "simple tense, literal language, and active voice makes it clear what needs to be done" `[SOURCE https://www.w3.org/WAI/WCAG2/supplemental/patterns/o3p01-clear-words/ and https://www.w3.org/WAI/WCAG22/Understanding/reading-level.html]`. This directly generates three of §4.1's house rules: kill «الخادم» (a computer word), kill the `(SMS)` parenthetical, kill raw exception text.

**Where it does NOT transfer.** COGA's testable form of the guidance rests on English-language frequency research ("the most common 1500 words"), and there is **no equivalent validated Sudanese-Arabic frequency list** to test against; Arabic readability metrics are far less mature than English ones. **The principle transfers; the measurement does not.** That is precisely why a native Sudanese Arabic reviewer is a hard dependency in §5, not a nice-to-have.

### 3.8 Summary table

| Reference | What it is | Borrow | Does NOT transfer | Provenance |
|---|---|---|---|---|
| **Revolut** | LTR consumer neobank, flagship, daily use | one bottom-anchored action; numbers as hero; colour restraint; "what happens next" | LTR grid; persuasion register; blur/translucency/animated cards; dashboards; dark mode; young-affluent tone | secondary UX analyses only |
| **GOV.UK Service Manual** | UK public-service standard | one thing per page; error summary; plain language; "check your answers" | browser system; Latin LTR scale; tall viewport; edit-links at the end | GDS's own design blog |
| **UAE Design System** | Government Arabic-first bilingual system | RTL header order; line-height ≥1.5; honour motion settings; "subtle, purposeful" motion; name a font | Noto Kufi for body; Gulf gov brand; web-first; high device baseline | official design-system site |
| **Saudi DGA Platforms Code** | Saudi national gov design system | IBM Plex Sans Arabic + matched Latin; one family, few weights | web component library; Saudi gov visuals; inherited conformance claims | secondary agency pages |
| **UAE PASS / Nafath** | National digital-ID apps | authority stated at the front door; visible step position; plain confirmations; "what happens to your data" | authenticate vs create; higher devices; state pre-fill removes the form burden; state branding | official descriptions + secondary; not inspected |
| **M3 motion + Flutter perf** | Platform guidance | transition taxonomy; ~300 ms; the expensive-operations list | expressive/container transform on API 28; silent on RTL shared-axis direction | vendor docs (M3 durations indicative) |
| **W3C WAI COGA** | Cognitive-accessibility patterns | clear words; no jargon; active voice; literal language | the English word-frequency test has no Arabic equivalent | W3C |

---

## 4. Arabic wording & typography

### 4.1 Register and house style

**Eight rules.** They are written to be checkable by a reviewer who is not a developer.

| # | Rule | Why |
|---|---|---|
| **R1** | **Field labels are nouns, never commands.** «رقم الهاتف», not «أدخل رقم هاتفك». | The Arabic imperative is gendered (أدخل / أدخلي) and sex is unknown until Stage 3. A nominal label sidesteps the problem across ~40 labels instead of solving it 40 times. `[JUDGEMENT]` |
| **R2** | **Buttons, instructions and errors take a verb, in the unmarked masculine**, and each states **what to do next**. | Unmarked masculine is standard MSA for a mixed/unknown audience; "what to do next" is the COGA principle `[SOURCE W3C COGA]`. |
| **R3** | **Never render a gendered string before sex is known — gate the section instead of guessing.** | Already the app's own precedent, with the reasoning recorded `[REPO stage3_screen.dart:338-346]`. Generalise it. |
| **R4** | **No computer words in customer copy.** «الخادم», «الشبكة», «الرمز البرمجي» out. | A retail customer does not need to know there is a server. `[SOURCE W3C COGA: remove jargon]` |
| **R5** | **No raw enum, status or column value ever reaches a screen**, and **no exception object is ever interpolated into a string.** | The `(من <status>)` defect and the four `$error` sites in §2.6-A4. |
| **R6** | **Arabic punctuation:** `،` `؛` `؟` and the real em-dash `—` (U+2014). Never `--`. Never U+0640 (tatweel). | BL-076's `--`; tatweel is a justification artefact that arrives via copy-paste from design tools. |
| **R7** | **Customer copy is Arabic only.** Operator copy may carry a Latin technical term in parentheses. | «الرسائل النصية (SMS)» `[REPO contact_channels_screen.dart:298]` creates a bidi island for no gain in customer copy; «التحقق الحي (Liveness)» `[REPO ProfileDetailPage.tsx:493]` is fine for bank staff. R7's customer half is a **judgement the PO should confirm** — some users may know the channel as "SMS". |
| **R8** | **Numbers, dates and identifiers follow §4.3 without exception.** | One rule, no per-screen decisions. |

#### Before / after — the S7-12 defects

| Site | Before | After | Note |
|---|---|---|---|
| `backoffice/src/profiles/ProfileDetailPage.tsx:189-194` | `بانتظار السجل المدني (من قيد التنفيذ)` | **`تغيّرت الحالة من «قيد التنفيذ» إلى «بانتظار السجل المدني»`** — and for the first row, where `fromStatus` is null: **`بدأت الحالة: قيد التنفيذ`** | Reads as a sentence. No arrow (an arrow glyph in RTL is its own trap). No agreement hazard: «تغيّرت» agrees with the fixed «الحالة», and both interpolations are noun phrases. |
| `backoffice/src/layout/AppShell.tsx:39` | `الواجهة الخلفية -- تحديث بيانات العملاء` | **`تحديث بيانات العملاء — واجهة الموظفين`** | Real em-dash; product first, role second. «الواجهة الخلفية» is a literal calque of "back office" — **PO/bank to confirm what SFB staff actually call it.** |
| `backoffice/src/profiles/ProfileDetailPage.tsx:66-68` | `dayjs(value).format('YYYY-MM-DD HH:mm')` | `dayjs(value).format('DD/MM/YYYY HH:mm')` | Keep the existing `<bdi>` wrapper `[REPO ibid.:62-64]` — it is exactly the right primitive. |
| `mobile/.../stage9_screen.dart:403` | `_field('تاريخ الميلاد', display.dateOfBirth)` → `1985-03-14` | `_field('تاريخ الميلاد', formatIsoDate(display.dateOfBirth))` → `14/03/1985` | Format at the presentation boundary only. **Do not change the wire type** — `identity_scan_models.dart:215` documents it as the backend's `LocalDate.toString()`, and S7-12's own lesson is that you canonicalise what you send and present differently. |
| `mobile/.../stage9_screen.dart:457-468` | `Row(SizedBox(width:140, Text(label)), Expanded(Text(value)))` | A `Column`: label in `titleSmall`, value in `bodyLarge` beneath it, both stretched | Removes the fixed width (which is what pushes the address to two lines), **and the Latin-field misalignment fixes itself** — in a stretched column under RTL, the bidi algorithm places a Latin run flush to the right edge like its Arabic neighbours, with no per-field direction override needed. `[INFERRED — confirm on device]` |

#### Before / after — strings found in the mobile source

| Site | Before | After |
|---|---|---|
| `contact_channels_screen.dart:196`, `stage12_screen.dart:139` | `تعذر الاتصال بالخادم. يرجى المحاولة مرة أخرى.` | `تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.` (R4 — drop «الخادم») |
| `stage3_screen.dart:238` and siblings | `حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى لاحقًا.` | `تعذر إكمال العملية. أعد المحاولة، وإذا تكرر الأمر راجع أقرب فرع.` (R2 — gives a next action) |
| `launch_screen.dart:34` | `تعذر بدء التطبيق: $error` | `تعذر بدء التطبيق. تأكد من اتصالك بالإنترنت ثم أعد فتح التطبيق.` (R5) |
| `account_entry_screen.dart:120,185` | `تعذر تحميل قائمة الفروع: $error` | `تعذر تحميل قائمة الفروع. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.` (R5) |
| `contact_channels_screen.dart:298` | `الرسائل النصية (SMS)` | `الرسائل النصية` (R7 — **PO to confirm**) |
| `stage3_screen.dart:416` | `عدد الأطفال: ${ArabicNounAgreement.children.phrase(n)}` | `عدد الأطفال: 4` — drop the agreeing phrase entirely (see §4.2 Rule 1) |

#### On introducing ARB / `gen_l10n` — a recommendation, not a deferral

**Do not introduce ARB for the pilot.** Under this project's constraints: there is exactly one locale and it is pinned `[REPO app.dart:19-20]`; an ARB migration touches every screen file and pulls the full Flutter gate plus the 80 % coverage script; **106 tests assert on exact Arabic literals** `[REPO mobile/test]` and would all have to move at once; and BL-081's rename is already queued as a large mechanical diff.

**What is actually needed is reviewability by a native Arabic speaker who is not a developer.** Achieve that far more cheaply by extracting customer-facing strings into a small number of per-feature `const` string classes (e.g. `features/dataentry/stage5_strings.dart`). One reviewable file per screen, no new tooling, no generated files, trivially revertible, and it is the natural first half of an ARB migration if one ever happens.

**Flip condition — state it so this is a decision and not a habit:** adopt ARB the moment (a) a second locale is required, or (b) the bank wants to change copy without a code change and a rebuild. Neither is true for the pilot.

### 4.2 The agreement hazard — recommended approach

The project already has a working helper: `ArabicNounAgreement`, covering 1 / 2 / 3–10 / 11+ with `minutes`, `hours` and `children` constants, used at three sites `[REPO mobile/lib/core/text/arabic_noun_agreement.dart:11-61]`. **The recommendation is to extend and enforce it, not to replace it.**

**Rule 1 — Design the string so no number is interpolated into agreeing text.** This is the primary rule and it removes the hazard rather than managing it. Prefer `«عدد الأطفال: 4»` (label + bare number) over `«4 أطفال»`. Stage 3 currently does *both*: it prints the invariant prefix and then appends the agreeing phrase `[REPO stage3_screen.dart:416]`. **Delete the agreeing phrase from that line** and the whole class of bug leaves Stage 3.

**Rule 2 — Where a countable phrase is genuinely unavoidable, use the helper.** The block countdowns are the real cases: «يمكنك المحاولة مرة أخرى بعد ساعتين» `[REPO scan_blocked_view.dart:105-132]`, «…بعد دقيقتين» `[REPO channel_verification_screen.dart:269]`. **Never hand-write `'$n دقيقة'`** — that is the exact defect the helper's own doc comment records being found under review.

**Rule 3 — Do not adopt ICU plurals / the `intl` package for this.** Reasons under our constraints, not in general:
- ICU's Arabic categories (zero/one/two/few/many/other) select a *form*; they do not *decline*. The helper's doc comment already records the deliberate simplification for 11+ (accusative-indefinite → plain singular) — a distinction ICU cannot express either way `[REPO arabic_noun_agreement.dart:5-10]`.
- Adopting `intl` for plurals in practice means adopting ARB/`gen_l10n`, which §4.1 has just recommended against for this pilot.
- The helper is a 60-line pure-Dart class with no Spring/Flutter context, trivially unit-testable, and already exercised.

**Rule 4 — Fix the latent zero/negative defect in the helper.** `phrase(0)` falls through `n == 1`, `n == 2`, then hits `n <= 10` and returns `'0 أطفال'`, which is not idiomatic Arabic; a negative `n` behaves the same way `[REPO arabic_noun_agreement.dart:31-36 — traced by reading, not by running]`. Add an explicit `zero` form (or route 0 to invariant phrasing) and assert `n >= 0`. **This is a real defect found by reading the helper, and it is currently reachable from Stage 3's children field, whose validator permits 1–30 but whose live label rebuilds on every keystroke including an intermediate `0`** `[REPO stage3_screen.dart:186, 409-421]`.

**Rule 5 — Gender: gate, never guess.** Already the app's practice `[REPO stage3_screen.dart:338-346]`. Make it a written rule.

**Rule 6 — The tatweel/definite-article merge: enforce it with a test, not a rule.** Never build a word by concatenating «ال» onto an interpolated value, and never insert U+0640.

**Rule 7 — Add a "copy lint" test.** This is the most durable recommendation in this section, because it survives the reviewer leaving and it costs one test file. A pure-Dart test that walks `mobile/lib/**/*.dart`, extracts string literals, and **fails** on:
- U+0640 (tatweel) anywhere;
- the literal `--` inside an Arabic string;
- a `YYYY-MM-DD` or `yyyy-MM-dd` format literal;
- an Arabic-Indic or Extended Arabic-Indic digit (U+0660–0669, U+06F0–06F9) in any literal;
- `$error`, `$e`, `${e}` interpolated inside a string containing Arabic characters.

Every one of these corresponds to a defect actually found — four by S7-12, one by this read. The back office wants the same test in Vitest.

**Rule 8 — A reviewer checklist** to accompany the extracted string files (§4.1): is every label a noun? does every error say what to do next? does any string contain a Latin word other than a brand? is every number/date per §4.3? does any sentence assume the reader's gender before Stage 3?

### 4.3 Digits, dates, numbers, alignment — a single consistent rule

**RECOMMENDATION: Latin (ASCII) digits `0123456789` everywhere in this product — display and storage — with no exceptions. Arabic-Indic digits are accepted as *input* and silently transliterated, exactly as today.**

The reasoning is specific to this app, not a general preference:

1. **Every number the customer must compare against a physical object is printed in Latin digits.** The Sudanese passport's national number, the ID card, the account number on a card or statement. **Stage 9's entire purpose is that comparison** — customer.md requires the national number "shown first, alone and prominent… the entire screen hinges on the customer checking that one value against the document in their hand" `[REPO stage9_screen.dart:365-368, quoting customer.md]`. Rendering «٩٩٩…» beside a document reading `999…` forces a transliteration under stress. **This is decisive on its own.**
2. **The wire and storage layer is already ASCII-only by hard rule.** Phone numbers are E.164 everywhere; `PhoneNumberNormalizer` rejects non-ASCII digits at the boundary (BL-016) `[REPO PROJECT_PLAN.md:274-285]`; `ArabicDigitInputFormatter` exists to convert before sending `[REPO arabic_digit_input_formatter.dart:1-11]`. Displaying Arabic-Indic while storing ASCII would put **two representations of the same value in one product** — precisely the divergence the phone-number rule exists to prevent.
3. **The reference number is Latin-prefixed** (`FRU-000000001`) and is the customer's only artifact to quote at a branch `[REPO confirmation_screen.dart:129-135]`. Mixed digit systems around a Latin prefix is the worst of both.
4. **Regional precedent:** UAE federal guidance is that Latin ("keyboard English") numerals are used for numbers in **both** languages, and Western Arabic numerals are the commercial default across the GCC `[SOURCE search summary of UAE Federal Ministry visual-identity guidance — secondary, and the sentence was not confirmed on the primary page]`.
5. ~~**The Sudan-specific half is `[UNVERIFIED]`**~~ — **RESOLVED (topic 4): the PO confirms Latin digits.** The premise is no longer an assumption. The flip path is recorded in §0 D4 for completeness but is not expected to be taken.

**Dates.** `DD/MM/YYYY`, Latin digits, `/` separators. Times `HH:mm`, 24-hour. **Never ISO on any customer or operator screen.** Apply at the presentation boundary only — one helper per tier — and never to a stored or wire value. This is S7-12's own asymmetric-canonicalisation lesson applied to presentation: canonicalise what you send, present differently.

**Alignment and direction — one rule, three cases.**

- **Case A — Arabic prose and labels:** set nothing. No `textAlign`, no `textDirection`. Let the ambient RTL do it. Any explicit `TextAlign.left`/`right` in Arabic copy is a bug in waiting; use `TextAlign.start`/`end` if an override is truly needed.
- **Case B — a number or Latin word *inside* an Arabic sentence:** set nothing. The Unicode bidirectional algorithm places it, and both European and Arabic-Indic digits "read left-to-right in bidirectional text" and are absorbed into the surrounding run `[SOURCE https://w3c.github.io/i18n-drafts/articles/inline-bidi-markup/uba-basics.en]`. **Do not wrap it in `Directionality`** — that breaks the paragraph around it.
- **Case C — a standalone numeric/Latin value in its own widget** (account number, phone, OTP, monthly expenses, children count, national number, reference number, English name): force `textDirection: TextDirection.ltr`, add `textAlign: TextAlign.right` so it hangs off the RTL edge beside its Arabic label (§2.3c), and **isolate** it — wrap the value in U+2068 FSI … U+2069 PDI, or the equivalent — so a value *beginning* with a Latin letter cannot reorder against its neighbours.
  - The back office already does this correctly with `<bdi>` `[REPO ProfileDetailPage.tsx:62-64]`. **Mobile has no equivalent.** Recommend a tiny `LtrValue` widget in `mobile/lib/core/text/` used at all seven sites, so the rule lives in one widget rather than seven ad-hoc property lists.
  - **`confirmation_screen.dart:131-135` is the one site where the rule is currently not applied** to a Latin-prefixed value. Whether `FRU-000000001` visibly misorders under RTL is `[UNVERIFIED — check on device]`.

### 4.4 Font and legibility on the 5-inch low-end screen

**Current state:** no font bundled `[REPO pubspec.yaml:123-141]`, no `textTheme` `[REPO app.dart:28]`. Arabic renders in the device's own face. On a 2019 Huawei that is EMUI's Arabic font — so the pilot device and the review machine render **different products**, with different metrics and different line breaks. Bundling one font is the only way to make the pilot reproducible.

**Candidates, all SIL OFL 1.1 — no licence cost and no procurement step for the bank:**

| Font | For | Against |
|---|---|---|
| **IBM Plex Sans Arabic** | UI-designed; large x-height; **ships a designed-to-match Latin**; OFL `[SOURCE https://github.com/IBM/plex, https://fonts.google.com/specimen/IBM+Plex+Sans+Arabic]`; already the base of the Saudi DGA national design system `[SOURCE — secondary]` | Corporate-neutral; the bank may have its own view |
| **Noto Sans Arabic** | Naskh-based, designed for UI, very wide coverage, OFL; close to what Android already falls back to, so glyph shapes are familiar `[SOURCE https://en.wikipedia.org/wiki/Noto_fonts]` | Latin companion is a separate family, so the mixed-script pairing is less exact |
| **Noto Kufi Arabic** | The UAE federal design system's base `[SOURCE designsystem.gov.ae/guidelines/typography]` | **Kufi: geometric, low stroke contrast — materially worse at 14–16 sp on a low-DPI panel for a mixed-literacy audience.** Wordmark only |
| **Cairo / Tajawal / Almarai** | Popular, OFL | More display-flavoured; weaker at 12–14 sp |

**RECOMMENDATION — ACCEPTED BY THE PO (topic 3): IBM Plex Sans Arabic, two weights only (Regular 400 + SemiBold 600), subsetted _by Unicode range_.**

⚠ **Subset by range, not by usage.** The obvious approach — scan the app's string literals and keep only those characters — is wrong here and would ship a live defect. **Customer names come from the Uqudo scan and the Civil Registry, not from our source code**, so a name containing a character absent from every literal in the repo would render as missing-glyph boxes *in the customer's own name*, on the identity-review screen whose entire purpose is checking that name against a document. Keep the Arabic block (U+0600–06FF) and its supplements, Basic Latin, digits and punctuation wholesale, and set `fontFamilyFallback` to the platform font so anything still outside the range degrades to the device face rather than to tofu. `[INFERRED]`

Why, against *our* constraints:
- **(a)** It ships a matched Latin, and this UI mixes Arabic labels with Latin digits, `FRU-` numbers and English names on nearly every screen. A single family means no visible face-jump mid-line.
- **(b)** A UI face with a large x-height is what a ~720p 5-inch panel needs.
- **(c)** OFL — nothing for the bank's procurement to approve.
- **(d)** A regional government design system already stands behind it, which is a defensible answer to "why this font?".

**Fallback if the bank objects on brand grounds:** Noto Sans Arabic, same reasoning minus (d).

~~**Flip condition:**~~ **CLOSED (topic 3): the PO confirms the bank mandates no Arabic typeface.** The recommendation stands unconditionally; the fallback to Noto Sans Arabic is not needed.

**Cost.** Two static weights, subsetted: expect roughly **150–250 KB added to the APK** `[UNVERIFIED — measure the real subset; unsubsetted static Arabic faces commonly run 150–400 KB each]`. Explicitly **do not**:
- ship a **variable** font (the reference device is on the legacy renderer, and we need two fixed weights, not a continuum);
- ship four or more weights;
- ship an italic — Arabic has no italic, and a synthesised oblique is simply wrong.

**Legibility specifics for Arabic on this device** (all of these are currently unset, so all are live defects):

- **Body 16 sp minimum; never below 14 sp for any customer-facing text.** Material's `bodyMedium` default is 14 sp. Arabic's dots (i'jām) and the descenders of ج ح خ ع غ ق ي collapse at small sizes far sooner than Latin does.
- **`height: 1.6` for body paragraphs** (vs ~1.4 typical for Latin) — Arabic ascenders and descenders overlap between lines. The UAE system sets a 1.5 minimum for body `[SOURCE designsystem.gov.ae/guidelines/typography]`. In Flutter, `TextStyle.height` is a multiple of font size and must be set explicitly, because Material's defaults are tuned for Latin.
- **`letterSpacing: 0` across the entire text theme.** Material 3's defaults carry positive tracking (`bodyMedium` 0.25 and similar). **Positive tracking on a connected script visually breaks the joins between letters** and reads as tatweel padding — the exact artefact R6 bans in strings. This is the highest-impact and most easily-missed item in the whole typography section. `[INFERRED — confirm on device]`
- **No `FontWeight.w300` or lighter.** Thin Arabic on a low-DPI panel loses the dots.
- **Set `fontFamily` on `ThemeData`**, so it also reaches widgets built from `MaterialLocalizations`.
- **Test at system font scale 1.0 and 1.3** (§2.6-A7).

### 4.5 Boundary with BL-071 — stated explicitly

**This strand covers strings *we* author** in `mobile/lib/**` and `backoffice/src/**`.

**BL-071 is a different piece of work:** ~176 Arabic override strings for the **Uqudo SDK's own** document-scan and liveness screens, supplied as Android `values-<lang>` resources because "the Uqudo SDK ships **no `values-<lang>` folder at all**", requiring a device pass for truncation and RTL mirroring, and **deferred post-demo by product-owner decision** `[REPO BACKLOG.md BL-071; docs/road-to-production.md:337-338]`.

Three practical consequences worth naming so nobody trips over them at the pilot:

1. **At the pilot, the app will be Arabic everywhere except the two Uqudo screens, which will be English.** For a bank-staff pilot that is tolerable — **provided it is stated to the testers up front**, or it will be reported as a defect dozens of times.
2. **The font chosen in §4.4 does not reach the SDK's screens** — they render under their own Android theme and typeface. The pilot will therefore show a visible typeface change at Stage 8 and Stage 10. `[INFERRED]` Name it in the pilot brief.
3. **The only real coupling:** whoever writes BL-071's 176 strings should be given the same house style (§4.1) and glossary produced here, or the two halves of the customer's Arabic will disagree in register.

---

## 5. Prioritised build list for the follow-up session

Effort: XS ≤ 30 min · S ≤ 2 h · M ≤ half a day · L ≥ a day. Risk is regression risk, not effort risk.

> ## ⭐ FINAL BUILD ORDER — after topics 1–9
>
> **Every input is resolved. Nothing below is blocked.** The tables that follow carry the per-item detail and are annotated with what changed; this is the sequence the build session works in. Decisions are specified at **§0 D1–D9** — the build session implements them and does **not** re-derive wording, colours or typography.
>
> | # | Item | Decision ref | Effort | Why here |
> |---|---|---|---|---|
> | **1** | **Theme:** seed `#105097` + **pin `primary`**; bundle IBM Plex Sans Arabic (2 weights, **range**-subset); `letterSpacing: 0`, `height: 1.6`, body 16 sp; light-only; **`pageTransitionsTheme: FadeUpwardsPageTransitionsBuilder`** | D2, D3, D6 | M | Restyles every screen at once **and** fixes the live RTL transition defect, in one file. Best ratio in the plan. |
> | **2** | **Apply the approved copy** — «الخادم» ×13, interpolated exceptions ×10, three one-offs; **delete `/reference-demo`** | D8.2–D8.4 | M | Specified verbatim and already reviewed. Largest wording defect in the app. |
> | **3** | **WhatsApp row:** deselect default, **visible but disabled** + «غير متاحة حالياً»; live note built behind the flag | D9.1 | S | Worst first impression in the pilot; now fixed without a false promise. |
> | **4** | **App identity:** `android:label` → string resource, adaptive icon (emblem only), launch drawable `#105097`, `values-v31/`, `values-night` = `values` | D1, D2, D7 | S | First frame the customer ever sees. Also discharges part of BL-082(4). |
> | **5** | **Brand launch screen:** logo → «الفرنسي بياناتي» → «لؤلؤة المصارف» → «تحديث بيانات حسابك»; continuous `#105097`; no minimum display | D1, D2, D7 | S | Replaces a bare spinner on a blank white screen. |
> | **6** | **Focus flow:** `textInputAction` runs; `done` never `next` across OTP rows; `textAlign: right` on the 5 islands; unfocus at 6 digits, no auto-submit; **per-field `errorText`** + focus + `ensureVisible`; no autofocus; **no custom traversal policy** | D5 | M | PO item 3. |
> | **7** | **Dates → `DD/MM/YYYY`**, both tiers, at the presentation boundary only | D4 | S | |
> | **8** | **`_field` layout fix** (label above value) — resolves the Latin misalignment *and* the two-line address | D4 | S | |
> | **9** | **Back-office wording** — status-history sentence, «واجهة الموظفين», em-dash | D8.5 | S | Explicit PO ask. |
> | **10** | **`LtrValue` widget** at all 7 islands + **copy-lint test** (tatweel, `--`, ISO literal, Arabic-Indic digit, `$error`-in-Arabic) | D4, D8.6 | M | The durability item — locks in a state D8.1 proved is currently clean. |
> | **11** | **`ArabicNounAgreement`:** add `zero`, assert `n ≥ 0`; drop Stage 3's agreeing phrase | D8.4 | XS | Closes a live latent defect and the per-keystroke rebuild. |
> | **12** | **Text-scale pass 1.0× / 1.3×**; **`cacheWidth`** on every `Image.memory`; **signature preview aspect ratio** | D9.4 | S | |
> | **13** | **Stage 12 summary** — read-only, no edit links, **data only, no images** | D9.3 | M | Privacy *and* performance win. |
> | **14** | **Section progress** — «بياناتك» · «عنوانك» · «إثبات هويتك» · «الإرسال» | D9.2 | M | **Cut this first if schedule tightens** — largest surface. |
> | **15** | **Logo on the confirmation screen** | D2 | XS | |
> | **16** | **The data notice** — one line at account entry, three sentences before identity capture at Stage 7 | D10 | S | Required by BL-082's Play declaration and by §3.5's pattern; states facts, promises no rights |
>
> **Deleted, with reasons recorded:** old item 13 (measure transitions — D6), old item 18 (string extraction — D8.6), old item 19 (autofill hint — D9.4).
>
> **Standing gate cost:** ~23 Arabic string changes against **106 `find.text()` assertions across 16 test files**. Expected work, not a surprise.

### High value / low risk — do first

| # | Item | Tier / files | Effort | Risk | Value |
|---|---|---|---|---|---|
| 1 | **Theme (D2/D3/D6):** seed from `#105097` **and pin `primary` to it**; bundle IBM Plex Sans Arabic (2 weights, range-subset); `textTheme` with `letterSpacing: 0`, `height: 1.6`, body 16 sp; light-only; **`pageTransitionsTheme: FadeUpwardsPageTransitionsBuilder` for Android** | `mobile/lib/core/app.dart`, `mobile/pubspec.yaml`, `mobile/assets/fonts/` | M | Low | **Very high** — restyles every screen at once, **and fixes the live RTL transition defect (PO item 4) in the same file** |
| 2 | **WhatsApp at Stage 1b (D9.1):** default deselected; row **visible but disabled** for the SMS-only pilot with «غير متاحة حالياً» note; the live sender-number note built behind the same flag with a placeholder number constant | `mobile/lib/features/entry/contact_channels_screen.dart:29,290-300` | S | Low | **Very high** — removes the worst first impression, with no false promise |
| 3 | **App identity:** `android:label` → `@string/app_name`, `values/strings.xml`, adaptive icon (`mipmap-anydpi-v26`), `launch_background.xml` + `values-v31/styles.xml` | `mobile/android/app/src/main/{AndroidManifest.xml,res/**}` | S | Low | High — also discharges part of BL-082(4) |
| 4 | **Brand launch screen** (logo + «الفرنسي بياناتي» + one sentence) replacing the bare spinner | `mobile/lib/features/entry/launch_screen.dart:28-39` | S | Low | High |
| 5 | **Apply the approved copy (D8) — the strings are specified verbatim, do not re-derive.** «الخادم» × **13** (D8.2); interpolated exceptions × **10** (D8.3); the three one-offs (D8.4); **delete the `/reference-demo` route** rather than translate its two strings | 13 + 10 sites listed in D8; `app_router.dart:127-130` | M | Low | **Very high** — UX **and** hygiene; the largest wording defect in the app |
| 6 | **Focus flow (D5):** `textInputAction` across every data-entry run (`done`, never `next`, between the three OTP rows); `textAlign: TextAlign.right` on the five LTR islands; unfocus at 6 OTP digits with **no** auto-submit; **per-field `errorText`** + focus move + `ensureVisible`, bottom message demoted to a summary; no autofocus; **no custom traversal policy** | `stage3/4/5/6/7_screen.dart`, `contact_channels_screen.dart`, `channel_verification_screen.dart`, `account_entry_screen.dart` | M | Low–Med | High — **PO item 3** |
| 7 | **Dates → `DD/MM/YYYY`**, both tiers | `mobile/lib/core/text/` new helper + `stage9_screen.dart:403`; `backoffice/.../ProfileDetailPage.tsx:66-68` | S | Low | Med–High |
| 8 | **`_field` layout fix** (label above value) — resolves the Latin misalignment *and* the two-line address | `mobile/.../stage9_screen.dart:457-468` | S | Low | Med–High |
| 9 | **Back-office wording (D8.5), approved verbatim:** status-history sentence + first-row form, header → `تحديث بيانات العملاء — واجهة الموظفين`, date → `DD/MM/YYYY` | `ProfileDetailPage.tsx:66-68,189-194`, `AppShell.tsx:39` | S | Low | Med — **explicit PO ask** |
| 10 | **`LtrValue` widget** + apply at all seven islands; **copy-lint test** (tatweel, `--`, ISO literal, Arabic-Indic digit, `$error`-in-Arabic) | `mobile/lib/core/text/`, `mobile/test/` | M | Low | Med–High — **durability** |
| 11 | **`ArabicNounAgreement`:** add `zero`, assert `n >= 0`; drop the agreeing phrase from Stage 3 | `arabic_noun_agreement.dart`, `stage3_screen.dart:409-421` | XS | Low | Med — closes a live latent defect |
| 12 | **Text-scale pass at 1.0× / 1.3×** on the reference device; fix overflows | across screens | S | Low | Med |

### Nice to have — defer

| # | Item | Tier / files | Effort | Risk | Value |
|---|---|---|---|---|---|
| ~~13~~ | ~~**Measure the current page transition**…~~ **DELETED at topic 6.** The PO declined device measurement; the SDK trace showed the shipped default is RTL-wrong, so the one-line `FadeUpwardsPageTransitionsBuilder` fix moved into **item 1** as a defect fix. No measurement, no overlay task, no bespoke shared-axis. See §0 D6. | — | — | — | — |
| 14 | **Section progress indicator (D9.2) — CONFIRMED for the pilot.** «بياناتك» · «عنوانك» · «إثبات هويتك» · «الإرسال»; section-labelled, never stage-numbered | every screen's `Scaffold` | M | Med (**largest surface in the plan** — cut this first if schedule tightens) | High — the strongest anxiety reducer in a 12-stage mandated flow |
| 15 | **Stage 12 "check your answers" summary (D9.3) — APPROVED.** Read-only listing, **no edit links**, and **data only — no photos or document images redisplayed**; artifacts acknowledged textually («تم مسح وثيقة الهوية», «تم إرفاق التوقيع») | `stage12_screen.dart` | M | Med | High — privacy *and* performance win, not only completeness |
| 16 | **Signature preview aspect ratio** + `cacheWidth` on every `Image.memory` | `stage11_screen.dart:271-297`, `stage9_screen.dart:413` | S | Low | Med |
| 17 | **Logo in AppBar** on launch and confirmation only | 2 screens | XS | Low | Low–Med |
| ~~18~~ | ~~**Extract customer strings into per-feature const classes**~~ — **DROPPED at topic 8.** Its only stated benefit was making the copy reviewable by a non-developer; the PO reviewed it in place and approved D8, so the benefit is already banked and the cost (L effort, ~106 assertions) buys nothing. Revisit only if ARB's flip conditions trigger. | — | — | — | — |
| ~~19~~ | ~~**`AutofillHints.oneTimeCode`**~~ — **SKIPPED at topic 9 (D9.4).** Needs the SMS sender format to match, which is unsettled; it would be decoration that may never fire. Revisit when the SMS provider is fixed — couple to BL-080. | — | — | — | — |

### Dependencies and sequencing

- ~~**Logo asset from the PO**~~ — **DISCHARGED (topic 2).** Master at `docs/brand/sfb-logo-master-2048.jpg`; all four variants derive from it (§2.2). Items 1, 3, 4, 17 unblocked.
- ~~**Brand colour hex values**~~ — **DISCHARGED (topic 2).** `#105097`, measured from the master. Items 1, 3, 4 unblocked.
- ~~**Font choice**~~ — **DISCHARGED (topic 3).** The PO confirms no mandated typeface; IBM Plex Sans Arabic is adopted (OFL, no procurement step). Item 1 is fully unblocked. The one remaining care point is subsetting **by Unicode range, not by scanned strings** (§4.4).
- ~~**A native Sudanese Arabic reviewer**~~ — **DISCHARGED (topic 8).** The PO *is* the reviewer and reviewed the full rewrite set in session on 2026-09-07; the approved strings are specified verbatim at §0 D8. §3.7's argument — that COGA's plain-language principle transfers but its measurement does not, so judgement must come from a person — is satisfied rather than deferred. The build session implements D8 as written and does **not** re-derive wording.
- **The reference device — scope narrowed at topic 6.** The PO has ruled out performance measurement on it and no longer treats it as the binding floor, and item 13 is deleted, so **motion no longer depends on it at all**. What still genuinely wants a look on a low-end 5-inch screen: the typography settings (item 1 — `letterSpacing: 0`, `height: 1.6`, 16 sp against IBM Plex Sans Arabic), the text-scale pass (item 12), and the two `[UNVERIFIED]` visual questions (the LTR islands' label/value split, and whether `FRU-000000001` misorders). None of these is a frame-rate question; all can be judged from a screenshot.
- ~~**R-042 / the SMS-only decision**~~ — **DISCHARGED (topic 9).** Row visible but disabled for the pilot, default deselected, no false promise, no placeholder number in front of a customer. See §0 D9.1.
- **The bank's WhatsApp Business number** — needed only to switch on D9.1's live note *after* the pilot. Does not block anything now; the placeholder constant ships unused behind the flag.
- **BL-079 / BL-081 sequencing — RESOLVED (topic 1).** `road-to-production` §3.4 said the polish depends on §2.1 (the backend Java rename). **The PO has confirmed it does not bind the mobile tier**; Android resources survive an `applicationId` change. The mobile branding items below are unblocked. Two plan-file amendments fall out: `road-to-production.md` §3.4's dependency scope, and BL-081's bank name.
- **Gate cost, stated once:** every mobile item pulls `fvm flutter test`, `fvm flutter analyze` and the 80 % coverage script. Items 5, 7, 9 and especially 18 change Arabic literals that **106 assertions across 16 test files** match on `[REPO mobile/test]`. Budget for it.

---

## 6. Open questions for the PO

Decisions that are the PO's or the bank's. None is settled here.

1. ~~**Launcher label**~~ — **RESOLVED (topic 1):** «بياناتي» alone; full lockup on splash, brand line and store listing. See §0 D1.
2. ~~**The bank's Arabic name**~~ — **RESOLVED (topic 1):** «البنك السوداني الفرنسي» / "Sudanese French Bank", slogan «لؤلؤة المصارف». BL-081's «بنك السودان الفرنسي» is corrected. See §0 D1.
2a. ~~**The slogan's placement**~~ — **RESOLVED (topic 7):** splash-only, subordinate to the app name. The PO confirmed the splash build including the four-line stack (§2.5) at topic 7 without contradicting the placement recommendation raised at topic 1. See §0 D1.5, §2.1(d).
3. ~~**Brand colours and typeface**~~ — **FULLY RESOLVED.** Colours at topic 2 (`#105097`); typeface at topic 3 (the bank mandates none; IBM Plex Sans Arabic adopted). See §0 D2, D3.
4. ~~**Logo asset deliverables**~~ — **RESOLVED (topic 2):** master supplied and stored at `docs/brand/sfb-logo-master-2048.jpg`; all variants derive from it. See §0 D2.
5. ~~**WhatsApp at Stage 1b**~~ — **RESOLVED (topic 9):** neither. Row **visible but disabled** for the pilot with a "not yet available" note, default deselected; the PO's sender-number note built behind the same flag for when WhatsApp goes live. See §0 D9.1.
6. ~~**Digits**~~ — **RESOLVED (topic 4):** Latin digits everywhere, `DD/MM/YYYY` dates on both tiers. See §0 D4.
7. ~~**«الرسائل النصية (SMS)»**~~ — **RESOLVED (topic 8):** drop the parenthetical. The PO's reasoning: the term is universally understood, so the gloss adds nothing. See §0 D8.4.
8. ~~**«الواجهة الخلفية»**~~ — **RESOLVED (topic 8):** SFB staff call it **«واجهة الموظفين»**. Header becomes `تحديث بيانات العملاء — واجهة الموظفين`. See §0 D8.5.
9. ~~**Dark mode**~~ — **RESOLVED (topic 3):** light theme only for the pilot. See §0 D3.
10. ~~**Who reviews the Arabic, and when?**~~ — **RESOLVED (topic 8): the PO, and it is done.** The full rewrite set was reviewed and approved in session on 2026-09-07 and is specified at §0 D8. No longer a dependency.
11. ~~**Are pilot testers told up front that the Uqudo scan and liveness screens are English**~~ — **RESOLVED (topic 10): yes, testers are told.** The pilot brief must state that the two Uqudo screens are English (BL-071 deferred) and that the typeface visibly changes there (§4.5). Without it, this is reported as a defect dozens of times.
12. ~~**Which device does the pilot run on**~~ — **RESOLVED (topic 6):** the PO has raised the floor. The 2019 Huawei is no longer the binding constraint and may lose transition elegance provided it works; no device performance measurement will be done. Item 13 is deleted. See §0 D6.
13. ~~**The "what happens to your data" wording**~~ — **RESOLVED (topic 10):** drafted to best practice and adopted, as a **layered just-in-time notice** in two placements. See §0 D10. **One residual bank question**, which blocks nothing: whether customers may request data deletion and by what route — Play's Data-safety form asks it directly and it cannot be inferred (D10.5). Customer copy still states **what** the app does and never **why the bank requires it** (D7).

## 7. Sources

**Official documentation**
- Flutter — Impeller rendering engine (Android API 29+ default; legacy OpenGL fallback below): https://docs.flutter.dev/perf/impeller
- Flutter — Performance best practices (Opacity, saveLayer, clipping costs): https://docs.flutter.dev/perf/best-practices
- Flutter — Improving rendering performance / shader jank: https://docs.flutter.dev/perf/shader
- Flutter — Adding a splash screen to an Android app (incl. the Android 12 two-resource warning): https://docs.flutter.dev/platform-integration/android/splash-screen
- Flutter API — `ReadingOrderTraversalPolicy`: https://api.flutter.dev/flutter/widgets/ReadingOrderTraversalPolicy-class.html
- Flutter API — `OrderedTraversalPolicy`: https://api.flutter.dev/flutter/widgets/OrderedTraversalPolicy-class.html
- Material Design — Bidirectionality (RTL mirroring; what is and is not mirrored): https://material.io/archive/guidelines/usability/bidirectionality.html
- Material Design 3 — Transitions: https://m3.material.io/styles/motion/transitions
- Material Design 3 — Easing and duration tokens: https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
- UAE Design System — Mobile applications guidelines: https://designsystem.gov.ae/guidelines/mobile-applications
- UAE Design System — Typography guidelines: https://designsystem.gov.ae/guidelines/typography
- W3C WAI — Use Clear Words (COGA pattern): https://www.w3.org/WAI/WCAG2/supplemental/patterns/o3p01-clear-words/
- W3C WAI — Understanding SC 3.1.5 Reading Level: https://www.w3.org/WAI/WCAG22/Understanding/reading-level.html
- W3C i18n — Unicode Bidirectional Algorithm basics: https://w3c.github.io/i18n-drafts/articles/inline-bidi-markup/uba-basics.en
- IBM Plex (OFL, source and formats): https://github.com/IBM/plex · https://fonts.google.com/specimen/IBM+Plex+Sans+Arabic
- Google Design — Implementing Motion (Material transition durations): https://medium.com/google-design/implementing-motion-9f2839002016
- GDS Design Notes — One thing per page: https://designnotes.blog.gov.uk/2015/07/03/one-thing-per-page/
- UAE Government — The UAE PASS app: https://u.ae/en/about-the-uae/digital-uae/digital-transformation/platforms-and-apps/the-uae-pass-app
- Sudanese French Bank (Arabic name, brand surface): https://www.sfbank-sd.com

**Secondary sources, named and marked as such**
- Revolut onboarding UX analysis: https://craftinnovations.global/revolut-onboarding-flow-analysis/
- Revolut design-system analysis: https://getdesign.md/revolut/design-md
- Saudi DGA component library (IBM Plex Sans Arabic, RTL): https://dga-registry.vercel.app/
- Saudi national design system case study: https://www.ijjad.com/case-study-saudi-national-design-system
- Digital identity in the GCC (UAE Pass / Nafath / Absher overview): https://makitsol.com/digital-identity-in-gcc-how-uae-pass-is-rewriting-access/
- Noto fonts (licensing and coverage): https://en.wikipedia.org/wiki/Noto_fonts

**Repository and SDK (local disk)** — `CLAUDE.md`; `PROJECT_PLAN.md`; `BACKLOG.md` (BL-071, BL-076, BL-079, BL-080, BL-081, BL-082); `docs/road-to-production.md`; `docs/sessions/2026-09-07-s7-12-acceptance-walk.md`; `docs/components/mobile-packages.md`; `mobile/lib/**`; `mobile/pubspec.yaml`; `mobile/android/app/src/main/**`; `mobile/test/**`; `backoffice/src/profiles/ProfileDetailPage.tsx`; `backoffice/src/layout/AppShell.tsx`; `C:\Users\DELL\fvm\versions\3.47.0\packages\flutter\lib\src\widgets\{transitions,editable_text,focus_traversal}.dart`.

---

## What I could not determine

Stated explicitly rather than filled with a plausible guess.

- ~~**The bank's brand colours**~~ — **RESOLVED at topic 2:** `#105097`, measured from the PO-supplied master. **Mandated typeface — RESOLVED at topic 3: the bank has none.** Worth recording *how* this resolved: the bank's public web assets gave three mutually inconsistent blues (≈`#000060` in three logo PNGs, `#211E57` in their traced SVG, `#2F5AAE` as the live site accent), and none matched the master. Scraped brand colour is not brand colour.
- ~~**Whether the current default page transition actually stutters on the API-28 reference device.**~~ — **MOOT after topic 6.** The PO declined measurement, and the SDK trace made the question irrelevant: the shipped default is *directionally wrong* for RTL regardless of its frame rate, and the replacement (`FadeUpwardsPageTransitionsBuilder`) is unambiguously cheaper than it — no snapshotting, shorter, single vertical translate plus fade. Nothing about the decision now turns on a frame measurement.
- ~~**Whether Sudanese retail customers expect Latin or Arabic-Indic digits.**~~ — **RESOLVED at topic 4: the PO confirms Latin.** §4.3's argument from document-comparison and from the existing ASCII-only storage rule was repo-grounded; the market expectation that it rested on is now a PO decision rather than an inference.
- **The real subsetted font file size.** The 150–250 KB figure is an estimate to be measured, not a measurement.
- **Whether `FRU-000000001` visibly misorders** at `confirmation_screen.dart:131-135` under RTL, and whether the label/value split at the LTR islands looks as bad on the device as it reads in the source. Both need a device.
- **Launcher label truncation width** on the reference device's EMUI launcher.
- **Any UI specific of UAE PASS or Nafath.** I did not install or inspect either; §3.5 rests on official descriptions and secondary analyses.
- **Revolut's own design documentation** — not reachable; §3.1 rests on secondary UX analyses.
- **Whether Material 3's exact duration figures** (300 ms / 35 ms overlap) are current: the direct fetch of m3.material.io returned no body, so those numbers come from Google's implementation article and a search summary. Treat them as indicative.

## Risks if this plan is wrong

| Risk | What breaks | Cost to reverse |
|---|---|---|
| ~~**The transition work is done before it is measured**~~ — **retired at topic 6.** The residual risk is now the opposite and much smaller: `FadeUpwardsPageTransitionsBuilder` is a *vertical* motion where the shipped default is horizontal, so the change is noticeable even though it is correct. | Someone reads the new vertical motion as a regression rather than as the RTL fix it is. | XS — one line, and the justification is recorded at §0 D6 with SDK line references. |
| **The font is chosen before the bank's brand guidelines are read** | The whole type scale, line heights and the theme are re-tuned for a second face. | M — one file, but re-testing every screen at two text scales. |
| **`letterSpacing: 0` / `height: 1.6` are wrong for the chosen face** | Arabic looks either cramped or padded on the reference device. | XS — three numbers in one file; but only findable on the device. |
| **The digit rule flips to Arabic-Indic for display** | Every `LtrValue` site and the date helper need a digit-shaping layer; the storage rule is unaffected, so the split between stored and displayed value becomes real and must be tested. | M, and it re-opens a question CLAUDE.md's E.164 rule deliberately closed on the wire. Reversible, but do not do it by halves. |
| **The wording pass runs before a native reviewer is available** | Copy is changed twice, and 106 test assertions move twice. | M–L. This is the strongest argument for treating the reviewer as a blocking dependency. |
| **BL-081's rename lands after the branded assets** | Only `MainActivity.kt`'s package path moves; Android resources are unaffected. **Low risk — this is the finding in §2.1**, but if I am wrong about it, the cost is re-adding a handful of resource files. | XS if wrong. |
| **`textInputAction: next` behaves differently than the SDK read suggests** | The focus item silently does nothing. Verified at `[SDK editable_text.dart:3948-3949]`, so low, but it is a behaviour confirmed by reading rather than by running. | XS — falls back to explicit `FocusNode` + `onSubmitted`. |

## Card updates

There is **no `docs/components/` card for mobile UI, theming or Arabic copy** — the closest is `docs/components/mobile-packages.md`, which owns the RTL rule. Two updates to that card, plus a new card proposal.

### Correction to `docs/components/mobile-packages.md` §"RTL — the four LTR islands" (lines 120-125)

Replace the section with:

```
## RTL — the LTR islands
Flutter's framework RTL handles layout. Values that must be forced BACK to
`TextDirection.ltr` fall in two classes.

INPUT (5) — each also normalises Arabic-Indic digits (٠-٩ U+0660-0669, ۰-۹ U+06F0-06F9)
to ASCII BEFORE `FilteringTextInputFormatter.digitsOnly`, which filters on `[0-9]` and
silently discards them. One `ArabicDigitInputFormatter`, used at all five:
  account number (1a)      [OBSERVED account_entry_screen.dart:130-137]
  phone (1b)               [OBSERVED contact_channels_screen.dart:263-271]
  OTP code (2, ×3 rows)    [OBSERVED channel_verification_screen.dart:482-495]
  monthly expenses (4)     [OBSERVED stage4_screen.dart:226-237]
  children count (3)       [OBSERVED stage3_screen.dart:399-408]  <- added S5-05; the
                           card previously said "four islands" and was one short.

DISPLAY (2 known) — same direction rule, no formatter:
  national number (9)      [OBSERVED stage9_screen.dart:377-384]
  reference number (12)    [OBSERVED confirmation_screen.dart:131-135] — currently carries
                           NO textDirection. `FRU-000000001` begins with a Latin run;
                           whether it misorders under RTL is [UNVERIFIED — device check].

All five input islands set `textDirection: ltr` and NO `textAlign`, so the Arabic
`labelText` renders at the right edge while the digits render at the left edge of the
same field. Add `textAlign: TextAlign.right`. [INFERRED — device confirmation owed]

Isolate, don't just direct: a value that BEGINS with a Latin character needs FSI/PDI
(U+2068 … U+2069) or an equivalent isolate, not only a direction. The back office already
does this with `<bdi>` [OBSERVED backoffice/src/profiles/ProfileDetailPage.tsx:62-64];
mobile has no equivalent. One `LtrValue` widget in `core/text/`, used at all seven sites.
```

### Addition to the same card's "Open items"

```
- [ ] Bundle an Arabic font (none today — `fonts:` block is commented out,
      pubspec.yaml:123-141) and set a `textTheme` with `letterSpacing: 0`, `height: 1.6`,
      body 16 sp. Material's Latin-tuned positive tracking visually breaks the joins of a
      connected script. [INFERRED — device confirmation owed]
- [ ] `textInputAction` is set on ZERO TextFields app-wide; the app holds exactly one
      FocusNode (reference_item_picker.dart:68). Traversal is already RTL-correct —
      `ReadingOrderTraversalPolicy` is the default [SDK focus_traversal.dart:63,458,2049]
      and Directionality is rtl app-wide [app.dart:26-27] — it is simply never invoked.
      Do NOT install `OrderedTraversalPolicy`: Stage 3 mounts fields conditionally.
- [ ] PAGE TRANSITIONS — the app must set `pageTransitionsTheme` explicitly. Leaving it
      unset resolves to `PredictiveBackPageTransitionsBuilder`
      [SDK material/page_transitions_theme.dart:766], which — with no manifest opt-in and
      on anything below Android 13 — falls back to `FadeForwardsPageTransitionsBuilder`
      [SDK predictive_back_page_transitions_builder.dart:95-97]: a 450 ms horizontal
      ±0.25 slide with LTR semantics hardcoded in its own comments
      [SDK page_transitions_theme.dart:470,490-499]. NO built-in Material page transition
      mirrors for RTL — `grep -c textDirection` over both transition files returns 0.
      DECIDED (S?-?? topic 6): `FadeUpwardsPageTransitionsBuilder`, whose tween is
      `Offset(0.0,0.25)` [SDK widgets/page_transitions_builder.dart:103-108] — x is zero,
      so it is direction-neutral by construction.
- [ ] Any BESPOKE transition must additionally pass `textDirection:
      Directionality.of(context)` to `SlideTransition`, which mirrors the x offset for RTL
      [SDK transitions.dart:197,205-219,237]. A hardcoded `Offset(1,0)` slides forward
      navigation the wrong way and every test still passes.
- [ ] Reference device is Android 9 = API 28, BELOW Impeller's API 29 threshold, so it
      runs the legacy renderer and pays first-run shader compilation
      [DOC docs.flutter.dev/perf/impeller]. A newer test phone will not reproduce it.
```

### New card proposed: `docs/components/arabic-copy-and-typography.md`

Not drafted in full here (this was not an SDK investigation), but it should own, as a single home across both tiers: the eight house rules of §4.1; the digit/date/alignment rule of §4.3; the `ArabicNounAgreement` usage rules and its zero-form gap; the font and metric decisions of §4.4; the copy-lint test's rule list; the reviewer checklist; and an explicit boundary statement against BL-071.

---

## Noticed in passing

Not expanded, not acted on, listed once so they are not lost.

1. **`stage3_screen.dart:67-82` calls `setState(() {})` on every keystroke of five controllers**, rebuilding the whole screen per character. The comment explains it was needed for the live agreement label — which §4.2 Rule 1 recommends deleting. Once that label goes, four of the five listeners may only need `_saveDraft()`. A UI-thread cost on a 2 GB device, and more likely to be *felt* than any transition.
2. **`ArabicNounAgreement.phrase(0)` returns `'0 أطفال'`** and negative values fall into the same branch. Covered as §4.2 Rule 4, but it is a code defect, not only a style matter.
3. **`mobile/pubspec.yaml:2` still reads `description: "A new Flutter project."`** — harmless, but it is the same class of unfinished-template signal as `android:label="mobile"`.
4. **`/reference-demo` is still a mounted route** `[REPO app_router.dart:127-130]`, reachable by deep link if one ever exists. Noted at S5-01 as deliberate; worth a second look before a store release.
5. **`ThemeData` has no `dark` counterpart and no `themeMode`.** If the reference device's system dark mode is on, `values-night/styles.xml` gives a dark *window* background behind a light Flutter UI — a possible flash at launch. `[UNVERIFIED]`
