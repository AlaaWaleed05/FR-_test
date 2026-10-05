# S5-05 — Stages 3-6 data-entry screens

Mobile-only session. Backend and backoffice untouched — the stages 3-6 wire contract
(`DataEntryController`/`DataEntryService`, built S3-11/S4-04) was read, never edited.

## 0. Commit check

`git log --oneline -3` at session start showed S5-04's stated hash (`9ceeabb`) as `HEAD`'s
ancestor on `main`, tree clean. Confirmed before planning.

## 1. What was built

- `core/text/arabic_fold.dart` — `arFold`, a character-for-character Dart transcription of
  `ref.ar_fold()` (`V0011__ref_registry_and_fold.sql`). No client-side fold implementation
  existed before this session (checked `core/text/`, `core/reference/`); this is the first.
  Applied only to the user's typed query, never to labels (`searchAr`/`searchEn` arrive
  pre-folded and hash-verified — client rule 6).
- `core/text/arabic_noun_agreement.dart` — `ArabicNounAgreement`, generalising
  `ChannelVerificationScreen`'s S5-04 `_minutesPhrase` (1/2/3-10/11+ forms) rather than writing
  a second copy. That screen now calls the shared helper; Stage 3's children-count field uses
  a `children` instance.
- `core/dataentry/` — `DataEntryApi`/`DioDataEntryApi` (four stage endpoints),
  `DataEntryRepository` (draft persistence, version pinning, `prepareCatalog`, the offline
  queue), `data_entry_models.dart`, `data_entry_providers.dart` — mirrors `core/entry/`'s
  shape exactly.
- `session_database.dart` schema v3→v4: `DataEntryDraft` (every stage 3-6 field, kept as a
  table separate from `LocalDraft` — disclosed deviation from AD-005 §8's literal wording, same
  reasoning that already makes `PinnedReferenceVersions`/`LocalProgress` separate from
  `LocalDraft`), `DataEntryIncomeSources`, `PendingStageSync` (the offline queue itself).
- `features/dataentry/` — `Stage3Screen`..`Stage6Screen`, `ReferenceItemPickerScreen`/
  `PickerField` (one reusable full-screen search picker for every reference-list selection —
  occupation, country ×4, admin_division state/locality ×5), `AddressCascadeFields` (the
  country→state→locality cascade shared by stages 5/6), `SalaryCertificateField`.
- Stage 2→3 boundary: `ChannelVerificationScreen._onNext` now calls
  `DataEntryRepository.prepareCatalog()` (syncs, then activates+pins `country`,
  `admin_division`, `occupation`, `income_source`, `education_level`) before advancing past
  Stage 2; failure blocks on Stage 2 with a retry (client rule 5).
- `entry_models.dart`/`entry_repository.dart`: `DataEntryStage` enum, `ResumeDataEntry`
  (new `LaunchDecision` variant for stages 3-6), `ResumeVerified` repurposed (`'verified'` →
  `'beyondStage6'`; `SessionPendingScreen` unchanged, just its trigger and copy).
- `bin/live_data_entry_flow_proof.dart` — new live proof script, mirrors
  `live_entry_flow_proof.dart`'s structure/redaction discipline.
- `image_picker` 1.2.3, `file_picker` 12.1.3, `image` 4.9.2 added (exact-pinned, matching this
  project's convention) for Stage 6's local-only salary-certificate capture — iOS support
  verified at the moment of addition: `image_picker_ios`/`file_picker_darwin` podspecs depend
  only on `Flutter`/`FlutterMacOS`, no third-party pod, no OpenSSL — closing two previously
  `[UNVERIFIED]` items in `docs/components/mobile-packages.md`.

## 2. Arabic fold — what existed, what was built

No Dart port of `ar_fold` existed anywhere in the client before this session (BL-014 names a
Dart port as one of three planned implementations, not yet written). `arFold` was written by
reading the actual SQL function's `translate`/`regexp_replace` arguments directly, not
re-derived from a general Arabic-normalisation recipe — same character-class ranges, same
hamza/ة/ى mapping, same digit folding, same omission (a decomposed hamza, ا + combining
U+0654, is not folded — matching the SQL's own documented gap, BL-014). Consistency was proven
three ways: a unit test against the SQL's own equivalence classes; a widget test using the
REAL seeded occupation labels (items 97/98, 38 — see §4); and, live, `arFold` output
cross-checked byte-for-byte against a raw `ref.ar_fold()` call over the same strings via
`psql` (§5c) — not merely asserted consistent, verified against the actual function.

## 3. Salary certificate — scope disclosure

Stage 6's optional certificate has no backend upload path: `Stage6Request` carries no such
field, and S3-11's own report already named this out of scope, blocked on AD-004 (object
storage, still an open architecture decision). Built local-only: camera capture or file
selection, on-device downscale, stored as a path in `DataEntryDraft`. Filed as **BL-022**
(BACKLOG.md) rather than left as a source comment — the file never leaves the device, and
wiring the real upload needs AD-004 to settle first. R-025 (RISKS.md) updated to disclose this
as a new, unencrypted, out-of-database PII artifact — a new exposure class, not a regression of
what R-025 actually closed (retired status unchanged).

## 4. Gate output (mobile only)

```
fvm flutter analyze
Analyzing mobile...
No issues found!

fvm flutter test
...
+225: All tests passed!

fvm dart run tool/check_coverage.dart
Line coverage: 85.03% (2181/2565 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

New test files: `arabic_fold_test.dart`, `arabic_noun_agreement_test.dart`,
`data_entry_repository_test.dart` (13 cases incl. the concurrency/rejection regressions),
`reference_item_picker_test.dart` (the أ/ا و ة/ه proof, §5b), `stage3..6_screen_test.dart`,
`data_entry_navigation_test.dart` (the back-navigation proof, §5d), `salary_certificate_field_test.dart`.

## 5. Proofs (§5 of the task)

**(a) All four stages online**, real seeded codes (occupation `2`, country `SD`,
admin_division `31`/`3103`), driven through the real `DataEntryRepository`/`DioDataEntryApi`
against the running backend, verified by `psql` reading `app.profile_customer_data` back —
see `bin/live_data_entry_flow_proof.dart` §a.

**(b) Fully offline, then reconnect — the headline proof.** The same repository classes,
pointed at an unreachable host (`http://127.0.0.1:1` — the closest equivalent to aeroplane
mode reachable from a script; no physical/emulator device was available this session, same
disclosed constraint as prior mobile sessions), submitted all four stages: all four queued
(`landed=false`, 4 `PendingStageSync` rows), none lost. Reconnected, called `flushPending()`:
all four landed, verified via the same `psql` read, `PendingStageSync` empty afterward.

**(c) Back-navigation with an edit surviving forward navigation.** Automated integration test
(`data_entry_navigation_test.dart`) drives the REAL four screens through a REAL shared
`SessionDatabase` via one router: stage3→4→5→6, back three times to stage3, edits ethnicity,
forward again through 4→5→6. Assertion is on what was actually POSTed the second time
(`api.lastStage3Args!['ethnicity'] == 'عربي مُعدَّل'`), not just what a field displays. This
test caught a real bug (see §6).

**(d) Occupation-picker Arabic search**, real seeded labels
(`V0014__seed_occupation.sql`): item 97/98 "استاذ جامعي"/"استاذ مساعد" (plain alef) matched by
querying "أستاذ" (hamza); item 38 "دعاية واعلان" (taa marbuta) matched by querying "دعايه"
(haa). Proven twice — a widget test (`reference_item_picker_test.dart`) and live against the
real fetched document (`bin/live_data_entry_flow_proof.dart` §c).

**(e) Cascade**: `stage5_screen_test.dart` proves selecting a state populates its localities
and changing state clears a previously-chosen locality; selecting a non-Sudan country falls
back to free text (both required cascade behaviours).

**(f) Exactly one primary income source** enforced in the UI (`stage4_screen_test.dart`: Next
is blocked with a source selected but none primary); the backend's own validation is
untouched, and its rejection surfaces as the same generic honest message every other
data-entry 400 does (no wire detail exists to be more specific — matches
`ContactChannelsRejectedException`'s established precedent).

**(g) Monthly expenses** rejects non-digits via `ArabicDigitInputFormatter` +
`FilteringTextInputFormatter.digitsOnly` (already the "four LTR islands" pattern), hint text
visible (`stage4_screen_test.dart`).

**(h) Arabic count agreement for 1, 2, 3, 11**: `stage3_screen_test.dart` drives the live
children-count label through all four forms (طفل واحد / طفلان / 3 أطفال / 11 طفلاً).

**(i) `flutter analyze` clean, coverage 85.03%** — §4.

## 6. Review disposition

`@agent-reviewer` ran twice per CLAUDE.md.

**First pass**, 7 SHOULD-FIX, all fixed with regression tests: a lost-update race between a
background `flushPending()` sweep and the customer's own "Next" resubmitting the same stage
concurrently (per-stage FIFO mutex); `flushPending()` could leak an uncaught rejection into an
unhandled async error (now never throws; a rejection drops that stage, a terminal profile
stops the sweep); Stage 3 rendered every marital-status label in its FEMININE form before a
sex was chosen — null was being treated as female, contradicting customer.md's own stated
reason sex is asked first (gated the block on `_sex != null`); BL-022 filed; a `mounted` check
left two awaits unguarded in `Stage3Screen._load`; the salary certificate was unencrypted PII
`abandon()` never deleted, plus a downscale bug comparing only image width against the resize
limit (both fixed, the resize target extracted into a unit-tested pure function); two required
behaviours (offline navigation, the Stage-2 blocking branch) had no test.

**Second pass, against the fixed diff, found the first pass's own salary-certificate fix
incomplete** — this project's pattern of a second pass catching a gap in the first pass's own
fix continues: the file-delete was wired into `abandon()` only, leaving `launchDecision()`'s
own two direct session-clear paths (terminal/inactive account — the MORE likely real-world
trigger than abandonment — and the defensive retry fallback) still orphaning the file (closed:
one shared `EntryRepository._clearSession()`, try/catch around the delete so a filesystem
failure never blocks the session actually clearing); `_deleteExistingFile` ran BEFORE the
replacement was confirmed written, so a decode failure left the UI claiming an attachment that
had just been deleted (reordered: delete only after the new file lands); the retired
`'verified'` resumeStage value had its table SCHEMA migrated (v4) but not this free-text VALUE
itself, so an S5-04-build device would silently resume to contact-channel selection instead of
Stage 3 (closed with an explicit `case 'verified'` mapping, regression-tested); RISKS.md's
R-025 still read as if local storage were closed for everything Stage 3-6 would add, not
disclosing the new out-of-database certificate file (added as a disclosed residual); the
per-stage mutex's own doc comment overclaimed a residual staleness window in the flush path as
fully closed (reworded to name it explicitly). Every fix re-verified: `flutter analyze` clean,
225/225 tests, coverage 85.03%, live proof re-run clean (§5).

## 7. Out of scope, confirmed

Stages 7 onward, Uqudo, the signature, the back office, authentication, iOS compilation,
BL-021/BL-012 (backend) — untouched. No backend or backoffice file in this diff.

## 8. Commit and push

```
$ git log --oneline -1
338e953 feat: S5-05 — Stage 3-6 data-entry screens, offline queue, Arabic fold

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed straight to `main` (`3f6aafd..338e953`), per this project's branch-per-session-is-not-
the-norm convention — no environment forced a branch this session.
