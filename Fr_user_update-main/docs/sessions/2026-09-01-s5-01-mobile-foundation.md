# S5-01 — Mobile foundation: RTL shell, local databases, reference-data client

## 0. Commit check

`git log --oneline -3`: `97937da` (S4-04 report), `78e02ec` (S4-04 code), `18bd6de` (S4-03 report).
`78e02ec` confirmed present and an ancestor of `main`; `git status` clean, branch up to date with
origin at session start. This is the first mobile session; `mobile/lib/` held only the stock
`flutter create` `main.dart`.

## 1. Packages added (`mobile/pubspec.yaml`), exact-pinned

Resolved live via `fvm flutter pub add`/`pub add dev:`, never guessed:

| Package | Version | Native code | iOS evidence |
|---|---|---|---|
| `drift` | 2.34.3 | None | No `ios/`/podspec [OBSERVED pub cache] |
| `sqlite3` | 3.5.2 | Native SQLite via Dart native-assets `hook/`, not a podspec | No CocoaPods surface at all — zero possible OpenSSL conflict (R-003/R-025) |
| `dio` | 5.11.0 | None | No `ios/`/podspec |
| `flutter_riverpod` | 2.6.1 | None | `pub add` itself resolved 2.6.1, not 3.4.2 (pub.dev-latest) — incompatible with this SDK's constraints |
| `go_router` | 18.0.0 | None | No `ios/`/podspec |
| `path_provider` | 2.1.6 | Federated; iOS via `path_provider_foundation` 2.6.0 | **No podspec at all** — implements iOS via `dartPluginClass` + `package:ffi`/`package:objective_c` FFI, not CocoaPods |
| `path` | 1.9.1 | None | dart-lang, pure Dart — 7th package (not one of the original five), needed for cross-platform file joins, disclosed |
| `crypto` | 3.0.7 | None | Pre-approved at S4-03, added now — first client code |
| `path_provider_platform_interface` 2.1.3, `plugin_platform_interface` 2.1.8 | — | dev-only | Never shipped |
| `drift_dev` 2.34.5, `build_runner` 2.16.0 | — | dev-only, build-time codegen | "iOS support" doesn't apply — never compiled into the app |

All evidence is filesystem-verified against the actual installed package in the local pub cache
(podspec/`ios/` presence), not the pub.dev summary page. **No package added links OpenSSL or any
native crypto, and none carries a CocoaPods podspec at all.** Full table with reasoning:
`docs/components/mobile-packages.md`.

## 2. Two drift databases (AD-005)

`ReferenceDatabase` (persists): `ReferenceLists` (per `(listCode, version)`, `isActiveVersion`
distinguishing active from staged — AD-002f's staging requirement), `ReferenceItems` (composite
FK to `ReferenceLists`, `ON DELETE CASCADE`, `PRAGMA foreign_keys = ON` set explicitly since
SQLite defaults it off), `ReferenceListFailures` (bounded-backoff/poison state — see §4),
`ManifestState` (singleton row for `If-None-Match`). `SessionDatabase` (cleared on
completion/abandonment): `PinnedReferenceVersions` — the session-pin half of AD-002f's rule 4,
motivated by the closed decision even though stage 3 doesn't exist yet; `clear()` has no caller
this session, stated as an explicit limitation.

**File-opening code kept out of the schema files.** `path_provider` transitively pulls the
Flutter engine (`dart:ui`); importing it from `reference_database.dart`/`session_database.dart`
would make those files — and anything that only needs their schema (unit tests,
`bin/live_reference_fetch_proof.dart`) — uncompilable under plain `dart run`. Confirmed live: the
first version of the live-proof script failed with `dart:ui`/`Offset` errors from deep inside the
Flutter framework before this split existed. Fixed by moving `getApplicationSupportDirectory()`
calls into two new files, `reference_database_native.dart`/`session_database_native.dart`,
imported only by `database_providers.dart`.

`labelEn`/`searchEn` are **nullable** on the wire — found live, not documented anywhere before
this session: several `branch` items have no English name seeded, so
`search_en = ar_fold(label_en)` is null too. The first live fetch crashed on this
(`type 'Null' is not a subtype of type 'String'`) before the DTO and drift columns were corrected.
Recorded in `docs/components/reference-data.md`.

## 3. Reference-data client (`mobile/lib/core/reference/`, `core/network/`)

`ReferenceApi` (interface) / `DioReferenceApi` (implementation, raw bytes for the list fetch,
never auto-parsed) / `ReferenceRepository` (the testable core):

- `syncCatalog()`: fetch manifest, skip lists already cached at the exact `(version, contentHash)`
  or poisoned, otherwise fetch bytes, SHA-256 (`package:crypto`) **before any JSON parse**,
  compare to the manifest's `contentHash`. Match → parse, write list + all items + clear any
  failure row in **one drift transaction**; first-ever version activates immediately, otherwise
  stages. Mismatch or unparseable-but-hash-verified → discard, never write, never adopt
  unverified data.
- `activateStagedVersion(listCode)` / `pinCurrentSessionVersion(listCode, sessionDb)`: manual
  stand-ins for the real stage-2→stage-3 session-boundary/stage-3-entry hooks (client rule 4),
  since neither hook exists yet. Called once from the demo screen's init.
- `watchActiveItems(listCode)`: the active version's enabled (`isActive`) items, `ORDER BY
  sort_ordinal` — server-computed, never re-sorted client-side.

## 4. Verification-failure handling (client rule 5) — the reviewer round's real substance

First-pass findings, all fixed (full account in §8): a **BLOCKER** — `manifest_state`'s
`If-None-Match` conditional GET let a 304 short-circuit the whole sync before any list was
evaluated, so a failed list was never retried once the server's catalogue itself stopped
changing, making poison-after-two-failures unreachable in practice. Fixed by bypassing the
conditional GET whenever any `(listCode, version)` has an unresolved failure. Fixing that
surfaced that a single failure counter conflated transient transport errors with genuine
verification failures (two dropped connections could permanently poison a list — routine on the
Sudan market this product targets, not a corner case); split into `totalFailureCount` (paces
backoff) and `verificationFailureCount` (drives poisoning). `backoffFor()` existed only as a
tested pure function with no production caller — wired into `_syncOne` as `ListSyncOutcome.
backingOff`. An unguarded parse call let one list's malformed document abort the rest of the
sync — now caught as `malformedDocument`. `demoInitProvider` discarded the sync result entirely,
so a hash mismatch resolved as success with an empty, indistinguishable-from-real-empty list.
Two NOTEs: a conflict-update path could silently demote the active flag to false with nothing
left active; `watchActiveItems` never filtered `isActive`.

## 5. App shell

`FruApp` (`core/app.dart`): `MaterialApp.router`, `Locale('ar')`, RTL **forced explicitly** via a
`builder`-level `Directionality` wrap — not left to locale inference. `go_router`: one route
(`/reference-demo`) only; no journey-stage routes stubbed. `OccupationDemoScreen`: renders the
active occupation list in `sort_ordinal` order, RTL.

## 6. Riverpod wiring

`dioProvider` (with `connectTimeout`/`receiveTimeout` — a black-holed connection must fail into
the backoff path, not hang indefinitely) → `referenceDatabaseProvider`/`sessionDatabaseProvider`
(open real files via the native factories, disposed via `ref.onDispose`) →
`referenceApiProvider` → `referenceRepositoryProvider` → `demoInitProvider` (runs `syncCatalog()`
then activate+pin for `occupation`) → `occupationItemsProvider`.

## 7. Proof

**Live two-tier proof** (`bin/live_reference_fetch_proof.dart`, `fvm dart run`, no emulator
needed): Postgres via `docker compose up -d postgres`, backend built (`./mvnw package
-DskipTests`), **`./mvnw flyway:migrate` run explicitly** — a genuine discovery this session:
`spring-boot-flyway` is `test`-scope only by deliberate S2-02 design, so the packaged jar does
NOT migrate its own schema; the first live-proof attempt hit `permission denied for table
reference_list_document` because the local database was two migrations behind (V0051/V0052) and
nothing failed loudly at startup. Documented in CLAUDE.md's Commands section so the next session
doesn't lose the same time. Publication step run once
(`--spring.profiles.active=publish-reference-documents`), then the backend run normally. Live
output, all seven lists, actual cached item counts verified against the manifest's claim:

```
Fetching reference catalogue from http://localhost:8080 ...
admin_division     version=1 itemCount=151/151 contentHash=7b4e405d370610fc710d70b7030dc2204c664d296212fe52b5e61c20d0b87523: OK
branch             version=1 itemCount=25/25 contentHash=a3ad2d0466314d5a6259d0355a136772fed43844a3f8493fe05315255db6511f: OK
country            version=1 itemCount=249/249 contentHash=ccf2b3ca29c17796d512a822d874ec516ed6d3aae871f018cccd8ceda79d7f1d: OK
education_level    version=1 itemCount=7/7 contentHash=15ae2b43f5fc8163513511c90ed2db840df7be3be25dbcfe95ec992a98f16f43: OK
income_source      version=1 itemCount=9/9 contentHash=88ef0829bfa2c84982544ef1a5345f657cf2950ae511d44ff3a1badcc997e10e: OK
occupation         version=1 itemCount=135/135 contentHash=5e2524249f3158d1e413211858af49852ee37cc7d1a732c34a7ceb2da41b2740: OK
rejection_reason   version=1 itemCount=7/7 contentHash=5ec0f9cbc9b940c009ac35662d572d0e7afe48f7d3a51c0e1b708e9e15bde55d: OK
All 7 lists fetched, verified and cached successfully.
```

**Tampered response / interrupted fetch — unit proofs, not live**, per CLAUDE.md's own
distinction (a direct wrong-value assertion needs no live capture): a real SQLite `BEFORE INSERT`
trigger forces a genuine write failure partway through the actual production
`_stageVerifiedList` transaction (driven through the real `syncCatalog()` call, not a hand-rolled
transaction bypassing the code under test), asserting both tables end up empty. Hash mismatch is
asserted directly: cache unchanged, bytes never parsed.

**Occupation list rendering**: widget test pumps the real `FruApp` (not a bespoke `MaterialApp`,
so the app's own RTL-forcing wrapper is what's actually being tested), asserts
`Directionality.textDirection == rtl` and that out-of-alphabetical `sortOrdinal` values render in
`sortOrdinal` order.

**Target**: Windows desktop dev machine, no emulator — matches "a physical device is not needed…
an x86_64 emulator or a desktop target is sufficient."

### Gate output (final, pasted verbatim)

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 21.0s)
```

```
$ fvm dart run tool/check_coverage.dart
...
00:16 +35: All tests passed!
Line coverage: 83.94% (345/411 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

`*.g.dart` (drift-generated) files are excluded from the LCOV tally, mirroring
`analysis_options.yaml`'s own analyzer exclusion — 3,700+ lines of generator boilerplate never
hand-written or hand-tested. Verified this masks no real gap: hand-written code alone is 83.94%.
35 tests total across both review passes (22 in `reference_repository_test.dart` alone).

Per-file residual gaps, all explained: `reference_database.dart` (Table column getters are
compile-time schema declarations consumed by the generator, never executed at runtime — genuinely
uncoverable by construction, not merely untested); a handful of one-line provider-wiring bodies
that construct real objects with no network I/O.

## 8. Review

`@agent-reviewer` ran twice per CLAUDE.md's rule.

**First pass**: 1 BLOCKER + 7 SHOULD-FIX + 6 NOTE. BLOCKER and all SHOULD-FIX/NOTE items detailed
in §4 above and fixed same session, each with its own regression test (13 new tests added to
`reference_repository_test.dart`, one each to `reference_database.dart`'s cascade behavior and
`dio_provider.dart`'s timeouts stated but not separately tested — a timeout is not practically
unit-testable without a real slow connection or a fake clock, accepted as configuration rather
than logic). Doc nits also fixed: a stale "crypto not added" claim, missing dev-tool entries in
the iOS-evidence table, a stale open item, and `EXECUTION_PLAN.md`'s S5-01 note.

**Second pass** (against the fixed diff): found real defects in the first pass's own fixes —
consistent with this project's established pattern. 3 SHOULD-FIX + 5 NOTE, all fixed:

- **The `demoInitProvider` throw (first pass's finding 3) had two live gaps.** Gating the throw
  on `occupationResult.isFailure` missed `backingOff` (deliberately excluded from `isFailure` —
  pacing, not a defect) and the case where a poisoned list stops appearing in the sync result
  entirely once the manifest goes back to 304-ing (`forList` returns `null`). Both leave the
  cache permanently empty with nothing thrown. Fixed by keying the throw on actual cache STATE
  instead of the sync outcome: a new `ReferenceRepository.hasActiveVersion(listCode)` checked
  after activation, independent of what `syncCatalog()` reported — this also correctly stops
  throwing once a list has ever been successfully cached, even if a later sync fails to fetch a
  newer version (rule 5: "keep the last verified version"). Two new tests: one for the
  no-cache-at-all throw, one proving an existing cache is NOT treated as failure by a later
  failed sync.
- **`backoffFor`'s bit-shift overflows at `priorFailureCount` ≈ 61**, wrapping to a small/negative
  duration and silently defeating the cap — reachable in production because
  `totalFailureCount` (unlike the poison-gated `verificationFailureCount`) is genuinely unbounded
  for a persistently-failing fetch that never hash-mismatches. Fixed by clamping before shifting.
  New test: `backoffFor(100)` and `backoffFor(1 << 40)` both return exactly the 120s cap.
- **The republication-conflict path still absorbed the republished bytes**, even after the first
  pass's fix stopped it from silently demoting `isActiveVersion`: AD-002f declares versions
  immutable, so a same-key conflict should be refused, not fetched and upserted. Added a new
  `ListSyncOutcome.immutabilityViolation`, checked before any fetch — the existing "republished
  version" test was rewritten to assert refusal (unchanged cache, bytes never even fetched)
  rather than merely "the active flag survives."

NOTE-level, also fixed: a stale failure row for a superseded OLD version was never cleared once a
NEWER version of the same list succeeded, permanently disabling the manifest's 304 optimisation
for that install (fixed — a success now clears every failure row for that `listCode`, any
version); `syncCatalog`'s own doc comment claimed a `manifest_state`-write guard that didn't
exist (the read-side bypass is what actually matters — comment corrected); the class doc's
"one list's failure does not stop the others" claim didn't scope out the one real exception (a
local database write failure is allowed to propagate, deliberately — comment narrowed); and
`docs/components/reference-data.md`'s client rule 5 text didn't record the
verification-vs-transport poisoning divergence (one paragraph added, matching this file's own
established convention for AD-002f divergences).

Zero tests existed for the demoInitProvider throw after the first pass — exactly the "the fix
itself is unproven" pattern this project's re-reviews keep finding; closed this pass with two
tests instead of the customary one, since the fix itself changed shape.

## 9. Backend/backoffice gates

Not required — no files changed in `backend/` or `backoffice/`. (The backend's local Postgres
schema WAS advanced via `./mvnw flyway:migrate`, an operational step, not a code change; no
backend source file in this repository was modified.)

## Out of scope (per task)

Any journey stage/screen beyond the occupation-list demo; Uqudo; signature capture; the back
office; authentication (AD-002e); iOS compilation (AD-003); S3-02.

## Commit

```
$ git log --oneline -1
4af2439 feat: S5-01 — mobile foundation: RTL shell, local databases, reference-data client

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main` directly (`97937da..4af2439 main -> main`) — this session ran on its
normal branch, no fast-forward needed.
