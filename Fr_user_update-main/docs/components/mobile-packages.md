# Component: Mobile third-party packages

Status: partially integrated (S5-01 added the app-shell/persistence/networking set below) ·
Last verified: 2026-09-01
Source: docs/sessions/2026-08-30-research-client-components.md;
docs/sessions/2026-09-01-s5-01-mobile-foundation.md;
docs/sessions/2026-09-01-s5-03-local-encryption.md

Flutter 3.47.0 (mobile/.fvmrc) · Android minSdk 24 / compileSdk 37 (raised from 36 at S5-03 —
`flutter_secure_storage` 11.0.0 requires it) / targetSdk 36,
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

**Addition (AD-002f, 2026-08-31, S4-03):** `crypto` 3.0.7 — dart.dev (verified publisher),
BSD-3-Clause, platforms "Android, iOS, Linux, macOS, web, Windows", pure Dart with
`typed_data` as its only dependency, no native/platform code
[DOC pub.dev/packages/crypto, read 2026-08-31]. No CocoaPods surface, therefore no
interaction with Uqudo's pinned OpenSSL. Needed so the mobile client can compute SHA-256
over a downloaded reference-list document and verify it against the manifest's
`contentHash` before trusting it (AD-002f). Hashing was not among the needs this table's
own research evaluated, so this is an addition, not a reversal of anything above. **Added to
`mobile/pubspec.yaml` at S5-01** — see the "Added at S5-01" table below.

## Added at S5-01 — app shell, persistence, networking

Resolved via `fvm flutter pub add` (never guessed) and pinned exact, matching this table's own
convention. iOS evidence below is filesystem-observed against the actual installed package in
the local pub cache, not the pub.dev summary page — more reliable, since a plugin's declared
platform list does not say *how* a platform is implemented.

| Package | Version | Licence | Native code | iOS evidence |
|---|---|---|---|---|
| `drift` | 2.34.3 | MIT | **None** | No `ios/`/`darwin/` dir, no podspec anywhere in the package [OBSERVED pub cache, 2026-09-01]. Already iOS-evidence-gathered in docs/components/persistence.md (2026-08-22) |
| `sqlite3` | 3.5.2 | MIT | Native SQLite, but via Dart **native-assets build hooks** (`hook/` dir, declares `hooks`/`code_assets`/`native_toolchain_c` in `pubspec.yaml`), **not** a CocoaPods podspec [OBSERVED pub cache: no `ios/`, no podspec] — the material fact for R-003/R-025: no CocoaPods entry, so no possible conflict with Uqudo's pinned `OpenSSL-Universal 3.3.3001`, unlike the rejected `sqlite3_flutter_libs`/`sqlcipher_flutter_libs`. **S5-03**: `mobile/pubspec.yaml` now selects the `sqlite3mc` binary flavor (`hooks: user_defines: sqlite3: source: sqlite3mc`) to encrypt `session.sqlite` — same package, same hook, no podspec regardless of flavor; the package's own `doc/hook.md` attributes the OpenSSL dependency to the `sqlcipher` flavor only, never `sqlite3mc`. See docs/sessions/2026-09-01-s5-03-local-encryption.md |
| `dio` | 5.11.0 | MIT | **None** | No `ios/`, no podspec [OBSERVED pub cache]. Native platform selection (`IOHttpClientAdapter` vs `BrowserHttpClientAdapter`) is a pure-Dart `dart:io`/`package:web` split, not a plugin |
| `flutter_riverpod` | 2.6.1 | MIT | **None** | No `ios/`, no podspec [OBSERVED pub cache]. Pure Dart/Flutter widget code, no platform channels. (3.4.2 is the pub.dev-latest but incompatible with this project's current SDK constraints — `flutter pub add` itself resolved 2.6.1, not a manual downgrade) |
| `go_router` | 18.0.0 | BSD-3-Clause | **None** | No `ios/`, no podspec [OBSERVED pub cache]. Declarative routing on top of `Navigator`/`Router`, no platform channels |
| `path_provider` | 2.1.6 | BSD-3-Clause | Federated; iOS implementation is `path_provider_foundation` 2.6.0 | **No podspec at all** [OBSERVED pub cache] — this version implements iOS/macOS via `dartPluginClass: PathProviderFoundation` plus `package:ffi` + `package:objective_c` (Dart FFI bindings straight into Foundation), not a CocoaPods-mediated platform channel. Zero CocoaPods surface, therefore zero possible interaction with Uqudo's pinned OpenSSL — a stronger result than the plan anticipated (a podspec was expected; there is none) |
| `path` | 1.9.1 | BSD-3-Clause | **None** | dart-lang (Dart team), pure Dart, no `ios/`, no podspec [OBSERVED pub cache]. Added alongside `path_provider` for cross-platform file-path joining — not one of the six planned packages, disclosed here for that reason |
| `crypto` | 3.0.7 | BSD-3-Clause | **None** | Actually added to `mobile/pubspec.yaml` at S5-01 — admitted at S4-03 (AD-006 amendment) but not added then, since no client code existed yet. iOS evidence unchanged from the AD-006 finding below: pure Dart, `typed_data` its only dependency |
| `path_provider_platform_interface` 2.1.3, `plugin_platform_interface` 2.1.8 | — | BSD-3-Clause | **None** | Dev-dependency only (test-time platform-channel mocking for the native database-opening factories, see below); pure Dart, never shipped in a release build |
| `drift_dev` 2.34.5, `build_runner` 2.16.0 | — | MIT / Apache-2.0 | **None** | Dev-dependency, build-time codegen tools only — never compiled into the app binary on any platform, so "iOS support" as a runtime question does not apply. Recorded here for completeness against CLAUDE.md's "every package added" rule |

**No package added at S5-01 links OpenSSL or any native crypto**, and none carries a CocoaPods
podspec at all — the strongest form of "no OpenSSL collision" this project has recorded for any
package set so far (R-003/R-025).

**Why `path_provider` is needed at all**: `drift_flutter` (the package that would otherwise
resolve a writable sqlite file path) is explicitly rejected in docs/components/persistence.md —
it still depends on the EOL `sqlite3_flutter_libs`/`sqlcipher_flutter_libs`. `path_provider`
resolves `getApplicationSupportDirectory()` by hand instead. The two local databases'
file-opening code (`reference_database_native.dart`, `session_database_native.dart`) is kept in
its own file, deliberately separate from the drift schema/query code (`reference_database.dart`,
`session_database.dart`), because `path_provider` transitively pulls in the Flutter engine
(`dart:ui`) — importing it from the schema file would make that file, and anything that only
needs its schema/queries (unit tests, `bin/live_reference_fetch_proof.dart`), uncompilable under
plain `dart run` [OBSERVED live, 2026-09-01: `dart run` failed with `dart:ui`/`Offset`
compilation errors from deep inside the Flutter framework before this split existed].

## Added at S5-03 — session database encryption (closes R-025)

| Package | Version | Licence | Native code | iOS evidence |
|---|---|---|---|---|
| `flutter_secure_storage` | 11.0.0 | BSD-3-Clause | iOS entirely delegated: the main package's `pubspec.yaml` declares `ios: default_package: flutter_secure_storage_darwin` and has no `ios/` directory or podspec of its own [OBSERVED pub cache, 2026-09-01 — corrected under review from an earlier read that had mistakenly checked the cached 9.2.4 copy, which does carry its own podspec] | `flutter_secure_storage_darwin` 0.4.0's `darwin/flutter_secure_storage_darwin.podspec` depends only on `Flutter` — no OpenSSL, no third-party pod. Pure Swift wrapping Keychain (`kSecClassGenericPassword`) APIs directly. iOS deployment target 13.0, below this project's 15.0 floor |

Resolved via `fvm flutter pub add flutter_secure_storage` (never guessed) — landed at exactly the
version the AD-005 research had already evaluated (§8), unchanged since 2026-08-22. Stores the
`sqlite3mc` encryption key generated by `generateSessionEncryptionKey()`
(`mobile/lib/core/security/session_key_store.dart`); never itself linked against OpenSSL, so it
does not reopen R-003 either. On Android, 11.0.0's default `AndroidOptions()` uses RSA-OAEP key
wrapping + AES-GCM data encryption via the Android Keystore — the `encryptedSharedPreferences`
option this project's AD-005 research originally cited was removed outright in this major and
replaced by this stronger default [CHANGELOG.md, juliansteenbakker/flutter_secure_storage].

**No package added at S5-03 links OpenSSL or any native crypto**, keeping the OpenSSL-collision
count at zero for every package this project has added so far (R-003).

## Added at S5-05 — Stage 6 salary-certificate capture (local only)

Resolved via `fvm flutter pub add image_picker file_picker image` — one minor-patch bump above the
versions this table's own research approved (`file_picker` 12.1.2 → 12.1.3, a patch release; no
other version drifted). No backend endpoint exists to upload this attachment to (AD-004 closed at S5-06 — see
docs/components/persistence.md — but the upload endpoint itself is BL-022, still open), so
these packages are wired to local capture/downscale/storage only, never a network call.

| Package | Version | iOS evidence |
|---|---|---|
| `image_picker` | 1.2.3 | Depends on `image_picker_ios` (resolved 0.8.13+7) — its podspec [OBSERVED pub cache, 2026-09-01] depends only on `Flutter`, `platform :ios, '13.0'`, no third-party pod, no OpenSSL. Closes this file's own previously-`[UNVERIFIED]` open item. |
| `file_picker` | 12.1.3 | Depends on `file_picker_darwin` (resolved 1.0.4) — its podspec [OBSERVED pub cache, 2026-09-01] depends only on `Flutter`/`FlutterMacOS`, no third-party pod, no OpenSSL. Closes this file's own previously-`[UNVERIFIED]` open item. |
| `image` | 4.9.2 | Unchanged from the original research finding below — pure Dart, no `ios/` dir, no podspec at all [OBSERVED pub cache]. |

**No package added at S5-05 links OpenSSL or any native crypto** (R-003 count stays at zero).

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

## RTL — the LTR islands
Flutter's framework RTL handles layout. Values that must be forced **back** to
`TextDirection.ltr` fall into two classes. The card said "four islands" until 2026-09-07 and was
**two short**; the real count is **eight**.

**INPUT (5)** — each also normalises Arabic-Indic digits (٠-٩ U+0660-0669, ۰-۹ U+06F0-06F9) to
ASCII **before** `FilteringTextInputFormatter.digitsOnly`, which filters on `[0-9]` and silently
discards them. One `ArabicDigitInputFormatter`, used at all five:

| Field | Site |
|---|---|
| account number (1a) | `account_entry_screen.dart:130-141` |
| phone (1b) | `contact_channels_screen.dart:263-272` |
| OTP code (2, ×3 rows) | `channel_verification_screen.dart:482-495` |
| monthly expenses (4) | `stage4_screen.dart:226-238` |
| children count (3) | `stage3_screen.dart:399-408` — added S5-05; this is the one the "four" missed |

**DISPLAY (3)** — same direction rule, no formatter:

| Value | Site |
|---|---|
| national number (9) | `stage9_screen.dart:377-384` |
| reference number (12) | `confirmation_screen.dart:131-135` |
| English name (9) | `stage9_screen.dart:405` — a wholly Latin value rendered through `_field`. **Not in the 2026-09-07 design plan's own site list**; found by the pre-build baseline review |

All five inputs now also set `textAlign: TextAlign.right`. Until 2026-09-07 they set direction and
NO alignment, so the Arabic `labelText` rendered at the right edge while the field's own digits
rendered at the left edge of the same box.

### Isolate, don't just direct — and know which mechanism belongs where
A value that BEGINS with a Latin run (`FRU-000000001`) needs isolating, not merely directing. The
back office does this with `<bdi>` [OBSERVED `ProfileDetailPage.tsx:62-64`]; mobile's equivalent is
`LtrValue` in `core/text/`. It carries **two mechanisms, and they are not interchangeable**:

- **Standalone value → `LtrValue` / `LtrValue.selectable`.** A `Text` with its own `textDirection`
  is already its own bidi paragraph, and a paragraph boundary is the strongest isolation there is.
  **No control characters are inserted.**
- **Value interpolated INTO an Arabic run → `LtrValue.isolate()`**, which wraps it in FSI/PDI
  (U+2068/U+2069). Only here is there no paragraph boundary to rely on. After the 2026-09-07 copy
  pass the only such site is the picked filename at `salary_certificate_field.dart:149`.

**Do not "simplify" the first case into the second.** The confirmation screen's reference number is
a `SelectableText` precisely so the customer can copy it and quote it at a branch; embedding FSI/PDI
there would put two invisible characters into whatever they paste, and a support agent would then
search for a reference number that does not match. Asserted at `test/core/text/ltr_value_test.dart`.

## Open items
- [x] **Bundle an Arabic font** — DONE 2026-09-07. IBM Plex Sans Arabic 400/600, Unicode-RANGE
      subset (never string-scanned: a customer's scanned name can hold characters no literal here
      has), `--layout-features='*'` so `init/medi/fina/rlig/ccmp/calt` survive and the joins do not
      break. `letterSpacing: 0`, `height: 1.6`, body 16sp. Generated by
      `mobile/tool/fetch_and_subset_fonts.py`. **Device confirmation of the metrics still owed.**
- [x] **`textInputAction`** — DONE 2026-09-07, 23 fields. It was set on ZERO fields app-wide;
      traversal was ALREADY RTL-correct (`ReadingOrderTraversalPolicy` is the default and
      `Directionality` is rtl at `app.dart:26-27`) and simply never invoked. **`OrderedTraversalPolicy`
      was deliberately NOT installed**: Stage 3 mounts fields conditionally at five branch points, so a
      hand-maintained order list would break silently on the next branch.
- [x] **Page transitions** — DONE 2026-09-07. Unset, Android resolved to
      `PredictiveBackPageTransitionsBuilder`, which with no manifest opt-in falls back to
      `FadeForwardsPageTransitionsBuilder`: a ±0.25 HORIZONTAL slide with LTR semantics hardcoded, and
      no built-in Material transition mirrors for RTL. Now `FadeUpwardsPageTransitionsBuilder`, whose
      tween is `Offset(0.0, 0.25)` — **x is zero**, so it is direction-neutral by construction.
- [ ] Any BESPOKE transition must additionally pass `textDirection: Directionality.of(context)` to
      `SlideTransition`. A hardcoded `Offset(1, 0)` slides forward navigation the wrong way under RTL
      **and every test still passes**. There are currently ZERO bespoke transitions in the app —
      this item exists to keep it that way.
- [ ] Reference device is Android 9 = API 28, BELOW Impeller's API 29 threshold, so it runs the legacy
      renderer and pays first-run shader compilation. A newer test phone will not reproduce it.
- [ ] Widget test: `signature` pad under `Directionality.rtl`, assert `points.first.dx < points.last.dx`
- [ ] Confirm `phone_numbers_parser`'s E.164 getter name against 9.0.25 [UNVERIFIED]
- [ ] S1-02: measure `instantiateImageCodec` + `encodeJpg` downscale on a physical arm64 device
- [x] Read `image_picker`/`file_picker_darwin` podspecs against OpenSSL-Universal 3.3.3001 — done
      at S5-05 the moment both were actually added (CLAUDE.md hard rule), not deferred to S1-03:
      neither depends on any third-party pod, so neither can conflict with Uqudo's pinned OpenSSL
      regardless of when S1-03 itself runs. See "Added at S5-05" above.
- [ ] `flutter_secure_storage`'s real Android Keystore/iOS Keychain behavior (as opposed to the
      `SessionKeyStore` abstraction it implements, which S5-03's automated tests do exercise
      through a fake) has not been run on a physical device — no device was available this
      session, the same constraint already tracked for S1-02/S1-03. Verify alongside whichever of
      those runs first. Not a reason R-025 stays open: a lost/reset key only ever degrades to
      S5-03's discard-and-regenerate path, never to plaintext.
- [x] ~~iOS support restated per CLAUDE.md at the moment each package is actually added~~ —
      **RETIRED 2026-09-14: the CLAUDE.md rule this item tracked no longer exists.** iOS was dropped
      at AD-003 (cancelled 2026-09-10) and V1 is Android only, so no future package needs an iOS
      check and none should be recorded. The history below is kept as a record of what was verified
      while the rule stood; it is not an obligation on anyone now. Historical record — done at
      S5-01 for `drift`/`sqlite3`/`dio`/`flutter_riverpod`/`go_router`/`path_provider`/`path`/
      `crypto` and at S5-05 for `image_picker`/`file_picker`/`image` (see "Added at S5-05" above),
      all filesystem-verified against the local pub cache. Still open for `signature`/
      `phone_numbers_parser` — approved by research but not yet added to `pubspec.yaml` by any
      session (Stage 11's signature pad remains out of scope; `phone_numbers_parser` has no call
      site yet either).
