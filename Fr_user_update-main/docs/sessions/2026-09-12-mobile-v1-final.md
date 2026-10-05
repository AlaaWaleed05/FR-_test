# S8-16 — Mobile V1 final: honest endings, and the Design_3 brand

Three commits. Slices 1-3 (the truth-telling defects) landed first and separately, so a review
finding on the styling can never be confused with one on the journey logic. A third commit
carries four follow-on items the product owner raised after reading the first two.

---

## Done

### What the customer now sees that they did not before

**Any screen that ends the journey — twelve of them.** Stages 3 to 11 all used to say
«تم استكمال تحديث بيانات هذا الحساب من قبل» — *this account's update was already completed*.
Four different statuses reach that screen: submitted, approved, **rejected**, and terminated on a
registry mismatch. The sentence was true for one of them. A customer the bank had **rejected** was
told their update had succeeded. They now reach one screen that says the update for this account
has **ended** and directs them to a branch — true for all four, claiming none.

**The approval and rejection screens (BL-106).** Approve and reject had no in-app consequence at
all: both landed on the confirmation screen, which says «تم إرسال طلبك إلى البنك للمراجعة
والاعتماد» and «لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة» — *sent for review, you will be
notified*. Told to a rejected customer that is false twice: the matter is not pending, and no
notification is coming. The back office rendered «مرفوض» correctly for the same profile, so the
two tiers disagreed about one fact. There are now separate screens.

**The rejection screen names no reason, deliberately.** BL-005's seven Arabic reason labels are
unapproved and with you. A plausible placeholder is the failure mode here, because it looks
finished and nobody comes back to it. The screen states the outcome, shows the reference number to
quote, and sends the customer to a branch.

**The stages 10-12 resume dead end (BL-110).** A customer returning to a journey the backend had
moved on from sat on a retry button that re-issued the same refused call forever, with no route
anywhere. Every arm now has an exit.

**The whole app looks like the bank's.** Design_3's approved splash — navy sky, the dune curve,
the pearl mark riding the crest, the app name in Amiri — and the navy banner with the dune edge on
every screen that has a bar.

### The three defects, and what each cost to fix

| Row | What was wrong | What shipped |
|---|---|---|
| **BL-123** | The false sentence hard-coded at **14 call sites** | One `/ended` route whose screen **owns its copy and takes no message parameter**. Twelve journey sites plus two at Stage 1a now route to it. A permanent guard test fails if the sentence ever returns to `lib/`. |
| **BL-106** | Approve and reject collapsed onto "awaiting review" | Separate `/approved` and `/rejected` screens. **No wire change was needed** — the resume read already answered the three stages distinctly. |
| **BL-110** | No handler for any coded 409 or 404 | Four arms, each with a real destination. |

**The brief's premise for BL-123 was wrong, and finding that out was the valuable part.** The row
says the fourteen sites resolve to one Dart class. They resolve to **three** — the three feature
areas have separate error contracts by design — so there was no single handler to put a screen
behind. What the sites actually share is the *navigation*. Building it as specified would have
produced a fifth recurrence of the same defect.

### The Design_3 adoption, and the six rulings behind it

Recorded as **AD-012** in PROJECT_PLAN.md, because the rulings were given in session and existed
nowhere else. A navy palette and a serif display font with no recorded reason is design decided by
momentum.

| Fork | Ruling | Consequence |
|---|---|---|
| What navy replaces | **Reseed the whole ramp** from `#0b1c47` | Every derived tone moved, so the accessibility contrast measurements were **re-taken, not assumed** — they clear (see gates). |
| How far Amiri goes | **Display and headings only** | Labels, fields and numbers stay on IBM Plex Sans Arabic, which was chosen because every screen sets Arabic beside Latin digits. |
| Square corners, white paper | **Keep the tinted canvas** | The handoff's white ground covers a splash and a header, neither of which has form fields. Going square-on-white would undo the treatment that fixed "no visual hierarchy anywhere". |
| Splash motion | **Entrances, then settle** | The handoff's camel bob, halo pulse and dust all loop forever; three infinite animations on the pilot handset decorate a wait the launch check may end at any moment. |

**Two adaptations the handoff does not cover, both deliberate and both recorded in the code:**
the **sign-out button is dropped** (this app has no customer authentication and no session to end,
so it would do nothing), and the **bank wordmark is dropped from inner screens** in favour of the
screen title — the handoff was drawn against a dashboard whose heading lives in the body, and every
screen here needs to say which of twelve steps the customer is on. The splash carries the wordmark
at full size, which is where the handoff itself puts the identity moment.

### Banner coverage — every screen, stated

**Covered (21).** Titled banner on 17: stages 3-7, account entry, blocked, channel verification,
contact channels, session pending, stages 8-11, confirmation, stage 12, and the reference-item
picker. Mark-only banner on 4: **terminal, session-complete, ended and the approved/rejected
screens** — each is one centred heading, and a titled bar would print it twice.

**Not covered, with the reason:**
- **`launch_screen`** — it *is* the splash. A banner on the brand moment is wrong.
- **`final_stages_gate_screen`** — a transient spinner that always redirects; it renders no chrome
  by design.
- **The Uqudo document-scan and liveness screens — UNREACHABLE.** These are the SDK's own
  full-screen activities. We do not own the view tree, so no banner can be placed there. Our own
  Stage 8 and Stage 10 screens carry it; the SDK camera screens cannot.

### What the reviewer refuted — four findings, all mine, none in the shipped code

The brief said to treat a refutation as the expected outcome. It happened four times, and twice
the reviewer was right about a defect **this session introduced**.

1. **`STATE_CONFLICT` → the launch check was an unattended loop.** The on-disk pointer still read
   `beyondStage9` and the account check answers PROCEED for every non-terminal status, so it landed
   straight back — two network calls a lap, no customer interaction, strictly worse than the retry
   button it replaced. **Moving the pointer is the fix, not the routing**, and the regression test
   asserts the pointer.
2. **The approved/rejected screens dropped a privacy clear.** The destination they replaced already
   performed it, and customer.md makes it a **privacy requirement on a shared device** — local
   state holds the customer's name, address, phone and email. I had reasoned it away in a comment.
3. **A missing asset declaration** would have thrown "Unable to load asset" on the splash at
   runtime while analyze and 604 tests passed — a widget test inspects the tree, never the bundle.
   Now guarded by a test that compares the two.
4. **The banner's abandon-session link was navy-on-navy** — present, labelled, tappable, invisible,
   at 1:1 contrast. It is the only way to abandon a session on Stages 1b and 2.

Each fix carries its own regression test, and the reviewer ran again against the fixed diff. That
second pass caught two comments asserting things the code did not do; both corrected in place
rather than left to freeze.

### Gates — final, verbatim

**After the third commit's work** (the four follow-on items):

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 11.8s)

$ fvm dart run tool/check_coverage.dart
03:55 +612: All tests passed!
Line coverage: 85.53% (4214/4927 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

$ fvm flutter build apk --release --dart-define=REFERENCE_API_BASE_URL=<staging>
Running Gradle task 'assembleRelease'...                          230.9s
√ Built build\app\outputs\flutter-apk\app-release.apk (108.8MB)
```

612 tests, up from 572 at the start of the session. Coverage up from 84.89%.

**The release build earned its place as a gate.** It caught a failure neither analyze nor the test
suite could see: an XML comment in `colors.xml` containing a double hyphen, which Android's
resource merger rejects outright. A green test suite and a broken build, distinguished only by
running the thing that ships.

**iOS support check.** No Flutter package was added. Amiri and the three brand PNGs are pubspec
asset and font declarations, which are platform-neutral by construction — no plugin, no native
code, no podspec. The only platform-specific work is Android resource files. Nothing here can be
Android-only.

---

## Needs your attention

### 1. A PO-approved splash string was dropped, and I want you to confirm it

Adopting Splash C removes «خطوات بسيطة لتحديث بياناتك» — *simple steps to update your data* — which
you selected on 2026-09-07 to set expectations for a mixed-literacy audience facing a twelve-stage
flow. Splash C has no slot for it: the composition is name, hairline, tagline, wordmark, progress.
The slogan «لؤلؤة المصارف» survives.

**Options:** accept the drop as part of the design; or restore it, which means inventing a slot the
handoff does not have. **Recommendation: accept it.** The line was doing work the old sparse
lockup needed and the new composition does not. **Cost if you disagree:** a first-time customer
loses the one cue that the journey is short.

### 2. ~~The launcher icon is navy, but still the old emblem~~ — RULED AND DONE

You reversed this the same day: the launcher carries the **pearl**. Built in the third commit,
across all five densities plus the adaptive foreground layer, and — because `values-v31` already
points `windowSplashScreenAnimatedIcon` at that layer — the Android 12+ native splash picks it up
too. Launcher, native splash and Flutter splash now show one mark from one source. Details under
*The four follow-on items* below.

### 3. BL-005's rejection copy is now the only thing standing between you and a complete screen

The rejection screen is built and routed. To name a reason it needs three things from you: the
approved Arabic label for REJ-01..07; a reason field on the resume read (**none exists today** —
that is a backend change); and a decision on whether the customer sees the operator's internal
label or the customer-facing SMS text. They are different strings written for different readers.

### 4. Observability, which is not a mobile item but is a V1 one

There are **no CloudWatch alarms of any kind** on the deployed stack, and ALB access logs are off.
Nothing would report a 5xx rate, an unhealthy target, a stopped task, or a log-delivery failure.
Filed as **BL-127**. This matters more than any single defect: a deployment expected to run
continuously needs to be able to tell you when it is not.

---

---

## The four follow-on items

### 1 — The launcher icon carries the pearl (AD-012 fork 5)

Ten files regenerated through the script, never hand-edited: `ic_launcher.png` at 48/72/96/144/192
and `ic_launcher_foreground.png` at 108/162/216/324/432. Comments in
`mipmap-anydpi-v26/ic_launcher.xml` and `values-v31/styles.xml` updated to say where the mark now
comes from.

**`generate_brand_assets.py` and `assets/brand/sfb-emblem.png` were DELETED.** Once the launcher
moved to the pearl, that script's only remaining output was a PNG nothing reads — and two
generators each claiming to produce the app's identity from a different source is how the launcher
came to disagree with the splash in the first place. One `git revert` undoes it if you disagree.

**Verified from the release build, and the first two methods were wrong** — worth recording
because both are the same false-negative shape:
- Filtering APK entries by NAME found nothing: AAPT2 renames resources in a release build
  (`res/9w.png`).
- Matching by content HASH also failed: AAPT2 recompresses PNGs, so bytes differ from source.
- What worked: decoding every square PNG in the APK. All five legacy densities carry the navy
  ground and the pearl; all five adaptive layers carry a transparent ground and the pearl. The two
  white-centred hits were flat single-colour Material glyphs at 5% and 17% coverage; the emblem
  filled ~72%.

The decisive proof is not the pixel scan though: **the emblem and its generator no longer exist in
the repository**, so no build can emit one.

### 2 — The splash is held for 4000 ms (AD-012 fork 6)

The mechanism is the handoff's own — Splash C specifies "hold the splash until app bootstrap
resolves, minimum ~1.6 s" — at your value. It **inverts D7.3**, recorded as a reversal.

**Your first question, a terminal answer.** `LaunchTerminal`, `LaunchEnded` and `LaunchBlocked`
**bypass the hold entirely.** Making someone wait four seconds to be told their account is inactive
spends their time on a brand moment in the one situation they will not enjoy it; and
`LaunchBlocked`'s screen renders a live countdown, so four held seconds is four seconds that
countdown is already wrong by when it appears. A launch-check **error** renders in place on the
splash — there is no navigation to delay, so it appears the instant it is known.

**Your second, backgrounding and rotation.** One navigation point, a `_navigated` latch, `mounted`
checked after the timer, and `dispose` cancelling it. Six tests cover the hold.

**Nothing was added to fill the time**, per fork 4. The composition settles at 1.8 s and holds; the
progress hairline keeps running, which is the handoff's instruction and stays truthful.

### 3 — [uqudo-device-test.md](../journeys/uqudo-device-test.md)

Eight sections, every step tagged **[OBSERVED]** / **[ARTIFACT]** / **[UNVERIFIED]**. Covers the
happy paths, the 134 Arabic override keys, the camera denial, cancelling mid-scan, network loss
between scan and upload, a table of what each of the seven Stage 8 end screens means for your
budget, and what the SDK does that our screens cannot control.

### 4 — BL-126 analysed, not built

**Not reachable today**: nothing sets `abandoned` — no scheduler, no SQL function, no service
call. The 30-day sweep customer.md describes does not exist. It is a latent defect, armed the day
that job is written. The original filing was also too broad: `reactivateFromAbandoned` is called
from Stage 1b **and** from any stage 3-7 submission, so the only door left is a device resuming
directly at Stage 8 or later. Cost: one of 20 lifetime mints, then one of 5 attempts per failure —
and the **fifth failure raises a CHECK violation and returns a 500** rather than a block screen.
Still yours to rule on whether Stage 8 should reactivate or refuse.

### What the reviewer refuted this time — and one it under-diagnosed

Thirteen findings across two passes. The two that mattered:

**The 4-second hold was broken, and my first fix did not fix it.** The reviewer found
`late final _shownAt = DateTime.now()` initialising lazily on first read — inside the decision
handler — so elapsed was always ~0 and the hold became 4 s ADDED to the launch check. I moved the
assignment to `initState`, and **the defect survived**. The real cause was underneath:
`DateTime.now()` is the wall clock, which `tester.pump(duration)` does not advance, while `Timer`
runs on the scheduler's fake clock. A throwaway diagnostic measured navigation landing at exactly
7000 ms with a 3000 ms check. Rewritten to remove clock arithmetic entirely — one timer, one latch
— and re-measured at exactly 4000 ms. A permanent regression test replaces the diagnostic.

**Re-entering the splash never navigated.** `launchDecisionProvider` is a plain non-autoDispose
`FutureProvider` and `ref.listen` fires only on change, so **seven** screens routing back to `/`
got a cached answer and a splash that span forever — including `BlockedScreen`'s phone-lock
recheck, whose own comment claimed "`/` re-asks the backend". Fixed at the choke point rather than
at seven callers. Filed as **BL-128** because a defect this size should not exist only as a code
comment.

Two of the second pass's findings were tests that did not test what they claimed: the
timer-cancellation test passed with the cancellation removed (it pumped past the hold, so nothing
was pending at teardown), and the rebuild test cannot exercise its guard because the provider emits
once. The first is fixed and **proven by reverting the fix** — flutter_test reports "A Timer is
still pending even after the widget tree was disposed". The second is now labelled honestly rather
than left claiming coverage it does not have.


## Carried forward

### The four verifications — reported, not built

| | Ruling | Verdict |
|---|---|---|
| **BL-086** WhatsApp | Row stays as it is | **HOLDS.** A declined channel is filtered out before the screen, so the customer sees no WhatsApp verification — not a code that never comes. **But it holds by one line**: `entry_repository.dart:232`. The verification screen itself is not declined-aware; every check is `== verified`. Filed as **BL-125**. |
| **BL-095** SMS | Accept for V1 | **HOLDS, premise corrected.** The row says dispatch is async through the outbox. It is not — the OTP is sent **inline** and the outcome is **captured and discarded**, not pending. The fix is one field on `ChannelInfo`, not a two-tier session. Row now records the reason as **scheduling, not cost**, per your instruction. |
| **BL-087** Stage 12 summary | Cut from V1 | **HOLDS.** `DataEntryApi` is entirely write-only; nothing depends on a summary. Recorded as a ruling: the customer does not get to re-read their entered values before the irreversible submit. |
| **BL-102** «بعد» genitive | Fix if one word | **STILL LIVE — left alone.** Not a one-word fix. **The row's file list was stale**: S8-14 moved two of the three sites into `core/widgets/blocked_view.dart`. Corrected. |

### Rows filed and corrected

- **BL-125** — the BL-086 fragility, as you asked.
- **BL-126** — an `abandoned` profile now reaches Stage 8 by design, and the backend has no
  `abandoned` guard on the scan path, so a scan attempt can be spent against it. Backend change.
- **BL-127** — the observability gap above.
- **BL-119's stated cost corrected.** Your ruling said an approved customer could never be told
  they were approved. That is true only on **relaunch** — the resume read always distinguished the
  three, and BL-106 now uses it. The ruling stands; the cost was overstated.
- **customer.md l.1145 struck** as a recorded reversal. It promised that reopening the app returns
  the current status, which BL-119's closure means will never be built.

### Pending PO gates this adds to

**New Arabic strings, verbatim, for the native-Arabic review** — all provisional on the same terms:

| String | Screen |
|---|---|
| انتهى تحديث بيانات هذا الحساب | Ended |
| لم يعد بالإمكان متابعة التحديث من التطبيق. لمعرفة حالة طلبك أو لأي استفسار، يرجى زيارة أقرب فرع. | Ended |
| تم اعتماد تحديث بياناتك | Approved |
| تم تحديث بياناتك لدى البنك. لا حاجة لأي إجراء آخر. | Approved |
| لم يتم اعتماد تحديث بياناتك | Rejected |
| لمعرفة السبب أو لتقديم طلب جديد، يرجى زيارة أقرب فرع ومعك الرقم المرجعي أدناه ووثيقة الهوية. | Rejected |
| بيــانــاتــي | Splash — the tatweel elongation is the handoff's own string |

**Screens changed, for the single pending device pass.** Every screen in the app except
`final_stages_gate_screen`: the splash and all 21 that carry a banner, plus the three new screens
(ended, approved, rejected). The two worth the most attention are **Stage 1b and Stage 2**, whose
abandon action was the invisible one, and whose titles are the tightest fit beside the mark.

### Not done

The **Uqudo scan path is not proven on this build** — which is precisely why
`docs/journeys/uqudo-device-test.md` exists. The device investigation itself is tracked outside
this report at your instruction; the observability half of what it found is **BL-127**.

`docs/incidents/` is now in `.gitignore`, so "deliberately untracked" is enforced by the repository
rather than by someone remembering it at `git add` time.
