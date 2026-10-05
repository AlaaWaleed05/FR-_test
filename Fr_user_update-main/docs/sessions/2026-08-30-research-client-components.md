# Research report — Assemble-vs-build: Flutter packages (mobile) and Ant Design components (back office)

**Date:** 2026-08-30 · **Author:** `@agent-researcher` · **Status:** research only, nothing implemented

---

## 1. Question

Which existing, well-maintained components should this project assemble rather than build — Flutter packages for `mobile/`, Ant Design components for `backoffice/` — and which parts genuinely have to be bespoke? Report before either client tier is built, because some answers change what `backend/` must expose.

### Reading of the task I pursued

The task is unambiguous in scope but ambiguous in one place: *"which parts genuinely have to be bespoke"* could mean "which parts have no package at all" or "which parts should be bespoke even though a package exists". **I pursued the second, stricter reading**, because it is the one that changes decisions: several needs here have a popular package that would technically work and should still not be used.

Readings I did **not** pursue: I did not evaluate back-office routing, data fetching or auth-state libraries. `backoffice/` has none of these chosen yet and nothing in PROJECT_PLAN.md settles them, but the task scopes Part B to "which Ant Design components map to which operator screen". They are flagged in §11 as work the calling session must schedule, not evaluated here.

### Files read before investigating

`PROJECT_PLAN.md` (full), `EXECUTION_PLAN.md`, `RISKS.md`, `docs/journeys/customer.md` (full), `docs/journeys/operator.md` (full), `docs/components/uqudo-sdk.md`, `docs/components/persistence.md`, `mobile/pubspec.yaml`, `backoffice/package.json`, `backoffice/src/main.tsx`, `backoffice/src/App.tsx`, `backend/src/main/resources/db/migration/V0011__ref_registry_and_fold.sql`, `V0016__seed_admin_division.sql`, `V0022__seed_country.sql`, and `../FIB/mobile/pubspec.yaml` (reference, read-only).

---

## 2. Answer

**Mobile: assemble four packages, build the rest.** Take `signature` (draw pad), `phone_numbers_parser` (validation core only, inside a bespoke field), `image_picker` (camera + gallery), `file_picker` (PDF), and `image` (pure-Dart downscale). Reject `pinput`, `country_picker`, `dropdown_search`, `flutter_form_builder` and `flutter_image_compress` — each for a specific, named reason, not on taste. The four recommended packages add **zero new CocoaPods entries beyond `image_picker` and `file_picker`, and no native crypto anywhere**, so none of them touches R-025 / OpenSSL-Universal 3.3.3001. The Flutter package layer is worth roughly a **1.5×** saving — real, but small, because the journey's genuinely hard parts (stage machine, offline reconcile, RTL layout, Arabic picker) have no package and never will.

**Back office: Ant Design 6.6.1 covers substantially more, and antd v6 specifically fixed the RTL defect that would have hurt most.** `Table`, `Descriptions`, `Timeline`, `Image`, `Modal`+`Form`+`Select`, `Tag`, `Result`, `Layout` map cleanly onto the operator screens. antd v6's bundled `@rc-component/table` normalises `fixed: 'left'|'right'` to logical `start`/`end` and emits `insetInlineStart`/`insetInlineEnd` — the long-standing v5 RTL fixed-column inversion is gone [OBSERVED, §5.1]. The back office is worth roughly a **3–4×** saving. **Ant Design should do no filtering and no sorting.** Every filter, every sort and the whole search surface must be server-driven, because the backend already owns `ref.ar_fold()` and `sort_ordinal`, and a browser `localeCompare('ar')` would silently disagree with them.

**One package beyond Ant Design is needed, and it should not be a JavaScript one.** Generate XLSX and CSV **server-side in `backend/` with Apache POI SXSSF**, not in the browser. The npm XLSX ecosystem is in poor shape (`xlsx` frozen at 0.18.5 on npm; `exceljs` last released 2024-10-19 with 659 open issues and 143 open PRs), and more decisively: the profile list is server-paginated, so the browser never holds the 10,000 rows an export needs, and operator.md requires the export audit event to record operator, filters, row count and fields — which is server-side truth. Client-side generation would mean shipping 10,000 rows of PII to a browser purely to write a file.

**Five things constrain `backend/` and are cheap to change now, expensive later.** They are listed in full in §9. The two largest: (a) `ref.reference_item.search_en` is declared in V0011 and **populated by no seed migration** — stage 4's mandatory "substring matching across Arabic *and English* simultaneously" cannot work today; (b) the profile API must return a per-operator `canApprove` boolean, because the UI cannot compute the four-eyes rule without being handed the status history's actor identities, and handing those over just to grey out a button is a worse outcome than adding the field.

---

## 3. Evidence — Part A: Flutter packages for `mobile/`

### 3.0 The filters, applied in order

Every candidate below was assessed against, in this order: **iOS support and how it was established** → **native/CocoaPods surface (R-003, R-025)** → **RTL behaviour under `Directionality.rtl`** → **licence** → **maintenance** → **fit**.

A note on how I established iOS support, because the CLAUDE.md rule is specific about this. A pub.dev "iOS" platform chip is weak evidence — it means the package declared support, not that anyone built it. **The strong evidence is structural: a package with no `ios/` directory and no podspec has no iOS-specific code to break.** Where a package is pure Dart I say so and cite its `pubspec.yaml` dependency list; where it has native code I cite the podspec and the declared deployment target. iOS is not compiled on this project (S1-03 ⚠️), so structural evidence is the best available and I have not dressed it up as more.

---

### 3.1 Need 1 — Signature capture (stage 11, mandatory)

Journey requirement: draw on screen with clear-and-retry, **or** upload an image; customer chooses; both produce a file. `[POLICY: signature file size and format limits]` is still unset.

| Candidate | Version | Released | Licence | Native code? | iOS evidence | Health |
|---|---|---|---|---|---|---|
| **`signature`** (4Q s.r.o.) | 6.4.0 | ≈2026-07-28 | MIT | **None** | `pubspec.yaml` declares only `flutter` + `flutter_svg` — no plugin platforms block, no `ios/`, no podspec [OBSERVED] | 2 open issues, 0 open PRs, 154 commits [OBSERVED GitHub] |
| `hand_signature` (basecontrol.dev) | 3.1.0+2 | ≈2025-07 (13 mo) | MIT | None ("pure Dart/Flutter … all platforms") | pub.dev platforms + stated pure Dart [DOC] | Verified publisher; 13 months since release |
| `syncfusion_flutter_signaturepad` | 34.2.5 | ≈2026-08-25 | **Commercial** | — | — | **Disqualified on licence** |

**Recommendation: `signature` 6.4.0.**

- *Why:* Pure Dart, MIT, actively released, and it is the only one of the three that is both currently maintained and licence-clean. `SignatureController` gives `clear()`, `undo()`, `isEmpty`, and `toPngBytes()` — clear-and-retry is one call, and the output is bytes, which is what stage 11 wants.
- *Licence:* MIT [OBSERVED pub.dev + repo LICENSE]. Imposes nothing on the bank.
- *Native crypto / OpenSSL:* **None.** No podspec exists, so it cannot participate in a CocoaPods version conflict. This is the cleanest possible answer to R-025's class of problem.
- *RTL:* The pad is a `GestureDetector` over a `CustomPaint`. Flutter's `Directionality` affects directional layout widgets (`Row`, `EdgeInsetsDirectional`, `Align`, `Positioned.directional`) and text shaping; it does **not** mirror `RenderBox` local coordinates, so pointer positions and the painted path are unaffected. **[UNVERIFIED — this is inference from Flutter's `Directionality` semantics, not an observation of `signature`'s source.** Verify with one widget test that pumps the pad inside `Directionality(textDirection: TextDirection.rtl)`, drags left-to-right, and asserts `controller.points.first.dx < controller.points.last.dx`. That test is ~15 lines and closes it permanently.]
- *The RTL work that IS real here:* the surrounding chrome — the "clear" button, the "draw / upload" toggle, the hint text — is ours and must use `EdgeInsetsDirectional` and logical alignment. That is bespoke either way.
- *Costs:* Integration ≈ 0.5 day. If it stalls: it is ~1,000 lines of `CustomPainter` over a point list; a vendored fork is a realistic fallback, which is exactly why a pure-Dart package is a lower-risk dependency than a plugin. Replacement cost later ≈ 1–2 days.
- *`flutter_svg` transitive dependency:* pure Dart, widely used, no native code. Acceptable. Note it is pulled in **only** for SVG export, which stage 11 does not need — if `flutter_svg` ever becomes a problem, the PNG path does not depend on it.

**The upload route is `image_picker`, see §3.6.** Do not build a second picker for it.

**Flagged for the journey, not decided here:** `[POLICY: signature file size and format limits]` at stage 11 is still unset, and the *draw* route and the *upload* route produce very different files (a drawn PNG is tens of KB; a photographed signature can be several MB). The limits must apply per route, not as one number.

---

### 3.2 Need 2 — Phone number entry (stage 1b, Sudan)

| Candidate | Version | Released | Licence | Native code? | Notes |
|---|---|---|---|---|---|
| **`phone_numbers_parser`** (cedvdb) | 9.0.25 | ≈2026-08-01 | MIT | **None** — sole dependency is `meta ^1.10.0` [OBSERVED pub.dev] | Google libPhoneNumber metadata; explicitly "instantly supports all platforms (no need for channeling)"; **documents Eastern Arabic digit support**; 9 open issues [OBSERVED GitHub] |
| `phone_form_field` (cedvdb) | 11.0.1 | ≈2026-08-17 | MIT | None | Wraps the above, but drags in `circle_flags`, `flutter_country_selector`, `material_ui`, `intl`, `flutter_localizations` [OBSERVED pub.dev] |
| `intl_phone_field` | 3.2.0 | **≈2023 (3 years)** | MIT | None | **Effectively unmaintained.** README never mentions RTL or `textDirection` |

**Recommendation: `phone_numbers_parser` 9.0.25 as a validation/normalisation library only, inside a bespoke `TextFormField`.**

- *Why not the full widget:* Stage 1b serves **one country**. `phone_form_field` exists to render a country selector with flags — 249 flag SVGs and a country list we neither need nor are allowed to hardcode (see §3.4's `country_picker` finding, which applies with equal force to any embedded country list). It also pulls `material_ui`, a dependency I could not identify the provenance of. Paying five transitive packages for a `+249` prefix we could render as a `Text` widget is the wrong trade.
- *Why the parser library is worth taking:* Sudan's numbering plan (national significant number length, valid mobile prefixes for Zain / MTN / Sudani) is real domain data that changes and that we should not transcribe. Google's metadata is the best source of it, and this package is the pure-Dart route to it. **It is also the one package on this list that explicitly handles Eastern Arabic digits (٠١٢٣٤٥٦٧٨٩)** [DOC pub.dev README], which on an Arabic-first Sudanese handset is not a curiosity — it is what a customer with an Arabic keyboard will actually type.
- *RTL:* **A phone number field must be forced to `TextDirection.ltr` regardless of the ambient direction.** A `+249` number typed into an RTL `TextField` places the `+` visually at the right and the digit grouping runs the wrong way; the *stored value* is correct but what the customer reads back is not what they dialled. Set `textDirection: TextDirection.ltr` and `textAlign: TextAlign.left` on the field explicitly. The same rule applies to the account number at stage 1a and the reference number at stage 12. This is one line each and is invisible until someone tests in Arabic.
- *Native crypto / OpenSSL:* none — no native code at all.
- *E.164:* The package parses to a `PhoneNumber` carrying `isoCode`, `countryCode` and the NSN, and validates by type (`mobile`) [DOC pub.dev README + dartdoc index]. **The exact name of the E.164 formatting getter is [UNVERIFIED]** — I could not retrieve the `PhoneNumber` class dartdoc page (404) or the source file from GitHub raw (404). The *capability* is not in doubt (`'+' + countryCode + nsn` is E.164 by construction); only the API name is. Confirm at the moment the package is added.
- *Costs:* Integration ≈ 1–1.5 days including the bespoke field and its RTL/digit handling. Maintenance risk: low; it is a data-plus-parser package with one dependency. If it stalls, the fallback is a hand-written `+249` + 9-digit regex, which loses prefix validation — an acceptable degradation, ≈0.5 day.

**Backend implication — see §9.1. This is the single most contract-relevant finding in Part A.**

---

### 3.3 Need 3 — OTP code entry (stage 2, three independent rows)

Journey requirement: one row per selected channel, each with its own input, its own resend control unlocking at 30s/60s/120s, and its own always-visible state.

| Candidate | Version | Released | Licence | Native code? | RTL |
|---|---|---|---|---|---|
| `pinput` | 6.0.2 | pub.dev: ≈6 months; changelog entry dated `4/01/2026` — **the two disagree, precise date [UNVERIFIED]** | MIT | None since 5.0.0 (`smart_auth` removed) [OBSERVED changelog] | **Broken — see below** |
| `flutter_otp_text_field` | 1.5.1+1 | ≈2025-02 (18 months) | BSD-3 | None | Not assessed — stale |
| **Bespoke** (6 × `TextField` in a `Directionality`-forced `Row`) | — | — | — | — | Correct by construction |

**Finding [OBSERVED — the most concrete RTL defect in this report].** `pinput` lays its boxes out with an internal `_SeparatedRaw` widget whose `build` returns:

```dart
return Row(
  crossAxisAlignment: CrossAxisAlignment.center,
  mainAxisAlignment: mainAxisAlignment,
  mainAxisSize: mainAxisAlignment == MainAxisAlignment.center
      ? MainAxisSize.min
      : MainAxisSize.max,
  children: indexedList.map((index) { ... }).toList(growable: false),
);
```

— a bare `Row` with **no `textDirection`**, so it inherits the ambient `Directionality` [OBSERVED `lib/src/widgets/widgets.dart`, `Tkko/Flutter_Pinput@master`]. Under `Directionality.rtl` the six boxes render **right-to-left**: the first digit typed appears in the rightmost box. The underlying value stays correct, so **every unit test passes and the bug is visible only to an Arabic-speaking human looking at the screen** — precisely the failure class this project is most exposed to. `pinput`'s README does not mention RTL at all, and neither does its changelog.

**Recommendation: build it. Do not take `pinput`.**

- *Why not "just wrap pinput in `Directionality.ltr`":* That fixes the box order and is a legitimate one-line mitigation. But it is a workaround against a package that has never considered RTL, on the screen where three near-identical rows must be *unmistakable from one another* (customer.md stage 2), and where the surrounding furniture — masked destination `•••• 4821` (itself an LTR fragment inside RTL text), channel icon, per-row live state line, per-row resend countdown, per-row lock state — is all ours anyway. `pinput` would supply maybe 20% of one row and impose its own theming model on the other 80%.
- *What bespoke actually costs here:* a `PinRow` widget is 6 `TextField`s with `maxLength: 1`, `FilteringTextInputFormatter.digitsOnly`, `keyboardType: TextInputType.number`, focus advance on input and retreat on backspace, wrapped in `Directionality(textDirection: TextDirection.ltr, …)`. That is a well-trodden ~120-line widget. **Additionally required and supplied by no package on pub.dev:** Arabic-Indic digit normalisation on input (a customer typing ٤ must produce `4`) — the same fold the backend's `ref.ar_fold` performs on digits [OBSERVED V0011 lines 45–47].
- *The resend timer is bespoke regardless.* 30s → 60s → 120s escalation, capped at 3 per channel per session, surviving app backgrounding, is a `Timer.periodic` inside a Riverpod notifier keyed by channel. No package models a per-channel escalating cap.
- *Costs:* bespoke ≈ 2–2.5 days for the row widget, the three-row screen, the timers and the state line. Taking `pinput` and fighting it: ≈1.5–2 days plus a permanent dependency with an unaddressed RTL defect. The ratio does not justify the dependency.

---

### 3.4 Need 4 — Searchable picker (138 occupations; 249 countries)

Journey requirement (stage 4, marked SETTLED): full-screen, search field focused with the keyboard up, **substring** matching across Arabic and English simultaneously, mandatory Arabic normalisation (أ إ آ ا identical; ة/ه identical; ى/ي identical; tatweel and diacritics stripped from both query and list), empty search shows the full list in Arabic alphabetical order, the code is never displayed, and a no-results state suggesting a shorter word.

| Candidate | Version | Released | Licence | Native? | Assessment |
|---|---|---|---|---|---|
| `dropdown_search` | 7.0.0 | ≈2026-04-30 | MIT | None (`cupertino_icons` + `flutter`) | Five modes incl. modal bottom sheet and dialog; custom `filterFn`; RTL not mentioned in README |
| `searchfield` | 2.0.0 | ≈2026-02-28 | MIT | None | Verified publisher; inline suggestion overlay; custom filter logic; RTL not mentioned |
| `country_picker` | 2.0.28 | ≈2026-06-30 | MIT | None | **Disqualified — see below** |
| **Bespoke full-screen picker route** | — | — | — | — | **Recommended** |

**`country_picker` is disqualified by a project hard rule, not by quality.** It **embeds its own country list and localisations** (English, Greek, Chinese Simplified/Traditional — **no Arabic**) [DOC pub.dev]. CLAUDE.md: *"Reference lists are never hardcoded … server-supplied, cached, and version-checked. The list version used for a submission is recorded on the profile."* A package-embedded country list cannot be version-recorded on a profile and cannot be corrected without an app release. **`../FIB/mobile/pubspec.yaml` line 39 pins `country_picker: ^2.0.26`** [OBSERVED] — this is one of the places the reference implementation must not be copied.

**Recommendation: build one reusable full-screen `ReferenceListPicker` page. Take no package.**

- *Why not `dropdown_search`, which technically fits:* Its `filterFn` hook would accept our Arabic fold, and its modal-bottom-sheet mode is close to the required shape. But the picker is used at least **six times** in this journey — occupation, country of residence, birth country, birth state, and both address hierarchies' three levels each — and every one of them reads from the same drift reference cache with the same `(item_code, parent_code, label_ar, search_ar, sort_ordinal)` row shape. One bespoke page parameterised by `listCode` and `parentCode` serves all six. A package would be configured six times and themed once, and would still not supply the focused-on-open keyboard, the Arabic no-results copy, or the offline-cache read.
- *The hard part is not the widget; it is the fold, and the backend has already done most of it.* [OBSERVED `V0011__ref_registry_and_fold.sql`] `ref.reference_item.search_ar` is a **generated stored column** — `GENERATED ALWAYS AS (ref.ar_fold(label_ar)) STORED`. So the *labels* arrive pre-folded from the server. The device only needs to fold the **query string**, which is one short string per keystroke. That is a materially smaller Dart obligation than persistence.md's "on both tiers" phrasing implies, and it does not contradict it: the shared golden-vector fixture is still required, because query-fold and label-fold must agree exactly or the substring match silently misses.
- *Sorting is already solved and must not be re-solved on the device.* `sort_ordinal` is `row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu")`, computed at seed time [OBSERVED V0016 header comment, V0022 header comment]. `ORDER BY sort_ordinal` in the drift query is the whole of it. AD-005 is explicit that the device never attempts Arabic collation; nothing here changes that.
- *Costs:* ≈2.5–3 days for the page, the Dart fold, the golden-vector test and the drift query. A package would be ≈1.5–2 days *plus* the fold and the golden vectors anyway, so the saving is under a day for a permanent dependency across six screens.

**Two defects found in the existing reference data while assessing this need. Both are backend-side and both block stage 4 as specified — see §9.2 and §9.3.**

---

### 3.5 Need 5 — Cascading pickers (country → state → locality; stages 5 and 6)

**No package is viable, and that is the correct outcome — the schema already models this.**

[OBSERVED `V0011__ref_registry_and_fold.sql` lines 53–72] `ref.reference_item` carries `parent_code text` with the comment `-- admin hierarchy: country->state->locality`, and an index on `(list_code, version, parent_code)`. [OBSERVED `V0016__seed_admin_division.sql`] the seed is exactly that shape: `('SD', NULL, 'السودان', 'Sudan')` as root, then `('11','SD','الشمالية','Northern')` and so on.

So a cascade is three instances of §3.4's `ReferenceListPicker` chained by `parentCode`, plus the journey's non-Sudan fallback: **selecting a country other than Sudan leaves state and locality with no dataset, and both fall back to free text** (customer.md stage 5). No off-the-shelf cascading picker models "the second and third levels become free-text fields when the first level takes a particular value", and forcing one to would be worse than 40 lines of conditional widget.

**One cross-list join the app must perform, which nothing currently enforces.** The country level comes from the flat `country` list (249 rows, **alpha-2** `item_code`, alpha-3 in `extra`) [OBSERVED V0022 header comment]; the state level comes from `admin_division` filtered on `parent_code = 'SD'`. These are two different reference lists, and the join works only because `admin_division`'s root `item_code` happens to equal `country`'s alpha-2 code for Sudan. Both are `'SD'` today [OBSERVED V0016 line 35, V0022 header comment]. **Nothing in the schema enforces that.** OQ-012's corrected administrative-divisions dataset is a known future swap, and a dataset that used `SDN` or `729` as its root code would break every address cascade with no compile error and no failing test. See §9.4.

*Costs:* ≈1.5 days on top of §3.4, mostly the non-Sudan fallback and its state clearing.

---

### 3.6 Need 6 — Image capture and upload (salary certificate; signature-upload route)

Journey policy (SETTLED): **10 MB max; JPEG, PNG or PDF; downscale images on-device before upload.** The certificate gates nothing and must never block completion (R-017).

| Need | Candidate | Version | Released | Licence | iOS |
|---|---|---|---|---|---|
| Camera + gallery | **`image_picker`** | 1.2.3 | ≈2026-07-01 | Apache-2.0 / BSD-3 | **iOS 13+**, `PHPickerViewController`; needs `NSPhotoLibraryUsageDescription` + `NSCameraUsageDescription`. Published by **flutter.dev** [DOC pub.dev] |
| PDF selection | **`file_picker`** | 12.1.2 | ≈2026-08-28 | MIT | **iOS 14.0+** (`PHPickerViewController`/`PHPickerResult`); federated, iOS impl in `file_picker_darwin ^1.0.4`. Publisher miguelruivo.com [DOC pub.dev] |
| Downscale | **`image`** | 4.9.2 | ≈2026-08-19 | MIT | Pure Dart, sole dep `archive ^4.0.9`; `copyResize`, `encodeJpg` [DOC pub.dev] |
| Downscale (rejected) | `flutter_image_compress` | 2.5.1 | ≈2026-07-25 | MIT | **See below** |

**`flutter_image_compress` is rejected on iOS native surface.** [OBSERVED `flutter_image_compress_common.podspec`, fluttercandies/flutter_image_compress@main] its iOS podspec declares dependencies on **`SDWebImage` and `SDWebImageWebPCoder`**. That is two third-party Objective-C pods plus libwebp, added to a project whose first iOS build has never happened (S1-03 ⚠️) and whose Uqudo pod requires **exactly** `OpenSSL-Universal 3.3.3001` (R-003, R-025). Neither SDWebImage pod links OpenSSL — so this is not a *predicted* collision, it is **unnecessary native surface on the one build we cannot test**. We need JPEG/PNG downscaling; we do not need WebP. Paying two pods for a codec we will never emit is the wrong side of R-003.

**Recommendation: `image_picker` + `file_picker` for selection; `image` (pure Dart) for downscaling, run in an isolate.**

- *Downscale approach:* decode with `dart:ui`'s `instantiateImageCodec(bytes, targetWidth: …)` (native Skia decode, zero dependencies, does the expensive resize during decode), then `img.encodeJpg` from `image` on the already-small result. `image`'s pure-Dart JPEG *decode* of a 12 MP phone photo is slow; its *encode* of a 1600 px image is not. Wrap in `compute()` so a slow device does not jank the form. **[UNVERIFIED — the `instantiateImageCodec` + `encodeJpg` hybrid is a design I am proposing, not one I observed working. Measure on the S1-02 physical arm64 device; if it is slow, `image` alone in an isolate is the fallback and is still correct, just slower.]**
- *PDF cannot be downscaled by any of these.* PDFs pass through with a size check only. That is fine — the 10 MB ceiling applies regardless.
- *A scope observation, flagged not decided:* `file_picker` exists in this list **solely because the policy allows PDF**. Dropping PDF from the salary certificate would remove a single-maintainer package with a large federated surface and an iOS 14 floor. That is a journey policy value (SETTLED), so it is not mine to change — but the dependency it costs is worth the product owner knowing about.
- *Native crypto:* none in any of the three. `image_picker` and `file_picker` use platform photo/file pickers only.
- *Costs:* ≈2 days total including permissions plumbing, the isolate, the size/type validation and the never-blocks-completion queueing (which is bespoke, §6).
- *iOS floors vs our target:* our deployment target is 15.0; `image_picker` needs 13+, `file_picker_darwin` needs 14+. Both satisfied with headroom.

---

### 3.7 Need 7 — Multi-stage form state, free back-navigation across stages 3–6, written as typed

| Candidate | Version | Released | Licence | Assessment |
|---|---|---|---|---|
| `flutter_form_builder` | 11.0.0 | ≈2026-08-16 | MIT, verified publisher (flutterformbuilderecosystem.com) | Real, maintained, 63.3k weekly downloads. **Wrong shape for this journey.** |
| **Bespoke: Riverpod notifier per stage + drift** | — | — | — | **Recommended** |

**Recommendation: bespoke. `flutter_form_builder` solves a problem this journey does not have.**

`flutter_form_builder` owns form state in a `GlobalKey<FormBuilderState>` scoped to one widget subtree and hands it back on submit. This journey requires the opposite ownership: customer.md stage 3 and stage 13 specify that stages 3–6 are **offline-capable, written to device storage as the customer types**, freely back-navigable, and reconciled against a backend that owns a *different* class of field. AD-005 settled the persistence for exactly this (drift, session database). The source of truth is the drift row; the widgets are a view over it. Putting a second, competing form-state owner between the widget and the drift write would mean two places holding the same value and a rule for which wins — the defect class that Stage 13's per-field-class ownership table exists to prevent.

It would also buy little: the stages need `TextFormField`, `DropdownButtonFormField`, radio groups and the bespoke pickers from §3.4 — all of which are Flutter framework widgets that already participate in a plain `Form`.

*Costs:* bespoke ≈4–5 days across the four stages, dominated by the debounced write-as-you-type and the conditional branching (marital status → spouse name / has children / number of children; birth country ≠ Sudan → free-text birth state). No package models journey-conditional field visibility.

---

### 3.8 Need 8 — Numeric field with a visible hint (monthly expenses, digits only, SDG)

**Only one option, and it is in the framework.** `TextFormField` with `inputFormatters: [FilteringTextInputFormatter.digitsOnly]`, `keyboardType: TextInputType.number`, `decoration: InputDecoration(helperText: …, suffixText: 'ج.س')`. No package is warranted and none was seriously considered.

Two RTL/Arabic details that are ours:

1. **Force `TextDirection.ltr` on the field.** A digit string in an RTL field with a `suffixText` renders with the suffix on the wrong side and the caret behaving unexpectedly.
2. **Arabic-Indic digits must be normalised on input**, same as §3.3. `FilteringTextInputFormatter.digitsOnly` uses `[0-9]` and will **silently discard** ٠١٢٣٤٥٦٧٨٩ — a customer typing on an Arabic keyboard would see their keystrokes vanish. Use a custom `TextInputFormatter` that maps Arabic-Indic and Extended Arabic-Indic to ASCII **before** filtering. The backend's `ref.ar_fold` already performs exactly this mapping [OBSERVED V0011 lines 45–47], and V0027 added a digits-only CHECK constraint on monthly expenses [OBSERVED EXECUTION_PLAN S2-08 note] — so a silently-emptied field would reach the backend as a constraint violation rather than a helpful error.

**This is the single cheapest, highest-value RTL finding in Part A and it applies to four fields: account number (1a), phone (1b), OTP digits (2), monthly expenses (4).** Write one `ArabicDigitInputFormatter`, use it in all four.

---

## 4. Evidence — Part B: Ant Design components for `backoffice/`

### 4.0 Baseline, verified locally

- **antd 6.6.1, MIT** [OBSERVED `backoffice/node_modules/antd/package.json` lines 2–5]. Peer deps `react >=18`, `react-dom >=18`; project is on React 19.2.8 [OBSERVED `backoffice/package.json`].
- **RTL and Arabic locale are already wired** [OBSERVED `backoffice/src/main.tsx`]: `<ConfigProvider direction="rtl" locale={ar_EG}>`. `direction?: DirectionType` is a live prop on `ConfigProviderProps` [OBSERVED `antd/es/config-provider/index.d.ts` line 47].
- **`ConfigProvider` accepts per-component config for 70+ components**, including `table`, `datePicker`, `rangePicker`, `image`, `descriptions`, `timeline`, `select`, `modal`, `form`, `upload`, `otp` [OBSERVED same file, lines 26–131]. This matters for RTL: several per-component adjustments below can be set once globally rather than at every call site.
- **DatePicker is dayjs-based** — `dayjs ^1.11.11` is a direct dependency of antd 6.6.1 [OBSERVED antd package.json line 160]. No separate date-library adapter is needed.

---

### 4.1 The RTL headline: antd v6 fixed the fixed-column inversion

**This is the most consequential Part B finding and it was verified from installed source, not documentation.**

Under antd v5 the long-standing complaint was that in RTL you had to write `fixed: 'right'` to pin a column to the *visual left* — reported as [ant-design#52942](https://github.com/ant-design/ant-design/issues/52942) (opened 2025-02-24 against antd 5.23.4), confirmed as a bug by the Ant Design team, **closed and labelled for 6.x** [DOC GitHub issue]. Related: [#21815](https://github.com/ant-design/ant-design/issues/21815), [#41070](https://github.com/ant-design/ant-design/issues/41070).

In the bundled `@rc-component/table ~1.11.1` that ships with antd 6.6.1:

```js
// node_modules/@rc-component/table/es/hooks/useColumns/index.js:43
const parsedFixed = fixed === true || fixed === 'left' ? 'start'
                  : fixed === 'right' ? 'end'
                  : fixed;
```
```js
// node_modules/@rc-component/table/es/Cell/index.js:98,103
fixedStyle.insetInlineStart = fixStart;
fixedStyle.insetInlineEnd   = fixEnd;
```
[OBSERVED, both files, installed tree]

`fixed: 'left'` is normalised to logical `start` and applied via `insetInlineStart`, which under `direction: rtl` **is the right edge**. The shadow classes are correspondingly `-fix-start-shadow` / `-fix-end-shadow` [OBSERVED `@rc-component/table/es/Table.js` lines 581–584]. **The v5 inversion is gone in the version we have installed.** `fixed: 'left'` now means "pin to the reading-start edge", which is what an RTL author would expect.

*Consequence for us:* the profile-list column pinning (account number should stay visible while scrolling) can be written naturally, and **any snippet copied from a v5-era blog post or Stack Overflow answer that says "use `fixed: 'right'` for RTL" will now be wrong.** Worth a comment in the code.

**Two RTL issues that remain open in the tracker and that we should expect to hit:**

| Issue | Component | Opened | Status |
|---|---|---|---|
| [#47488](https://github.com/ant-design/ant-design/issues/47488) — subMenu RTL positioning | `Menu` | 2024-02-19 | Open, inactive |
| [#32679](https://github.com/ant-design/ant-design/issues/32679) — responsive column title does not appear in RTL | `Table` | 2021-10-28 | Open, inactive |
| [#49664](https://github.com/ant-design/ant-design/issues/49664) — DatePicker panel order wrong in RTL; RangePicker arrow missing | `DatePicker` | 2024-07-01 | **Closed with no documented fix version** |

[DOC, all four, GitHub issue pages]

The `DatePicker` one is live for us — operator.md's profile list filters by **date range**, which means `RangePicker`. #49664 is closed but the page shows no maintainer response and no fix version; the reporter worked around it with CSS. **[UNVERIFIED against 6.6.1 — I have not rendered a `RangePicker` under `direction="rtl"`.** This is the highest-value single manual check in Part B and it costs ten minutes: render `<DatePicker.RangePicker />` inside the existing `main.tsx` provider and look at it. Do that before designing around it.]

---

### 4.2 Need-by-need component mapping

#### (1) The profile list

| Requirement | Component | What it does **not** cover |
|---|---|---|
| Tabular list, default columns account number · name · branch · status · provenance · submitted date | `Table` | — |
| Default sort most recently submitted first | `Table` `column.defaultSortOrder` + `sorter: true` | Nothing; but the **comparison must be server-side**, see §4.3 |
| Filter by status, provenance, branch, rejection reason | `Table` `column.filters` + `filteredValue` + `filterMultiple`; or standalone `Select mode="multiple"` above the table | antd applies filters **client-side to `dataSource` by default**; must be neutralised — §4.3 |
| Filter by date range | `DatePicker.RangePicker` | **RTL behaviour unverified — §4.1** |
| Filter menu with many options (branch: 25; rejection reason: 7) | `column.filterSearch` + `filterMode: 'tree'` [OBSERVED `antd/es/table/interface.d.ts` lines 122–123] | `filterSearch` searches **the filter menu's own option labels**, not the table records. It does no Arabic folding. |
| **Search across any field or combination, including status history** | **Nothing in Ant Design.** | This is the largest bespoke item in Part B — §6 |
| Server pagination | `Table` `pagination={{ current, pageSize, total }}` + `onChange` | — |
| Incomplete profiles visible | — | Pure data concern |

`Table` also exposes `virtual?: boolean` [OBSERVED `antd/es/table/InternalTable.d.ts` line 64]. **Do not use it.** With server-side pagination the client holds one page; virtualisation solves a problem we will not have, and virtualised rows interact badly with variable-height RTL content.

`Tag` for the status and provenance columns, with a fixed colour map. **Provenance must never be merged into the status column** — operator.md is explicit that digital and manual are materially different artifacts and must stay distinguishable. Two `Tag`s, two columns.

#### (2) The single-profile view

| Requirement | Component | Notes |
|---|---|---|
| Submitted data (contact, social, addresses, occupation) | `Descriptions` (`bordered`, `column={{ xs:1, md:2 }}`) | Note the v6 size rename: `size="middle"` → `"medium"`, `size="default"` → `"large"` [DOC v6 migration guide] |
| Per-channel verification state (`verified`/`declined`/`unverified`) | `Descriptions` + `Badge status` or three `Tag`s | The three states need three visually distinct treatments, not two — "declined" and "unverified" mean different things to the bank |
| Uqudo results, **liveness and face-match recorded separately** | `Descriptions` in a dedicated `Card`, `Alert type="error"` for `face.match === false` | **Ant Design will happily let you render these as one row. Do not.** operator.md: a face-match failure is the strongest fraud signal the journey produces and must never be absorbed into a generic failure. And per AD-002a there **is no liveness score** — the "liveness" row shows either "no signed artifact / terminated after N attempts" or "passed", never a number |
| Civil Registry data | `Descriptions` | The registry's name model is a four-generation Arabic chain plus a Latin pair (R-032, resolved at S2-08) — that is six-plus rows, not one "name" row |
| Document images + two portraits, **each labelled with origin** | `Image` inside `Card` with a mandatory caption | §4.4 |
| Full status history with timestamps and actors | **`Timeline`** | `mode` is `'left' | 'right' | 'alternate'` — **physical, not logical.** Under RTL, `mode="left"` puts the rail on the visual left, which for Arabic reading order is wrong. Use the default single-sided mode, or set `mode="right"`, and verify visually. **[UNVERIFIED — not rendered.]** |
| Reference number | `Typography.Text copyable` | Force `TextDirection`/`dir="ltr"` on the value — same class of bug as §3.2 |
| Layout | `Layout` (`Sider`+`Header`+`Content`), `Tabs`, `Card`, `Skeleton`, `Result` | `Layout.Sider` and `Tabs` respect `ConfigProvider direction` |

**"Opening a profile is an audit event. Viewing a document image is a separate audit event."** Neither is a component concern — both are API calls the page makes. But the second one has a component hook, see §4.4.

#### (3) Image display — downscaled derivatives in list/preview, full resolution on explicit request

**`Image` covers this exactly, and the API is verified.** `ImageProps` accepts `src` and `preview?: boolean | PreviewConfig`; `PreviewConfig` extends `InternalPreviewConfig`, which declares its **own `src?: string`** [OBSERVED `@rc-component/image/es/Preview/index.d.ts` line 53, reached via `@rc-component/image/es/Image.d.ts` lines 15, 35, 38].

```tsx
<Image src={derivativeUrl} preview={{ src: fullResolutionUrl, onOpenChange: recordImageViewAudit }} />
```

This is precisely operator.md's rule — the thumbnail is a downscaled derivative, the original is fetched only when the operator opens the preview — **and `onOpenChange` is the exact hook for the "document image viewed" audit event**, which operator.md requires to be recorded separately from viewing the profile. In v6, `visible`/`onVisibleChange`/`toolbarRender` are deprecated in favour of `open`/`onOpenChange`/`actionsRender` [OBSERVED `antd/es/image/index.d.ts` lines 8–32]; write the new names.

`Image.PreviewGroup` gives arrow navigation across a profile's images. **RTL caution:** the preview's next/previous arrows are physical. Under RTL an operator expects "next" to advance leftward. **[UNVERIFIED — verify visually; if wrong, `actionsRender` allows replacing the toolbar.]**

**What `Image` does not cover:** authenticated image fetching. If image URLs are bearer-token-protected (they must be — these are identity documents), `<img src>` cannot carry an `Authorization` header. Either the backend issues short-lived signed URLs, or the client fetches to a blob URL. **This is a backend decision, §9.5.**

#### (4) Approve and reject

| Requirement | Component |
|---|---|
| Approve (final, irreversible) | `Popconfirm` → `Modal.confirm` for the wording. A `Popconfirm` alone is too light for an irreversible bank action |
| Reject: coded reason from a fixed list | `Modal` + `Form` + `Select` over the `rejection_reason` reference list (7 codes, seeded V0019) |
| Show the derived customer-facing message | `Alert` or `Typography.Paragraph` inside the modal, **read-only, updating as the code changes** |
| Optional internal detail | `Input.TextArea` |
| `REJ-07` — internal detail **mandatory** | `Form.Item` conditional `rules` via `Form.useWatch` |

**The customer-facing message must be rendered from the server's `extra` payload, never hardcoded in the React app.** `ref.reference_item.extra jsonb` is described in V0011 as *"customer-facing message for REJ codes, etc."* [OBSERVED V0011 line 68]. Hardcoding it in the front end would put four of the seven codes' deliberately-identical neutral messages (REJ-03/04/05/07) in a second place, where one could drift and tell a fraudster which control fired. **But note R-033: `extra` is currently outside the `content_hash` integrity envelope** — so a customer-facing message could change without the list hash changing. That is AD-002f's to settle and this is a second use case arguing the same way.

#### (5) Manual completion and the four-eyes rule

`Modal` + `Form` with a mandatory justification `TextArea`.

**The four-eyes rule must not be computed in the UI.** AD-005 settled it as *"a single conditional UPDATE with a NOT EXISTS clause over the status history"* — enforced in the database, unbypassable by calling the API directly. operator.md: *"the UI must reflect it, not duplicate it."* The React app therefore needs the *answer*, not the *inputs*. See §9.6 — this is a backend contract implication.

Ant Design's part is trivial: `<Button disabled={!canApprove}>` wrapped in a `Tooltip` giving the reason. Getting `canApprove` is the whole problem.

#### (6) Two access levels, viewer and operator

**Ant Design has no permission model and offers nothing here.** Bespoke: a role from the session, a `useRole()` hook, and conditional rendering. `Menu` items and `Button`s hidden or disabled. This is ~half a day of glue and cannot be shortened by a component library.

The rule to write down: **hiding a button is presentation, not authorisation.** Every write endpoint and the export endpoint must independently enforce the role server-side. The same argument as the four-eyes rule, one level up.

#### (7) Export — XLSX and CSV, field data only, 10,000 rows

**`Button` + `Dropdown` for the trigger; everything else server-side.** See §5 for the full option comparison and the recommendation.

#### (8) Dashboard (BL-001, deferred) — brief

Ant Design ships `Statistic`, `Progress` and `Card` but **no charts**. When BL-001 is scheduled:

| Candidate | Version | Licence | React 19 peer | Note |
|---|---|---|---|---|
| `@ant-design/plots` | 2.6.8 | MIT | `>=16.8.4` | Visually native to antd; pulls the whole AntV G/G2 stack (6 deps) |
| `recharts` | 3.10.1 | MIT | explicitly `^19.0.0` | Lighter, SVG, React-idiomatic |

[OBSERVED registry.npmjs.org/@ant-design/plots/latest and /recharts/latest]

**RTL warning applying to both, and to every charting library:** charts render to SVG or canvas and **do not inherit CSS `direction`**. Axis label placement, legend order and tooltip anchoring must be configured manually. Budget a day for RTL chart configuration whenever BL-001 lands, and do not let a demo screenshot in LTR stand as evidence it works. `recharts`'s explicit React 19 peer range is the tiebreaker if nothing else distinguishes them, but **this is deferred and should be re-researched when scheduled — both packages will have moved.**

---

### 4.3 Arabic search and sort in `Table` — the decisive answer

**Ant Design's `Table` must do neither. Both are server-driven. This is not a preference; it is forced by three separate facts.**

**Fact 1 — antd filters and sorters are client-side by default, over `dataSource` only.** A `column.filters` array with no `onFilter` override, or a `sorter` given as a comparator function, operates on the rows currently in `dataSource` — i.e. the current page. With server pagination that means "filter this page of 20", which is not what any operator means by "filter by status".

**Fact 2 — the backend already owns Arabic equality and Arabic ordering, and the browser cannot reproduce either.**
- Substring/equality: `ref.ar_fold()` — `أ إ آ ٱ→ا · ة→ه · ى→ي`, strip tatweel and harakat, map Arabic-Indic digits, lowercase [OBSERVED V0011 lines 40–51]. There is no JavaScript equivalent, and `String.prototype.normalize('NFKD')` does **not** collapse ة/ه or ى/ي — the same reason persistence.md gives for why no collation strength does.
- Ordering: `sort_ordinal` computed under `COLLATE "ar-x-icu"` [OBSERVED V0016/V0022 header comments]. `localeCompare('ar')` in a browser uses the browser's own ICU build at whatever version ships that month, and **will disagree with PostgreSQL's `ar-x-icu`** on some pairs. Two views of the same list in different orders is worse than an unfamiliar order.
- AD-005 already ruled for the mobile tier: *"the device never attempts Arabic collation."* **The browser is a device.** Extending the settled rule, not relitigating it.

**Fact 3 — every search and filter is an audit event.** operator.md requires "Search and filter executed" in the audit trail. A client-side filter produces no server call and therefore no audit record. Client-side filtering would silently create unaudited identity-data access.

**The concrete shape:**

```tsx
<Table
  dataSource={page.rows}
  loading={isFetching}
  pagination={{ current, pageSize, total: page.total, showSizeChanger: false }}
  onChange={(pagination, filters, sorter) => refetchFromServer({ pagination, filters, sorter })}
  columns={[
    { dataIndex: 'accountNumber', fixed: 'left', sorter: true },
    { dataIndex: 'status', filters: statusOptions, filteredValue: query.status ?? null },
    { dataIndex: 'submittedAt', sorter: true, defaultSortOrder: 'descend' },
    // ...
  ]}
/>
```

The rules: **`sorter: true`, never `sorter: (a,b) => …`** (the boolean form declares the column sortable and defers to `onChange`); **`filters` supplies the menu options, `filteredValue` makes them controlled, and `onFilter` is never written** (its absence is what stops client-side filtering); `onChange` is the single funnel to the server; every response carries `total`.

`filterSearch: true` on the branch column (25 options) is fine — it searches the *menu labels* client-side, which is a 25-string list, not customer data. But it does **no Arabic folding**, so a customer typing `القضارف` without the definite article, or with a different alef, will not find it. If that matters, pass `filterSearch` a custom predicate — the type allows it: `filterSearch?: FilterSearchType<ColumnFilterItem>` [OBSERVED `antd/es/table/interface.d.ts` line 123] — and give it a TypeScript port of `ar_fold`. **That is the same Dart fold as §3.4, in a third language, and it must be pinned by the same golden-vector fixture.** See §9.7.

---

### 4.4 Export — the one place a package beyond Ant Design is needed, and it belongs in the backend

**Requirement (operator.md):** XLSX **and** CSV; field data only, never images; 10,000 rows maximum; every export is an audit event recording operator, filters applied, row count, and fields included.

**Options considered:**

| Option | Version | Released | Licence | Health | Verdict |
|---|---|---|---|---|---|
| **A. `backend/` + Apache POI (SXSSF)** | POI **5.5.1** | **2025-11-30** | Apache-2.0 | ASF project, active, security-patched | **RECOMMENDED** |
| B. `backend/` + dhatim `fastexcel` | 0.20.2 (writer) | — | Apache-2.0 (LICENSE present; **exact SPDX [UNVERIFIED]**) | 66 open issues; no POI dependency; ~10× faster than POI non-streaming | Viable, smaller, less proven here |
| C. browser + npm `xlsx` (SheetJS) | **0.18.5** | ≈2022 | Apache-2.0 | **npm registry copy is frozen; current SheetJS ships from cdn.sheetjs.com, not npm** | **Reject** |
| D. browser + `exceljs` | **4.4.0** | **2024-10-19** | MIT | **659 open issues, 143 open PRs**; 9 runtime deps incl. `uuid@8`, `unzipper`, `archiver@5` | **Reject** |
| E. browser + `write-excel-file` | 4.1.1 | — | MIT | Single dep (`fflate`); single maintainer | Reject on architecture, not quality |

[OBSERVED registry.npmjs.org/xlsx/latest → `0.18.5`; registry.npmjs.org/exceljs/latest → `4.4.0`; github.com/exceljs/exceljs releases → v4.4.0 dated 2024-10-19; github.com/exceljs/exceljs → 659 issues / 143 PRs; registry.npmjs.org/write-excel-file/latest → 4.1.1; poi.apache.org → 5.5.1, 2025-11-30; github.com/dhatim/fastexcel → 66 open issues, no POI dependency]

**Recommendation: A — generate both formats in `backend/`.**

Four reasons, in order of force:

1. **The browser does not have the data.** With server-side pagination (§4.3) the client holds one page. A client-side export would require a second endpoint returning all 10,000 rows to the browser purely so JavaScript can write a file — **10,000 rows of customer PII crossing to a client that has no reason to hold them.** operator.md calls the export "the highest-risk artifact this product could produce"; sending it through the browser twice is the wrong direction.
2. **The audit event lives where the truth is.** "The operator, the filters applied, the row count, and the fields included" is exactly what the server query knows. Client-side generation means the client *reports* the row count, which is a self-attested audit record.
3. **Licence and maintenance.** Options C and D both fail the maintenance filter on their own terms: SheetJS's npm artifact is four years stale while the project moved distribution elsewhere, and ExcelJS has not released in 22 months with a 659-issue backlog. Apache POI is an ASF project with a release three months before this report, explicitly noting dependency security updates.
4. **CSV needs no library at all** — but it does need a **UTF-8 BOM** (`EF BB BF`) or Microsoft Excel on Windows will render Arabic as mojibake. **[UNVERIFIED against the operators' actual Excel — OQ-008 (operator browser/hardware baseline) is unanswered, so their Excel version is unknown too.** Write the BOM; it is harmless everywhere else and it is the difference between a usable file and a support call. Verify once against a real operator machine.]

*Cost of A:* POI SXSSF streaming write of 10,000 rows is ~1 day including the audit event and the two content types. `fastexcel` (option B) would be lighter — POI adds ~15 MB of transitive jars — and is a reasonable substitute if dependency weight matters; **it does not change any contract, so the choice can be deferred to implementation.** What must be decided now is *where*, not *which*.

*Exit cost if this is wrong:* one controller and one service class. Low. The expensive-to-reverse part is the decision to paginate server-side, which §4.3 forces independently.

---

## 5. What must be bespoke — verified, not assumed

The task asked me to verify rather than assume the three predicted items, and to answer the same question for the back office.

### Mobile

| Bespoke item | Verified because |
|---|---|
| **The journey's stage machine** | **Confirmed.** Stages 8–10 carry two independent scan counters (3 per document type, 6 per session), a separate 5-attempt liveness budget, 24-hour blocks scoped to *one stage only*, and a stage-9 "wrong national number" path that routes back to stage 8 **and consumes stage 8's budget**. No package models a state machine with per-stage retry budgets, cross-stage budget consumption and per-stage temporary blocks. A generic FSM package would model transitions and none of the budgets. |
| **Offline reconciliation / resume** | **Confirmed, and stronger than predicted.** Stage 13 specifies ownership *per field class* (device wins for customer-entered, backend wins for system-derived), idempotency keys on every mutating call, a **30-minute** practical retry window for a dropped JWS upload (bounded by Uqudo image retention, not by the JWS), and a device-less resume that restores customer data for explicit review while refusing to inherit identity artifacts. Nothing on pub.dev models per-field-class ownership. |
| **Arabic RTL layout** | **Confirmed, and the finding is sharper than "it's bespoke".** Flutter's framework-level RTL (`MaterialApp` + `flutter_localizations` + `Directionality`) does most of the work for free — that was AD-001's rationale for Flutter. The bespoke work is the **inverse**: the four islands that must be forced *back* to LTR (account number, phone, OTP digits, monthly expenses) plus one `ArabicDigitInputFormatter` shared by all four. §3.3 and §3.8. |
| **Per-channel OTP row + escalating resend timer** | Not predicted; confirmed by §3.3. |
| **The reference-list picker and its fold** | Not predicted; confirmed by §3.4. |
| **The "never blocks completion" upload queue** (R-017) | The salary certificate must submit after the profile if still queued. That is a drift-backed outbox on the device, not a package feature. |

### Back office — the same question, answered

| Bespoke item | Why Ant Design does not cover it |
|---|---|
| **"Search across any field or combination, including status and status history"** | **The single largest bespoke item in Part B.** Ant Design has no query builder. `Input.Search` supplies a box; `Form` supplies a filter panel; the *semantics* — which fields, combined how, matched with `ar_fold` for Arabic text and exactly for account numbers, and searching *history* rows not just current state — are entirely ours, and mostly the backend's. |
| **The role gate (viewer vs operator)** | No permission model in antd. §4.2(6). |
| **The four-eyes reflection** | Ant Design can disable a button; it cannot know why. §9.6. |
| **The Arabic fold in TypeScript** | For `filterSearch` menu matching and any client-side highlight. Third implementation of the same function. §9.7. |
| **Status/provenance colour and label mapping** | Nine statuses × Arabic labels × colour semantics. `Tag` renders it; the mapping is a policy table. |
| **Audit-event emission on view, image-view, search, export** | Four call sites. Not a component concern, but easy to forget, and operator.md treats them as the point of the record. |
| **Routing, data fetching, auth state** | Not chosen, not evaluated (see §1). §11. |

**Notably NOT bespoke, contrary to what one might assume:** the profile list table, the status-history timeline, the image preview with derivative-vs-original, the reject modal, and the whole layout shell. Ant Design covers these properly, and the RTL defect that would have hurt most (fixed columns) is fixed in the version we already have installed.

---

## 6. Effort comparison — assemble vs build from scratch

Rough, single developer, including tests to the 80% gate. The ratio is the point, not the absolute numbers.

### `mobile/`

| Area | Assemble (recommended) | From scratch |
|---|---|---|
| Signature draw + upload | 1.5 d | 5 d |
| Phone entry + validation | 1.5 d | 3 d (loses libPhoneNumber metadata) |
| OTP rows + timers | 2.5 d | 2.5 d (**bespoke either way**) |
| Searchable picker + Arabic fold | 3 d | 3 d (**bespoke either way**) |
| Cascading pickers + non-Sudan fallback | 1.5 d | 1.5 d (**bespoke either way**) |
| Image capture / PDF / downscale | 2 d | 10 d+ (native pickers per platform) |
| Multi-stage form + drift persistence | 5 d | 5 d (**bespoke either way**) |
| Numeric field + digit formatter | 0.5 d | 0.5 d (**bespoke either way**) |
| Stage machine + resume/reconcile | 8 d | 8 d (**bespoke either way**) |
| RTL islands + localisation plumbing | 2 d | 2 d (**bespoke either way**) |
| **Total** | **≈27.5 d** | **≈40.5 d** |

**Ratio ≈ 1.5×.** The saving is real but modest, and it is concentrated in exactly two places: **image capture (5×) and signature (3×)**. Everything else is bespoke either way. **The honest headline for the mobile tier is that packages are not where the time goes** — the stage machine, the resume model and the form persistence are 15 of the 27.5 days and no package touches them.

### `backoffice/`

| Area | Assemble with antd | From scratch |
|---|---|---|
| Layout shell, nav, theming, RTL | 1 d | 8 d |
| Profile list table (sort/filter/paginate/fixed cols) | 3 d | 20 d |
| Single-profile view (Descriptions, Timeline, Cards) | 3 d | 10 d |
| Image display + preview + derivative/original | 1 d | 7 d |
| Approve / reject / manual-complete modals + forms | 3 d | 8 d |
| Role gate | 0.5 d | 0.5 d |
| Search panel (bespoke on top of antd `Form`) | 3 d | 5 d |
| Export trigger (server does the work) | 0.5 d | 0.5 d |
| Export generation in `backend/` (POI) | 1 d | 1 d |
| Arabic fold in TS + golden vectors | 0.5 d | 0.5 d |
| Audit-event emission call sites | 0.5 d | 0.5 d |
| **Total** | **≈17 d** | **≈61 d** |

**Ratio ≈ 3.6×.**

**The comparison itself is the finding: Ant Design earns its place roughly two and a half times more than the Flutter package layer does.** If effort has to be redirected, it should go into the mobile bespoke work, because that is where no library is going to rescue the schedule. This is consistent with EXECUTION_PLAN's standing note that "the back office ships thin" — but it is worth knowing that the back office is where component reuse pays best, so shipping it thin sacrifices the cheapest work in the project.

---

## 7. Anything to avoid, and why

Named deliberately, most valuable first.

1. **`pinput` under RTL without a `Directionality.ltr` wrapper.** Popular, MIT, no native code, and it lays out its boxes with a bare `Row` carrying no `textDirection` [OBSERVED `lib/src/widgets/widgets.dart`]. Under `Directionality.rtl` the six OTP boxes render right-to-left. **The value is correct, so every test passes.** This is the archetype of the defect class this project is most exposed to.

2. **`country_picker` (and any package embedding a country or reference list).** MIT, maintained, and **disqualified by CLAUDE.md's "reference lists are never hardcoded"**. It also ships no Arabic localisation [DOC pub.dev]. `../FIB/mobile/pubspec.yaml` line 39 pins it [OBSERVED] — a place the reference implementation must not be copied.

3. **`flutter_image_compress`.** Its iOS podspec pulls **`SDWebImage` and `SDWebImageWebPCoder`** [OBSERVED podspec] — two Objective-C pods and libwebp, for a WebP codec we will never emit, added to the one build we cannot compile (S1-03) alongside an exactly-pinned `OpenSSL-Universal 3.3.3001` (R-003/R-025). Use pure-Dart `image`.

4. **`syncfusion_flutter_signaturepad`.** Actively maintained (34.2.5, five days old) and **commercial**: *"you need to have either a Syncfusion commercial license or Free Syncfusion Community license"* [DOC pub.dev]. Fails "licence must impose nothing on the bank at delivery" outright. The Community licence has revenue and headcount conditions that would need the bank's own numbers to evaluate — which is itself a reason not to.

5. **npm `xlsx` (SheetJS) and `exceljs` in the browser.** `xlsx` is frozen at **0.18.5** on the npm registry while the project distributes current builds elsewhere; `exceljs` last released **2024-10-19** with **659 open issues and 143 open PRs** [OBSERVED]. Generate server-side with Apache POI.

6. **Any client-side Arabic sort — `localeCompare('ar')`, `Intl.Collator('ar')`, or a `Table` `sorter` comparator.** It will disagree with the backend's `sort_ordinal` under `ar-x-icu`, and the two views will differ with no error anywhere. AD-005 already ruled this out for the device; the browser is a device.

7. **`fixed: 'right'` copied from a v5-era RTL snippet.** antd v6 normalises `'left'→'start'` / `'right'→'end'` and applies `insetInlineStart`/`insetInlineEnd` [OBSERVED]. Old advice inverts the columns.

8. **`FilteringTextInputFormatter.digitsOnly` on any field an Arabic keyboard will touch.** It filters on `[0-9]` and silently discards `٠١٢٣٤٥٦٧٨٩`. The customer sees their keystrokes vanish.

9. **`flutter_form_builder` for stages 3–6.** Not defective; it just owns form state, and Stage 13's per-field-class ownership model requires drift to own it. Two owners, no rule for conflicts.

10. **`intl_phone_field`.** Last published ≈3 years ago [DOC pub.dev]. Whatever its quality, three years without a release against a framework on a quarterly cadence is not a maintenance profile a solo developer with no CI should take on.

---

## 8. What constrains `backend/` — every implication, explicitly

This is why the report runs now. Each item is cheap to change today and expensive once tests and audit behaviour hang off it.

### 8.1 Phone numbers: stage 1b should accept and store **E.164**

`phone_numbers_parser` normalises to country code + national significant number, i.e. `+2499XXXXXXXX`. **Decide now that the wire format and the stored format are E.164**, because everything downstream depends on it: `app.profile`'s phone column, `app.profile_channel`'s per-channel destinations, Stage 2's masked display `•••• 4821`, the `MessageSender` SMS/WhatsApp payloads (AD-002c, CLOSED), and — critically — S3-07's `ProfileRepository.findExisting` / re-entry logic, which compares "the previous phone" against the new one. If one path stores `0912345678` and another `+249912345678`, re-entry sees a change that did not happen and writes a `session_reentered` event with a false delta into an **append-only** audit trail.

**Also: the backend must reject non-ASCII digits explicitly.** The app will normalise Arabic-Indic digits, but a client that does not would otherwise write `+٢٤٩...` into the profile. `ref.ar_fold` already maps them [OBSERVED V0011 lines 45–47]; a CHECK constraint or a validator on the phone column is the cheap guard.

### 8.2 `ref.reference_item.search_en` is declared and never populated — stage 4 cannot work as specified

[OBSERVED] V0011 line 65 declares `search_en text` as a **plain** column (unlike `search_ar`, which is `GENERATED ALWAYS AS (ref.ar_fold(label_ar)) STORED` on line 64). A grep for `search_en` across all of `backend/src/main/resources/db/migration/` returns **exactly one hit — that declaration.** No seed migration (V0014 occupation, V0015 branch, V0016 admin_division, V0017 income_source, V0018 education_level, V0019 rejection_reason, V0022 country) populates it.

customer.md stage 4, marked **SETTLED**: *"**Substring** matching, not prefix, across Arabic **and English** simultaneously."* With `search_en` NULL on every row, the English half of that requirement has nothing to match against. The bank's own occupation labels make this concrete: خياط is supplied as "Needle", محاسب as "Amenable", ربة بيت as "HOUSEWIFELY" — an operator or a bilingual customer searching English has only these to go on.

**Fix (a migration, not a code change):** `UPDATE ref.reference_item SET search_en = ref.ar_fold(label_en) WHERE label_en IS NOT NULL;` — `ar_fold` lowercases and is safe on Latin text, so one function serves both languages. Better, make it generated like `search_ar` so it cannot drift; note that `search_en` must tolerate NULL `label_en` (V0011 line 59 allows it). Also consider a trigram index to match line 73's `reference_item_search_trgm`.

### 8.3 `ref.ar_fold` does not fold decomposed hamza forms

[OBSERVED V0011 lines 44–49] `translate()` maps the **precomposed** أ إ آ ٱ ة ى, and the following `regexp_replace` strips `[ـ ً-ْ ٰ ۖ-ۭ …]` — that is TATWEEL (U+0640), U+064B–U+0652, U+0670, U+06D6–U+06ED and bidi/zero-width controls. **U+0653 MADDAH ABOVE, U+0654 HAMZA ABOVE and U+0655 HAMZA BELOW are in neither set.** So a decomposed أ (`ا` + U+0654) folds to `ا` + U+0654, which does not equal the folded precomposed form `ا`.

Standard Arabic soft keyboards on iOS and Android emit precomposed forms, so **the probability of this reaching a customer is low** and I am not claiming it is a live defect. But it is exactly the kind of thing the shared golden-vector fixture exists to pin, and the fixture is about to be written in a second language (Dart) and a third (TypeScript). **Decide it once now:** either extend the stripped range to U+0653–U+0655, or add `normalize(t, NFC)` ahead of the fold, or record deliberately that decomposed input is out of scope. Whichever — put a decomposed-hamza case in the golden vectors so all three implementations agree.

### 8.4 The cascade depends on an unenforced code equality between two reference lists

The address cascade joins the flat `country` list (alpha-2 `item_code`) to `admin_division` (`parent_code`). It works only because `admin_division`'s root row is `('SD', NULL, 'السودان', 'Sudan')` [OBSERVED V0016 line 35] and the `country` list's Sudan row is alpha-2 `SD` [OBSERVED V0022 header comment, which even notes the Arabic label matches byte-for-byte].

**Nothing enforces this.** OQ-012's corrected administrative-divisions dataset is a known future swap; a replacement rooted on `SDN` or a numeric code breaks every address cascade in the app with no failing test and no error. **Either add a FK/constraint tying `admin_division`'s root `item_code` to a `country` `item_code`, or state the invariant in AD-002f and add an assertion to the reference-data publication step.**

### 8.5 Image URLs must be fetchable by an `<img>` tag

`<Image src={...}>` renders an `<img>`, which cannot carry an `Authorization` header. Identity-document images must not be publicly readable. **Choose one: short-lived signed URLs, or a blob-URL fetch in the client.** This lands squarely in AD-004 (image and artifact storage, currently OPEN and blocked on hosting) and it interacts with operator.md's requirement that **viewing an image is its own audit event** — a signed URL redeemed later, or twice, or by a different session, must still produce a truthful record. Signed URLs make the audit event fire at *issue* time, not at *view* time. That distinction should be decided deliberately, not by whoever writes the first handler.

### 8.6 The profile response must carry a per-operator `canApprove`

The four-eyes rule is enforced in the database (AD-005). The UI must reflect it. The UI cannot compute it without the status history's **actor identities** — and shipping every prior actor's identity to the browser purely to grey out a button widens identity-data exposure for no benefit.

**Add `canApprove: boolean` (and ideally `approveBlockedReason: string | null`) to the single-profile response, computed server-side for the requesting operator.** Two things must both be true and stated: the field is presentation only, and the conditional `UPDATE ... WHERE NOT EXISTS` remains the actual enforcement. A client that ignores `canApprove` must still be refused.

### 8.7 One Arabic fold, three languages, one fixture

`ref.ar_fold` (SQL, exists) · `arFold` (Dart, for the mobile picker's query string) · `arFold` (TypeScript, for `Table` `filterSearch` and any client highlight). persistence.md already mandates *"pinned by a shared golden-vector fixture"* for two tiers; **this report adds the third.** Keep the vectors in one file, versioned, consumed by all three test suites, and include §8.3's decomposed-hamza case.

### 8.8 Filter, sort and search are server-side — the list endpoint's contract

Following from §4.3, the profile-list endpoint must accept: page + pageSize; a sort field + direction (server maps to `sort_ordinal` or a timestamp, never a client comparator); multi-valued filters for status, provenance, branch and rejection reason; a date range; and a free-text search term folded through `ref.ar_fold` server-side. It must return `total` (antd `Table` pagination needs it) and the folded/normalised echo of what it applied, so the audit record and the UI agree. And **the search/filter execution is itself an audit event** — it cannot be, if the filtering happens in the browser.

---

## 9. What I could not determine

Stated explicitly. None of these is filled with a guess.

1. **Whether `DatePicker.RangePicker` renders correctly under `direction="rtl"` in antd 6.6.1.** Issue #49664 (panel order wrong, RangePicker arrow missing) was closed with no documented fix version and no maintainer comment on the page. The v6 migration guide documents no RTL changes. **Verify by rendering it — ten minutes.**
2. **Whether `Timeline` `mode` and `Image.PreviewGroup` navigation arrows behave sensibly under RTL.** Both use physical direction. Not rendered.
3. **The exact E.164 formatting getter name on `phone_numbers_parser`'s `PhoneNumber`.** The dartdoc class page and the GitHub raw source both returned 404. The capability is certain; the API name is not.
4. **`pinput`'s precise latest release date.** pub.dev says ≈6 months; the changelog dates 6.0.2 as `4/01/2026` with an ambiguous date format. The two disagree. Immaterial to the recommendation (which is to not use it).
5. **`dhatim/fastexcel`'s exact SPDX licence.** A LICENSE file exists; the page did not state which. Apache-2.0 is the widely-cited answer but I did not read the file. Immaterial unless option B is chosen over POI.
6. **Whether `signature`'s canvas is genuinely unaffected by `Directionality.rtl`.** Inferred from Flutter's `Directionality` semantics; not observed in `signature`'s source and not tested. §3.1 gives the 15-line test.
7. **Real-device performance of the `instantiateImageCodec` + `encodeJpg` downscale.** Proposed, not measured. Measure at S1-02.
8. **Whether Excel on the operators' actual machines needs the UTF-8 BOM.** OQ-008 (operator browser and hardware baseline) is unanswered, so their Excel version is unknown. Write the BOM regardless; verify once.
9. **Back-office routing, data fetching and auth-state libraries.** Deliberately out of the scope I was given, and not chosen anywhere in PROJECT_PLAN.md. Flagged, not researched.
10. **Whether any recommended package's *transitive* pods conflict with `OpenSSL-Universal 3.3.3001`.** I verified that `signature`, `phone_numbers_parser` and `image` have **no** native code at all (structurally impossible to conflict), and I read `flutter_image_compress_common`'s podspec (which is why it is rejected). I did **not** read `image_picker`'s or `file_picker_darwin`'s podspecs. Both are photo/file-picker wrappers over Apple frameworks with no plausible crypto dependency, but that is inference. **The only thing that settles it is S1-03, and S1-03 is ⚠️ blocked on AD-003.** This is R-003 exactly as recorded.

---

## 10. Risks

| # | If the recommendation is wrong | Cost to reverse |
|---|---|---|
| R-a | **`signature` stalls or is abandoned.** | Low. Pure Dart, ~1,000 lines, MIT — vendor a fork. 1–2 days. This is the structural advantage of a no-native-code dependency. |
| R-b | **`file_picker`'s federated iOS implementation breaks at the first iOS build (S1-03).** | Medium. It is the only recommended package whose iOS layer I did not read. Fallback: drop PDF from the salary certificate (a journey policy change, product owner's call) and use `image_picker` alone. ≈1 day of code, unknown days of decision. |
| R-c | **`RangePicker` really is broken under RTL in 6.6.1.** | Low–medium. Fallback: two separate `DatePicker`s, or a CSS override. ≈0.5–1 day. **Cheap now, annoying after the filter panel is built** — which is why §9.1 asks for the ten-minute check first. |
| R-d | **The E.164 decision (§8.1) is deferred and the two formats diverge.** | **High and partly irreversible.** Audit rows are append-only; a false `session_reentered` delta written today cannot be corrected, only annotated. Decide before S3-10. |
| R-e | **`search_en` (§8.2) is not populated before stage 4 ships.** | Low to fix (one migration), but it ships a SETTLED requirement half-implemented, and reference-list migrations are versioned — a later fix bumps the list version, which is **recorded on every profile submitted in between** (`ref.profile_reference_version`). Fixing it after launch splits the campaign's data across two list versions for no reason. |
| R-f | **Server-side export (§4.4) is overruled for client-side.** | Low in code (one controller), **high in exposure**: 10,000 rows of PII to a browser and a self-attested audit row. |
| R-g | **The Arabic fold diverges across SQL/Dart/TypeScript.** | Medium. Silent — a customer's occupation "isn't in the list", an operator's search returns nothing, no error anywhere. The shared golden-vector fixture (§8.7) is the whole mitigation and it must exist before the second implementation, not after the third. |
| R-h | **antd v6's logical-property fix is incomplete somewhere I did not read.** | Low. I verified `useColumns` normalisation and `Cell`'s `insetInline*`; I did not audit every component's stylesheet. Discovery is visual and immediate, unlike the mobile RTL bugs. |
| R-i | **`image_picker` or `file_picker_darwin` pods conflict with OpenSSL-Universal 3.3.3001.** | Unknown until S1-03. This is R-003/R-025 materialising in a third and fourth place. Nothing in this report reduces it below what R-025 already carries for `sqlite3mc`; **but every package I recommend other than these two adds zero pods, which is the most I can do about it from here.** |

---

## 11. Card updates

### 11.1 New card — `docs/components/mobile-packages.md` (draft in full)

```markdown
# Component: Mobile third-party packages

Status: researched, not integrated · Last verified: 2026-08-30
Source: docs/sessions/2026-08-30-research-client-components.md

Flutter 3.47.0 (mobile/.fvmrc) · Android minSdk 24 / compileSdk 36 / targetSdk 36,
ABIs armeabi-v7a + arm64-v8a · iOS deployment target 15.0.

## Approved — add these

| Package | Version | Released | Licence | Native code | iOS evidence |
|---|---|---|---|---|---|
| `signature` | 6.4.0 | ≈2026-07-28 | MIT | **None** | pubspec declares only `flutter` + `flutter_svg`; no `ios/`, no podspec [OBSERVED] |
| `phone_numbers_parser` | 9.0.25 | ≈2026-08-01 | MIT | **None** | sole dep `meta`; "instantly supports all platforms (no channeling)" [DOC pub.dev] |
| `image` | 4.9.2 | ≈2026-08-19 | MIT | **None** | pure Dart, sole dep `archive ^4.0.9` [DOC pub.dev] |
| `image_picker` | 1.2.3 | ≈2026-07-01 | Apache-2.0 / BSD-3 | Yes (flutter.dev) | iOS 13+, PHPickerViewController; needs NSPhotoLibraryUsageDescription + NSCameraUsageDescription [DOC pub.dev] |
| `file_picker` | 12.1.2 | ≈2026-08-28 | MIT | Yes (federated, `file_picker_darwin ^1.0.4`) | iOS 14.0+, PHPickerViewController/PHPickerResult [DOC pub.dev] |

**No recommended package links OpenSSL or any native crypto.** The first three have no
native code at all and therefore cannot participate in a CocoaPods version conflict with
Uqudo's exactly-pinned `OpenSSL-Universal 3.3.3001` (R-003, R-025). `image_picker` and
`file_picker_darwin` podspecs were **not** read — [UNVERIFIED], settled only by S1-03.

## Rejected — do not add, with the reason

| Package | Reason |
|---|---|
| `pinput` | `_SeparatedRaw` uses a bare `Row` with no `textDirection`; under `Directionality.rtl` the OTP boxes render right-to-left while the value stays correct, so every test passes [OBSERVED lib/src/widgets/widgets.dart, Tkko/Flutter_Pinput@master] |
| `country_picker` | Embeds its own country list and localisations, no Arabic; violates CLAUDE.md "reference lists are never hardcoded" and cannot be version-recorded on a profile. **FIB pins it — ../FIB/mobile/pubspec.yaml line 39 [OBSERVED]** |
| `flutter_image_compress` | iOS podspec pulls `SDWebImage` + `SDWebImageWebPCoder` [OBSERVED podspec] — two ObjC pods for a WebP codec we never emit, on the build we cannot compile |
| `syncfusion_flutter_signaturepad` | Commercial licence required [DOC pub.dev] |
| `flutter_form_builder` | Owns form state; Stage 13 requires drift to own it. Two owners, no conflict rule |
| `intl_phone_field` | Last published ≈3 years ago [DOC pub.dev] |
| `dropdown_search` / `searchfield` | Not defective; the picker is used 6× over one drift row shape, so one bespoke page is cheaper than six configurations |

## RTL — the four LTR islands
Flutter's framework RTL handles layout. Four fields must be forced **back** to
`TextDirection.ltr`: account number (1a), phone (1b), OTP digits (2), monthly expenses (4).
All four must also normalise Arabic-Indic digits (٠-٩, ۰-۹) to ASCII **before**
`FilteringTextInputFormatter.digitsOnly`, which filters on `[0-9]` and silently discards
them. Write one `ArabicDigitInputFormatter`; use it in all four.

## Open items
- [ ] Widget test: `signature` pad under `Directionality.rtl`, assert `points.first.dx < points.last.dx`
- [ ] Confirm `phone_numbers_parser`'s E.164 getter name against 9.0.25 [UNVERIFIED]
- [ ] S1-02: measure `instantiateImageCodec` + `encodeJpg` downscale on a physical arm64 device
- [ ] S1-03: read `image_picker` and `file_picker_darwin` podspecs against OpenSSL-Universal 3.3.3001
- [ ] iOS support restated per CLAUDE.md at the moment each package is actually added
```

### 11.2 New card — `docs/components/backoffice-components.md` (draft in full)

```markdown
# Component: Back-office UI components

Status: researched, not integrated · Last verified: 2026-08-30
Source: docs/sessions/2026-08-30-research-client-components.md

antd **6.6.1**, MIT [OBSERVED backoffice/node_modules/antd/package.json].
React 19.2.8 · Vite 8 · `<ConfigProvider direction="rtl" locale={ar_EG}>` already wired
[OBSERVED backoffice/src/main.tsx]. DatePicker is dayjs-based (`dayjs ^1.11.11` is a direct
antd dependency) — no date-adapter needed.

## Mapping
| Need | Component | Not covered |
|---|---|---|
| Profile list | `Table` (+`Tag` for status/provenance) | Cross-field search; all filtering/sorting must be server-side |
| Date-range filter | `DatePicker.RangePicker` | **RTL unverified — see open items** |
| Single-profile data | `Descriptions` | v6: `size="middle"`→`"medium"`, `"default"`→`"large"` |
| Status history | `Timeline` | `mode` is physical, not logical — verify under RTL |
| Images | `Image` + `Image.PreviewGroup` | Authenticated fetch (`<img>` carries no header) — AD-004 |
| Approve / reject / manual complete | `Modal` + `Form` + `Select` + `Popconfirm` | Four-eyes: needs `canApprove` from the API |
| Roles | — | **No permission model in antd.** Bespoke |
| Export trigger | `Button` + `Dropdown` | File generation is server-side (POI) |
| Dashboard (BL-001) | `Statistic`, `Progress` | **No charts.** Re-research when scheduled |

## RTL — verified from installed source
antd v6's `@rc-component/table ~1.11.1` normalises `fixed: 'left'|true → 'start'` and
`'right' → 'end'` [OBSERVED es/hooks/useColumns/index.js:43] and applies
`insetInlineStart`/`insetInlineEnd` [OBSERVED es/Cell/index.js:98,103]. **The antd v5 RTL
fixed-column inversion (#52942, closed and labelled for 6.x) is fixed in the version
installed.** Any v5-era snippet advising `fixed: 'right'` for RTL is now wrong.

Still open upstream: `Menu` subMenu RTL positioning (#47488), `Table` responsive column
title in RTL (#32679). `DatePicker` panel order + missing RangePicker arrow (#49664) closed
with no documented fix version — **[UNVERIFIED against 6.6.1]**.

## `Image` — the derivative/original pattern, verified
`preview?: boolean | PreviewConfig`, and `PreviewConfig` carries its **own** `src`
[OBSERVED @rc-component/image/es/Preview/index.d.ts:53]:

    <Image src={derivativeUrl}
           preview={{ src: fullResolutionUrl, onOpenChange: recordImageViewAudit }} />

`onOpenChange` is the hook for operator.md's separate "document image viewed" audit event.
v6 deprecates `visible`/`onVisibleChange`/`toolbarRender` → `open`/`onOpenChange`/`actionsRender`.

## `Table` — the standing rule
**Ant Design does no filtering and no sorting.** Declare `sorter: true` (boolean, never a
comparator), supply `filters` for menu options with controlled `filteredValue`, never write
`onFilter`, and funnel everything through `onChange` to the server. Three independent
reasons: antd filters client-side over the current page only; `ref.ar_fold` and
`sort_ordinal` have no browser equivalent and `localeCompare('ar')` will disagree; and
operator.md requires "search and filter executed" as an audit event, which a client-side
filter cannot produce. `virtual` exists but is not for us — server pagination means one page
in the client.

`column.filterSearch` searches the **filter menu's own labels**, not records, and does no
Arabic folding. Where that matters, pass it a predicate — the type allows it
(`filterSearch?: FilterSearchType<ColumnFilterItem>` [OBSERVED es/table/interface.d.ts:123])
— using the TypeScript `ar_fold` port.

## Export — server-side, not npm
`backend/` + Apache POI **5.5.1** (2025-11-30, Apache-2.0) via SXSSF streaming, for both
XLSX and CSV. `dhatim/fastexcel` 0.20.2 is a lighter substitute that changes no contract.
**Rejected:** npm `xlsx` frozen at 0.18.5 on the registry while SheetJS ships current builds
elsewhere; `exceljs` 4.4.0 last released 2024-10-19 with 659 open issues / 143 open PRs
[OBSERVED registry.npmjs.org + github.com]. Deciding reason is not licence but architecture:
the list is server-paginated, so the browser does not hold the 10,000 rows, and the export
audit event (operator, filters, row count, fields) is server-side truth.
**CSV must carry a UTF-8 BOM** or Excel on Windows renders Arabic as mojibake
[UNVERIFIED against the operators' actual Excel — OQ-008].

## Open items
- [ ] Render `DatePicker.RangePicker` under `direction="rtl"` and look at it (10 min, do first)
- [ ] Verify `Timeline` `mode` and `Image.PreviewGroup` arrows under RTL
- [ ] TypeScript `ar_fold` port + shared golden-vector fixture (third implementation)
- [ ] Choose routing / data fetching / auth-state libraries — not settled anywhere
- [ ] BL-001 charts: re-research `@ant-design/plots` 2.6.8 vs `recharts` 3.10.1 when scheduled;
      neither inherits CSS `direction` — budget a day for RTL chart configuration
```

### 11.3 Corrections to `docs/components/persistence.md`

Under **"Arabic — the rule"**, add:

```markdown
- The fold has **three** implementations, not two: `ref.ar_fold` (SQL, V0011), `arFold`
  (Dart, mobile picker query string), and `arFold` (TypeScript, back-office `Table`
  `filterSearch`). One shared golden-vector fixture pins all three. [2026-08-30 research]
- **The device folds only the query string, not the labels.** `ref.reference_item.search_ar`
  is `GENERATED ALWAYS AS (ref.ar_fold(label_ar)) STORED` [OBSERVED V0011:64], so folded
  labels arrive from the server. This narrows the Dart obligation; it does not remove it,
  and the golden vectors still apply. [OBSERVED]
- **`ar_fold` does not fold decomposed hamza.** `translate()` maps precomposed أ إ آ ٱ ة ى;
  the regexp strips U+0640, U+064B–U+0652, U+0670, U+06D6–U+06ED and bidi controls
  [OBSERVED V0011:44-49]. **U+0653–U+0655 (maddah above, hamza above, hamza below) are in
  neither set**, so an NFD-decomposed أ does not fold to ا. Low probability from a standard
  Arabic soft keyboard; decide it once — extend the range, prepend `normalize(t, NFC)`, or
  record it out of scope — and put a decomposed case in the golden vectors before the
  second implementation is written. [OBSERVED, 2026-08-30]
```

Under **Open verification items**, add:

```markdown
- [ ] `ref.reference_item.search_en` is declared (V0011:65) and populated by **no** seed
      migration — grep for `search_en` across db/migration returns exactly one hit, the
      declaration. customer.md stage 4 (SETTLED) requires substring matching across Arabic
      **and English** simultaneously; the English half has nothing to match. Fix with a
      migration (`ref.ar_fold(label_en)`, ideally generated like `search_ar`, NULL-tolerant),
      before stage 4 ships — a later fix bumps the list version, which is recorded on every
      profile submitted in between. [OBSERVED 2026-08-30]
- [ ] Nothing enforces that `admin_division`'s root `item_code` equals the `country` list's
      alpha-2 code for Sudan. Both are `'SD'` today (V0016:35, V0022 header). OQ-012's
      corrected dataset could break every address cascade with no failing test. Add a
      constraint, or state the invariant in AD-002f and assert it at publication.
      [OBSERVED 2026-08-30]
```

### 11.4 Additions to `PROJECT_PLAN.md` → *Open architecture decisions* → **AD-002f**

```markdown
Five additional requirements from the 2026-08-30 client-components research, none of which
changes a settled decision:

(d) The reference manifest ships, per item: `item_code`, `parent_code`, `label_ar`,
    `label_en`, `search_ar`, `search_en`, `sort_ordinal`, `is_active`, `extra`. This is
    `ref.reference_item`'s existing shape (V0011) and it already supports the cascading
    address pickers via `parent_code` — no schema change needed, only a wire contract that
    carries the columns.
(e) `search_en` must actually be populated (see persistence.md open items) — stage 4's
    English substring search is SETTLED and currently unimplementable.
(f) The address cascade joins two lists: `country` (flat, alpha-2 item_code) at the country
    level, `admin_division` (parent_code chain rooted at 'SD') below it. State the invariant
    that the roots match, and enforce or assert it.
(g) R-033 gains a second case: reject-reason customer-facing messages live in
    `extra` (V0011:68) and the back office must render them from there, never hardcode them
    — four of seven codes share a deliberately identical neutral message. `extra` being
    outside the `content_hash` envelope means that message can change without the list hash
    changing.
(h) Stage 1b's contact contract should carry E.164 phone numbers (see the mobile-packages
    card); the same normalisation applies to the reference manifest only insofar as
    `ar_fold` already maps Arabic-Indic digits.
```

### 11.5 Suggested new risk entries

```markdown
| R-045 | The Arabic fold now has three implementations (SQL / Dart / TypeScript) and one
  is not written yet | A divergence is silent: a customer's occupation "isn't in the list",
  an operator's search returns nothing, no error anywhere. `ar_fold` already misses
  U+0653–U+0655 (decomposed hamza), so the vectors must settle that case too. | One shared
  golden-vector fixture, versioned, consumed by all three test suites, written **before**
  the second implementation, not after the third. See docs/components/persistence.md. | 🔴 Live |

| R-046 | Stage 4's SETTLED English substring search cannot work — `ref.reference_item.search_en`
  is populated by no migration | The bank's own occupation labels (خياط="Needle",
  محاسب="Amenable") are all a bilingual user has to search on. Cheap to fix now; after
  launch the fix bumps a reference list version that is recorded on every profile submitted
  in between, splitting the campaign's data across two versions for no reason. | One
  migration: `search_en` generated from `ref.ar_fold(label_en)`, NULL-tolerant, with a
  trigram index matching `reference_item_search_trgm`. | 🔴 Live |
```

---

## 12. Noticed in passing

Not expanded, not part of the question, recorded so it is not lost.

- **`../FIB/mobile/pubspec.yaml` carries `screen_protector: ^1.5.2`** [OBSERVED line 44]. AD-001's rationale for rejecting a browser/PWA rests explicitly on screenshot blocking. Nothing in this repository has chosen a screenshot-blocking package, and Uqudo's own `disableSecureWindow()` (which we correctly never call) protects only the SDK's screens — not stages 3–6, not stage 9's Civil Registry review, not stage 11's signature. That is a gap with a named justification behind it. Not researched here.
- **`flutter_localizations` + `intl` are not yet in `mobile/pubspec.yaml`** [OBSERVED — the file is still the `flutter create` default plus `cupertino_icons`]. Arabic-first cannot start without them, and FIB's pubspec carries a comment about `intl` being version-pinned by `flutter_localizations` on its Flutter version — expect the same pin negotiation on 3.47.0.
- **antd 6 ships `Input.OTP`** (`otp?: OTPConfig` on `ConfigProviderProps` [OBSERVED config-provider/index.d.ts:30]). Irrelevant to the back office, which has no OTP screen — noted only because it is the component `pinput` would be on the web, and it would have the same RTL question.
- **antd 6 ships three components with no v5 equivalent** — `listy`, `masonry`, `border-beam` [OBSERVED es/ directory listing]. None is needed here; noted because a v6-only component is a one-way door if the version is ever rolled back.
- **`backoffice/` lints with `oxlint`, not ESLint** [OBSERVED package.json]. Nothing checked whether oxlint has rules covering the React/antd patterns this report recommends (controlled `filteredValue`, deprecated v6 props). Not researched.

---

## Commit/push proof

```
$ git log --oneline -1
00f8569 docs: research client component selection (Flutter + Ant Design)

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
