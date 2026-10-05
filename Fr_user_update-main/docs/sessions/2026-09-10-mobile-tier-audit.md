# Mobile-tier audit — 2026-09-10

**Audit, not a build. No application code was changed in any tier.** Every defect is FILED.
Files edited: `PROJECT_PLAN.md`, `EXECUTION_PLAN.md`, `BACKLOG.md`, `RISKS.md`, and this
document plus the session report.

## How to read the provenance markers

Every finding carries two things: **where the evidence came from**, and **who established it**.

- `[OBSERVED]` — I read the file or ran the command named on the line.
- `[DOC]` — a document says it; the link is given. A plan-file claim is a `[DOC]` claim, never
  evidence.
- `[UNVERIFIED]` — inference, or a subagent conclusion I could not confirm at source.

And, per the audit rule that a backlog id may only be minted from source:

- **VERIFIED HERE** — I read both sides myself this session. Only these earned a BL/R id.
- **SUBAGENT, NOT VERIFIED** — a delegated conclusion I did not confirm. Reported because it is
  worth reporting; **deliberately given no id.** Filing an id makes it plan-file truth, and this
  project has already filed a row for a defect that did not exist (BL-061, closed as not
  reproducible).

Eight subagents did the reading for Parts B, C and D. Their conclusions are inputs, not findings.

---

# STEP 0 — the ground

## (a) Commit 90ab089 [OBSERVED]

```
$ git cat-file -t 90ab089
commit
$ git merge-base --is-ancestor 90ab089 main && echo yes
yes
$ git log --oneline -1 90ab089
90ab089 S8-10: in-place email correction on the Stage 2 OTP row (BL-101, BL-098)
```

**It exists and it IS an ancestor of `main`.**

## (b) What else 90ab089 swept in — `Design_3/`, 91 files, ~4.6 MB

**Nothing forbidden is in it. The audit proceeded.** I inspected every binary rather than
inferring from filenames.

| CLAUDE.md rule | Result | How established |
|---|---|---|
| No identity-document image | **None** | [OBSERVED] All 4 raster uploads viewed directly: `uploads/SFB-Screenshot-1.jpg` and `-5.jpg` are the bank splash and header bar; `uploads/Last SFB.png` and `assets/sfb-logo-*` are the bank emblem and wordmark. `uploads/Color Paltted.pdf` I decoded: one image XObject, `/Indexed 8 0 R 3 <FFF2105D F3D92AA3 E8D258B5 E0CD7ABE>`, 536×600 — **a four-colour indexed swatch strip. A photograph cannot exist in 4 colours.** |
| No real customer data | **None** | [OBSERVED] The only PII-shaped strings across all markup are the placeholders `12345678901` and `mohamed@example.com`. No `+249` number anywhere. |
| No credential | **None** | [OBSERVED] Every hit for `password\|secret\|api_key\|token\|bearer\|private key\|AKIA` resolves to a CSS **design token**. |

Content is bank brand assets, a design-system token sheet, 9 component cards, 15 screen mockups,
and a splash-screen + banner design — the artefacts Part F asks about. It belongs to the project.

**Filed as R-053** (VERIFIED HERE): the *practice* is the risk, not this instance. A `git add -A`
at session end bypasses the only moment those three rules are checked. The same commit also
duplicated ~2.9 MB byte-for-byte inside `Design_3/` — `sfb-logo-master-2048.jpg` (1,118,279 B)
at `assets/` and `docs/brand/`; `sfb-logo-circle.png` (1,102,699 B) at `assets/` and
`design_handoff_sfb_app/assets/`; `Last SFB.png` = `source-logo-original.png` (781,648 B).
[OBSERVED]

## (c) Mobile gates, as committed [OBSERVED]

| Command | Result |
|---|---|
| `fvm flutter analyze` | PASS — `No issues found!` |
| `fvm flutter test` | PASS — 540 tests |
| `fvm dart run tool/check_coverage.dart` | PASS — 84.97% (3896/4585) |

Final verbatim output, including the release build, is in the session report.

---

# PART A — independent review of S8-10

## A.1 The report's four source claims, checked against live source

All four were checked by me. **Three hold exactly. One holds but is understated, and its backlog
row is wrong.**

**A.1.1 — "A `TOO_SOON` refusal spends no cap budget." HOLDS. VERIFIED HERE.**
[OBSERVED `OtpVerificationService.java:366-404`] `reserveResend` returns `TOO_SOON` at `:399`;
`incrementResendCount` is at `:403`, reached only by the success path. `ALREADY_VERIFIED` (`:380`),
`CHANNEL_LOCKED` (`:383`) and `CAP_EXHAUSTED` (`:386`) all return earlier still. The feared
interaction — a customer driving themselves into `CAP_EXHAUSTED` by correcting a typo repeatedly —
does not exist.
*Minor:* the report cites `incrementResendCount` at `:404`; it is at `:403` (`:404` is the
`return`). Off-by-one in the citation, conclusion correct. Not a finding.

**A.1.2 — the `ArabicNounAgreement` / «ث» reasoning. HOLDS. VERIFIED HERE.**
[OBSERVED `mobile/lib/core/text/arabic_noun_agreement.dart`] The class varies four bands for
spelled-out counted nouns and carries a single `dual` — «دقيقتان», the nominative. «ث» is a unit
symbol, does not inflect, and routing it through the helper would introduce a genitive error after
«بعد» that the helper cannot express. The report's reasoning is sound.

**A.1.3 — BL-102 IS UNDERSTATED. This is a finding. VERIFIED HERE.**
BL-102 says "the one production caller is a preposition." [OBSERVED — grep for `.phrase(` across
`mobile/lib`] There are **three** production call sites, and **all three sit after «بعد»**:

| # | Site | Helper | n == 2 renders | Required |
|---|---|---|---|---|
| 1 | `channel_verification_screen.dart:426` | `minutes` | «بعد دقيقتان» | «دقيقتين» |
| 2 | `scan_blocked_view.dart:111` | `minutes` | «بعد دقيقتان» | «دقيقتين» |
| 3 | `scan_blocked_view.dart:108` | **`hours`** | **«بعد ساعتان»** | «ساعتين» |

Site 3 is the one BL-102 misses entirely, and it is not an edge case: it is the ordinary 24-hour
scan/liveness block screen as it counts down through two hours remaining. Fixing only the caller
BL-102 names would leave two-thirds of the defect in place. **BL-102 amended.**

**A.1.4 — the `_updateChannelMask` race argument. HOLDS. VERIFIED HERE.**
[OBSERVED `entry_repository.dart:329-405`] The corrected argument is right: without the
transaction, this method's `SELECT` completes and yields, `_markChannelVerified`'s transaction
opens and completes, and this method then writes the pre-verification snapshot. The other
transaction cannot prevent that — it serialises what queues behind it, not a read already taken.
The guard is load-bearing. The reasoning also correctly records that the test pins the pair rather
than isolating this guard.

**A.1.5 — "No revert-restore is owed for the other new tests." HOLDS. VERIFIED HERE.**
[OBSERVED — the new assertions in `git show 90ab089 -- mobile/test/core/entry/`] They are direct
presence/absence/value claims: `lastResendBody!['correctedEmailAddress'] == 'nour@example.sd'`,
`containsKey(...) isFalse` plus full map equality, `loadDraft()).emailAddress == 'nour@y.com'`.
None can pass against the unfixed code. The one indirect, ordering-dependent test is the
concurrency test, and it got its revert-restore in §6. The claim is correct.

## A.2 The reviewer pass — one finding that matters more than anything else in S8-10

**A.2.1 — the BL-098 guard is unsound WITHIN one session. No restart needed.**
**VERIFIED HERE. Filed as BL-104; BL-103 amended.**

Found by `@agent-reviewer`; I confirmed it against both sides before filing.

[OBSERVED — every occurrence of `resendExhausted` in `channel_verification_screen.dart`] It is
written at **exactly one place**: line `340`, the `capExhausted` arm of `_resend`'s switch. So it
is false until the client has already made a resend that was *refused* for cap.

The path, with no process death anywhere in it:

1. Customer mistypes their address, taps resend three times over the 30/60/120 s schedule. Each
   answers `ISSUED`. [OBSERVED `OtpVerificationPolicy:22` — `RESEND_LIMIT = 3`]
2. Server-side `resend_count` is now 3. Client-side `resendExhausted` is still `false`, because no
   refusal was ever seen.
3. `_canCorrectEmail` (`:248-254`) therefore still offers the correction control.
4. Save → `reserveResend` applies the correction and invalidates the live challenge at
   `OtpVerificationService.java:375-377` → `:425-426` — **before** the `CAP_EXHAUSTED` branch at
   `:385`.
5. The last usable code is destroyed and no further code can be minted.

That is precisely the outcome BL-098 exists to prevent. The guard is genuinely sound for `locked`
and for `TOO_SOON` (the cap is tested at `:385` before the delay at `:394`, so a `TOO_SOON` always
implies a resend remains). `resendExhausted` is the single unsound input.

**Why this belongs in Part A specifically:** S8-10's report, the screen's disclosed-limitations
block at `:34-42`, and BL-103 all frame this hole as *resume staleness* — "after a kill-and-resume".
That framing came out of the AD-011 research and was never re-tested against the flag's write
sites. It is the exact hazard the audit brief predicted for a build done inside a research session.

**A.2.2 — "6 new Arabic strings" undercounts. VERIFIED HERE. Reported, no id.**
[OBSERVED — Arabic literals added in `git show 90ab089 -- .../channel_verification_screen.dart`]
The diff adds **at least 13** distinct customer-visible literals: two validation errors, one
unreachable-state error, the affordance label, two save labels, the field label, the cancel label,
the pre-tap warning, the seconds phrase, and four notice variants. The report and the S8-10
EXECUTION_PLAN row both say six. It matters because the pending native-Arabic review is scoped
from that number, and the four notice variants carry the "corrected but not sent" distinction the
whole task exists to make. Not given an id: it is a scoping correction to an existing pending
review, recorded in the session report instead.

**A.2.3 — reviewer notes I did NOT independently verify.** Reported as
**SUBAGENT, NOT VERIFIED**, no id: the `codeFieldOf` by-type finder ambiguity latent in the test
harness; the missing client-side control-character check; and the `|`-delimiter corruption risk in
`channelsSummary` when a mask contains a pipe. All three are plausible and cheap to check; none is
filed, because I did not read both sides.

## A.3 Did the AD-011 research carry into the build unre-tested?

**One conclusion did, and it produced a live defect. VERIFIED HERE. Filed as BL-112.**

The research recorded that `applyEmailCorrectionIfChanged` **no-ops on an unchanged value**. The
build carried the headline ("the correction lands even when the resend is refused") and dropped
the qualifier.

[OBSERVED `OtpVerificationService.java:420-430`] The method returns early at `:423-425` when
`newEmailAddress.equals(previous.emailAddress())` — no write, **no challenge invalidation**, no
audit event.

[OBSERVED `channel_verification_screen.dart:355-377`] The client asserts the opposite
unconditionally for every outcome except `alreadyVerified`: the comment at `:361-364` states "the
old address's challenge was invalidated inside the same transaction", `row.codeController.clear()`
follows, and `:365-376` sets a notice reading «تم تحديث البريد الإلكتروني…».

Reachable path: the editor prefills from the draft with the address already on file; the customer
saves without editing during a `TOO_SOON` countdown — the one state where BL-098 deliberately keeps
the control available. Nothing was corrected, the old code is still live, and the app discards
what they had typed and tells them the address changed. No test covers it.

Every other research conclusion the build depends on was re-checked and **holds** — the request
record's shape, the controller's trim/blank/254/control-char handling, the non-email-channel 400,
the correction running inside the Phase-1 row lock, the mask being computed after Phase 1 commits,
`LocalDraft` surviving Stage 1b's submit, and the `RESEND_LIMIT`/delay policy values.

## A.4 The six (thirteen) new Arabic strings — agreement, bidi, truncation

Beyond the countdown case the session already checked:

- **Agreement — clean.** [OBSERVED] The new strings interpolate only `_secondsAr` (`'$seconds ث'`),
  which is invariant, and a masked address. No new counted noun was introduced.
- **Truncation — no truncation, but wrapping.** [OBSERVED `channel_verification_screen.dart:772-773,
  801-804`] The longer save label «حفظ البريد الجديد وإعادة إرسال الرمز» sits in a full-width
  `FilledButton` whose child is a plain `Text` with `textAlign: TextAlign.center`. `Text` defaults
  to `softWrap: true`, so it **wraps to two lines rather than truncating**, growing the button. It
  does not lose characters. It does compound the 5-inch keyboard-overlap check S8-10 already owes,
  because the growth is directly above the fold on the smallest handset. Reported, no id — it folds
  into the owed device pass.
- **Bidi — a real gap, but not in the new strings. VERIFIED HERE. Filed as BL-111.**
  [OBSERVED `channel_verification_screen.dart:644`] The masked destination is a bare
  `Text(row.maskedDestination)` inside the app-wide forced RTL (`core/app.dart:28`), with neither
  `LtrValue` nor a `textDirection`. [OBSERVED `mobile/lib/core/text/ltr_value.dart`] The project has
  a purpose-built helper for exactly this, whose own class comment warns that a value beginning with
  a Latin run gets reordered — and four other sites use it. [OBSERVED
  `DestinationMasker.java:19-23`] The at-risk shape is the phone mask `"•••• 4821"`: leading
  bidi-neutral characters followed by European digits. The email mask `a•••@gmail.com` is flanked by
  Latin on both sides and should resolve as a single LTR run.
  This is pre-existing, **but S8-10 made the value mutable** (`:360`), so after a correction it is
  the customer's only confirmation that the right address was stored.
  **[UNVERIFIED] — I did not render it on a device.** The bidi derivation is reasoning, not an
  observation, and the row says so.

**One deliberate choice I checked and am NOT reporting as a defect:** the new email `TextField`
sets no `textDirection`. [OBSERVED `channel_verification_screen.dart:785-788`] Its comment says it
matches Stage 1b's own email field, and [OBSERVED `contact_channels_screen.dart:316` vs `:378`]
that is true — Stage 1b sets LTR on the *phone* field and not on the *email* field. Matching an
established precedent in a change about something else is the right call.

---

# PART B — mobile tier coherence

## B.1 Guard at a caller instead of the shared choke point

**VERIFIED HERE — BL-107: `flushPending()` is called from stages 3-7 only.**
[OBSERVED — every `flushPending` reference in `mobile/lib`] Call sites are
`stage3/4/5/6/7_screen.dart` and `session_pending_screen.dart:55`, which is unreachable.
Sequence: stages 3-6 completed offline (each `_submitOrQueue` catches
`BackendUnreachableException`, queues, returns `false`, and the screen advances anyway) →
connectivity returns while the customer is at stage 8 or later → **nothing ever sweeps the queue
again**. `SubmissionService` gates only on `facePassed`/`hasSignature`, so the profile submits with
addresses, occupation and social data the backend never received. This is the choke-point failure
in its purest form: the sweep is attached to five screens instead of to the thing every later stage
passes through.

**SUBAGENT, NOT VERIFIED — no id:** that `prepareCatalog` (the Stage 2→3 reference-catalogue gate)
has one caller and every resume path bypasses it; that `resendChannel`'s `saveDraft` write-through
lacks the session-still-exists guard its two siblings have; and that
`Stage8Screen._spendAttemptForAbandonedSession` is missing the terminal-profile arm its two
siblings carry. All three are the right *shape* and worth a session's attention. I did not read
both sides, so none is filed.

## B.2 State that cannot survive a restart and cannot be re-learned

BL-103 is one. The audit was asked to find the others.

**VERIFIED HERE — BL-104** is the sharpest: it is the BL-103 state, and it turns out not to need a
restart at all (Part A.2.1).

**VERIFIED HERE — BL-108: `SessionDatabase.clear()` loses data in the other direction.**
[OBSERVED `session_database.dart:280-287`] Six un-transacted deletes, in this order:
`pinnedReferenceVersions`, `localDraft`, **`localProgress`**, `dataEntryDraft`,
`dataEntryIncomeSources`, `pendingStageSync`. It destroys the pointer *before* the three tables
holding the customer's entered data. A process death mid-clear strands spouse name, addresses and
employer, plus a queued submission, on disk with no `LocalProgress` referencing them — and
`launchDecision` then returns `FreshStart` without a backend call, so nothing ever clears them
again. Note the contrast within the same tier: `_markChannelVerified` and `_updateChannelMask` both
run in `transaction()`; the method that deletes all the PII does not.

**SUBAGENT, NOT VERIFIED — no id, but these are the most likely remaining siblings:**
the reference number existing only in memory once `confirmation_screen.dart:62` clears local state
(the pointer key is destroyed, so it is not re-learnable in-app); and `RetainedScanStore` /
`RetainedFaceStore` losing a paid-for capture across a restart, where the backend never received
the JWS and so cannot tell the app one exists. Both are argued as destructive, both are disclosed
in code as product-owner decisions. **The same pass explicitly cleared a set as benign**
(`row.locked`, `resendSecondsRemaining`, `_blockedUntil` on stages 8 and 10, `_uploaded`,
`_images`) on the grounds that each is re-learned from the next round trip — that distinction is
the right one and matches BL-103's own reasoning.

## B.3 A test whose name asserts more than its assertion proves

**VERIFIED HERE — BL-113, three of them.** I read each test body against its name.

1. `stage10_screen_test.dart:473` — *"no attempt count appears anywhere on this screen, in any
   state"*. [OBSERVED] The assertion is `find.textContaining('محاولات $n')` for n = 1..5 —
   **noun-then-digit**. Arabic renders counts digit-then-noun, which is the order the sibling test
   at `stage8_screen_test.dart:575` actually uses (`'3 محاولات'`, `'2 محاولة'`). **Nothing this
   screen could ever render can match these five strings**, so the test passes regardless of what
   is on it. It also pumps one view, not "any state". This is a vacuous assertion guarding R-052's
   named failure mode.
2. `stage12_screen_test.dart:284` — *"clears local state only AFTER the screen has everything it
   needs"*, with the comment "the ordering is the point". [OBSERVED] It asserts, after
   `pumpAndSettle`, that the reference is on screen **and** `localProgress` is null. Both are
   equally true if the clear ran first, because the reference arrives via `ConfirmationArgs`.
   Nothing observes sequence.
3. `stage8_screen_test.dart:566` — *"no screen in Stage 8 **ever** displays an attempt count"*.
   [OBSERVED] Pumps one of ten `_Stage8View` states and forbids five literal strings.

## B.4 A comment saying a capability does not exist when it does

This project's signature failure — BL-101 and BL-065 were both this. Two more, both VERIFIED HERE:

- **BL-105 / BL-022** — `salary_certificate_field.dart:14-18` states plainly that no backend
  endpoint exists to upload to. [OBSERVED] `SalaryCertificateController.java:18` has served
  `POST /api/v1/salary-certificate` since S4-06. See Part C.1.
- **BL-021** — `entry_repository.dart:428-444` documents that `account-check` has no wire signal
  for a phone lock. [OBSERVED] `AccountCheckContinuation.java:24` has `BLOCKED` and
  `AccountCheckResponse` carries `blockedUntil`, both since S4-06. See Part C.1.

**SUBAGENT, NOT VERIFIED — no id:** `entry_models.dart:170` documenting
`SessionTemporarilyBlockedException` as unreachable "because Stage 2 doesn't exist yet" (Stage 2
shipped), and `session_database.dart:72-73` saying the same thing.

## B.5 Screens carrying disclosed limitations or stale claims

A delegated pass checked ~45 such claims across all 20 screens against the backend and the mobile
code. **I did not re-verify the majority, so the table is not reproduced here as findings.** Its
useful summary, marked **SUBAGENT, NOT VERIFIED** except where noted:

- Most disclosed limitations are **still true**, and several are unusually well-written.
- **Six were reported NOW FALSE.** Of those I independently verified **two** — the two in B.4
  above, which are filed. The other four (the `account_entry_screen` "inactive account" terminal
  path, the "wire has exactly proceed/terminal/retry" comment, `stage6_screen.dart:22` claiming
  Stage 7 is out of scope, `session_complete_screen.dart:16-17` claiming local state is already
  cleared) are reported here unverified and unfiled.
- No `[UNVERIFIED]` marker exists anywhere in `mobile/lib/features/` — the codebase uses prose
  disclosure instead. Worth knowing: a grep for the marker finds nothing and proves nothing.

## B.6 Do the local store, the draft and the resume path agree?

**No — and the disagreement is by design for stages 3-9, undocumented at the boundary.**
[OBSERVED] There is no single source of truth. Five things claim to know where the customer is:
`LocalProgress.resumeStage`, the `DataEntryDraft`'s filled columns, the `PendingStageSync` queue,
the backend pointer `POST /submission/current`, and `account-check`'s continuation. Only the last
two are backend-owned, and they cover only stages 10-12 and session-openness.

The settled rule (`customer.md:1169-1178`) is "progress → device wins" for stages 3-9 and
backend-wins for 10-12, and the code implements exactly that. The gap is that **nothing documents
what happens when they disagree at the boundary** — and BL-107 (B.1) is that gap becoming
permanent data loss.

**SUBAGENT, NOT VERIFIED — no id:** that a process death between the Stage 9 accept's HTTP 200 and
its local write leaves the store behind the backend, and the customer is walked back to a rescan by
the `STATE_CONFLICT` handler rather than forward to Stage 10. Worth a session's attention; I did
not confirm it.

---

# PART C — cross-tier coherence

A delegated pass compared **26 endpoints** the app actually calls against their controllers;
**12 agreed on all four axes** (field names, nullability, response type, error codes). I verified
the three highest-impact disagreements myself.

## C.1 The wire contract

**VERIFIED HERE — BL-105 (with BL-022): the salary certificate is never sent, then deleted.**
This is the most consequential single finding in the audit.
[OBSERVED — every `'/api/v1/...'` literal in `mobile/lib`, enumerated by grep] The complete list is
`account-check`, `contact-channels`, `data-entry/stage3..7`, `identity-scan`, `liveness`,
`otp/resend`, `otp/verify`, `reference/manifest`, `signature`, `submission`, and the `spike/uqudo/*`
paths. **`/api/v1/salary-certificate` is not among them and the string appears nowhere in
`mobile/lib`.** [OBSERVED `SalaryCertificateController.java:18`] The backend serves it.
So: the customer attaches a certificate at Stage 6, is shown «تم إرفاق: …»
(`salary_certificate_field.dart:155`), the bank never receives the file, and the session clear then
deletes it from the handset. `Stage6Request`'s own Javadoc asserts "the mobile client already
captures/uploads it independently" — it does not. `customer.md:508-514` describes it as stored on
the profile. Three documents and one comment all describe a path that does not exist.

**VERIFIED HERE — BL-021 amended: `account-check` can answer `BLOCKED` and the client throws.**
[OBSERVED `AccountCheckContinuation.java:24`] `BLOCKED` exists. [OBSERVED
`mobile/lib/core/entry/entry_models.dart:7`] `enum AccountContinuation { proceed, terminal, retry }`.
[OBSERVED `dio_entry_api.dart:32-34`] The decode is
`AccountContinuation.values.byName((body['continuation'] as String).toLowerCase())`, which throws
`ArgumentError` on `"blocked"`.
Consequence: a phone-locked customer cannot start **or resume**. At Stage 1a they get the generic
«حدث خطأ غير متوقع»; on cold launch `launchDecision()` fails and the launch screen shows
«تعذر بدء التطبيق. تأكد من اتصالك بالإنترنت» — **blaming the network for a bank block** — and
retrying never works.
**BL-021's own text was wrong** and is corrected: it claimed "the app currently ignores the new
field and falls back to its existing self-correcting behaviour". It does not ignore it; it throws.
This is a plan-file claim contradicted by code, i.e. a Part E finding as well.

**VERIFIED HERE — BL-110: the resume gate names a recovery the client cannot perform.**
[OBSERVED `final_stages_gate_screen.dart`] The screen catches `BackendUnreachableException`, then a
bare `on Object` that sets «حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى.» with a retry button.
`POST /api/v1/submission/current` can answer `STATE_CONFLICT` ("you are back at stage 8/9,
re-sync"), `PROFILE_TERMINAL`, or 404. None has a branch. A customer whose status is `blocked_scan`,
`awaiting_registry`, `abandoned` or terminated sits on a retry button that re-issues the same
refused call forever, with no route to Stage 8, Stage 9 or `/terminal`. Every other stage 10-12
screen maps these codes; **the one screen whose entire job is to place a resuming customer does
not.**

**SUBAGENT, NOT VERIFIED — no id.** The remaining 11 disagreements, reported for the next session
to confirm: an unhandled 404 on the five data-entry endpoints whose queue path swallows it to `0`,
so the `PendingStageSync` row is retried forever; `_mapOtpError` missing a `>=500` arm that every
sibling mapper has; an uncoded 400 for a retired scan session landing in the connectivity screen
while the copy claims no attempt was spent; a permanent reference-list 404 classified as transient
and retried under backoff forever; unhandled 404s on `/signature` and `/submission`; the
`INACTIVE` dead branch; and `contact-channels`' 429 carrying no readable retry hint. Notably the
pass found the Stage 8/9 and Stage 10-12 **code enumerations complete in both directions** — every
code emitted has a branch, no branch names a code the backend cannot emit.

## C.2 Does the app disagree with the back office about the same profile?

**VERIFIED HERE — BL-106: yes, on the outcome that matters most.**
[OBSERVED `final_stages_gate_screen.dart:71-77`] `JourneyStage.submitted`, `.approved` and
`.rejected` all fall through to the same `_goToConfirmation`. [OBSERVED
`confirmation_screen.dart:113,121`] That screen renders «تم إرسال طلبك إلى البنك للمراجعة والاعتماد»
and «لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة.» for all three.
So **the app tells a rejected customer their update is awaiting review.** If local state was already
cleared they instead reach «تم استكمال تحديث بيانات هذا الحساب من قبل»
(`entry_repository.dart:473-477`) — which tells a rejected customer the update **succeeded**.
Meanwhile the operator correctly sees «مرفوض». There is no approval screen, no rejection screen, no
reason code and no branch instruction anywhere in `mobile/lib`; the coded reason reaches the
customer only by SMS.

**SUBAGENT, NOT VERIFIED — no id:** date formats diverge (customer sees `DD/MM/YYYY`, operator
reads raw ISO on four fields); the back office renders the reference number without `<bdi>` though
it has an `ltr()` helper, so the operator may see it reordered; the Civil-Registry `state` is shown
untranslated; and `awaiting_registry` profiles are labelled «لم يتم الوصول إلى هذه المرحلة بعد» —
"not reached yet" for a profile stuck precisely *at* that step.

## C.3 Mobile-visible states the back office cannot show

**SUBAGENT, NOT VERIFIED — reported, no id.** The delegated pass reports that the operator query
selects none of: `phone_lock_until`, `scan_blocked_until`, `liveness_blocked_until`, the attempt
counters, or `resume_stage`. If that holds, an operator taking a call from a blocked customer
cannot confirm the block or say when it lifts, and cannot tell how far an in-flight customer got.
It also reports **manual completion as the one operator action that IS coherent** end to end — it
makes the profile terminal, and every in-flight stage call answers `ProfileAlreadyCompleteException`
consistently.

This is a coherent and plausible picture, and it is the natural companion to BL-106. **It is not
filed**, because confirming it means reading two JDBC repositories I did not open.

---

# PART D — journey integrity

`customer.md` was read by stage, in two passes. Findings below are grouped by what they cost.

## D.1 Dead ends — a state with no in-app route out

| Dead end | Harm class | Status |
|---|---|---|
| Stage 2 — corrected email after 3 successful resends kills the last code (A.2.1) | **WASTES A SINGLE-USE ACCOUNT** — the email channel becomes unverifiable for the session | **VERIFIED HERE — BL-104** |
| Stage 10 — the 24-hour liveness block screen offers no exit | **MERELY ANNOYING** — no data lost, no budget spent, the block lifts and relaunch resumes; but the only way out of the app is to force-quit | **VERIFIED HERE — BL-109** |
| Stages 10-12 resume gate — a conflicted resume is an infinite retry loop | **MERELY ANNOYING to LOSES DATA** depending on status; nothing is charged, but there is no route onward | **VERIFIED HERE — BL-110** |
| Stage 0 — an unmapped launch failure renders one sentence and no action | reported **LOSES DATA** (the only exit is OS-level clear-data, which destroys the local draft) | SUBAGENT, NOT VERIFIED |
| Stage 3 — a reference-list error state leaves a mandatory dropdown with no retry and no Back | reported **MERELY ANNOYING** (drafts survive; relaunch resumes) | SUBAGENT, NOT VERIFIED |
| Stage 11 — a customer who can neither draw nor upload gets a disabled button, no exit, no mention of the branch | reported **WASTES A SINGLE-USE ACCOUNT** | SUBAGENT, NOT VERIFIED |
| Stage 8 / Stage 10 — after the 20-mint lifetime cap the block screen invites a retry that can never succeed | reported **WASTES A SINGLE-USE ACCOUNT** | SUBAGENT, NOT VERIFIED |

**BL-109 verification detail** [OBSERVED]: `stage10_screen.dart:370-374` constructs
`ScanBlockedView` with `title`, `blockedUntil` and `onRecheck` and passes **no `onLeave`**;
`scan_blocked_view.dart:152-156` renders its exit button only `if (widget.onLeave != null)`;
`stage8_screen.dart:419-424` passes `onLeave: () => context.go('/')`. The two screens share a widget
and only one of them uses its exit. The screen is reached by `context.go`, so there is no AppBar
back either.

**On the four unverified dead ends:** the Stage 8/10 lifetime-cap loop and the Stage 11 signature
dead end are, if real, more serious than anything I verified except BL-105. They are the two things
I would have the next session confirm first. I am not filing them because the audit's own rule is
that an id requires source, and I ran out of session before reading the budget code.

## D.2 Where the document is wrong, not the app

Reported by the delegated passes, **SUBAGENT, NOT VERIFIED**, and worth a documentation session
rather than a code one:

- `customer.md:1182-1184` states "every mutating call from the app carries an idempotency key".
  The code records a deliberate replacement by structural idempotency. The doc asserts a mechanism
  that was consciously not built.
- `customer.md:146-197`'s "resuming without local state" review flow is described as a settled
  rule; the app has no route, no screen and no restore path for it (it is BL-006's deferred
  remainder). Unlike every other gap in the file, this one carries no "not built" annotation.
- `customer.md:900-902` calls liveness "deliberately more generous than the scan" — its own policy
  table gives the scan 10 attempts across two document types and liveness 5.
- `customer.md:1218-1220` promises the retained scan JWS "retries the upload on reconnect", which
  reads as surviving a restart; the code records a product-owner decision not to persist a
  PII-bearing artifact.
- `customer.md:508-514` describes the salary certificate as stored on the profile. **This one I did
  verify from the code side (BL-105) — the doc is wrong.**

## D.3 Where the app is wrong

Beyond the dead ends: **VERIFIED HERE — BL-107** (offline stage 3-6 data can never reach the
backend once past stage 7, yet submission succeeds) is the most serious, because it is silent on
both sides. The customer sees success; the operator sees a profile; the addresses are simply absent.

---

# PART E — plan-file truth

## E.1 Sweep coverage — stated honestly, in three buckets

`BACKLOG.md` is 221 KB and `RISKS.md` 82 KB. **One session cannot re-test every open row against
code, and this one did not.** [OBSERVED — row counts by grep]

| | BL | R |
|---|---|---|
| Total rows | **103** | **53** (52 + the new R-053) |
| Closed / retired | 40 | 11 |
| **Open** | **63** | **41** (15 Live, 26 Watching) |
| **Re-checked against source this session** | **9** — BL-012, BL-021, BL-022, BL-074, BL-098, BL-101, BL-102, BL-103, BL-105 | **2** — R-014, R-052 (partially) |
| **Read, not verified against source** | **103** (all rows read at ID + ~150-character summary level only) | **53** (all rows read at ID + status + ~120-character summary) |
| **Not reached — full body text never opened** | **~94** | **~50** |

So: **9 of 63 open BL rows and 2 of 41 open R rows were actually tested against code.** Everything
else is a `[DOC]` claim that this audit did not confirm. The brief predicted "several will be stale,
several will be V2, and at least one will describe behaviour that has since changed" — I found the
last one (BL-021) but cannot responsibly claim to have found all of them.

**What the next session should take:** the 54 unexamined open BL rows, in a pass that opens each
body. On this session's hit rate — 4 of 9 rows examined turned out to be wrong or understated — a
meaningful fraction of the rest is likely stale.

## E.2 AD-010, and the hunt for other unrecorded allocations

**Entered. [OBSERVED — `PROJECT_PLAN.md` now carries AD-010 in both the decisions log and the open
decisions section.]**

Status recorded honestly: **researched and recommended, not ruled on by the product owner.** The
research is a researcher pass; no bank ask has been made. It is recorded in the open-decisions
section as *not open as a technical question, open only as a bank ask.*

**The hunt for siblings — AD-010 is the only one.** [OBSERVED — repo-wide grep for `AD-[0-9]+` and
`OQ-[0-9]+` across `docs/` and the plan file] The full sets are `AD-001..AD-012` and
`OQ-001..OQ-026`. `AD-012` appears only as "the next free number". No `OQ-027+` was ever allocated.
So the residual S8-10 named is closed, and it was a single instance, not a pattern.

**Why it happened, recorded so it does not recur:** the allocation check that preceded the AD-011
research grepped `PROJECT_PLAN.md` alone, which ends at AD-009, so AD-010 looked free. The
2026-09-07 report had done the correct thing — a repo-wide grep — but recorded its result only in
its own file. **An AD allocated in a session report and not entered in the plan file is invisible
to the next grep.**

## E.3 Every place a plan file disagrees with the code

The report wins; the plan file was corrected. All four VERIFIED HERE.

| Plan-file claim | Source says | Correction made |
|---|---|---|
| **BL-021**: "the app currently ignores the new field and falls back to its existing self-correcting behaviour" | The app **throws** `ArgumentError` on `BLOCKED` and cannot start or resume | BL-021 amended with the real behaviour |
| **BL-022**: "Still open: the client wire-up" | No upload path exists at all; the customer is shown a false confirmation and the file is then deleted | BL-022 severity corrected; BL-105 filed |
| **BL-102**: "the one production caller is a preposition" | Three call sites, all after «بعد», including `hours.phrase` → «بعد ساعتان» | BL-102 scope corrected |
| **BL-103**: framed as surviving-a-restart staleness | The destructive path needs no restart (three successful resends) | BL-103 scope corrected; BL-104 filed |
| **`EXECUTION_PLAN.md` S1-03**: "Deferred, not cancelled" | iOS is dropped by product-owner decision 2026-09-10 | S1-03 marked ❌ Cancelled |

**One place I deliberately went one step beyond "one entry, then move on":** marking S1-03 ❌. The
instruction was to record the iOS decision in `PROJECT_PLAN.md` and leave the backlog and risk rows
alone, which I did — **BL-052, BL-088 and R-003 are untouched, open and stale, exactly as
instructed.** S1-03 is a task row rather than a backlog or risk row, and leaving a ⚠️ Blocked task
for dropped work would make the execution plan actively false on the document every session reads
first. It is one line and trivially reversible if you disagree.

---

# PART F — what remains for production V1

Scope as set 2026-09-10: **Android only** · **SMS-only OTP** · **reference data as it stands** ·
**existing AWS** · **real bank integrations replacing stubs** · **new splash + banner**.
No iOS is reported. WhatsApp/email, reference-data update mechanisms and hosting moves are V2.

## F.1 Can the APK in the bank's hands become the production APK?

**No. The hostname forces a rebuild. VERIFIED HERE.**

[OBSERVED `mobile/lib/core/config/app_config.dart:6-9`]
```
static const String referenceApiBaseUrl = String.fromEnvironment(
  'REFERENCE_API_BASE_URL', defaultValue: 'http://localhost:8080');
```
`String.fromEnvironment` is a **`const`** — resolved at compile time, not read at run time.
[OBSERVED `mobile/lib/core/network/dio_provider.dart:9`] It is the **only** Dio `baseUrl` in the
app; despite the name it is the whole app's API address. [OBSERVED — grep for `baseUrl` across
`mobile/lib`] The single other occurrence is the unrouted S1-02 spike screen.

[OBSERVED — the S8-10 gate output, `docs/sessions/2026-09-10-s8-10-email-correction.md`] The pilot
APK was built with
`--dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net` — the CloudFront
**default** name, which is AWS's, cannot be moved or re-pointed, cannot survive replacing the
distribution, and can carry no ACM certificate.

**So the APK now in the bank's hands is a pilot artifact, permanently. Production needs a rebuild
and a re-signed, re-distributed APK.** This is exactly what **BL-074** already records, and BL-074
holds against source in full — I checked it rather than trusting it.

**The scheduling consequence, which is the useful part:** the two halves of the subdomain work
split cleanly.

| Half | Whose | Blocking? |
|---|---|---|
| **Choose the hostname string** (e.g. `update.sfbank-sd.com`) | **The bank's** — it is their domain | **YES.** It is compiled into the APK, so it must be fixed before the production build. Nothing else about it is hard; it is a decision, not work. |
| **Create one NS record** delegating that subdomain to a Route 53 hosted zone | **The bank's** — one DNS action, once | **NO.** It can follow later. The delegation mechanism is reversible with no rebuild. |
| ACM certificate in **`us-east-1`** (not the stack's `eu-central-1`), `Aliases` + `ViewerCertificate` on the existing distribution | **Ours** | Follows the NS record. ~$0 for the certificate, ~$6/year for the zone. |
| Rebuild + re-sign + redistribute the APK with the final URL | **Ours** | Follows the name, not the DNS record. |

The origin posture does not change: the ALB stays internal, the VPC origin is untouched, no public
ALB and no API Gateway is introduced. [DOC — `docs/sessions/2026-09-07-research-ad-010-mobile-api-domain.md`,
now recorded as AD-010 in `PROJECT_PLAN.md`]

**Do not let the DNS record block the name.** The name is the fast ask and the only blocking one.

## F.2 Reference data — the production consequences of freezing it

Ruled once, here, as asked. Under "lists as they exist today, any update mechanism is V2":

| # | Item | Consequence of shipping as-is | Id |
|---|---|---|---|
| 1 | **Three Sudanese states are missing.** [OBSERVED `V0016__seed_admin_division.sql`] 15 states seeded (11,12,21,22,23,31,41,42,43,44,51,52,61,62,63); Sudan has 18. Missing **West Kordofan, East Darfur, Central Darfur**. The seed's header explains why: the list was driven from the states appearing in the *Localities* sheet, not the States sheet. | The pick is **mandatory** at Stage 3 (birth), Stage 5 (home) and Stage 6 (work). A resident of those three states **cannot complete the journey truthfully** — they must name a neighbouring state. The cost lands on the bank's own data quality, permanently, with no update mechanism to repair it. **This is the one I would not ship without a ruling.** | **R-014** (re-verified at source and annotated this session) |
| 2 | **34 localities are uncertain transcriptions** and cannot be identified. | Same shape, one level down, and lower stakes — a locality is less load-bearing than a state. | R-015 |
| 3 | **Rejection-reason (REJ-01..07) Arabic labels and customer-facing SMS copy have never had a translation/compliance review.** | These are the words the bank tells a customer *why they were refused*. They ship as an unreviewed translation. Compounded by **BL-106**: there is no in-app rejection screen, so the SMS is the customer's **only** channel for the reason — the copy carries the entire weight. | **BL-005** |
| 4 | **Every customer-facing message names the CENTRAL BANK, not this bank.** | Wrong institution named in OTP, submission and review messages. | **BL-085** |
| 5 | The reference-list `content_hash` covers 4 of 9 served columns. | A label could change without the version changing, and cached clients would not refresh. With no update mechanism in V1 this is largely inert — it becomes live the moment a list is ever edited. | R-033 |
| 6 | `admin_div_version` is one column shared by birth, home and work addresses. | Whichever stage writes last sets the version recorded for all three. Provenance imprecision, not customer-visible. | BL-017 |

## F.3 Design work outstanding — what exists already

[OBSERVED — `Design_3/`, the directory 90ab089 swept in]

- **Splash screen — a design exists**, not an implementation: `Design_3/SFB Splash Screen.dc.html`
  and a copy under `design_handoff_sfb_app/designs/`. [OBSERVED] There is already a launch screen
  in code (`mobile/lib/features/entry/launch_screen.dart`), with brand work landed at BL-076, so
  this is a re-skin against a design that exists rather than a new screen.
- **Banner — a design and a component exist**: `Design_3/SFB Mobile Banner.dc.html`,
  `Design_3/components/brand/BrandBar.jsx` + `.d.ts` + `brand.card.html`.
- **Which screens a banner would touch: all 20.** [OBSERVED — the screen list under
  `mobile/lib/features/`] `entry/` 6 (launch, account entry, contact channels, channel
  verification, session pending, terminal) · `dataentry/` 5 (stages 3-7) · `identityscan/` 2 ·
  `liveness/` 1 · `signature/` 1 · `submission/` 4 · plus the shared `scan_blocked_view`.
  **[UNVERIFIED]** whether a shared app-shell wrapper exists that would let a banner land in one
  place — I did not read the router's shell structure. That question decides whether this is one
  slice or twenty edits, and it is the first thing the implementing session should establish.

## F.4 V1 remaining — grouped by what unblocks each item

### Ours to build

| Item | Id | Note |
|---|---|---|
| **Salary certificate: the client upload path** | **BL-105** / BL-022 | Bank currently never receives it; file is then deleted |
| **`account-check` `BLOCKED` / `blockedUntil` client wiring** | **BL-021** | Today a phone-locked customer cannot start or resume at all |
| **Approval / rejection screens** | **BL-106** | App currently tells a rejected customer their update is awaiting review |
| **Per-channel resend state at load** (closes both halves) | **BL-104** + BL-103 | Backend + client; the destructive path needs no restart |
| **Offline queue: sweep at a real choke point** | **BL-107** | Silent data loss to the bank |
| **Resume gate: handle coded 409/404** | **BL-110** | With BL-109 |
| **Stage 10 block screen: an exit** | **BL-109** | One line + a device check |
| **`SessionDatabase.clear()`: transaction + delete order** | **BL-108** | PII stranded on process death |
| **Arabic dual after «بعد» — all three call sites** | **BL-102** (scope corrected) | PO-gated copy decision |
| **Unchanged-address correction tells the truth** | **BL-112** | PO-gated copy |
| **Masked destination bidi isolation** | **BL-111** | Needs a device/golden check, not an argument |
| **Three tests that do not prove their names** | **BL-113** | One of them can never match anything |
| **Splash screen + banner implementation** | **BL-076** (largely done) + UNFILED for the banner rollout | See F.3; scope hinges on whether a shell exists |
| **Real bank integrations replacing stubs** | **BL-089** | AWS staging still runs the core-banking stub |
| **Production APK rebuild on the final hostname** | **BL-074** | Follows the name, not the DNS record |

### Needs a bank input

| Item | Id | What exactly |
|---|---|---|
| **The hostname string** | **BL-074** / AD-010 | A decision, not work. **The one genuinely blocking bank ask.** |
| **One NS record** delegating the subdomain | AD-010 | Can follow later; no rebuild |
| **Real core-banking / Civil Registry / Uqudo production credentials and endpoints** | BL-089, R-007, R-001 | R-001 stays amber until production is inspected |
| **SMS sender ID registration** | **R-041** | An unregistered alphanumeric sender causes silent partial delivery failure |
| **The SMS bill / route** | **R-043** | Five-to-six-figure exposure, no route chosen |
| **Google Play distribution** | **BL-082** | Four technical blockers, none the store's fault |

### Needs a decision from you

| Item | Id | The decision |
|---|---|---|
| **The three missing states** | **R-014** | Ship without them, or seed them before freeze. See F.2 #1 — this is the one I would not ship without a ruling. |
| **Rejection-reason Arabic + SMS copy review** | **BL-005** | With BL-106 unbuilt, the SMS is the customer's only route to the reason |
| **"Central Bank" named in every customer message** | **BL-085** | Wrong institution; a copy fix, but it needs your word |
| **The 13 new S8-10 Arabic strings** | (A.2.2) | The pending native review is scoped to 6; hand it 13 |
| **AD-011** — channel/OTP screen structure | AD-011 | Out of scope for this audit by instruction; still open |
| **Whether S1-03 should be ❌** | — | I marked it; reverse if you disagree (E.3) |

## F.5 Effort shape — "ours to build" only, relative, no calendar

Ordered smallest to largest. Sizes are relative to each other.

1. **BL-109** — Stage 10 block screen exit. One line plus a device check.
2. **BL-108** — clear() transaction + order. Small, self-contained, one tested slice.
3. **BL-102** — three call sites, one helper. Small once the copy decision is made.
4. **BL-112** — one guard plus reworded copy. Small; PO-gated.
5. **BL-111** — swap in the existing helper. Small, but needs a real render check.
6. **BL-113** — three tests. Small each; (3) means pumping nine more views.
7. **BL-110** — coded 409/404 routing at the gate. Medium; pairs with BL-109.
8. **BL-021** — a new enum arm, a nullable field, and a blocked-launch screen. Medium.
9. **BL-105** — a new client upload path, retry behaviour, and a `customer.md` amendment. Medium.
10. **BL-107** — the offline sweep. Medium-large: the work is small, **choosing the choke point is
    a design decision** and the wrong choice re-creates the bug.
11. **BL-104 + BL-103** — backend reports per-channel resend state, client consumes it. Large:
    spans two tiers, and it is the only item here that changes a wire contract.
12. **BL-106** — two new screens plus PO-gated rejection copy. Large.
13. **Splash + banner** — unknown until F.3's shell question is answered; potentially the largest
    by surface area (20 screens) and the smallest by difficulty.
14. **BL-089 / real integrations** — largest and least in our control; gated on bank input.

## F.6 V2 — kept separate so nothing bleeds across

WhatsApp and email messaging (BL-086, R-042 — WhatsApp must be disabled at the flag level, not
hidden in the UI; BL-097 records that the staging flag flip is not durable) · reference-data update
mechanisms (R-033, BL-017) · any hosting move (BL-072, BL-073) · iOS in every form (BL-052, BL-088,
R-003 — left open and stale by instruction) · export and admin account management (S6-03 ❌,
BL-023, BL-026) · the statistics dashboard (BL-001) · screenshot blocking (BL-002) · operator
manual profile creation (BL-004) · seal export (S7-10, R-037).

---

# What this audit did NOT cover

Stated so the gaps stay known rather than becoming unknown:

- **54 of 63 open BL rows and 39 of 41 open R rows** were never opened past their summary line
  (E.1). This is the largest gap.
- **The back-office tier was read only through a delegated pass** (C.2, C.3). I verified nothing in
  `backoffice/src` myself.
- **Four reported dead ends were not verified** (D.1) — Stage 0's launch failure, Stage 3's
  reference-list error state, Stage 11's signature refusal, and the Stage 8/10 lifetime-cap loop.
  The last two are, if real, more serious than anything I verified except BL-105, and are what the
  next session should confirm first.
- **No device run.** BL-111's bidi conclusion, the save-label wrap, and S8-10's owed 5-inch
  keyboard-overlap check all need a handset. None was attached to this session.
- **iOS** — untouched by instruction.
