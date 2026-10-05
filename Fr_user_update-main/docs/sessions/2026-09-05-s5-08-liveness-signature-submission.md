# S5-08 — Stages 10, 11, 12: liveness, signature, submission

**Status: gates passed, live liveness device run OWED. NOT ✅.**

Stages 1-12 are now walkable end to end against the stub backend. Mobile only — the diff
contains zero `backend/` files, so no backend gate was run.

## What the brief got wrong, checked against source first

Two claims in the S5-08 brief did not survive reading the code, and both would have produced
working-looking wrong code:

- **"Backend mints sessionId + nonce per attempt."** It does not, for face sessions.
  `FaceSessionIssuance` and `FaceTokenResponse` carry `(accessToken, faceSessionId)` only,
  `V0044` adds only `pending_face_session_id`, and `UqudoJwsParser.boundFaceClaims` binds on
  `data.sessionId` alone. The SDK builder *does* expose `setNonce` and `UqudoScanner.enroll`
  *does* take one, so copying the scan seam would have compiled and then failed against the
  real SDK. AD-002a was already corrected by S5-13 to scope nonce minting to `enroll()`; the
  brief was stale, not the plan.
- **"R-016 if BL-028's mobile half shifts it."** R-016 is RETIRED (`RISKS.md:23`, product-owner
  decision 2026-09-04). BL-028 is the item that moved; R-016 was not touched.

A third correction went into `EXECUTION_PLAN.md` itself: the S5-08 row still instructed this
session to get the S3-13 backend corrected before the screen could work ("R-034 rewrite"). That
landed on 2026-09-04 and R-034 is retired, so the row would have sent this session chasing a
finished fix. Corrected in place, along with its "and echoes a nonce".

## The two STOP conditions, and why this session did not stop

A pre-build review at `03947ec` found S5-08 unbuildable: no machine-readable error codes on
liveness/signature/submission, and no read endpoint for resume into 10-12. Both were closed by
S5-13 (`9c72db1`) before this session began, and both were re-verified against source here
rather than taken on trust — twelve distinguishable codes counted from the three controllers,
and `POST /submission/current` non-mutating in the *service*, not merely in the controller.

Two smaller gaps could not be closed inside a mobile-only slice and were **filed, not worked
around** — the rule that made S5-11 and S5-13 their own slices:

- **BL-059** — `SignatureController.java:70-75`'s comment justifies its error code by a
  distinction the code does not make. Comment-only fix, but touching `backend/` pulls
  `./mvnw verify -Pdb-integration-test` (Docker) into a mobile session for zero behaviour change.
- **BL-060** — the ~5 MB-5.72 MB signature band that answers a bare uncoded 400 instead of
  `SIGNATURE_REJECTED`. See "Signature" below for why this slice makes it unreachable from the
  app rather than papering over it.

## Stage 10 — liveness

Mirrors the S5-07 scan seam: token minted at the point of use and never stored, SDK behind a
fakeable interface, in-memory-only retention, one error-code→screen mapper.

**The timeout is 120 s, and it is reasoned for the face flow rather than inherited.** Stage 8's
180 s is not transferable. The evidence, all from S1-02 on the handset that also produced the
52 s scan figure: face latency 42-49 s (n=2), SDK screen up 0.9 s after tap versus 3.5 s for the
scan, and the SDK's own retry loop bounded at 3. What the face flow has and the scan flow does
not is a **hard 600 s ceiling** — the face session's JWS lives `exp − iat` = 600 s and Uqudo
deletes the session and its reference image at 600 s, so any timeout at or above that is
meaningless.

That ceiling also removes the asymmetry F-1 had to reason around: a JWS arriving after `exp` is
refused as `ARTIFACT_EXPIRED` and **costs no attempt**, so erring long cannot take anything the
600 s clock was not taking anyway. 120 s is ~2.5× the observation and ~1/5 of the ceiling, pinned
by test.

The timeout wraps the `faceSession` await and nothing else — load-bearing for the same reason as
at Stage 8: `Future.timeout` does not cancel what it wraps, so a wrap further out would let a late
JWS still reach `keep()` and still fire an upload against an already-terminated attempt. Asserted
by a test that completes the late future and checks it reached nothing.

**Gap 1 — the block-triggering cancel.** `POST /terminated` answers a bare
`AckResponse(profileId)`; `LivenessService` applies the 24-hour block and returns normally, and
its `activeBlockUntil` is populated only from the pre-check branch. So the call spending the
fifth attempt is indistinguishable from the one spending the second, and the customer would be
shown "try again", tap it, and only then learn they are blocked. Every `/terminated` ack is now
followed by a `POST /submission/current` probe. **Not `/token`** — that mints a real Uqudo Face
Session and writes `pending_face_session_id`, so using it as a "where am I" probe would burn a
metered operation to ask a question. A failing probe is swallowed: the attempt is already
recorded, and turning a successful `/terminated` into a visible error because a follow-up read
failed would misreport what happened.

**Gap 2 — the invented `sdkErrorCode`.** `/terminated` requires a non-blank code with **no
allowlist**, written verbatim into the audit payload beside real Uqudo `SessionStatusCode`s, and
nothing in the system would catch a bad choice. Stage 8 needed no equivalent — its `/cancel`
carries no code at all. The literal is **`APP_NO_SDK_RESPONSE`**; the `APP_` prefix is the
load-bearing part, since no Uqudo code carries it and an operator must not go looking for this one
in Uqudo's documentation.

**BL-028's mobile half is closed.** `returnDataForIncompleteSession()` is set, and the partial JWS
is lifted from the `data` field of the `{code,message,task,data}` envelope in
`PlatformException.code` and forwarded raw — never decoded, never logged. Two things worth
recording: the two terminated paths are **not symmetric** (the timeout path has no
`PlatformException`, so `partialJws` is necessarily null there, asserted by test), and the
`[UNVERIFIED]` question of what the partial artifact carries is untouched — the app takes what
arrives and degrades silently when nothing does, without claiming to know which occurs.

## Stage 11 — signature

**The size question was reframed before building, and the answer was that no backend change is
needed.** A signature is drawn *or* uploaded from the camera roll, which makes it a
customer-supplied photograph — exactly the thing Stage 6's salary certificate already solved. The
salary pattern interposes a downscale between what the customer *picks* and what is *stored*:
10 MB ceiling on the original picked bytes, then ≤1600 px long edge, JPEG q85.

So a customer may pick anything Stage 6 accepts, while what reaches the wire is a 1600 px JPEG on
the order of 100-400 KB — two orders of magnitude under the backend's 5 MB. **That puts BL-060's
uncoded-400 band out of reach from this app entirely**: nobody is shown "bad request" for a
photograph that was merely large, and nobody is asked to sign more simply. The backend defect
remains real for any future non-mobile client, which is why it is filed rather than declared
solved.

The shared helper is the one refactor of existing code here: `certificateResizeTarget` and the
decode→resize→JPEG step moved into `core/images/image_downscale.dart` with their tests, so the two
callers cannot drift. Stage 6's behaviour is unchanged — same default, same quality, same contract.

Limits are mirrored, not tightened, and not presented as settled: customer.md:879's `[POLICY: ...]`
marker is still open and `SignatureService`'s javadoc calls its own values a placeholder.

## Stage 12 — submission, and the confirmation screen

**Submission is the point of no return, so what is "confirmed complete" is read, never computed.**
`Stage12Screen` enters by calling `POST /submission/current` and offers submit only on
`stage == SUBMIT` — which the backend derives from liveness having passed *and* a signature being
stored. Anything earlier routes backward; a profile already submitted goes straight to
confirmation.

**The ordering, and why the local clear is last:**

1. `POST /submission` → reference number and status
2. `POST /submission/current` → the verified channels
3. render the confirmation screen from values now in memory
4. **only then** clear local state

Step 2 needs the `profileId` that step 4 destroys, and after step 4 the reference number is the
customer's only artifact. Clearing any earlier would strand a customer who submitted successfully
with nothing to quote at a branch.

**The binding constraint is honoured structurally, not by discipline.** Channels come from the
pointer on every path. `SubmissionResponse.verifiedChannels` exists on the wire and this app
**never decodes it** — `SubmissionReceipt` has no such field at all, so there is nothing for a
future reader to reach for and no "did the submit response carry channels" branch to reintroduce
BL-058. One extra call on the happy path, by design.

Failure partway:

| Failure point | What the customer gets |
|---|---|
| Submit never acknowledged | Nothing cleared. Resume → pointer says `SUBMITTED` → confirmation with the reference number. |
| Submit 200, pointer read fails | Reference number shown, channels said to be unavailable with a retry, **local state NOT cleared** so a resume can still finish. |
| `LIVENESS_REQUIRED` / `SIGNATURE_REQUIRED` | Routed back to the stage the backend named. |
| `PROFILE_TERMINAL` | Terminal screen. |

Confirmation copy follows customer.md exactly: submitted **for review and approval**, never
"approved", never "done".

## Resume, and the AD-008 line

`beyondStage9` is repurposed from "past everything this app builds" to "somewhere in stages
10-12 — ask the backend". **No legacy branch is needed**, unlike `beyondStage6`: that value's old
and new meanings are different places, whereas this one's coincide (a device holding it under an
S5-07 build had finished Stage 9 and had Stage 10 next, which is what it means now).

One local value for three stages, deliberately: all three stages' real state is backend-owned, and
a per-stage local pointer would be a second source for facts the device does not own.
`FinalStagesGateScreen` is the single place that consumes it.

**The pointer is a PLACEMENT answer, never an AUTHORIZATION one, and this is stated in the code.**
`SIGNATURE`/`SUBMIT` are derived server-side from `facePassed`, which makes "the backend says
liveness passed, so skip Stage 10" a tempting shortcut. It is safe today only because reaching the
gate requires a locally-held `profileId`. When BL-041's device-less supersession lands, a
new-device re-entry will reach a profile whose prior `face_result` it inherited, and that shortcut
would let an impostor skip liveness entirely. Nothing here was built that assumes the pointer
authorizes.

## Shared error contract

One mapper for three stages rather than three copies, because the controllers deliberately share
codes (`PROFILE_TERMINAL` across all three, `STATE_CONFLICT` and `LIVENESS_REQUIRED` across two).
Twelve distinct codes counted from source — S5-13's report says eleven, which is a miscount in the
prose, not a missing code. `core/identityscan/` was **not** retrofitted onto it: that code is
proven and covered, and rewriting it for tidiness would risk working code to remove one enum and
one switch.

The five arms keep S5-07's order, and two orderings are load-bearing: any 5xx is connectivity
before anything status-specific, and a coded 400 outranks the status-only arm, so
`LIVENESS_REJECTED` (an attempt spent) is never collapsed with an uncoded 400 (nothing spent). An
*unknown* coded 400 degrades to the harmless meaning — guessing in the alarming direction would
tell a customer they lost an attempt they may still have.

`JourneyStage.fromWire` makes the opposite call from `JourneyCode.fromWire`: an unrecognised error
code degrades, an unrecognised *stage* throws, because a stage arrives on a successful 200 and
guessing a screen from one is how a customer ends up somewhere they should not be.

## iOS check, stated honestly

`signature` 6.4.0 was added (AD-006) and pulled **five transitive packages AD-006 did not name**:
`flutter_svg` 2.3.0, `path_parsing` 1.1.0, `vector_graphics` 1.2.3, `vector_graphics_codec`
1.1.13, `vector_graphics_compiler` 1.3.0. All six were inspected at addition time: none declares
`flutter: plugin:`, none ships an `ios`/`android`/`darwin`/`macos` directory, and the only
`.podspec`/`.swift`/`.kt`/`.gradle` files anywhere in them live under `example/` demo apps that are
never compiled into a consuming app. So AD-006's finding holds as written — no CocoaPods surface
beyond `image_picker`/`file_picker`, nothing touching R-025/OpenSSL-Universal.

**This is artifact inspection, not a build** — the same standard `uqudosdk_flutter` was held to at
S1-02, and iOS is not compiled on this machine. **It does not mean Stage 11 runs on iOS.** BL-052
stands unchanged: no `NSCameraUsageDescription`, no `NSPhotoLibraryUsageDescription`, no Podfile,
`pod install` has never run here, so stages 8, 10 and 11 cannot run on iOS at all today regardless
of this package. Pinned exactly (`signature: 6.4.0`), matching every other dependency here.

## Tests

456 tests. What is asserted rather than merely executed:

- the 120 s timeout pinned by value, and pinned as *under* the 600 s ceiling
- the silent-SDK path resolving to a terminated attempt with `partialJws` **null**, and a late JWS
  reaching nothing after the timeout fired
- the `/terminated` → pointer probe firing, using the pointer read and **not** `/token`
  (`issueTokenCallCount` asserted zero), and degrading rather than erroring when the probe fails
- `RetainedFaceStore` cleared on each of its terminal outcomes and *left in place* on
  `BackendUnreachableException`, with the retry re-posting without a second face session
- the confirmation screen re-reading `/submission/current` after a fresh submit
  (`currentPointerCallCount` goes 1 → 2), on the lost-ack path, and on resume
- local state cleared *after* the screen is complete, and **not** cleared when the pointer read
  failed
- the absence of any attempt counter, in the repository and on the screen in every state
- BL-028's envelope extraction directly — S5-07 left the scan equivalent untested on the grounds
  that the SDK is not exercisable here, which is true of the SDK but not of the pure string parsing
  that is the whole of what BL-028 asks the app to do

**Three existing tests were updated rather than deleted**, each because the behaviour they pinned
genuinely changed: the `beyondStage9` resume test (now asserts the repurposed meaning and why it
carries no cached channel list), and Stage 9's accept test plus the 7→8→9 navigation walk (both
now assert the gate instead of the retired placeholder).

Coverage caveat (R-009/S1-08) checked explicitly, per file rather than by trusting the percentage:
every new source file is now both imported and EXERCISED by a test. Four were not when the reviewer
looked — the three Dio adapters and `SignatureRepository` — which is exactly the hole R-009
describes: lcov counted them through their providers while no test ever ran a line of them, so the
percentage was never going to reveal it.

## Review findings and dispositions

`@agent-reviewer` against the diff and the task. It confirmed the eleven hard constraints satisfied
— no `backend/` files, no credentials on device, the channel source, the timeout scoping, BL-028,
no `setNonce`/`setMinimumMatchLevel`, in-memory retention, no local counter, the pointer-driven
resume, the AD-008 line, and Stage 6 byte-for-byte unchanged — and found one blocker, four defects,
three gaps and a nit. **All nine are fixed**; none was deferred.

**BLOCKER — the journey was not actually walkable in one session.** `stage9_screen.dart`'s
`_accept()` still sent the customer to `/session-pending`, the placeholder whose only action is
*abandon*. S5-08 had rewired the *relaunch* path into stages 10-12 and left the *forward* path on
the old dead end, so Stage 10 was reachable only by restarting the app — and the Stage 9 test
asserted `/session-pending` — as did `identity_scan_navigation_test.dart`'s "7 → 8 → 9 → accept"
end-to-end walk, the one test whose whole purpose was to catch exactly this. Both now assert the
gate. This is the finding that
mattered: the EXECUTION_PLAN row I had already written claimed "walkable end to end", and that was
false when written. Accept now routes to `/final-stages` — the same gate a relaunch uses, rather
than straight to `/stage-10`, which would be Stage 9 inferring a stage the backend owns.

**DEFECT — three comments described a `faceSessionId` staleness check the code does not make.** The
entry guard compares `profileId` only, and cannot do more: on entry the screen holds no
`faceSessionId`, because the backend mints one per attempt at the moment of tapping. Reworded in
all three places. Same defect class as BL-059, which this session filed against the backend.

**DEFECT — `ResumeVerified` and `SessionPendingScreen` kept docs asserting the opposite of the new
truth** ("Stage 10 onward isn't built in this app version"). Both are now unreachable. Marked
retained-but-unreachable with the real reason rather than deleted: each of the three previous "past
everything" points repurposed exactly that shape.

**DEFECT — `backendSignatureMaxBytes` claimed to be a guard and guarded nothing.** Rather than
weaken the doc, the check is now real in `Stage11Screen._submit`. It should never fire; if a future
path skips the downscale the customer gets actionable copy instead of the backend's uncoded 400.

**DEFECT — `_Stage10View.failed` said "an attempt was spent" and also took outcomes that spent
nothing.** `mapJourneyError` deliberately degrades an unknown coded 400 *away from* "attempt
spent"; the screen then rendered it in a state claiming the opposite, without the "no attempt was
counted" line its sibling states carry. A separate `clientError` state now takes those paths.

**GAP — no test touched the three Dio adapters.** Fixed (15 tests), following
`dio_identity_scan_api_test.dart`. It proves at the wire layer what was only proven at the model
layer: `submit` does not decode `verifiedChannels` even when the body carries it,
`reportTerminated` **omits** the `partialJws` key rather than sending null, and an unknown stage
throws rather than landing a customer on a guessed screen.

**GAP — the test named for the silent-SDK timeout did not exercise it.** It threw immediately, so
the timeout never engaged; it actually proved the unparseable-error path. Renamed, and a real
screen-level case added with an injected short timeout asserting the spinner is gone — the F-1
failure mode itself, not only its repository half.

**GAP — Stage 11's upload route had no test.** The picker needs a platform channel no test here can
drive (which is why Stage 6's picker is untested too), so the *rules* were extracted into
`signatureFromPickedBytes` and tested directly: the 10 MB ceiling on the original bytes, the 1600 px
downscale on the correct edge, the two failure modes kept distinct, and — the point — that a
4000×3000 photo lands orders of magnitude below **both** backend limits, which is what makes BL-060
unreachable from this app rather than merely unlikely.

**Found by writing that test — a pre-existing latent crash on Stage 6.** `downscaleToJpeg`'s
"returns null when undecodable" contract was false: `img.decodeImage` throws a `RangeError` from
inside `PsdDecoder`'s validity probe on four junk bytes. The version that lived in
`SalaryCertificateField` checked only for null, so **a truncated or non-image file picked at Stage
6 would have thrown out of the tap handler as an unhandled async error** rather than showing "this
file could not be read". Neither caller catches it and no test had ever fed either genuinely
malformed bytes. Now caught, contract made true, three tests pinning it.

**NIT — forward navigation dropped the `offline` flag**, so the banner silently switched off
mid-journey. Every forward `context.go` now carries it.

**Process note, because it cost real time.** Running `dart format lib test` to tidy one file
reformatted 75 files this slice never touched and introduced 17 lint infos in code it had no
business changing — mobile has no format gate, unlike backend's Spotless. All 75 reverted with
`git checkout`; the committed diff is the intended set only.

## Gates

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 7.5s)
```

```
$ fvm flutter test
01:17 +456: All tests passed!
```

```
$ fvm dart run tool/check_coverage.dart
Line coverage: 82.98% (3428/4131 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

No backend gate: this slice touches no `backend/` file.

**One thing about this gate worth writing down.** `check_coverage.dart` exits **0** when the tests
underneath it fail — it prints `flutter test failed (exit 1); coverage not checked` and returns
success. An intermediate run in this session did exactly that after the Stage 9 routing fix broke
two tests, and reading the exit code alone would have recorded a passing gate over a red suite. Read
its last line, not its status.

## What is NOT done

- **The live on-device liveness run is OWED** (S1-02 handset, product owner present). The face SDK
  does not run on an x86_64 emulator, so the gates prove Dart logic and wiring and never that a
  real face capture works. This is why S5-08 is not ✅.
- **The two-person face-mismatch test** (R-016's accepted residual) — live hardware, two people,
  future. Whether a terminated session's partial JWS carries usable match data stays `[UNVERIFIED]`.
- **`SDN_ID` stays `[UNVERIFIED]`** — unchanged by this slice.
- R-002 is live and accepted: the AAR ships no Arabic, so the SDK's own liveness UI is English on
  the demo, the same position the scan flow already occupies.

## Plan files

- `EXECUTION_PLAN.md` — S5-08 → 🟨 gates-passed/device-run-pending; stale "R-034 rewrite" and
  "echoes a nonce" clauses corrected.
- `RISKS.md` — R-051 extended to name `/submission/current`'s reference number and channel names on
  the unauthenticated-by-UUID surface (filed, not fixed; auth is AD-002d Phase 2). R-052 narrowed a
  fourth time with the stages 10-12 consumption half. R-016 untouched — it is retired.
- `BACKLOG.md` — BL-028's mobile half closed; BL-059 and BL-060 filed.

## Commit

```
$ git log --oneline -1
8fe4832 docs: append commit proof to the S5-08 session report

$ git status --short
[no output — clean]

$ git status -sb
## main...origin/main

$ git push origin main
   6db5ece..c3ee0c5  main -> main
```

Two commits, straight to `main` (this project's norm): `c3ee0c5` for the slice and `8fe4832` for
this proof block.

**On `this.md`.** It was the untracked scratch output of the pre-build readiness review at
`03947ec` — superseded, since both STOP conditions it raised were closed by S5-13 before this
session began, and its findings are recorded here instead. It was deliberately excluded from the
commit (files were staged by explicit path, never `git add -A`) and it was never committed to any
branch. It is also no longer on disk — the product owner deleted it manually mid-session, which is why the
final status is clean rather than showing it untracked. Recorded rather than glossed over, because
an earlier draft of this section pasted a `git status` showing `?? this.md` that had stopped being
true by the time it was written.
