# S5-02 — Stage 0 launch check, Stage 1a/1b screens, R-025 determination

## 0. Commit check

`git log --oneline -3`: `0f506a1` (S5-01 commit-proof doc), `4af2439` (S5-01 code), `97937da` (S4-04
report). `4af2439` confirmed present and an ancestor of `main`; `git status` clean at session
start, branch up to date with origin.

## 1. R-025 determination

**Evidence**: docs/sessions/2026-08-22-research-ad-005-persistence.md §8, "Two databases, not one"
table — `session.db` is marked **"Encrypted: yes"** against `reference.db`'s "not required (no
PII)". `sqlite3mc` was chosen specifically to satisfy this ("I chose `sqlite3mc` over `sqlcipher`
... to avoid a linker collision", not because encryption itself was optional). `session.db` holds
`local_draft` — "every customer-entered field from stages 1b and 3–6" — exactly the PII this
session starts writing (phone number, account number).

**Determination: local encryption was REQUIRED, not a preference.**

S5-01 added plain `sqlite3` instead of `sqlite3mc`, correctly closing the OpenSSL-collision half of
R-025 (`sqlite3`'s Dart native-assets build hooks carry no CocoaPods podspec — verified) but
**without closing the encryption half** — S5-01's own text and `mobile-packages.md` conflated the
two, and R-025's RISKS.md status was never actually changed to retired (the task brief's framing
of it as "marked resolved" describes only the informal S5-01 report text, not RISKS.md itself).

**Action taken**: R-025 reworded in RISKS.md, stays 🔴 Live, correctly scoped to the encryption gap.
Two closure paths filed: (a) reinstate `sqlite3mc` — the original OpenSSL fear does not reattach,
to be verified at S1-03; or (b) field-level encryption of `session.db`'s PII columns with a key
from `flutter_secure_storage`/Keychain. **Not implemented this session**, per the task's explicit
scope — `session.db` now genuinely holds unencrypted PII (phone number, account number) and this
must be decided before Stage 3 adds the rest of the customer-entered profile to the same table.

## 2. Stage 0 design — reusing `account-check`

No dedicated session-status endpoint exists and none was added (backend out of scope this
session). Stage 0's "ask the backend for session status" reuses `POST /api/v1/account-check`:
`ACTIVE`+`PROCEED` → resume; `ACTIVE`+`TERMINAL` → "already completed"; `INACTIVE`+`TERMINAL` →
"visit a branch"; `INVALID`+`RETRY` → defensive fallback, clears local state, fresh Stage 1a.

**"An in-progress session" is scoped to `LocalProgress` existing** — created only once Stage 1a's
`account-check` returns `PROCEED`, deliberately excluding a bare unsubmitted `LocalDraft` (Stage 1a
itself writes nothing server-side per customer.md: "the session is created [at 1b]"). A half-typed
1a/1b form surviving an app restart triggers no backend call at all.

**Known, disclosed gap**: Stage 0's "blocked until X" case (Stage 2's phone-lock, S3-08/R-044) has
no wire signal on `account-check` and is unreachable through this session's own screens (Stage 2 UI
doesn't exist to ever set the lock). `LaunchDecision` has no `blocked` variant; the gap is disclosed
in `EntryRepository.launchDecision()`'s own doc comment for whoever builds Stage 2.

## 3. Screens built

- **`LaunchScreen`** (`/`, new initial route) — runs Stage 0, navigates once resolved.
- **`AccountEntryScreen`** (Stage 1a) — branch picker (server reference list, `sort_ordinal`
  order) + account number (LTR, `ArabicDigitInputFormatter`).
- **`ContactChannelsScreen`** (Stage 1b) — phone (LTR + digit formatter), SMS/WhatsApp checkboxes
  (both default true, header states the requirement, group flips to an error visual — never a
  dialog — when both deselected), optional email with its own deselect checkbox once an address is
  entered, a live consequence line, an in-screen abandon action.
- **`SessionPendingScreen`** — honest placeholder for "resumed past what this sprint builds a
  screen for" (profile created, OTPs sent, Stage 2 doesn't exist); shows only `unverified` cached
  channels, offers abandon.
- **`TerminalScreen`** — generic, parameterised message.
- `core/entry/` (api/repository/models/providers) mirrors `core/reference/`'s shape;
  `core/text/ArabicDigitInputFormatter` transliterates Arabic-Indic/Extended-Arabic-Indic digits to
  ASCII before `FilteringTextInputFormatter.digitsOnly`, per `mobile-packages.md`'s "four LTR
  islands" note.
- `SessionDatabase` gained `LocalDraft` (customer-entered fields, written as typed) and
  `LocalProgress` (exists only once a session is in progress; `verifiedBranchCode`/
  `verifiedAccountNumber`, `resumeStage`, `profileId`, cached channel summary).

No new pubspec packages — the whole feature is Flutter's built-in `TextInputFormatter` plus the
already-approved `dio`/`drift`/`riverpod`/`go_router` set. Nothing new to iOS-verify.

## 4. Review — two passes, both found real defects

**First pass**: 1 BLOCKER + 8 SHOULD-FIX/NOTE, all fixed same session.

- **BLOCKER**: `LocalDraft`/`LocalProgress` were added to `SessionDatabase` without bumping
  `schemaVersion` or declaring a `MigrationStrategy`. A device with an S5-01-era `session.sqlite`
  (schemaVersion 1) would never gain the new tables — `onCreate` only fires on first-ever open —
  and Stage 0's very first statement would throw `no such table`. Every test used
  `.forTesting()` (always a fresh in-memory `onCreate`), so nothing could have caught it. Fixed:
  `schemaVersion => 3` with a real `MigrationStrategy`; new
  `session_database_migration_test.dart` opens a REAL v1 file and a real v2 file via raw
  `sqlite3` and asserts the upgrade path actually runs.
- Every `400` from `contact-channels` was shown as a specific "select a channel" message —
  verified live via curl that the backend's 400 body carries no distinguishing field, so this was
  sometimes an actively wrong message (e.g. for a malformed phone). Renamed to
  `ContactChannelsRejectedException`, a generic honest message.
- `ContactChannelsScreen` allowed submit with an empty phone field (backend-only validation).
- Both submit handlers left an unmapped failure (e.g. a core-banking 5xx) as an unhandled async
  error — silent re-enabled button, no message. Added trailing generic-catch handlers.
- `ContactChannelsScreen` read the branch/account it submits from `LocalDraft` (the customer's own,
  possibly-since-edited draft) rather than the pair Stage 1a actually validated. New
  `EntryRepository.verifiedAccount()` reads `LocalProgress` instead.
- Navigating to `/terminal` from inside either screen's own submit handler never cleared local
  state, unlike `launchDecision()`'s own TERMINAL branch — the previous customer's data stayed
  prefilled on "back to start". Both call sites now `abandon()` first.
- `SessionPendingScreen` rendered `declined` channels too, contradicting customer.md ("a customer
  who deselected WhatsApp never sees a WhatsApp row"). Filtered to `unverified` only.
- Email had no deselect control despite customer.md requiring one. Added a genuinely new
  `LocalDraft.emailSelected` column (which is what drove schemaVersion 3), its own checkbox, and
  updated the consequence text and the actual submitted payload.
- Added an abandon action to `ContactChannelsScreen` too (customer.md: available "from the resume
  screen", not only `SessionPendingScreen`).
- Documented the Stage-0 "blocked" gap explicitly (§2 above) rather than leaving it silently absent.

**Second pass, against the fixed diff, found the first pass's own fixes had 2 real gaps and 3
test-coverage gaps** — this project's now-familiar pattern:

- A genuine race: the F9 abandon-action fix let Abandon be tapped while `_load()` was still async
  in flight; `_load()`'s own `setState` (setting the pre-fill text) then fired the newly-added
  autosave listener, which could write the just-abandoned customer's phone/email straight back
  into the now-cleared `LocalDraft`. Fixed three ways: listeners now attach only after `_load()`
  sets initial text (a programmatic pre-fill no longer counts as an edit); the abandon action is
  gated on `_draftLoaded`; an `_abandoned` flag is checked before every `_saveDraft()` write,
  re-checked after its own internal `await`.
- The F5 fix (`verifiedAccount()`) never updated `canSubmit`, so Next could render enabled with a
  null branch/account and silently do nothing. Added the null check to `canSubmit` too.
- No test proved the F8 email-deselect fix actually excluded the address from the submitted
  payload (only that the UI hid it) — `FakeEntryApi` now captures the last submitted
  branch/account/email; three new tests assert deselected email is genuinely never sent, a fresh
  address after deselect-and-clear reactivates and IS sent, and (F5's own gap) the *verified* pair
  is submitted even when `LocalDraft` has since been edited to a different account.
- (NOTE, disclosed not required) email deselection reset to `true` only reactivates on an
  empty→non-empty transition, matching customer.md's "activates once an address is entered" — a
  customer who deselects, clears, then retypes now gets the checkbox back on, not silently stuck
  off.
- Stale "(v2)" reference in the new migration test's own comment, corrected.

Left open by design (NOTE-level, disclosed): the offline banner never re-checks connectivity
without an app restart; two latent (currently unreachable) non-null casts in `app_router.dart`/
`entry_repository.dart`; a stale `profileId`/channel summary can linger in `LocalProgress` across
different accounts (low impact); Stage 0 re-running `account-check` on every launch adds audit
volume on R-038's serialised chain (design trade-off, not a defect).

## 5. Proof

**Live, real backend** (branch `16`, stub-seeded accounts `0000000001` active / `0000000002`
inactive, per `application.properties`), via `bin/live_entry_flow_proof.dart` — the actual
production `DioEntryApi`, not curl:

```
Driving Stage 1a/1b against http://localhost:8080 ...
invalid account (0000009998): outcome=invalid continuation=retry requestId=...
inactive account (0000000002): outcome=inactive continuation=terminal requestId=...
active account (0000000001): outcome=active continuation=proceed requestId=...
Submitting Stage 1b for the active account ...
profileId=4756b56f-dd5a-4b6c-b526-9285b1243065
  sms: unverified •••• 5678
  whatsapp: unverified •••• 5678
Stage 1a/1b driven end to end successfully.
```

Matching `audit.audit_event` rows (`docker exec fru_postgres psql`), all three account-check
attempts on the `system`/`account_check` chain:

```
 event_type               | outcome  | resultCode
 account_check_attempted  | INVALID  | -1
 account_check_attempted  | INACTIVE | 2
 account_check_attempted  | ACTIVE   | 1
```

And on the profile's own chain: `session_created`, `otp_issued` ×2, `notification_dispatched` ×2 —
then, from a second run of the same script (re-entry, since the profile from the first run still
exists): `session_reentered`, `otp_issued` ×2, `notification_dispatched` ×2, proving the production
client correctly drives S3-07's re-entry path too, not only the fresh-profile path.

**Arabic-Indic digits → E.164**: submitted `phoneNumber: '0912345678'` (typed as `٠٩١٢٣٤٥٦٧٨` per
the client-side transliteration, unit- and widget-tested — see below); confirmed stored as
`+249912345678` in `app.profile_customer_data.phone_number` via the same psql session — the
backend's own E.164 normalisation (BL-016) closing the loop.

**Both phone channels deselected → Next disabled**, group shows its error state in the header's own
position (no new dialog): direct widget assertion, `contact_channels_screen_test.dart`.

**Abandonment clears the session database, returns to a fresh 1a**: direct widget assertion against
a real (non-`.forTesting()`-mocked-away) drift database and a real `GoRouter`,
`session_pending_screen_test.dart` and `contact_channels_screen_test.dart`.

**Offline indicator**: `EntryRepository.launchDecision()` catching `BackendUnreachableException`
and returning `offline: true` with local state intact — direct repository- and widget-level
assertions (`entry_repository_test.dart`, `contact_channels_screen_test.dart`). Per CLAUDE.md's
live-proof discipline, these are direct assertions a broken guard could not pass against, so no
live capture was taken for this one.

### Gate output (final, pasted verbatim)

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 7.1s)
```

```
$ fvm dart run tool/check_coverage.dart
...
00:36 +109: All tests passed!
Line coverage: 88.59% (862/973 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

Mobile gates only — no files changed in `backend/` or `backoffice/` (the live proof ran against
the already-built/migrated backend from a prior session; no backend source touched).

## 6. Out of scope (per task)

Stages 2 onward; Uqudo; the signature; the back office; authentication (AD-002e); iOS compilation
(AD-003); implementing local encryption (R-025 filed, not fixed).

## Commit

```
$ git log --oneline -1
2f7830c feat: S5-02 — Stage 0 launch check, Stage 1a/1b entry screens

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main` directly (`0f506a1..2f7830c main -> main`) — this session ran on its
normal branch, no fast-forward needed.
