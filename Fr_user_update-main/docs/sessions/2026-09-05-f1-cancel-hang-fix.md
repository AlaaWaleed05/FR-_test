# 2026-09-05 — F-1: Stage 8 cancel-hang fix

Mobile only. No backend changes. Addresses the fix-before-demo finding raised by the S5-07
device-run pre-flight (`docs/sessions/2026-09-05-s5-07-device-run.md`). S5-07 itself stays
device-run-pending — this hardens one path the run will exercise and does not complete the
run, and the run still owes the observation that would confirm F-1 actually occurs.

## The defect, verified against source before designing

**Stated precisely, because half of F-1 is proven and half is not.** Proven from source: a
`RESULT_CANCELED` carrying a null Intent would leave Stage 8 spinning forever. Not proven:
that the Uqudo scan activity ever returns that shape. The distinction is kept throughout
this report, and the fix is correct either way — see "What is still unobserved" below.

Three facts had to hold for the hang to be possible, and all three were confirmed in this
repo rather than taken from the pre-flight report:

1. **No timeout anywhere on the enrol path.** `stage8_screen.dart:134` →
   `identity_scan_repository.dart:89-94` → `plugin_uqudo_scanner.dart:72` → the pinned
   package's bare `invokeMethod`. `grep -rn "PopScope\|WillPopScope\|\.timeout(\|Timer("
   mobile/lib/` returned nothing; the only timeouts in the tier were Dio's 15s/30s
   (`dio_provider.dart:14-15`), which cover `issueToken`/`submitScan` and not `enroll`.
2. **Every Stage 8 arm keys off a throw.** `_startScan` sets `_Stage8View.working` and
   every exit from it sits inside an `on X catch` / `catch (_)`.
3. **`working` renders a bare spinner** (`stage8_screen.dart:318-320`) — no button, no
   gesture, no back affordance. `/stage-8` is a flat top-level `GoRoute` reached by
   `context.go`, so `automaticallyImplyLeading` finds nothing to pop.

The native gap is real and sits at `UqudoIdPlugin.kt:862-880`: `RESULT_CANCELED` is handled
only `if (data != null)`, with no `else`, so a null-result Intent would complete
`pendingResult` neither way.

### What is still unobserved

The hang has **never been seen on hardware.** A null Intent is the ordinary Android result
of a back-press out of an activity that sets no result before finishing, but the Uqudo scan
activity lives in the native AAR — a Maven dependency, not in the pub package — so whether
it sets `key_session_status` on a back-press cannot be read from source at all.
`docs/sessions/2026-09-05-s5-07-device-run.md:177-180` says exactly this
("unproven-not-disproven"), and `:235` goes further: "F-1 in particular should be resolved
by observation before anyone writes a fix for it."

This fix was written before that observation, deliberately, and the reasoning is worth
stating rather than glossing: the timeout is the correct defence whether or not this
specific plugin gap is ever reached, because it converts *any* silent native side into a
handled outcome. What it does not do is settle the question — **the device run still owes
the cancel observation**, and the report's item 2 keeps its purpose. Caught by
`@agent-reviewer` against this diff, which found the first draft of this document and both
plan files writing the hang in the past indicative as though it had been witnessed.

### The first thing F-1 asked to settle: is that Kotlin ours to edit?

**No.** `UqudoIdPlugin.kt` does not exist anywhere in this repository. `find` over the repo
returns nothing; the file lives only in the pub cache at
`.../Pub/Cache/hosted/pub.dev/uqudosdk_flutter-3.10.0/android/src/main/kotlin/io/flutter/plugin/uqudo/uqudosdk_flutter/UqudoIdPlugin.kt`,
inside the package pinned at `mobile/pubspec.yaml:68`. Editing it is forbidden by CLAUDE.md
and would vanish on the next `pub get`. The fix is Dart-side and needs no fork, patch or
vendoring — that decision was never reached.

### Two corrections to F-1's own wording

Neither changes the fix; both change the record.

- **"No exit but killing the app" overstates it.** On a single-page go_router stack an
  Android system back-press *exits the app*, and a relaunch resumes at Stage 8 with **no
  attempt spent**, because `/cancel` never fired. The hang would be recoverable by restart,
  not permanent loss. Still fix-before-demo: an indefinite spinner on a mandated-update
  journey is not something to demonstrate.
- **`REQUEST_TIMEOUT` does exist in the plugin's enumeration.** The pre-flight concluded it
  did not, but drew that from the **iOS** Objective-C switch alone;
  `docs/components/uqudo-sdk.md:420` lists it as a mobile `SessionStatusCode` new in 3.9.0,
  and the Android side reads the name off an enum inside the AAR that the pub package does
  not expose. The doc comment at `uqudo_scanner.dart:13-15` was not wrong. No behavioural
  impact either way.

## The two decisions, and their reasoning

**Decision 1 — a timed-out scan spends an attempt.** Not an open contract question.
`docs/journeys/customer.md:656-659` keys the charge on *a session having been launched*,
not on an SDK status code: "A launched SDK session consumes a real Uqudo operation whether
or not a document was captured, so a cancel is not free." A null-Intent `RESULT_CANCELED`
means `startActivityForResult` ran and the activity finished, so the operation was
consumed. The wire agrees and leaves no alternative: `CancelScanRequest` carries only
`profileId` + `documentType`, so "system back-press" and "USER_CANCEL" are not
distinguishable to the backend even if we wanted them to be. `IdentityScanService.issueToken`
reads the budget but never increments it; only `recordFailedAttempt`, reached from
`cancelScan`, spends one. So the attempt is spent iff the client calls `/cancel` — and it
does.

**Decision 2 — 180 seconds.** The only latency measurement in existence is 52s
tap-to-completion on the exact target handset, the low-end 2GB Huawei of
`docs/sessions/2026-09-03-s1-02-device-spike.md:3`, measured at `:46`, and that 52s already
included one SDK-prompted blur retry. It is **n=1**: one document, one operator who knew the flow, and
the SDK's internal retry loop has no known ceiling. 180s is ~3.5× that single observation —
a deliberately generous margin, not a precise figure, and the code comment says so in those
words.

The margin errs long because the costs are not symmetric. A false trip is expensive and
invisible: it spends a real customer's attempt via `/cancel` *and* discards the capture
they are still producing, from behind the SDK's own full-screen UI where they see nothing
wrong. A long timeout only delays surfacing a rare hang. Those are not comparable losses,
so the duration is chosen against the worse one.

**The accepted consequence, stated plainly.** In the canonical F-1 scenario — a back-press
at t≈5s — the customer is returned to the Flutter activity and sees the same dead spinner
for the balance of the 180s, roughly 175 seconds, before the timeout resolves it. That is
bounded, so F-1's criterion is met, and it follows directly from the settled figure rather
than from the implementation. F-1's "a timeout **or equivalent**" would also have permitted
an additional Android lifecycle-resume signal to collapse that wait, which is the obvious
future improvement if the device run shows the cancel path is real and common. Raised by
`@agent-reviewer`; recorded rather than acted on, because changing it means revisiting the
180s decision.

## What changed

| File | Change |
|---|---|
| `mobile/lib/core/identityscan/uqudo_scanner.dart` | New `UqudoScanNoResponse` exception + `const uqudoScanNoAnswerTimeout = Duration(minutes: 3)`, both carrying their reasoning and the accepted residual. |
| `mobile/lib/core/identityscan/identity_scan_repository.dart` | Constructor takes `Duration scanTimeout = uqudoScanNoAnswerTimeout`; `runScan` wraps **only** the `_scanner.enroll` await in `.timeout(...)`. |
| `mobile/lib/features/identityscan/stage8_screen.dart` | Explicit `on UqudoScanNoResponse` arm before the `catch (_)` fallthrough, routing to the existing `_spendAttemptForAbandonedSession()`. |
| `mobile/test/core/identityscan/fake_uqudo_scanner.dart` | New `Completer<String>? pendingEnroll` hook; the four existing hooks unchanged. |
| `mobile/test/core/identityscan/identity_scan_repository_test.dart` | 3 new tests. |
| `mobile/test/features/identityscan/stage8_screen_test.dart` | 1 new test. |
| `BACKLOG.md`, `EXECUTION_PLAN.md` | BL-053 filed; S5-07 annotated, still 🔵. |

### Why the timeout sits on the enrol await and nowhere else

This is the design decision worth recording, because the obvious placements are both
wrong and one of them is worse than the bug.

`Future.timeout` **does not cancel the future it wraps** — it only stops listening. Placed
around `repository.runScan(...)` at the screen's call site, a late-arriving JWS would still
run `keep()` into the process-scoped `RetainedScanStore` and still fire `_upload` in the
background, while the screen had already spent the attempt via `/cancel` and moved on.
That leaves an orphaned upload racing a cancelled attempt, plus a retained capture that
Stage 8's entry guard (`stage8_screen.dart:107-113`) would *pass* on the next visit — same
`profileId`, same `documentType` — so the screen would offer to re-send a capture the
backend had been told was abandoned. Strictly worse than an honest spinner.

Placed in `PluginUqudoScanner` instead, it would be correct but unprovable:
`uqudoScannerProvider` is overridden with a fake in every test, so no automated run could
ever exercise it, and per CLAUDE.md's own coverage caveat an untested path leaves the
percentage unmoved. It would ship unproven.

Scoped to the enrol await inside `runScan`, the throw happens before anything is retained,
a late JWS is simply dropped, Dio stays the authority for the two HTTP calls, and the
five-arm error mapper is untouched by construction.

### Why the catch arm is explicit and not the existing fallthrough

A bare timeout reaching `catch (_)` would set `_Stage8View.connectivity`, whose body reads
**"لم يتم احتساب أي محاولة"** — *no attempt was counted* (`stage8_screen.dart:394-395`) —
immediately after this code spent one. That is the third time this codebase has needed the
same guard: the S5-07 review already forced the identical false-budget statement out of
`_spendAttemptForAbandonedSession`'s `BackendUnreachableException` branch. The new arm is
placed before the fallthrough so the timeout can never reach it.

`UqudoScanNoResponse` is deliberately **not** a subtype of `UqudoScanFailure`. That type
means the SDK *told* us the session ended without a JWS and carries the status code it
reported; this one means we never heard anything at all. Making it a subtype would have
routed correctly today by accident, and hidden the distinction at the catch site.

No other arm's behaviour changed. `_spendAttemptForAbandonedSession` already handles a
`/cancel` that itself trips the 24-hour block, a `STATE_CONFLICT`, and an unreachable
backend, so all three of those work identically on the timeout path. `_retryUpload` never
calls the scanner and needs no arm; `_resync` and `_applyConflict` are untouched.

## Proof

**Form: direct assertion. No revert-restore.** CLAUDE.md permits skipping the revert when
the assertion is a direct wrong-value one that cannot pass against the bug. These are:
the widget test asserts `api.cancelScanCallCount == 1`, the presence of the rejected
screen's title, and the *absence* of a `CircularProgressIndicator`. Against the pre-fix
code the screen is still `_Stage8View.working` rendering that spinner and `/cancel` has
never been called, so all three assertions fail on the unfixed version by construction.
There is no indirect proxy and no identical-happy-path ordering here, so a revert would
demonstrate nothing the assertions do not already state.

Four tests, and what each is actually for:

- **`an SDK that never answers times out instead of hanging forever`** (repository) — the
  base case, with a 50ms injected timeout so it does not wait three real minutes.
- **`a JWS arriving AFTER the timeout is dropped, never retained and never uploaded`**
  (repository) — the one that guards the scoping decision above. It completes the pending
  `Completer` *after* the timeout has fired, so the late answer genuinely arrives, and
  asserts it reaches nothing: `retainedScan` still null, `submitScanCallCount` still 0.
- **`the shipped no-answer timeout is the 180s F-1 settled on`** (repository) — pins the
  reasoned figure so changing it is a deliberate act rather than a drift.
- **`an SDK that never answers spends the attempt instead of spinning forever`** (widget) —
  drives the **real shipped 180s constant** under the widget tester's fake clock, so what
  is proven is the duration that ships, not a test-only stand-in. It asserts the hang shape
  first (SDK launched, spinner up, nothing filed), then the resolution.

Two implementation notes worth keeping, both discovered while writing the tests:

- The widget test cannot use the file's `startScan` helper. That helper calls
  `pumpAndSettle`, and while the spinner is up the progress indicator schedules frames
  forever, so settling would time out rather than reach anything. It taps and `pump()`s
  explicitly instead.
- `fake_async` cannot be imported to fake the clock in a plain unit test: it is only a
  transitive `pubspec.lock` entry, not a declared dev dependency, so importing it trips
  `depend_on_referenced_packages`. Hence the injected duration at the repository level and
  the tester's own fake clock at the widget level.

## The accepted residual (BL-053)

The pinned plugin never nulls `pendingResult` after completing it (`UqudoIdPlugin.kt:69`,
assigned :94-130, completed at :640/:866, never reset). A very late `onActivityResult`
arriving after a timeout has fired could therefore deliver the abandoned session's result
against a retry's handle — wrong-result cross-talk between attempts.

It is narrow in practice (Android delivers `onActivityResult` before the next
`startActivityForResult`) and the Dart timeout bounds the user-visible symptom regardless.
Closing it properly means forking, patching or vendoring the plugin, which is a separate
decision and explicitly not one to take in passing. Recorded so that a stray wrong result
on hardware has a written cause to check against instead of being re-diagnosed from
scratch.

## Review

`@agent-reviewer` ran once against the diff and F-1. It confirmed the fix closes F-1 —
tracing silent native → `.timeout` → `UqudoScanNoResponse` → the new arm →
`_spendAttemptForAbandonedSession` → `/cancel` → `rejected`, and confirming no residual
unbounded await (the three HTTP calls are all bounded by Dio's 15s/30s and land on
`BackendUnreachableException`, which that method already handles). It independently
re-derived every `UqudoIdPlugin.kt` line citation against the pub cache and found them
correct. Five findings, all dispositioned:

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | SHOULD FIX | **An unobserved hypothesis was written as an observed defect.** The doc comment, BL-053 and the EXECUTION_PLAN row all said the screen "spun forever" in the past indicative. The plugin's missing `else` is proven from source; that the scan activity ever returns a null-Intent `RESULT_CANCELED` is not, and this repo's own pre-flight says so at `:177`, `:180` and `:235`. A future reader would have had no way to tell the hang was never seen, and the device run's item 2 would have lost its stated purpose. | **Fixed.** All four places — `uqudo_scanner.dart`, `BACKLOG.md`, `EXECUTION_PLAN.md` and this report — rewritten to keep the proven and unproven halves apart, and to state that the run still owes the observation. No code change; the timeout is correct defence either way. |
| 2 | NOTE | **Miscited line for the load-bearing half of the 180s reasoning.** `s1-02-device-spike.md:38` is measurement #1 and supports only the bare 52s; the blur-retry detail is at `:46` (measurement #9) and the handset at `:3`. | **Fixed** in both the doc comment and this report. |
| 3 | NOTE | **The scope claim ran ahead of the code.** The comment said the bounded wait "covers ANY future silent-native case", but the `.timeout` is applied by `IdentityScanRepository.runScan`, the single caller of `enroll`. It covers any silent cause *inside enroll*; a future native call is not covered. | **Fixed** — the claim is narrowed to the enrol seam, and names S5-08's face session as needing its own. |
| 4 | NOTE | **The canonical scenario still shows a dead spinner for ~175s.** A back-press at t≈5s returns to a spinner that does not resolve until the timer fires. Bounded, so the criterion is met, but it is the same symptom F-1 names. | **Recorded, not acted on** — see "The accepted consequence" above. It follows from the settled 180s figure, so changing it means reopening that decision. |
| 5 | NOTE | The EXECUTION_PLAN row cited this report before it existed on disk. | **Resolved** — the file was written before commit, per the session-end rule. |

The reviewer explicitly checked and did not object to either user-settled decision:
spend-an-attempt "is the only thing `CancelScanRequest` can express", and 180s "is
defensible given the asymmetry as stated". It also confirmed the widget test fails against
pre-fix code by construction, that the late-JWS test genuinely discriminates (a `.timeout`
around the whole of `runScan` would flip both of its assertions), and that no existing arm,
generated file, secret or open architecture decision was touched.

## Gates

Run from `mobile/`, with `fvm` resolved at
`C:\Users\DELL\AppData\Local\Pub\Cache\bin\fvm.bat` per CLAUDE.md.

```
########## fvm flutter analyze ##########
Analyzing mobile...
No issues found! (ran in 7.8s)

########## fvm flutter test ##########
01:01 +339: All tests passed!

########## fvm dart run tool/check_coverage.dart ##########
01:28 +339: All tests passed!
Line coverage: 84.53% (2820/3336 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

339 tests, up from S5-07's 335 — the 4 added here. Coverage is unchanged at 84.53%: the new
code is small and fully exercised, so it neither lifts nor lowers the ratio meaningfully.

An earlier identical run of all three gates preceded the review; the output above is the
re-run after the review fixes, which were doc-comment and Markdown edits only.

## Not done / owed

- **The live on-device run (S5-07) is still owed.** This fix is one of the things that run
  will exercise; it does not substitute for it. No device was available this session.
- **F-2 (device config setup) was deliberately not folded in** — it belongs to the run
  session, not to a code fix.
- The cancel path still has never executed on hardware. What is proven here is the Dart
  wiring around a faked seam, which is exactly the limitation `FakeUqudoScanner`'s own doc
  comment states.

## Commit proof

```
=== git log --oneline -1 ===
33f8ae8 F-1/BL-053: bound the Stage 8 scan wait so a silent SDK cannot hang the screen

=== git push ===
To https://github.com/Osmantou/Fr_user_update
   486402a..33f8ae8  main -> main

=== git status ===
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Straight to `main`, per CLAUDE.md — no feature branch, and `@{u}` is `origin/main`.
