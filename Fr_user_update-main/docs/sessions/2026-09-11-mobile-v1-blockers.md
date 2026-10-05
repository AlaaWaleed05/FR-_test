# Mobile V1 blockers — 2026-09-11 (S8-14)

**Built:** BL-021, BL-105, BL-115, BL-109, and **the (b) half of BL-114**. **Not built:**
BL-114(a), BL-116 and BL-106 — each for a reason established at source before any code was
written, not because the session ran out.

Two of those three were removed from scope because `@agent-reviewer` refuted the analysis they
rested on — the second and third such refutation in three sessions. Each time the cost of finding
it was a pre-build audit rather than a shipped screen. **The same reviewer then found four real
defects in the code I wrote that day**, three of them in the salary-certificate work; all four are
fixed and listed with their dispositions below.

Provenance: [OBSERVED] = checked at the named file or command · [UNVERIFIED] = inferred and
labelled.

---

# Done

## What the customer now sees that they did not before, screen by screen

### The block screen — Stages 8, 9 and 10 (BL-114(b), BL-120)

**Before:** a customer whose block deadline had passed was told **«يمكنك المحاولة مرة أخرى الآن»**
— *you can try again now* — with a retry button. For a profile that had spent its lifetime token
cap, that sentence was false and stayed false for ever, and the button could never succeed. Tapping
it produced the same screen. There was no way out of the loop.

**Now:** the screen distinguishes four things it can actually observe, and says only those.

| What the app observed | What the customer sees now |
|---|---|
| A deadline still in the future | A countdown, and no retry control at all |
| The countdown ran out **while they watched** | «يمكنك المحاولة مرة أخرى الآن» + a retry — still honest, because nothing has been refused since the wait ended |
| The server refused **and its deadline had already passed** | «انتهت مدة الانتظار، ومع ذلك لا يزال هذا الإجراء غير متاح. يرجى زيارة أقرب فرع.» and **no retry control** |
| The refusal carried **no deadline at all** | «هذا الإجراء غير متاح حاليًا. يرجى زيارة أقرب فرع.» and **no retry control** — its own case, because "the wait is over" is a claim about a wait that was never announced |

The last two lines are true under a lifetime cap and under an ordinary block alike, which is the
whole point — **they need no discriminator**. None of the four claims anything about limits, names
a remedy, or mentions manual completion, per your amended ruling A. **The loop now terminates in
exactly one round trip**, and two regression tests prove the retry disappears after a refusal.

The fourth row exists because `@agent-reviewer` caught the first version folding "no deadline" in
with "deadline passed", and so telling a customer **the waiting period has ended** about a wait
that was never announced. That state is real — BL-049 records a blocked profile whose deadline is
legitimately null — and it is exactly the kind of unobserved claim this whole design exists to
avoid.

**One screen, not four.** `ScanBlockedView` was promoted to `mobile/lib/core/widgets/blocked_view.dart`
as `BlockedView` and is now shared by Stages 8, 9, 10 and the new Stage 0/1a block screen, so the
four cannot drift apart. [OBSERVED]

### Stage 10 — it now has a way out (BL-109)

**Before:** Stage 10 was the only block screen in the journey that passed no exit control at all,
while Stages 8 and 9 both did — and it is reached by `context.go`, so there was no back arrow
either. A customer who hit the liveness block could only force-quit. **Now:** «العودة إلى البداية»,
the same control its siblings already had. [OBSERVED]

### Stage 0, the launch screen — a phone lock is no longer blamed on the customer's internet (BL-021)

**Before:** the backend has answered `BLOCKED` with a real deadline since S4-06. The app had no
such value in its enum, so decoding **threw**, and the customer was shown **«تعذر بدء التطبيق. تأكد
من اتصالك بالإنترنت»** — *check your internet connection* — for a lock the bank had applied.
Reopening the app produced it again.

**Now:** a dedicated block screen with the **real deadline counting down**, the same honest
treatment every other block gets, and the reassurance that nothing entered has been lost. When the
lock lapses, the next launch simply resumes — **the block costs a wait, never the draft**, and a
test pins that the session is not cleared on this path. [OBSERVED]

**The launch screen's error copy was also wrong for every case it could show.** Connectivity
failures never reach it — they are caught earlier and resume offline — so "check your internet"
was never the right advice for anything landing there. It now reads «تعذر بدء التطبيق. يرجى إعادة
فتح التطبيق، وإذا تكرر ذلك يرجى زيارة أقرب فرع.»

### Stage 1a — the phone lock stops being an account-number error (BL-021, second surface)

**Before:** the same unmapped value fell through a bare `catch` and set the **account-number field
error**, pointing the customer at the one field that was not the problem. **Now:** the same block
screen, with the deadline. The reassurance about saved data is deliberately suppressed here,
because this session has entered nothing yet. [OBSERVED]

### Stage 6 — «تم إرفاق» is now true (BL-105)

**Before:** the salary certificate was never uploaded. `POST /api/v1/salary-certificate` has
existed since S4-06 and **nothing in the app ever called it**. The customer was shown «تم إرفاق»
— *attached* — the moment they picked a file, and that file was deleted with the rest of local
state when the session cleared. The bank never received it.

**Now:** the file is uploaded, and the confirmation is earned rather than assumed. Three states,
and only one of them claims an attachment:

- **Uploading** — «جارٍ إرفاق: <filename>» with a spinner.
- **Accepted by the backend** — «تم إرفاق: <filename>». This is the only state that says it, and
  it is driven by a new `salaryCertificateUploadedAt` stamp on the local draft, not by the mere
  existence of a file path.
- **Picked but not accepted** — «لم يتم إرفاق <filename> بعد. يمكنك المتابعة، وسنحاول الإرفاق مرة
  أخرى.» with a retry control.

**Nothing blocks Next.** customer.md Stage 6 is explicit that this attachment "gates nothing, must
never block completion", so a failed upload changes only what the screen *claims* — a fix that
stopped the journey would have been a worse defect than the false confirmation it replaced. A
second best-effort attempt runs on Next for a certificate picked while the connection was down.

### Stage 11 — a customer who can neither draw nor upload is told where to go (BL-115)

**Before:** submit disabled until there is a capture, Back disabled by customer.md's own rule, and
**no branch mentioned anywhere** — a screen with no usable control and no advice. **Now:** «إذا
تعذر عليك التوقيع على الشاشة أو رفع صورة، يرجى زيارة أقرب فرع.» Back stays disabled,
deliberately: that is customer.md's rule, not an oversight. [OBSERVED]

## What was removed from scope before any code was written

**BL-114(a) — a customer who never reaches the camera must not consume a lifetime mint. Not
possible mobile-only.** The Uqudo plugin's entire Dart API is `init`, `setLocale`, `enroll`,
`recover`, `faceSession`, `lookup`, `reading` and four `isXxxSupported(DocumentType)` probes —
**no camera or permission probe**. `pubspec.yaml` carries no permission package. The token is a
required argument to `enroll`, so the mint cannot move after the camera. And **no endpoint can
un-mint** — `/cancel` *spends* an attempt. Filed; see *Carried forward*. [OBSERVED]

**BL-116 — the retry transplant would have been dead code.** Stages 3 and 4's reference providers
are `StreamProvider`s over the **local Drift database** and never touch the network, so a
connectivity failure yields an **empty list, not `hasError`** — the arm a takeover would have keyed
on. `ref.invalidate` re-reads the same local database rather than re-syncing. The row has been
rewritten with what is actually true, including the answer you asked for explicitly: **the state is
unreachable on the forward path and reachable on resume**, because `prepareCatalog()` gates Stage
2→3 (`channel_verification_screen.dart:475`) but `LaunchScreen`'s resume enters Stage 3 without
re-running it. [OBSERVED]

## What was deliberately not built

**BL-106.** Per your ruling: not the mobile half alone. `rejected` is terminal, so
`AccountCheckService` overrides to `TERMINAL` and Stage 1a tells a rejected customer **their update
was already completed** — and `profileStatus` is computed there and then **dropped** from the
response. After `clearAfterSubmission()` every re-entry is Stage 1a, so that is the path a rejected
customer actually takes. Filed as **BL-119**, two-tier, and also waiting on your BL-005 copy.

## Your four rulings, recorded

| Ruling | Where it now lives |
|---|---|
| **A** (amended) — cap stays permanent, no reset, no remedy promised, manual completion never named, and the "reached its limit" instruction withdrawn | BACKLOG.md **BL-114**, with the reason the app cannot detect a cap |
| **B** — drop the profile UUID from the export | BACKLOG.md **BL-117**, backend-only, with the consumer verification attached |
| **C** — national ID stays offered, rollout gated on one card-holder | BACKLOG.md **BL-083**, with what would trigger disabling it |
| **D** — OQ-001 deferred to V2, not a V1 gate | PROJECT_PLAN.md Phase 2 entry gates, with the exposure **and** the line that the bank ask still goes now |

**CLAUDE.md** gained the 250-line cap and the commit-proof-after-push rule. It is at **245 lines**,
so **nothing had to be cut**.

**S8-12's commit proof has been corrected.** It pasted a `git status` captured *before* the push,
so it read "ahead of 'origin/main' by 1 commit" while the text claimed both commits were pushed.
The claim was true — both are ancestors of `origin/main`, re-verified — but evidence that
contradicts its own claim is worth nothing. That is now the rule in CLAUDE.md.

## Review findings and what happened to each

`@agent-reviewer` ran twice on the diff, as required. The first pass raised **four SHOULD FIX
findings, three of them in code I had written that day**, and every one was a real defect.

| # | Finding | Disposition |
|---|---|---|
| 1 | A failed certificate upload **could block Stage 6 → Stage 7**. Only the read and the API call were inside the `try`; `file.exists()` and the draft write were outside, so either could escape into `_onNext`'s generic catch and stop `advanceToStage`. | **Fixed** — the whole method body is now inside the `try`. Directly contradicted the method's own contract, and customer.md's "must never block completion". |
| 2 | The **uploading spinner could never clear**. No `finally`, so an escape left «جارٍ إرفاق» on screen for ever with `onRetryUpload` null — no route back to a retry. | **Fixed** — `try/finally`, with a `mounted` check. |
| 3 | **Two overlapping picks re-created the BL-105 falsehood.** Pick A, upload A in flight, pick B, A succeeds → the draft is stamped and the field says «تم إرفاق» while naming file B, which the bank does not have. | **Fixed twice over** — the screen discards a result whose captured path no longer matches, and both pick buttons are disabled while an upload is in flight. Regression test added. |
| 4 | The **absent-deadline case claimed something unobserved** — «انتهت مدة الانتظار» for a block that never carried a deadline. | **Fixed** — a fourth case with its own copy. The existing test was rewritten to assert the new behaviour. |
| 5 | NOTE: the new branch sentences **promise an outcome** («you can complete your request at a branch»), which ruling A forbids. | **Fixed conservatively, and surfaced to you** — see the string list under *Needs your attention*. |
| 6 | NOTE: the class comment **credited `didUpdateWidget`** for breaking the loop; it is actually the re-created State, because a capped re-refusal returns the same instant untouched. | **Comment corrected.** `didUpdateWidget` stays as defence in depth, and the comment now says so. |
| 7 | NOTE: `BlockedScreen` and the `LaunchBlocked` navigation were **wholly untested** — and per R-009 an untested file leaves coverage unmoved rather than lowering it, so the gate could not have caught it. | **Fixed** — a new `blocked_screen_test.dart` (5 tests) and two launch-navigation tests. |
| 8 | NOTE: `identity_scan_models.dart` still **named the deleted `ScanBlockedView`**. | **Fixed** — and it is the same frozen-comment failure the rule added this session exists to prevent, found in code written the same day. |
| 9 | NOTE: base64 of a 10 MB PDF on the main isolate is jank, not a correctness problem. | **Accepted, not changed.** Images are downscaled before storage, so only a PDF approaches the ceiling. |

### The second pass found more — including the same defect one layer down

Re-run against the fixed diff, `@agent-reviewer` closed six of the eight but found **three more
real problems**, one of them serious:

| # | Finding | Disposition |
|---|---|---|
| 10 | **My fix for #3 only reached memory, not the database.** The screen discarded a stale result, but the repository still stamped the draft **unconditionally** — and the draft holds one path and one stamp, not keyed to each other. So a late success for file A stamped a row that by then named file B, and Stage 6 reads that stamp back on re-entry: **«تم إرفاق: B» returning from disk for a file the bank never received.** BL-105, restored from storage. | **Fixed** — the repository stamps only if the draft still names the file it uploaded. Deterministic regression test, **proved by revert** (fails "Expected: false, Actual: true"). |
| 11 | **A stalled upload could still block Next.** Dio declares no `sendTimeout`, and `receiveTimeout` does not start until a response begins arriving — so a connection that establishes then stalls part-way through a multi-megabyte body had no client-side deadline at all, with `_onNext` awaiting it. | **Fixed** — a 2-minute ceiling inside the repository, which the existing catch turns into the honest "not attached yet". |
| 12 | **Tapping Next during an upload started a second concurrent POST**, storing the certificate twice. | **Fixed** — the Next-path retry is gated on `!_certificateUploading`. |
| 13 | NOTE: my new launch test was titled "carrying the real deadline" but asserted only the destination — the shared stub route ignores `state.extra`. **The same shape I had just deleted another test for.** | **Fixed** — `/blocked` got a real builder and the test now asserts the instant and the flag. |

Findings 10 and 13 are the ones worth dwelling on: **10 is the defect this session exists to fix,
re-created by my own fix for it**, and **13 is the exact failure mode I had criticised twenty
minutes earlier**. Both were caught by review, not by me.

### One test I wrote, proved worthless, and deleted

I added a test asserting finding 1's fix — that the upload returns false rather than throwing when
the local write fails, inducing it by closing the session database. **On revert-restore it passed
against the buggy version too**: `_requireProfileId()` reads the same closed database and throws
*inside* the try first, so the draft write is never reached.

I deleted it rather than ship a test that passes against the bug it names — that is BL-113's exact
shape, and this register already carries three of those.

**The reviewer then showed me how to write it properly**, and it now exists: the fake API gained an
`onSalaryCertificateUpload` hook, and the test closes the database *inside* that hook. Everything
before the final local write runs against a live database, so only the statement under test fails.
**Proved by revert** — against the pre-fix version it fails with the escaping exception.

**One gap I am reporting rather than papering over.** The screen-level half of finding 3 — the
guard in `Stage6Screen._uploadCertificate` that discards a result whose file is no longer the one
on screen — has **no test**. I wrote one, and it passed against the reverted code, because real
file I/O does not complete under a widget test's fake async without `runAsync`, which fights the
gating the test needs. I removed it rather than ship a second false guarantee in the same session.
The **persisted** half — the more serious one, since it survives a restart — is covered
deterministically at the repository level and proved by revert.

## Proof that the migration guard works

Schema v6 adds `salaryCertificateUploadedAt`. The migration file already documents a trap it fell
into twice, and v6 is where the existing `else if` chain stopped being sufficient: **a device at v4
needs both the v5 and the v6 column**, while a device below v4 needs neither. Written as a flat
`else if` chain it silently skips v6 on exactly the v4 devices that need it.

Reverted to the naive chain, the v4 test fails and the v5 test still passes — which is precisely
what makes it dangerous:

```
$ (reverted to `else if (from < 5) ... else if (from < 6)`)
00:00 +3 -1: opening a real v4 file ... [E]
  SqliteException(1): while preparing statement, no such column: salary_certificate_uploaded_at
00:00 +3 -1: opening a real v5 file ... (passes)
00:01 +4 -1: Some tests failed.
```

Restored, both pass. This is the indirect-assertion case CLAUDE.md requires a revert-restore for.

---

# Needs your attention

## One decision

### The salary certificate has no offline retry queue

**The situation.** The certificate now uploads when the customer attaches it, and again when they
press Next on Stage 6. If both attempts fail — a customer who attaches while offline and then moves
on — the file stays on the handset, correctly marked "not attached yet", and nothing retries it
again. The customer can go back to Stage 6 and tap retry, but nothing prompts them to, and at
submission the bank simply does not have the document.

**The options.** (1) Leave it: two attempts, an honest label, and a manual retry. (2) Add the
certificate to the existing offline pending-sync queue so it flushes with the stage data. (3) Retry
it once more at submission time.

**My recommendation: option 1 for V1.** The attachment is optional and gates nothing, the screen
now tells the truth about it in every state, and the queue is entangled with **BL-107** — which
already records that `flushPending()` runs only from stages 3-7, so wiring the certificate into it
would inherit a known defect rather than avoid one. **What option 1 costs if you rule otherwise:**
some customers on poor connectivity will reach the end of the journey with no certificate attached
and only a label on a screen they have left behind to tell them so; the operator sees a profile
with no document and cannot tell a customer who declined to attach one from a customer whose upload
failed.

## New Arabic strings — for the pending native-Arabic review

These join the existing gate on the same provisional terms. **No new gate is opened.** All eight
verified at source. [OBSERVED]

| # | File:line | String |
|---|---|---|
| 1 | `blocked_view.dart:185` | انتهت مدة الانتظار، ومع ذلك لا يزال هذا الإجراء غير متاح. يرجى زيارة أقرب فرع. |
| 2 | `blocked_view.dart:187` | هذا الإجراء غير متاح حاليًا. يرجى زيارة أقرب فرع. |
| 3 | `blocked_screen.dart:50` | الدخول غير متاح مؤقتًا |
| 4 | `blocked_screen.dart:55` | تم إيقاف المحاولات مؤقتًا |
| 5 | `launch_screen.dart:346` | تعذر بدء التطبيق. يرجى إعادة فتح التطبيق، وإذا تكرر ذلك يرجى زيارة أقرب فرع. |
| 6 | `salary_certificate_field.dart:178` | جارٍ إرفاق: `<filename>` |
| 7 | `salary_certificate_field.dart:196` | لم يتم إرفاق `<filename>` بعد. يمكنك المتابعة، وسنحاول الإرفاق مرة أخرى. |
| 8 | `salary_certificate_field.dart:204` | إعادة محاولة الإرفاق |
| 9 | `stage11_screen.dart:420` | إذا تعذر عليك التوقيع على الشاشة أو رفع صورة، يرجى زيارة أقرب فرع. |

**Two of these were softened during the session**, after `@agent-reviewer` pointed out that
«يمكنك إكمال طلبك بزيارة أقرب فرع» — *you can complete your request by visiting a branch* — is a
promise of an outcome, which ruling A forbids even though it never uses the words "manual
completion". Strings 1 and 9 now point to a branch without promising what happens there. **If you
would rather they promised completion, that is a one-line change in each place** — I took the
conservative reading of your ruling rather than decide it for you.

String 5 **replaces** «تعذر بدء التطبيق. تأكد من اتصالك بالإنترنت ثم أعد فتح التطبيق.» The rest
are additions. Existing strings reused unchanged on the new screens, and so **not** new
review items: «يمكنك المحاولة مرة أخرى بعد …»، «يمكنك المحاولة مرة أخرى الآن، أو زيارة أقرب فرع.»،
«كل ما أدخلته وتم التحقق منه محفوظ، وستتابع من هذه الخطوة عند عودتك.»، «إعادة المحاولة»، «العودة
إلى البداية».

## New and changed screens — for the single pending device pass

Again, joining the existing pass; **no new gate**.

- **New:** the Stage 0 / Stage 1a block screen (`BlockedScreen`) — both the countdown state and
  the refused state.
- **Changed:** Stage 8, Stage 9 and Stage 10 block states (new copy, and the retry control now
  appears and disappears by rule); **Stage 10 additionally gains its exit control**.
- **Changed:** Stage 6's salary-certificate field — three states where there was one.
- **Changed:** Stage 11 — one new advice line.
- **Changed:** the launch screen's error state — new copy.

The state most worth putting on a real handset is **the block screen's refused case**, since it is
the one that must never reappear as a loop, and **Stage 6's upload states** on a genuinely poor
connection.

---

# Carried forward

## Filed, not built

| Row | What it is | Tier |
|---|---|---|
| **BL-114(a)** | The mint is charged before the camera opens, so a permission denial costs lifetime budget. **Needs a backend un-mint** — proven not possible mobile-only. | Two-tier |
| **BL-118** | The wire cannot say "this profile hit its cap", so the screen describes an observation instead of a fact. Needs a distinct coded response. | Two-tier |
| **BL-119** | `profileStatus` is dropped from `AccountCheckResponse`, so Stage 1a tells a rejected customer their update completed. **Blocks BL-106.** | Two-tier |
| **BL-120** | ✅ Closed same-day. The block screen manufactured its own past deadline when a genuine countdown hit zero — a client-side cause that would have survived any wire change. Fixed by `_elapsedOnArrival`. | Mobile |
| **BL-117** | Drop the profile UUID from export column 1 (your ruling B). | Backend |
| **BL-116** | Corrected, not built. Awaiting a corrected proposal from me. | Mobile |
| **BL-106** | The rejection state. Waits on BL-119 **and** on your BL-005 copy. | Two-tier |

**A pre-existing documentation defect, noted not filed:** six BACKLOG.md rows (BL-033, BL-034,
BL-035, BL-039, BL-086, BL-092) contain literal `|` characters in their prose, so they render as
broken table rows. Verified identical at HEAD, so this session did not introduce them; every row
added or edited here is well formed.

**A hazard found in passing and recorded on BL-116 rather than filed separately:**
`stage3_screen.dart:97-118` awaits the country and admin-division futures with **no `try`/`catch`**
while the body is gated on `_loaded` — if either throws, the screen is a permanent spinner.

## What the next session takes

The backend/deploy session: **BL-117**, **BL-119**, **BL-118** and **BL-114(a)** are all backend or
two-tier, and three of them unblock mobile work that is otherwise stuck. BL-119 first, since BL-106
is a V1 blocker waiting on it — though it also waits on your approval of the BL-005 copy, so the
copy is on the critical path for that one.

## Out of scope, untouched as instructed

iOS in every form · the export column itself, BL-089 and BL-097 · BL-082's keystore and store
assets · BL-104 and BL-103 · the 54 unexamined backlog rows · the back-office tier · AD-011 ·
WhatsApp and email messaging · reference-data update mechanisms · the rejection-reason wording.

---

## Gate output — verbatim, final run

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 10.8s)
```

```
$ fvm dart run tool/check_coverage.dart
02:19 +572: All tests passed!
Line coverage: 84.88% (3986/4696 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

```
$ fvm flutter build apk --release --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
Font asset "CupertinoIcons.ttf" was tree-shaken, reducing it from 257628 to 848 bytes (99.7% reduction).
Running Gradle task 'assembleRelease'...                          100.3s
√ Built buildpp\outputslutter-apkpp-release.apk (108.2MB)
```

The coverage command runs the full suite, so the test count above is the test gate too. The APK is
still **debug-signed** — BL-082, already ruled a V1 blocker for any Play Store route — and the
AGP/Kotlin deprecation warnings are BL-003, already filed. Neither is introduced here.

Test count moved **540 → 572**. Two tests that asserted the old dishonest block copy were
**rewritten** rather than deleted (they would otherwise have pinned the defect in place); two were
written, proved worthless against the bugs they named, and **deleted** — one of those was then
rewritten properly on the reviewer's suggestion and now discriminates. The rest are additions,
most of them written in response to review findings.

**Three revert-restore proofs were run this session**, each on an indirect assertion where a green
test could have coexisted with the broken version: the schema-v6 migration trap (above), the
repository's "nothing escapes" guard, and the path-keyed acceptance stamp.

---

## Untracked files before staging

Reported before `git add`, per R-053 (`git add -A` at 90ab089 swept 91 unrelated files into
`main`). **Exactly five untracked, every one deliberate:**

```
?? docs/sessions/2026-09-11-mobile-v1-blockers.md      this report
?? mobile/lib/core/widgets/blocked_view.dart           the shared block treatment
?? mobile/lib/features/entry/blocked_screen.dart       BL-021's screen
?? mobile/test/features/dataentry/salary_certificate_field_test.dart
?? mobile/test/features/entry/blocked_screen_test.dart
```

One deletion — `mobile/lib/features/identityscan/scan_blocked_view.dart`, replaced by
`blocked_view.dart`. The 108 MB release APK is correctly ignored and does not appear. Nothing
unrelated is being swept in.

---

## Commit proof

**Captured AFTER the push, per the rule this session added to CLAUDE.md.** The work commit:

```
$ git log --oneline -2
dad5819 S8-14: the four screens that told the customer something untrue
bc64fe5 docs: record S8-12's commit proof
```

```
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

**up to date with `origin/main`, not ahead of it** — which is the whole point of the rule.
Independently confirmed with `git merge-base --is-ancestor HEAD origin/main`, which exits 0.

As at S8-12, the report is one commit behind the work it describes, because a report cannot carry
the hash of the commit that creates it. `dad5819` holds the work; this proof section is added by
its immediate child and pushed with it. The difference from S8-12 is that **the output above was
taken after the push rather than before it**, so it shows the state it claims.

