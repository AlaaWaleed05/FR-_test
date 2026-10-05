# S8-10 — in-place email correction on the Stage 2 OTP screen (BL-101, BL-098)

**2026-09-10 · mobile only · no backend change · AD-011 left OPEN**

Closes a live violation of `docs/journeys/customer.md` Stage 2 "Corrections": a mistyped email
address had no in-app repair. The backend has accepted `correctedEmailAddress` on
`POST /api/v1/otp/resend` since S4-06 and BL-012 has read CLOSED since then, but the client never
sent the field and `channel_verification_screen.dart:23-27` asserted the backend could not accept
one. Five sessions of a rule being live and unimplemented.

---

## 1. AD number collision — the research filed on 2026-09-10 is AD-011, not AD-010

**AD-010 was already taken**, by `docs/sessions/2026-09-07-research-ad-010-mobile-api-domain.md`
("how the mobile app's public API hostname should be provided", commit `040d059`).

The collision happened because that decision **was never entered into `PROJECT_PLAN.md`** — it
exists only as a session report. The allocation check that preceded the channel/OTP research
grepped `PROJECT_PLAN.md` alone, which ends at AD-009, so AD-010 looked free. The 2026-09-07 report
itself records doing the opposite and correctly: a repo-wide grep.

Renumbered this session: the report is now
`docs/sessions/2026-09-10-research-ad-011-channel-otp-structure.md` (git-tracked rename), and every
reference in `PROJECT_PLAN.md` and `BACKLOG.md` moved to AD-011. Verified afterwards that `AD-010`
appears in exactly one file, the 2026-09-07 hostname report. AD-011 was unused; AD-012 is the next
free number.

**Residual, not fixed here:** AD-010 is still absent from `PROJECT_PLAN.md`'s decisions log and open
questions. Allocating an AD in a session report without recording it in the plan file is what
produced this collision, and it will produce another.

## 2. What was built

| Layer | Change |
|---|---|
| `entry_api.dart` | `resendChannel` gains `String? correctedEmailAddress`, documented as email-channel-only and as landing even when the resend is refused |
| `dio_entry_api.dart` | key sent **only when non-null**, so an ordinary resend's body is byte-identical to before |
| `entry_repository.dart` | parameter threaded; two write-throughs — new mask into `channelsSummary`, new address into `LocalDraft` — both suppressed on `alreadyVerified`; new `_updateChannelMask` |
| `channel_verification_screen.dart` | edit affordance + in-place editor on the email row; BL-098 gate; save label switches during a countdown; `_CorrectionNotice`; stale doc comment replaced |

**The save label is the design decision worth recording.** The backend applies the correction
inside the transaction that decides the reservation, *before* that outcome is known, so a refused
resend still changes the address and still kills the old code. A single "save and resend" label
would therefore lie during a countdown. It switches:

- resend available → «حفظ البريد الجديد وإعادة إرسال الرمز»
- in a `TOO_SOON` countdown → «حفظ البريد الجديد فقط»

**6 new Arabic strings**, all on one screen, all in the email row. Provisional on the same terms as
the 134 Uqudo override strings — see §7.

## 3. Two questions answered from source rather than assumed

**Does a `TOO_SOON` refusal spend cap budget?** **No.** `OtpVerificationService.reserveResend`
returns on the `TOO_SOON` branch (`:396-399`) *before* `incrementResendCount` (`:404`), which only
the success path reaches. Same for `CAP_EXHAUSTED`, `CHANNEL_LOCKED` and `ALREADY_VERIFIED`. So a
customer correcting a typo repeatedly cannot drive themselves into `CAP_EXHAUSTED` through
refusals — the feared interaction does not exist. What each *successful* resend costs is unchanged:
`RESEND_LIMIT = 3`, delays 30/60/120s indexed by `resendCount`.

**Is «إعادة الإرسال بعد N ث» invariant across Arabic's four agreement bands?** **Yes, and routing
it through `ArabicNounAgreement` would be actively wrong.** «ث» is a unit *symbol*, like "s" or
"min"; numeral–noun agreement (tamyīz) governs spelled-out counted nouns, which is what that helper
varies across n == 1, n == 2, 3–10 and 11+. Abbreviations do not inflect.

Spelling it out would also break a case rule the helper cannot express: both call sites put the
count after «بعد», a preposition governing the genitive, and `ArabicNounAgreement` carries a single
`dual` — the nominative. Asserted at all four boundaries by four widget tests.

**That check surfaced a live defect in the existing spelled-out caller.**
`_goToBlockedTerminal` renders «يرجى المحاولة مرة أخرى بعد ${minutes.phrase(n)}», which at n == 2
produces «بعد دقيقتان» where «بعد دقيقتين» is required. n == 2 is reachable — the backend reports
remaining lock time on every verify attempt while blocked. Filed as **BL-102**, not fixed: it is
PO-gated copy on a terminal screen, and choosing between a genitive variant and a rephrase is a
copy decision.

## 4. Review — two passes, both substantive

`@agent-reviewer` ran against the diff, findings were fixed with regression tests, and it ran again
against the fixed diff as the standing rule requires. The second pass earned its place.

**Pass 1 — fixed:**

| Finding | Disposition |
|---|---|
| `alreadyVerified` wrote a refused address into `LocalDraft`, which Stage 1b re-entry prefills and submits | Fixed — both write-throughs guarded; repository test |
| Editor stayed open with a live-looking save button after a cap/lock arrived; save was silently dropped | Fixed — editor closes on those arms, render condition re-gated, save refuses with a message; widget test |
| `capExhausted` notice said "not sent **yet**", promising a code that can never come | Fixed — new `correctedNoneAvailable` end state; widget test |
| `_beginEmailEdit` was `void async` with no catch | Fixed — `Future<void>` + try/catch, degrades to an empty prefill |
| Save-label comment claimed a total invariant that fails immediately after ISSUED | Fixed — comment narrowed to the countdown case and states the exception |
| BL-098 gate inputs do not survive a resume | **Not fixable client-side** — disclosed in three places, filed as **BL-103**. See §5 |

**Pass 2 — caught a blocker my own gate run had missed.** A doc-comment edit made *after* my last
test run introduced two unescaped apostrophes in a Dart string, so
`entry_repository_test.dart` **did not compile** and its entire suite silently stopped running —
including the two tests proving pass-1 fixes. Fixed, and the gates below were re-run from scratch.
The lesson is procedural: a gate run is only evidence for the tree as it stood when it ran.

Pass 2 also **corrected my own analysis** of the `_updateChannelMask` transaction — see §6 —
and found: a wildcard `_` arm that would silently render "not sent yet" for any future terminal
outcome (fixed, arms named explicitly); the correction notice outliving verification (fixed,
cleared on verify); and `widget.offline` missing from the save action (fixed on the action, not the
render condition — removing the editor on a connectivity blip would discard typed text).

**Accepted, not fixed:** tapping resend with the editor open and typed text discards that text
without a message. Bounded — the cap/lock message appears, and correcting in either state is what
BL-098 forbids, so the text had no use. Recorded rather than given another Arabic string.

## 5. BL-103 — the BL-098 guard does not survive a restart

`resendExhausted` and `locked` are per-screen-session flags; `pendingVerificationChannels()`
returns only channel/state/mask, so `_load` re-initialises both to `false`. After a kill-and-resume
a genuinely capped email row is offered the correction control again — and saving there invalidates
the last usable code, the precise outcome BL-098 exists to prevent.

The same staleness on the resend and verify buttons costs one redundant round trip and re-learns
the truth. **This one cannot be re-learned, because the damage is done by the attempt itself.**
Closing it needs the backend to report per-channel resend state at load; the client must not infer
it. Disclosed in the screen's limitations block and on `customer.md` Stage 2 Corrections rather
than left silent.

## 6. The transaction claim I got wrong, and the revert-restore that surfaced it

`_updateChannelMask` was written by transplanting `_markChannelVerified`'s drift transaction. The
task required confirming the race is genuinely the same rather than merely similar-looking.

**Revert-restore** (`entry_repository_test.dart`, "a correction concurrent with a phone verify"):

- both transactions removed → **fails** (the update is lost)
- only `_updateChannelMask`'s removed → **still passes**

I first concluded the guard was therefore "defensive rather than proven". **That was wrong**, and
pass 2 caught it. The interleaving only this transaction prevents is: its `SELECT` completes and
the `await` yields → `_markChannelVerified`'s transaction opens and runs to completion → this
method writes the snapshot it read before, discarding the verification. The other transaction
cannot prevent that; it serialises what queues *behind* it, not a read already taken. The guard is
**load-bearing**. The test does not reach that ordering because `FakeEntryApi` returns
synchronously, so the verify's transaction happens to open first.

The case that would isolate it — two concurrent `_updateChannelMask` calls — is genuinely
unreachable: one email row, and `resendSubmitting` serialises it against itself. So the ordering is
argued in the doc comment rather than covered by a test, and the comment says so. The first version
of that comment would have licensed a later session to delete the transaction; this is the BL-034 /
BL-033 failure mode the check existed to catch.

## 7. Gates — final run, verbatim

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 8.4s)
```

```
$ fvm dart run tool/check_coverage.dart
02:28 +540: All tests passed!
Line coverage: 84.97% (3896/4585 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

```
$ fvm flutter build apk --release --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
Font asset "CupertinoIcons.ttf" was tree-shaken, reducing it from 257628 to 848 bytes (99.7% reduction).
Font asset "MaterialIcons-Regular.otf" was tree-shaken, reducing it from 1645184 to 4276 bytes (99.7% reduction).
Running Gradle task 'assembleRelease'...                          105.4s
√ Built build\app\outputs\flutter-apk\app-release.apk (108.2MB)
```

540 tests, up from 515. The **release** variant was built deliberately, not `--debug`: S8-09's
reviewer caught a `lintVital` blocker that a debug proof had missed, and this screen ships in the
pilot APK next.

**No revert-restore is owed for the other new tests.** Each asserts a direct presence, absence or
wrong-value claim — the control is absent at `capExhausted`, the body carries no
`correctedEmailAddress` key, the notice does not say a code was sent — none of which can pass
against the unfixed code. The one indirect, ordering-dependent assertion is the concurrency test,
and it got the revert-restore in §6.

## 8. Outstanding — recorded against the gates that already exist

- **The 5-inch keyboard-overlap check joins the single pending device pass, alongside the three
  S8-09 items.** No handset was attached to this session. It is genuinely owed: a new text field
  and a full-width save button below it on the OTP screen is the exact shape that produced BL-087
  and the six-digit unfocus fix.
- **The 6 new Arabic strings join the pending native-Arabic review of the 134 Uqudo override
  strings, provisional on the same terms.**
- Not covered by a test: the `widget.offline` term on the save action. This screen's test harness
  always pumps `offline: false`, as it does for every other offline behaviour here; adding an
  offline path is a change to shared test infrastructure.

## 9. Scope

Built: BL-101, BL-098. Filed: BL-102 (genitive defect), BL-103 (resume staleness). Renumbered:
AD-010 → AD-011.

**AD-011 is untouched and still OPEN.** No screen merge, no route change, no `resumeStage` change.
The in-place email edit is mandatory under every structure the research evaluated — no backend path
corrects an email without either this field or a `contact-channels` re-POST that unverifies every
phone channel — so building it settles nothing. BL-099 and BL-100 remain open as filed.

## 10. Commit proof

```
$ git push
To https://github.com/Osmantou/Fr_user_update
   d8f2d51..90ab089  main -> main
```

```
$ git log --oneline -1
90ab089 S8-10: in-place email correction on the Stage 2 OTP row (BL-101, BL-098)
```

```
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Straight to `main`, per CLAUDE.md — no feature branch. `90ab089` also carries two things that were
untracked before this session began and are unrelated to S8-10: `Design_3/` (91 files) and
`docs/sessions/2026-09-08-journey-feedback-triage.md`. They were swept in by the instructed
`git add -A`; named in the commit message so history is not misleading about what the commit is.

This report itself was written after `90ab089` and is committed separately on top of it — the
commit proof above is the code commit, which is what the gate output belongs to.
