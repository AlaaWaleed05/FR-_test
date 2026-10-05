# S5-03 — Encrypt `session.sqlite`, close R-025

## 0. Commit check

`git log --oneline -3`: `d8b27c1` (S5-02 commit-proof doc), `2f7830c` (S5-02 code), `0f506a1`
(S5-01 commit-proof doc). `2f7830c` confirmed present and an ancestor of `main` (S5-02's own
report cites it as its final commit, pushed `0f506a1..2f7830c main -> main`); `git status` clean
at session start, branch up to date with origin.

## 1. Mechanism decision: reinstate `sqlite3mc`, verified safe on iOS with evidence

AD-005 §8 chose `sqlite3mc` to encrypt `session.db` but marked the iOS risk `[UNVERIFIED: ... must
be checked at S1-03, not assumed]`. S5-01 substituted plain `sqlite3` instead, closing the OpenSSL
fear but silently reopening the encryption gap (R-025). This task closed the `[UNVERIFIED]` item
directly by checking the installed package rather than deferring: `sqlite3` 3.5.2 carries **no
podspec, no `ios/`, no `darwin/` directory for any `source:` flavor** (`sqlite3`/`sqlite3mc`/
`sqlcipher`) — all three are pre-compiled binaries fetched by the same Dart native-assets build
hook (`hook/build.dart`, `native_toolchain_c`), never CocoaPods. The package's own `doc/hook.md`
attributes the OpenSSL dependency to the `sqlcipher` flavor only, on Windows/Linux/Android — never
to `sqlite3mc` (SQLite3 Multiple Ciphers embeds its own crypto); its CycloneDX SBOM note names
"SQLCipher (with an OpenSSL dependency on some platforms)" as the one flavor carrying it. `drift`'s
`NativeDatabase.createInBackground(file, setup: ...)` (already pinned at 2.34.3) documents exactly
this use: "perform a setup just after the database is opened, before drift is fully ready... to
provide encryption keys in SQLCipher implementations" — it runs before drift's own `PRAGMA
user_version` migration check.

Chosen over field-level encryption (the task's alternative path) because it protects the whole
file, including every column Stage 3–6 will add next sprint, with no per-column code to keep in
sync as the schema grows.

Enabled via `mobile/pubspec.yaml`:
```yaml
hooks:
  user_defines:
    sqlite3:
      source: sqlite3mc
```
This selects the binary for the whole `sqlite3` package — there is no per-database selector.
`reference.db` shares the binary but is never given a `PRAGMA key`, so it stays a plain,
unencrypted file, matching AD-005 §8's "not required (no PII)" call.

**Live-proven in the real Android build, not just Windows `dart run`:** after raising
`compileSdk` (below), `fvm flutter build apk --debug` succeeded, and `unzip -l` on the built APK
confirms `lib/arm64-v8a/libsqlite3mc.so` and `lib/armeabi-v7a/libsqlite3mc.so` are genuinely
bundled for both shipped ABIs — the flavor selection is proven in the artifact that ships, not
inferred from the pubspec alone.

## 2. Key management

32 `Random.secure()` bytes, hex-encoded to 64 ASCII characters (`generateSessionEncryptionKey()`,
`mobile/lib/core/security/session_key_store.dart`) — never hardcoded, never derived from anything
the app ships. Stored via `flutter_secure_storage` 11.0.0 (`SecureSessionKeyStore`,
`session_key_store_native.dart`), added with `fvm flutter pub add` and landing at exactly the
version AD-005's research had already evaluated:

- **Android**: 11.0.0's default `AndroidOptions()` — RSA-OAEP key wrapping + AES-GCM data
  encryption, both Android-Keystore-backed. The `encryptedSharedPreferences` option AD-005's
  research cited was removed outright in this major, replaced by this stronger default.
- **iOS**: Keychain (`kSecClassGenericPassword`). The main `flutter_secure_storage` package has no
  `ios/` directory at all — it delegates entirely via `ios: default_package:
  flutter_secure_storage_darwin` in its own `pubspec.yaml`; that federated package's podspec
  depends only on `Flutter` — no OpenSSL, no third-party pod. (An earlier read of this evidence
  mistakenly checked the 9.2.4 copy also in the pub cache, which does carry its own podspec;
  corrected under review, §5.)

**First run**: no key in the store → generate, store, then key the (new) file. **Every later
run**: read the stored key back and key the existing file with it. Wiring lives in
`mobile/lib/core/database/session_encryption.dart`'s `openEncryptedSessionExecutor`, called from
`session_database_native.dart`'s `openSessionDatabase()` — that function's public signature is
unchanged, so no ripple into `database_providers.dart`, `entry_providers.dart`, or any screen.

## 3. Existing plaintext `session.sqlite` — discard, not migrate

Per the task's own reasoning: one in-flight session the customer can re-enter in under a minute,
versus reading plaintext PII just to re-encrypt it. Mechanism: having no *usable* key is the
signal that a file predates encryption, because this code always writes a key before/at every
successful open of a database it created (see §4 for what "usable" excludes and why that matters).
On that signal, the file — and any `-wal`/`-shm`/`-journal` sidecars, independent of whether the
main file itself is still present — is deleted outright, a fresh key generated, and the customer
lands on a fresh Stage 1a via `LaunchScreen`'s existing no-`LocalProgress` path. Not a crash.

`reference.db` is untouched — no PII, explicitly not encrypted per AD-005 §8.

## 4. Implementation

Split for testability, mirroring the existing `reference_database.dart`/
`reference_database_native.dart` pattern: `session_key_store.dart` (pure — the `SessionKeyStore`
interface + `generateSessionEncryptionKey()`) and `session_encryption.dart` (pure —
`openEncryptedSessionExecutor`, the discard/key/`PRAGMA` logic, taking a `File` and a
`SessionKeyStore` so tests and the live-proof script can drive it against real files with no
platform channel) versus `session_key_store_native.dart` (`SecureSessionKeyStore`, the real
`flutter_secure_storage`-backed store — isolated because that package imports
`package:flutter/services.dart` and cannot compile under plain `dart run`) and
`session_database_native.dart` (`openSessionDatabase()`, resolving the real file via
`path_provider` and delegating to `openEncryptedSessionExecutor(file, const
SecureSessionKeyStore())`).

No change to `session_database.dart`'s schema, tables, or `schemaVersion` — this is purely the
file-opening layer underneath drift.

## 5. Review — two passes, both found real defects

**First pass**: 2 BLOCKER + 3 SHOULD-FIX + 3 NOTE, all fixed.

- **BLOCKER**: `flutter_secure_storage` 11.0.0 requires Android `compileSdk` ≥ 37; the project was
  pinned at 36 and the debug APK build failed outright. Fixed: `compileSdk = 37`
  (`build.gradle.kts`; minSdk/targetSdk unchanged), `PROJECT_PLAN.md`/`mobile-packages.md` updated.
  `fvm flutter build apk --debug` now succeeds; went further than "it builds" by confirming the
  packaged `libsqlite3mc.so` directly (§1).
- **BLOCKER**: `keyStore.readKey()`'s value was used as the `PRAGMA key` with no validation.
  `flutter_secure_storage`'s Android backend resets and returns the literal string `"Data has been
  reset"` from `read()` on a Keystore decryption failure (`resetOnError`, its own default) — that
  string would have silently become the encryption key for a brand-new database, and separately a
  *throwing* read would have propagated uncaught, leaving a pre-S5-03 plaintext file un-discarded
  indefinitely. Fixed: `session_encryption.dart` now validates any stored value against a
  64-char-lowercase-hex pattern and wraps the read in try/catch — both collapse to "no key stored,"
  the same safe discard-and-regenerate path. Two new regression tests assert this against a fixed
  `"Data has been reset"` key store and a throwing one.
- **SHOULD FIX**: `session_database.dart`'s `LocalDraft` doc comment still read "NO encryption at
  rest... Filed, not fixed." Reworded to cite `session_encryption.dart` and R-025's retirement.
- **SHOULD FIX**: `mobile-packages.md`'s iOS-evidence row had checked the wrong cached version
  (9.2.4, which does carry its own podspec) and stated the wrong licence. Corrected to cite only
  `flutter_secure_storage_darwin`'s podspec and BSD-3-Clause, verified against both `LICENSE` files.
- **NOTE**: the sidecar sweep was nested inside "main file exists," so a missing main file with a
  surviving `-wal`/`-shm`/`-journal` sidecar would leave it undeleted. Fixed: the sweep now runs
  whenever no usable key is found, independent of the main file's presence.
- **NOTE**: the live-proof script printed the full 64-hex key to stdout (pasted into this report).
  Fixed: a `_redact()` helper prints only the first 8 characters + a length marker.
- **NOTE**: the raw-handle-without-key test asserted only `isA<SqliteException>()`, looser than the
  task's stated proof. Tightened to assert `extendedResultCode == 26` (`SQLITE_NOTADB`).

**Second pass, against the fixed diff, found one real gap**: the code fixes were correct, but
`RISKS.md`, `persistence.md`, and one doc comment in `session_encryption.dart` still described the
pre-fix model — "the absence of a stored key is *exactly* the signal" — without mentioning that
the "never plaintext" guarantee now also depends on the key-format validator and the try/catch.
Fixed: all three reworded to state that a stored value failing the hex-format check, or a throwing
read, both collapse to "no key stored" — which is what makes the discard claim true. No new code
defect this pass; this project's "second pass finds a gap in the first pass's fix" pattern held
here in documentation rather than code.

Asked explicitly whether the key is recoverable from anything the app ships: no — it is generated
fresh from `Random.secure()` on first run and exists only in the platform keystore and the
encrypted file's own header state; nothing in the app's source, config, or shipped assets derives
or reproduces it.

## 6. Proof

**Live** (`fvm dart run bin/live_session_encryption_proof.dart`, real files, no `.forTesting()`):

```
== First run: no key, no file ==
generated key: 0c311ea7... (64 chars)
OK   key generated and stored (64 hex chars)

== Raw sqlite3 handle, NO key ==
caught (expected): SqliteException(26): while preparing statement, file is not a database, file is not a database (code 26)
  Causing statement: SELECT name FROM sqlite_master
OK   unkeyed SELECT rejected (extendedResultCode=26)

== Raw sqlite3 handle, WITH key ==
tables visible with key: [pinned_reference_versions, local_draft, local_progress]
OK   tables listed with key

== Raw byte scan of session.sqlite for plaintext PII ==
phone number "+249912345678" found in raw bytes: false
account number "0000000001" found in raw bytes: false
OK   phone number absent from raw bytes
OK   account number absent from raw bytes

== Second run: same fake key store, same file ==
key retrieved: 0c311ea7... (64 chars)
row read back: accountNumber=0000000001 phoneNumber=+249912345678
OK   same key retrieved on second run
OK   same data reads back on second run

== Discard path: pre-S5-03 plaintext file, no stored key ==
legacy plaintext file written, key store cleared
rows present after discard-and-recreate: 0
OK   legacy row discarded, not migrated
OK   a fresh key was generated for the new file

All session-encryption checks passed.
```

**Live** (real Android APK, `unzip -l build/app/outputs/flutter-apk/app-debug.apk | grep sqlite`):
```
  2001152  1981-01-01 01:01   lib/arm64-v8a/libsqlite3mc.so
  2051348  1981-01-01 01:01   lib/armeabi-v7a/libsqlite3mc.so
  2144744  1981-01-01 01:01   lib/x86_64/libsqlite3mc.so
```

**Live** (S5-02's Stage 0/1a/1b flows, `openSessionDatabase()`'s signature unchanged):
`fvm dart run bin/live_entry_flow_proof.dart` still drives invalid/inactive/active account-check
and a Stage 1b submission end to end against the real backend.

**Automated regression** (real temp files, never `.forTesting()`): first-run key generation;
second-run key/data retrieval; discard of a real pre-existing plaintext file; the raw-handle
`SQLITE_NOTADB`/success round trip; a malformed stored value and a throwing read both treated as
no-key. `database_native_test.dart`'s pre-existing `openSessionDatabase` test updated with
`FlutterSecureStorage.setMockInitialValues({})` to keep exercising the real secure-storage path.

### Gate output (final, pasted verbatim)

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 8.0s)
```

```
$ fvm flutter test
...
00:23 +117: All tests passed!
```

```
$ fvm dart run tool/check_coverage.dart
...
00:37 +117: All tests passed!
Line coverage: 88.93% (884/994 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

```
$ fvm flutter build apk --debug
...
√ Built build\app\outputs\flutter-apk\app-debug.apk
```

Mobile-only — no `backend/`/`backoffice/` files touched; those gates were not re-run.

## 7. RISKS.md and docs disposition

- **R-025: retired (✅).** Whole-database encryption implemented and proven; key lives only in the
  platform keystore, never in the database it protects; discard path removes the one plaintext
  file predating this work, with the key-validation guard closing the one path that could have let
  that guarantee quietly fail. Disclosed residual, not a reason to reopen: no physical device has
  exercised `flutter_secure_storage`'s real Keychain/Keystore behavior this session (same
  constraint already tracked for S1-02/S1-03) — filed in `mobile-packages.md`'s open items, since a
  lost/reset key only ever degrades to the safe discard-and-regenerate path.
- `docs/components/persistence.md`: new "Mobile local store — encryption" section; the S1-03 open
  item for `sqlite3mc`'s OpenSSL question closed with evidence.
- `docs/components/mobile-packages.md`: new "Added at S5-03" table for `flutter_secure_storage`;
  `sqlite3` row updated for the `sqlite3mc` flavor selection; compileSdk header corrected to 37;
  new open item for the undevice-tested `flutter_secure_storage` plumbing.
- `PROJECT_PLAN.md`: compileSdk 36→37 noted where it states Android build parameters.

## 8. Out of scope (per task)

Stage 2 onward; Uqudo; the back office; authentication (AD-002e); iOS compilation (AD-003) — the
iOS podspec evidence here is a pub-cache read, not a build; encrypting `reference.db`; any backend
change.

## Commit

```
$ git log --oneline -1
bae2e61 feat: S5-03 — encrypt session.sqlite via sqlite3mc, close R-025

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main` directly (`d8b27c1..bae2e61 main -> main`) — this session ran on its
normal branch, no fast-forward needed.
