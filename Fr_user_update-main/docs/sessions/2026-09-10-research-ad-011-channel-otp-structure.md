# AD-011 — Channel selection and OTP verification: one screen or two, and how a customer corrects a wrong phone number or email address

**Researcher report · 2026-09-10 · no code written, no files changed (`@agent-researcher` has no write access by design)**

Produced by `@agent-researcher` on the parent session's brief. The parent session verified the
three load-bearing claims independently before filing (see §17). **AD-011 is OPENED by this
report, not settled** — the decision is the product owner's.

---

## 0. Reading of the question pursued

The task is ambiguous in one place: "merged into one" could mean *one go_router route* or *one
viewport of content*. These were treated as **two distinct options** (B and D below) rather than
picking one, because the blast radii differ sharply and the 5-inch constraint bites on only one
of them.

Readings **not** pursued: merging Stage 1a (account entry) into the picture; changing what Stage
2's Next gate requires; anything about WhatsApp enablement (R-042/BL-086 territory).

---

## 1. Question

How should the communication-channel selection stage (Stage 1b) and the OTP-verification stage
(Stage 2) be structured — as the two separate screens they are today, or merged — and within that
structure, how does a customer correct a phone number or email address they entered wrongly?

---

## 2. Answer

**Keep the two screens. Do not merge. Add the in-place email edit control to the email row on the
Stage 2 screen, now, before the pilot APK ships.**

The merge is not merely unnecessary — it is *foreclosed by the backend's own semantics*.
Correcting a phone number is a `POST /api/v1/contact-channels` re-entry, and that call **wipes
every verified channel state and re-issues every code** [OBSERVED `ContactChannelsService.java:312-323`;
`customer.md:193-196`]. Correcting an email is a `correctedEmailAddress` field on
`POST /api/v1/otp/resend` that touches nothing but the email row [OBSERVED `OtpResendRequest.java:20`].
Those two repairs are *different transactions with different blast radii*, and the two-screen
split is the honest expression of that difference: the destructive one costs a screen change, the
non-destructive one does not. A merged screen would sit one tap away from a control that silently
unverifies an already-verified phone. It would have to re-impose exactly the separation the
current structure gets from the route boundary — at the cost of a rewrite.

The defect the brief is really about is real and is **mobile-only**: the backend half of the email
correction shipped at S4-06 and BL-012 is closed; the app never calls it, and the screen's own doc
comment still says the endpoint cannot accept a correction. That comment is **stale**. Fixing it
is roughly a **half-session mobile change with zero backend work**, and it can land before the
pilot APK.

The restructure, if anyone still wants it after this report, is a **2–3 session rewrite** that
must wait until after the pilot.

---

## 3. Verification of the six leads the brief supplied

| # | Claim as given | Verdict | Evidence |
|---|---|---|---|
| 1 | OTP screen carries «رقم الهاتف غير صحيح؟ العودة لتعديله» back to channels, gated on no phone channel verified | **TRUE** | [OBSERVED] `mobile/lib/features/entry/channel_verification_screen.dart:401-405` — `if (!_anyPhoneVerified) TextButton(onPressed: () => context.go('/contact-channels'), child: const Text('رقم الهاتف غير صحيح؟ العودة لتعديله'))`. Gate predicate at `:279-280`. |
| 2 | The gate narrowed a broader journey rule; the journey doc was amended in the same commit | **TRUE in substance; "same commit" not independently verified** | [OBSERVED] `docs/journeys/customer.md:266-276` carries the amendment, marked "AMENDED 2026-09-08 (S8-05), a recorded reversal of the rule as originally written." The code comment at `channel_verification_screen.dart:392-400` says the same. [UNVERIFIED] The commit atomicity itself — the researcher had no shell, so could not run `git log`. Three independent plan records assert it (`BACKLOG.md:95` BL-091, `EXECUTION_PLAN.md:138` S8-05, the code comment). |
| 3 | Email row is resend-only; no in-app route to correct a mistyped email; the phone link does not rescue it | **TRUE** | [OBSERVED] `channel_verification_screen.dart:491-547` — the unverified-row body is a code `TextField`, a verify button, a resend `TextButton` and an error line. No editable destination field anywhere. The mask at `:488` is a plain `Text`. The phone link is gated on `!_anyPhoneVerified` (`:401`) and labelled about the phone, so it neither reaches an email-only correction after a phone verifies nor reads as offering one. |
| 4 | Only verified channels are written to the customer profile | **TRUE, with a precision** | [OBSERVED] `customer.md:290-302` — the profile records **three** per-channel outcomes (`verified` / `declined` / `unverified`), all persisted; what is gated on `verified` is **usability for contact** and Stage 12's completion notices ("Completion notices at Stage 12 go only to channels in the verified state"). So the state of an unverified channel *is* written; the address behind it is not usable. The app's own copy says «غير موثقة — لن يتم حفظها في ملفك» (`:553`), which is the customer-facing simplification, not the schema. |
| 5 | Backend half already built and shipped: optional corrected email on resend, rejected for non-email channels, applied in the same transaction that reserves the resend, invalidating the old address's live code | **TRUE on all four sub-claims** | See §4 — quoted verbatim. |
| 6 | The comment in the verification screen saying the endpoint cannot accept a corrected address is stale | **TRUE — confirmed stale** | [OBSERVED] `channel_verification_screen.dart:23-27` states: *"customer.md's 'editable in place, then resend' needs `/api/v1/otp/resend` to accept a corrected address; it doesn't (`OtpResendRequest` is `{profileId, channel}` only) — already filed as BL-012, confirmed still open by this session."* Both halves are now false: `OtpResendRequest` is a **three**-field record, and BL-012 is **closed** (`BACKLOG.md` BL-012 opens with "**CLOSED at S4-06.**"). S5-04 (the comment's author) and S4-06 (the backend fix) are separate sessions and the comment was never revisited. |

**One further correction to the framing in the brief.** The brief says the journey document was
amended to narrow the *phone* rule. It was. But `customer.md:262-265` still states the **email**
rule unamended and in force:

> - **Email address mistyped** → editable in place on its row, then resend. A typo otherwise
>   leaves the customer watching a timer for a code that will never arrive.

So the app is currently in **violation of a live, unamended journey rule** — not merely missing a
nice-to-have. That is the strongest single argument for shipping the fix before the pilot.

---

## 4. The backend contract, quoted

[OBSERVED] `backend/src/main/java/com/sfbank/bayanati/otpverification/web/OtpResendRequest.java:20`

```java
public record OtpResendRequest(String profileId, String channel, String correctedEmailAddress) {}
```

[OBSERVED] `OtpVerificationController.java:72-93` — `POST /api/v1/otp/resend` cleans the optional
email (`:113-130`: trim, `null` if blank, 400 over 254 chars per RFC 5321 §4.5.3.1.3, 400 on any
ISO control character, **no format validation, deliberately** — `:107-112` says none exists at
Stage 1b intake either) and passes it to
`otpVerificationService.resend(profileId, channel, correctedEmailAddress)`.

[OBSERVED] `OtpVerificationService.java:271-275` — non-email channel with a correction throws
`EmailCorrectionNotApplicableException` → 400.

[OBSERVED] `OtpVerificationService.java:290-293, 366-377` — the correction is applied **inside**
the Phase-1 `transactionTemplate` that holds `findChannelStateForUpdate`'s row lock, **before** the
reservation outcome is decided:

```java
if (correctedEmailAddress != null && state.state() != ChannelState.VERIFIED) {
  applyEmailCorrectionIfChanged(profileId, requestId, correctedEmailAddress, now);
}
```

[OBSERVED] `OtpVerificationService.java:420-430` — `applyEmailCorrectionIfChanged` no-ops on an
unchanged value, else writes `updateEmailAddress`, calls
`invalidateChallengesForChannel(profileId, EMAIL, now)` **in that same transaction**, and appends
an `email_address_corrected` audit event carrying previous and new address. Its Javadoc
(`:411-418`) records why the invalidation cannot be left to Phase 2: a refused reservation returns
before Phase 2 runs, which would leave the old address's live code enterable — *"exactly the 'a
single code sent to several channels proves none of them' failure CLAUDE.md's OTP rule exists to
prevent. Found by `@agent-reviewer`, S4-06."*

[OBSERVED] `OtpVerificationService.java:296-299` — the masked destination is computed **after**
Phase 1 commits, so a refused-but-corrected resend returns the **new** address's mask, not a stale
one.

**Client side, unchanged since S5-04** [OBSERVED]:

- `mobile/lib/core/entry/entry_api.dart:43` —
  `Future<OtpResendResult> resendChannel({required String profileId, required String channel});`
- `mobile/lib/core/entry/dio_entry_api.dart:105-110` — posts
  `data: {'profileId': profileId, 'channel': channel}`. The third field is never sent.
- `mobile/lib/core/entry/entry_repository.dart:271-281` — `resendChannel({required String channel})`,
  no email parameter.

**A useful fact the fix gets for free:** `dio_entry_api.dart:116` already parses
`maskedDestination` off the resend response into `OtpResendResult`, and
`channel_verification_screen.dart:208-223` never reads it. A corrected address's new mask is
therefore already on the wire and already decoded — the edit control needs no extra round trip to
refresh the row.

---

## 5. A hazard nobody has recorded, found while verifying claim 5

**Correcting an email while resends are exhausted or the channel is locked destroys the customer's
last usable email code, and nothing warns them.**

[OBSERVED] `OtpVerificationService.java:375-387`: the correction runs **before** the `VERIFIED` /
`locked` / `CAP_EXHAUSTED` / `TOO_SOON` checks, and `applyEmailCorrectionIfChanged` invalidates the
email challenge unconditionally when the address actually changes.

[OBSERVED] `customer.md:283-284`: *"Resends exhausted on a channel — 3 per channel per session →
that channel's resend control is disabled. **Any code already delivered remains usable until it
expires.**"*

Put together: a customer at `CAP_EXHAUSTED` who still holds a valid code for the old address, and
who taps an edit control, loses that code and can never obtain another this session. The email
channel becomes permanently unverifiable. This is *correct backend behaviour* — the journey frames
edit and resend as two separate acts, and the alternative (a live code for an address no longer on
file) is worse. But it is a **client-side design constraint the fix must encode**, and it is
currently written down nowhere.

**Recommended handling in the fix:** show the edit control only when a resend is actually available
(`!resendExhausted && !locked && !verified`), which is the same predicate `canResend` already
computes at `channel_verification_screen.dart:456-462` minus the countdown term. During a
`TOO_SOON` countdown the control should stay available (correcting is legitimate; the customer just
waits), with copy that says the address was updated and no code has been sent yet.

**Filed as BL-098** regardless of which structure is chosen. It is a live property of shipped
backend code with no test naming it from the client's perspective and no note in `customer.md`.

---

## 6. Second finding: `resumeStage` is not reset when the phone-correction link is tapped

[OBSERVED] `channel_verification_screen.dart:403` is a bare `context.go('/contact-channels')` with
no repository call. [OBSERVED] `entry_repository.dart:156` writes `resumeStage = 'contactChannels'`
**only** inside `checkAccount` (Stage 1a); `:189` writes `'awaitingVerification'` on a successful
Stage 1b submit.

Consequence: a customer who taps the correction link and is then interrupted (app killed, battery
dies) relaunches into `ResumeAwaitingVerification` → `/channel-verification`
(`launch_screen.dart:358-359`), **not** back into the 1b screen they were editing. Self-recovering
— they can tap the link again if no phone has verified — but it is a real inconsistency between
the route the customer is on and the state the store records.

It matters to this AD in two directions: it is a small argument *for* collapsing the route boundary
(option D removes the inconsistency by construction), and it is evidence that the current split
already leaks state that a merge would have to handle anyway. **Filed as BL-099.**

---

## 7. The structures

Four viable structures were found. Two of them (A and C) are variations on the current shape; two
(B and D) are genuine merges at different levels.

### Structure A — Two screens as today, plus an in-place email edit on the OTP row

The journey document's own design (`customer.md:262-276`). Phone correction = the existing gated
link back to 1b. Email correction = an inline editable field on the email row that populates
`correctedEmailAddress` on the next resend.

**What changes:** `entry_api.dart:43` signature; `dio_entry_api.dart:105-110` map literal;
`entry_repository.dart:271` signature; the email branch of `_buildRow` in
`channel_verification_screen.dart`; delete the stale comment at `:21-27`. Roughly **3–4 new Arabic
strings** (an edit affordance label, a save/confirm, an "address updated, code not sent yet" line,
and a validation/error line if any).

**What breaks:** nothing structural. `_ChannelRowState` gains a `TextEditingController` and an
editing flag; it already owns one controller and disposes it at `:140`, so the disposal pattern
exists.

**What must be retested:** the 17 widget tests in
`mobile/test/features/entry/channel_verification_screen_test.dart` should all still pass unmodified
(none asserts the absence of an edit field); add ~5 new ones — edit-then-resend sends the field,
edit is refused/hidden at `capExhausted` and `locked`, a `TOO_SOON` refusal with a correction shows
the new mask and does not claim a code was sent, the row's mask updates from
`OtpResendResult.maskedDestination`, phone rows never show the control.

**State/route changes:** none. No go_router change, no drift schema change, no `resumeStage` change.

**Arabic RTL at 5 inches:** the email row grows by one field's height (~56 dp) *only while
editing*, and the OTP screen already puts every row in a `SingleChildScrollView` (`:386`) with the
Next button pinned outside it (`:410-441`). The pilot handset is 720×1520 at API 28 [OBSERVED
`docs/sessions/2026-09-06-s5-08-liveness-device-run.md:93`]. Worst case is three channel rows plus
an expanded email editor; that already scrolls today with three rows and is not a new class of
problem. **The one thing to check on-device:** the keyboard covering the save affordance, which is
the exact failure the existing code already solves for the code field by unfocusing at six digits
(`:521-524`) — the same treatment applies. The email field is LTR content in an RTL layout;
`contact_channels_screen.dart:375-382` sets **no** `textDirection` on its own email field, so
whatever that renders as today is the precedent to match, not to improve in this change. Field
prefill has a source: `LocalDraft.emailAddress` survives Stage 1b submit (`submitContactChannels`
at `entry_repository.dart:182-194` writes only `LocalProgress`), and `loadDraft()` is public.

**Backend support:** **complete. Zero backend change.**

**Cost:** see §9(a).

---

### Structure B — One merged screen: phone/email entry and OTP rows on the same viewport

Everything on one route, one scroll view: phone field, SMS/WhatsApp checkboxes, email field, a
send-codes action, and the three OTP rows appearing beneath once codes are issued.

**What breaks:**

1. **The phone field becomes a permanently-visible control that must be frozen after any phone
   verifies.** Today the constraint is satisfied structurally — the *route* becomes unreachable. In
   a merged screen it becomes a per-widget `enabled:` flag that a later edit can silently drop.
   This is the same class of defect BL-086 exists to name: a behaviour rule expressed as
   presentation.
2. **Two very different write paths sit adjacent.** Editing the phone and re-sending is
   `POST /api/v1/contact-channels`, which on the re-entry branch calls `updateContactDetails`,
   `invalidateOtpChallenges` and `upsertChannel(…, UNVERIFIED)` for every selected channel
   [OBSERVED `ContactChannelsService.java:312-323`] — i.e. **it unverifies channels that were
   verified**. Editing the email and re-sending is `POST /api/v1/otp/resend`, which touches only the
   email row. On one screen these must never be confusable, and the current copy («العودة لتعديله»
   — *go back to edit it*) does that work by naming a navigation.
3. **The at-least-one-phone-channel validation and the OTP attempt model become simultaneously
   live.** Today `_bothPhoneChannelsDeselected` disables Next on 1b
   (`contact_channels_screen.dart:279`) and the OTP screen's Next gates on `_anyPhoneVerified`
   (`:304`). Merged, one Next serves both and its meaning changes mid-screen — which
   `customer.md:240` forbids outright: *"**Next** is labelled Next throughout and never changes its
   label or meaning."*
4. **Arabic RTL at 5 inches: this is where it fails.** Count the merged content at 1.0× text scale:
   an explanatory line, phone field, a bordered group box with a header and two `CheckboxListTile`s,
   an email field, an email checkbox, two consequence lines, then three channel rows each carrying
   an icon+label+mask row, a code field, a verify button, a resend button and a state line — plus
   the status line and Next pinned below. That is roughly **twice** the tallest screen this app
   currently ships. The project already has direct evidence of this exact failure mode at this exact
   size: [OBSERVED `docs/sessions/2026-09-08-mobile-pilot-build.md:152-157`] — BL-087's five
   `ListTile`s "*pushed the irreversibility warning and submit control off the viewport — on a
   5-inch handset, a worse defect than the sparseness it fixed*", caught by four tests failing with
   "derived an Offset … that would not hit test". And S8-03 committed to **1.3× text scale without
   overflow** [OBSERVED `EXECUTION_PLAN.md:136`]. Arabic strings running longer than English is the
   constraint that makes this not close. **A merged screen at 5 inches would be a long scroll in
   which the customer must scroll up to fix the number and back down to enter the code — strictly
   worse than a route change, which at least resets the scroll position and the mental frame.**

**Backend support:** no new endpoint needed — both calls exist. But note the AD-008 coupling:
`ContactChannelsService.java:284-290` justifies the device-less-reentry heuristic partly by
asserting *"the 'wrong phone number?' correction lives on the Stage 2 screen, and every in-journey
route back to account entry clears local state first."* The heuristic itself survives a merge (it
requires an **active accepted identity cycle**, impossible at Stage 2, so it matches nothing either
way) — but that written justification would need re-checking against the new shape, and AD-008 is
**CLOSED**. Touching a closed AD's supporting reasoning before a pilot is exactly what the
constraints forbid.

---

### Structure C — Two screens, plus one generic "correct my contact details" affordance on Stage 2 that routes by field

A single control on Stage 2 («البيانات غير صحيحة؟») opening a sheet listing the phone and the
email, each with its own repair: the phone entry navigates to 1b (and is absent once a phone has
verified); the email entry edits in place.

**What it buys:** one discoverable affordance instead of one link that only mentions the phone, so
an email-only mistake is findable after a phone verifies — the exact hole in today's UI. It also
gives one place to explain *why* the phone is frozen, rather than the link simply vanishing.

**What it costs:** a modal/sheet is a second surface with its own Arabic copy, its own tests, and
its own keyboard-overlap behaviour at 5 inches. It also introduces a nested surface into a screen
whose only current dialog is the Stage 2 confirmation (`:337-369`) — and `customer.md:258-260`
records a deliberate stance against dialogs on the happy path (*"a dialog on the happy path only
teaches people to dismiss dialogs"*). Correction is not the happy path, so it does not violate
that; but it is more UI than the journey document asks for.

**Backend support:** identical to A — complete, zero change.

**Verdict:** strictly more work than A for a benefit A can deliver in copy alone. A's edit control
on the email row is *itself* the discoverable affordance for the email; the phone link already
handles the phone. C is a reasonable fallback if the PO finds A's email control insufficiently
discoverable on the device, and it does **not** require reopening anything — it can be built on top
of A later.

---

### Structure D — One route, two panes (a `PageView` / stepper inside a single go_router route)

Merge at the **route** level while keeping one pane of content visible at a time. `/channels`
becomes one route holding a two-page controller; "back to edit the number" is a page-controller
call instead of `context.go`.

**What it buys:** it fixes §6's `resumeStage` inconsistency by construction, and it makes the
back-to-edit transition feel like part of one task rather than a navigation.

**What it costs:**

1. **Every 5-inch objection from B disappears** — only one pane is on screen at a time. Good.
2. **But every state-model objection stays, and one new one arrives.** `launch_screen.dart:352-373`
   switches on a `LaunchDecision` sealed type with distinct `ResumeContactChannels` and
   `ResumeAwaitingVerification` variants (`entry_repository.dart:442-462`), each mapping to a route.
   A single route means the *pane index* must be carried as route `extra` or derived, and `extra` is
   already occupied by the `offline` bool at `:357` and `:359`. The `LaunchDecision` type,
   `_navigate`, and 10 launch-screen widget tests all move.
3. **The two screens' controllers cannot simply cohabit.** `ContactChannelsScreen` owns two
   `TextEditingController`s plus autosave listeners attached only after the initial prefill
   (`:68-107`, a fix for a real abandon-race, S5-02 second pass); `ChannelVerificationScreen` owns a
   per-row controller map plus a 1-second `Timer.periodic` (`:118`) it cancels in `dispose`
   (`:139`). Merging the routes means one `State` owning both lifecycles, or a nested-widget
   arrangement that keeps them separate — in which case the merge has bought nothing but a shared
   `Scaffold`.
4. **The `_abandoned` race guard exists twice, deliberately, in two screens**
   (`contact_channels_screen.dart:44`, `channel_verification_screen.dart:95` — the latter's comment
   reads *"Mirrors `ContactChannelsScreen._abandoned` exactly — same race, same fix"*). Collapsing
   them is a correctness change to a guard that was added under review to stop one customer's data
   resurrecting for the next person on a shared device. That is not refactoring; that is
   re-deriving a privacy control.

**Backend support:** identical to B — no new endpoint, same AD-008 comment coupling.

**Verdict:** the least-bad merge. Still a rewrite, still pre-pilot-infeasible, and it does not solve
the email dead end any faster than A does.

---

## 8. Blast radius of the merge (structures B and D specifically)

| Surface | Verdict | Evidence |
|---|---|---|
| **go_router routes** | Disturbed. `/contact-channels` and `/channel-verification` are two flat top-level `GoRoute`s (`app_router.dart:44-53`), each taking `offline` from `state.extra`. A merge deletes or repurposes one and must re-home the `extra` payload. | [OBSERVED] `app_router.dart:30-53` |
| **Back-navigation** | Not disturbed — there is nothing to disturb. All five navigations into these screens use `context.go` (a stack replacement), not `push`: `launch_screen.dart:357,359`; `account_entry_screen.dart:78`; `contact_channels_screen.dart:181`; `channel_verification_screen.dart:403`. Neither screen declares a `PopScope`. | [OBSERVED] complete grep of `mobile/lib` for `context.go`/`GoRoute` |
| **Deep links** | **Not disturbed — none exist.** `mobile/android/app/src/main/AndroidManifest.xml:28-31` declares exactly one `intent-filter`, `MAIN`/`LAUNCHER`. No `VIEW` action, no scheme, no App Link. | [OBSERVED] |
| **Resume-after-restart from the local store** | **Disturbed, materially.** `LocalProgress.resumeStage` is a string with distinct `'contactChannels'` and `'awaitingVerification'` values (`entry_repository.dart:9-10`), written at `:156` and `:189`, read into `ResumeContactChannels`/`ResumeAwaitingVerification` at `:442-462`, and mapped to routes at `launch_screen.dart:356-359`. A merge either collapses those two values (a stored-data semantics change on devices that may already hold the old value) or keeps them and adds pane-selection logic. Also see §6: the boundary is already slightly wrong today. | [OBSERVED] |
| **Drift schema** | Not necessarily disturbed. `LocalProgress` already carries `verifiedBranchCode`, `verifiedAccountNumber`, `resumeStage`, `profileId`, `channelsSummary`, `updatedAt`; `LocalDraft` carries the phone/email/selection fields. A merge needs no new column — only a `resumeStage` value decision. **Structure A needs no drift change at all.** | [OBSERVED] `entry_repository.dart:120-196, 225-243, 302-325` |
| **OTP attempt and rate-limit model** | **Not disturbed by any structure.** Every limit is server-side: `RESEND_LIMIT`, `RESEND_DELAYS[resendCount]`, the wrong-code lock, and the escalating phone block (`OtpVerificationService.java:385-400`, `activePhoneLock` at `:247-252`). The client only ever reflects `secondsUntilAllowed` and is explicitly forbidden from guessing a schedule (`channel_verification_screen.dart:36-40, 70-72`). **What a merge *does* disturb is how easy it is to reach the destructive re-entry**, which invalidates all challenges and resets channel states (`ContactChannelsService.java:318-323`) and is itself refused during an active phone block (`SessionTemporarilyBlockedException`, 429). | [OBSERVED] |
| **What the back office displays for an in-progress profile** | **Not disturbed.** The operator contract is `ChannelStateResponse { channel, state: 'verified'\|'declined'\|'unverified', verifiedAt }` on `ProfileDetail.channels` (`backoffice/src/api/types.ts:106-113, 268`). It reflects backend channel state, which no structure changes. **One consequence worth naming to the PO though it needs no UI change:** an email correction writes `app.profile_customer_data.email_address` and emits an `email_address_corrected` audit event carrying previous and new address — so an operator reading the audit trail will see corrections, and the back office has no view that surfaces them today. That is existing behaviour since S4-06, not something this AD introduces. | [OBSERVED] `types.ts`; `OtpVerificationService.java:432-447` |
| **The journey document** | **Disturbed by a merge, not by structure A.** `customer.md:217-219` opens Stage 2 with *"One screen. It shows a row for each channel selected at 1b"* — "at 1b" is a cross-stage reference that a merge invalidates. `:97-143` is Stage 1b's own section with its own Next semantics. `:240` forbids Next changing meaning. A merge is a rewrite of two stage sections plus the Corrections block. **Structure A requires no journey change at all — it implements what `:262-265` already says.** | [OBSERVED] |
| **Existing tests and the coverage ratio** | **Merge: 32 widget tests rewritten** — 17 in `channel_verification_screen_test.dart`, 15 in `contact_channels_screen_test.dart` — plus the 10 in `launch_screen_test.dart` that assert routing. Current mobile suite is 515 tests at **84.65 %** coverage (S8-09 gate). A rewrite that deletes tested code and adds untested code puts the 80 % gate genuinely at risk mid-change; note also CLAUDE.md's caveat that Flutter coverage does not count a file no test imports, so a half-migrated screen can hide the gap. **Structure A: no existing test should need changing; ~5 added.** | [OBSERVED] test-file `testWidgets` counts; `EXECUTION_PLAN.md:141` S8-09 gate line |
| **Closed ADs** | **AD-008 is touched by a merge — at the level of its supporting reasoning, not its rule.** `ContactChannelsService.java:284-290` grounds the device-less-reentry detector on where the phone correction lives. The rule survives (the superseder's `WHERE` requires an active accepted identity cycle, unreachable at Stage 2), but the recorded justification would need re-checking. Constraints forbid reopening a closed AD pre-pilot. **Structure A does not touch it.** | [OBSERVED] |

---

## 9. Separate costings

### (a) Minimum change that removes the email dead end, inside the current structure

**Scope:** thread `correctedEmailAddress` through three client files; add an inline edit affordance
to the email row only; gate it on `!verified && !locked && !resendExhausted`; refresh the row mask
from `OtpResendResult.maskedDestination`; distinguish "address updated, code sent" from "address
updated, no code sent yet (retry in N s)"; delete the stale comment at
`channel_verification_screen.dart:21-27`.

**Files:** `mobile/lib/core/entry/entry_api.dart`, `dio_entry_api.dart`, `entry_repository.dart`,
`features/entry/channel_verification_screen.dart`, plus the API fake used by the widget tests.
**Backend: none.**

**Effort:** **one Claude Code session, comfortably** — likely half of one, with the remainder going
to the review pass and the gate. Mobile only, so one gate: `fvm flutter analyze` +
`fvm dart run tool/check_coverage.dart`.

**Can it land before the pilot APK?**

- **Coverage/gates:** yes. Additive, tested code; the 84.65 % headroom absorbs it.
- **Second device pass:** **required, and unavoidable.** Any new interactive control on the OTP
  screen needs the 5-inch keyboard-overlap check that this project has now been burned by twice
  (BL-087's below-fold submit; the OTP button covered at six digits). It is a **short** pass — one
  screen, one interaction — not a full walk. Budget it honestly rather than claim it away.
- **Arabic-string PO gate:** it adds **3–4 new customer-facing Arabic strings**, so the wording
  review is touched. But note what is *actually* pending: BL-071 is the **134 Uqudo SDK override
  strings**, explicitly out of scope for this AD, and the S8-05 "Arabic wording gate" that is owed
  at row close. Three new strings on one screen is an increment to a review that is already
  outstanding, not a reopening of a closed one. **[UNVERIFIED]** whether the PO treats that review
  as a single batch that a late addition would restart — that is a process question not resolvable
  from source; state it to them explicitly rather than assume.

**Cost of waiting until after the pilot:** the pilot ships in knowing violation of
`customer.md:262-265`. Concretely: a pilot tester who mistypes their email address has **no route
to fix it** and watches a resend timer for a code that will never arrive — which is verbatim the
failure that line of the journey document was written to prevent. They can only escape by
abandoning the whole session and starting over (losing Stage 1b entirely), or by tapping the phone
link *if* no phone has verified — a workaround that is unlabelled, undiscoverable, destructive of
phone verification, and unavailable at exactly the moment it is most needed. In a pilot where email
is optional and Sudan testing is SMS-first, the odds of it being hit are moderate; the cost if hit
is a support conversation and a burned single-use account (accounts are single-use — BL-089).

### (b) The full restructure (merge — B or D)

**Scope:** rewrite two screens totalling 1,006 lines (`contact_channels_screen.dart` 429,
`channel_verification_screen.dart` 577); rework `LaunchDecision`, `_navigate` and the `resumeStage`
value set; amend two stage sections of `customer.md` plus its Corrections block; rewrite 32 widget
tests and revisit 10 launch tests; re-derive the duplicated `_abandoned` privacy guard as one;
re-verify the AD-008 justification comment.

**Effort:** **2–3 sessions minimum**, and the estimate is soft in the direction of longer. This code
is dense with review-derived fixes whose reasons live in comments — the S5-02 listener-attachment-
ordering race (`contact_channels_screen.dart:68-74`), the S5-04 `_markChannelVerified` transaction
(`entry_repository.dart:297-325`), the `Material`-not-`Container` ink-splash fix (`:326-344`), the
round-up on the block countdown (`:259-263`), four separate Arabic grammar fixes. Each is a defect
that a rewrite can silently reintroduce, and several would produce a green suite while doing so.

**Can it land before the pilot APK?** **No, and it should not be attempted.** It forces a full
journey re-walk on the handset (not a spot check), a complete Arabic wording re-review of two
screens, and it puts the coverage gate under real pressure mid-change. All code is complete and the
pilot is one PO gate away; this is the single largest discretionary change available in the mobile
tier.

**Cost of waiting until after the pilot:** near zero, and possibly negative. The pilot is the first
time real bank staff walk Stage 1b→2 on the 5-inch handset. Their observations are the best
evidence anyone will get about whether the two-screen split is actually confusing — and the walk
that produced BL-090/BL-091 shows this project gets high-quality findings from exactly that
exercise. Restructuring *before* that evidence arrives means rebuilding against an assumption
instead of an observation.

---

## 10. Backend support, per structure

| Structure | Backend change needed? | Detail |
|---|---|---|
| **A** — two screens + in-place email edit | **None** | `OtpResendRequest(profileId, channel, correctedEmailAddress)` exists and is wired end to end through `OtpVerificationController.resend` → `OtpVerificationService.resend` → `reserveResend` → `applyEmailCorrectionIfChanged`. Covered by `OtpVerificationControllerTest`, `OtpVerificationIntegrationTest` — the latter including a live test driving a real profile to genuine `CAP_EXHAUSTED` with a correction attached (BL-012's closure note). |
| **B** — merged single screen | **None new** — but see the AD-008 comment coupling in §8. Both `POST /api/v1/contact-channels` (phone re-entry) and `POST /api/v1/otp/resend` (email) exist. |
| **C** — correction sheet | **None** — identical to A. |
| **D** — single route, two panes | **None new** — identical to B. |

**The decisive backend fact for the structure question:** there is **no** backend path that corrects
an email without either (i) `correctedEmailAddress` on resend, or (ii) a `contact-channels` re-POST
that also unverifies every phone channel. Once a phone has verified — which the constraints require
to be permanent — (ii) is forbidden. So **the in-place email edit is mandatory in every structure**,
and no merge removes the need to build it. A merge is therefore *additional* work on top of the fix,
never a substitute for it.

---

## 11. Benchmark: https://mb1.sfbank-sd.com

[OBSERVED — WebFetch, 2026-09-10] The URL returns a single rendered word, **"Pearl"**. It is a
client-rendered SPA whose shell yields no markup a fetch can read; no colours, logo, typography,
form structure, OTP handling or correction pattern were obtainable. "Pearl" corroborates the bank's
slogan **«لؤلؤة المصارف»** (*pearl of banks*) [OBSERVED
`docs/sessions/2026-09-07-research-ui-ux-design-plan.md:472`], so the page is the right property.

What the benchmark has **already** contributed and is settled, so this AD does not need to
re-derive it: brand blue `#105097` pinned as theme `primary`, white-on-blue measured at 8.03 : 1,
IBM Plex Sans Arabic bundled and Unicode-subset, emblem-only launcher icon — all shipped at S8-03
[OBSERVED `EXECUTION_PLAN.md:136`].

**What could not be got from the benchmark, and it is the part this AD wanted:** how the bank's own
mobile-banking product handles multi-step entry and correction on a small Arabic screen. A grep of
the FIB clone's built `Web_Code` bundle for Arabic OTP/correction strings was attempted;
**ripgrep timed out at 20 s** against the minified bundle. That is a tractable follow-up for a
session with a shell (extract the source maps, then grep) but it is not evidence held here. **No
guess was substituted about how the bank does it.**

---

## 12. Recommendation

**Ship Structure A now. Defer the merge indefinitely, and specifically past the pilot.**

Concretely, for the product owner:

1. **Before the pilot APK:** the mobile-side email correction (§9a), one session, no backend change,
   no journey-document change, no closed AD touched. It closes a live violation of
   `customer.md:262-265` and deletes a comment that has been telling every future session a false
   thing about the backend since S4-06.
2. **Filed as part of it:** the edit-while-capped hazard from §5 (**BL-098**) — a new backlog row
   plus one line in `customer.md` Stage 2 Corrections, since the backend applies a correction even
   when the resend is refused and that is currently written down nowhere.
3. **Filed separately:** the `resumeStage`-not-reset inconsistency from §6 (**BL-099**). Low
   severity, self-recovering, but it should not be discovered again by a third person.
4. **Do not merge.** If Stage 1b→2 confusion is observed during the pilot walk, revisit with
   **Structure D** (single route, two panes), not B — D keeps the 5-inch viewport intact, which B
   cannot.

### Conditions under which the recommendation flips

- **The pilot walk shows customers cannot connect the two screens.** If bank staff observably lose
  the thread at the 1b→2 boundary — "where did my number go?", tapping back, abandoning — then the
  split is costing comprehension, not just taps, and D becomes worth 2–3 sessions. This flips the
  *merge* half only; the email fix ships either way.
- **The PO removes the "phone frozen after verification" rule.** If a phone number could be
  corrected after a phone channel verified, the two repairs stop having different blast radii, the
  route boundary stops carrying meaning, and the main structural argument for two screens dissolves.
  **This would require reopening the S8-05/BL-091 decision** and would need a new answer to what
  happens to a verification that a re-POST would wipe.
- **A backend endpoint appears that corrects the phone number *without* invalidating verified
  channels.** Then the destructive/non-destructive asymmetry is gone and a merged screen carries no
  trap. Nothing in the backlog proposes this and it is not recommended — the asymmetry is correct,
  not accidental.
- **The target device floor rises off 5 inches.** Structure B's disqualifying objection is viewport
  height at 1.3 × text scale with Arabic strings. On a 6-inch-plus floor, B becomes merely a rewrite
  rather than a rewrite that produces a worse screen. It still would not be worth doing pre-pilot.
- **The email channel is dropped from the pilot entirely.** [OBSERVED `EXECUTION_PLAN.md:123`] S7-12
  records *"the email OTP channel was never exercised because the product owner declared only SMS
  and WhatsApp."* If the PO formally declares email out of the pilot, the urgency of §9a drops from
  "before the APK" to "before email is enabled" — but the fix does not get cheaper by waiting, and
  the stale comment should be deleted regardless.

---

## 13. What could not be determined

- **Commit atomicity of the S8-05 amendment.** No shell in the researcher session, so no `git log`.
  Three independent plan records assert code and journey changed together; not verified.
- **How mb1.sfbank-sd.com handles multi-step entry and correction.** §11. The SPA yields nothing to
  a fetch and the FIB bundle grep timed out.
- **Whether the pending Arabic wording review is a batch that a late addition would restart.**
  Process question, not a source question. Must be asked, not inferred.
- **Whether the email field renders correctly LTR-in-RTL today.**
  `contact_channels_screen.dart:375-382` sets no `textDirection` on the Stage 1b email field, unlike
  the phone field at `:316-317` and the OTP code field at `:505-506` which both set it explicitly.
  Source does not say whether that is a deliberate choice for email or an omission — and it becomes
  load-bearing the moment a second email field exists on the OTP screen. **Check on the device
  before copying the pattern.**
- **Whether any existing test would fail if the merge collapsed the duplicated `_abandoned` guard.**
  Both guards and their comments were read; not every test that exercises abandon-during-load was
  traced. Sizing input for (b) only.
- **Real-world frequency of email typos in this population.** No data. The severity argument in §9a
  rests on the journey document's own stated rationale, not on measurement.

---

## 14. Risks if the recommendation is wrong

| Risk | If it fires | Reversal cost |
|---|---|---|
| Structure A's email control is undiscoverable on the 5-inch device | Same dead end persists in practice; the fix is theatre | **Low.** Structure C (a labelled correction affordance) builds on top of A without undoing it. Copy-and-layout change, part of a session. |
| The merge really was the right call and the pilot walk proves it | 2–3 sessions spent later that could have been spent before | **Moderate.** The work is the same size whenever it happens, plus a second Arabic wording pass on two screens. Nothing built in A is wasted — the in-place edit is mandatory in every structure (§10). |
| The edit-while-capped hazard (§5) ships unguarded | A customer who corrects at `CAP_EXHAUSTED` loses their live code and cannot verify email this session | **Low if caught in the same session** (one predicate on the control's visibility). **Higher if it ships:** it is invisible to the backend, produces no error, and would present to support as "email verification is broken sometimes." |
| The new Arabic strings restart the PO wording review | Pilot slips by the length of that review | **Unknown — the one cost that could not be sized.** Ask before building, not after. |
| The "no deep link exists" finding is wrong | A merge breaks an external entry point | **Very low risk.** `AndroidManifest.xml:28-31` is unambiguous — `MAIN`/`LAUNCHER` only. iOS is out of scope per the task and uncompiled per AD-003. |

---

## 15. Card updates

**No `docs/components/` card exists for OTP verification or contact channels** (the eleven cards
are: reference-data, backoffice-components, persistence, uqudo-api-findings, cloudfront-vpc-origin,
core-banking, civil-registry, mobile-packages, backoffice-auth, messaging, uqudo-sdk). This was not
an SDK investigation, so no card is owed.

**Corrections owed in source, exact:**

**1. `mobile/lib/features/entry/channel_verification_screen.dart:23-27`** — the first bullet of the
class doc comment is factually wrong and must be replaced when the fix lands. Current text asserts
`OtpResendRequest` is `{profileId, channel}` only and that BL-012 is open. Both are false as of
S4-06. [OBSERVED]

**2. `docs/journeys/customer.md`, Stage 2 "Corrections" (after line 265)** — add, marked as the
S4-06 backend behaviour it describes:

> - **Correcting the email address and resending are two separate acts.** The correction is applied
>   and the old address's live code is invalidated **even when the resend itself is refused** (too
>   soon, resends exhausted, channel locked). One consequence must be reflected in the UI:
>   correcting the address after resends are exhausted destroys the last usable code and leaves the
>   email channel unverifiable for the session, so the edit control is offered only while a resend
>   is still obtainable. [OBSERVED `OtpVerificationService.reserveResend`,
>   `applyEmailCorrectionIfChanged`]

**3. `PROJECT_PLAN.md` open architecture decisions** — AD-011 opened by this report. **Not settled
here.**

---

## 16. Noticed in passing

Not investigated, not expanded, recorded so it is not lost:

- `channel_verification_screen.dart:577` has a stray blank line before the closing brace — Spotless
  does not govern Dart and `dart format` is not a mobile gate, so this is cosmetic and should
  **not** trigger a repo-wide format.
- `OtpResendResult.maskedDestination` is decoded (`dio_entry_api.dart:116`) and never read by any
  screen. Dead data today; the §9a fix gives it a first consumer.
- `OtpVerificationService.java:285-289` records an accepted, open race on the resend **delay** check
  (`mostRecentIssuedAt` only advances in Phase 2, after the Phase-1 lock releases, so two
  near-simultaneous requests can both pass the delay gate). The cap still bounds total spend.
  Flagged by a reviewer at S3-08 as a residual; no backlog row was carrying it — **now filed as
  BL-100.**
- `contact_channels_screen.dart:375-382` sets no `textDirection` on the email field while the phone
  field two blocks up sets it explicitly. See §13.

---

## 17. Parent-session verification of the load-bearing claims

The whole recommendation rests on the backend already supporting the email correction. The parent
session confirmed all three claims directly against source before filing this report, rather than
relying on the subagent's report alone:

- `backend/src/main/java/com/sfbank/bayanati/otpverification/web/OtpResendRequest.java` — confirmed
  a three-component record ending
  `public record OtpResendRequest(String profileId, String channel, String correctedEmailAddress) {}`,
  with a Javadoc naming S4-06/BL-012 and documenting the write-scope posture of the unauthenticated
  endpoint.
- `mobile/lib/features/entry/channel_verification_screen.dart:22-27` — confirmed the stale comment
  verbatim, including "(`OtpResendRequest` is `{profileId, channel}` only) — already filed as
  BL-012, confirmed still open by this session."
- `BACKLOG.md` BL-012 — confirmed the row opens "**CLOSED at S4-06.**"

The stale comment and the closed backlog row contradict each other in the repo today. That
contradiction is itself the finding.

**New items filed from this report:** BL-098 (edit-while-capped hazard), BL-099 (`resumeStage` not
reset on the correction link), BL-100 (resend-delay race, previously unfiled), BL-101 (the mobile
email-correction defect itself — the §9a work).
