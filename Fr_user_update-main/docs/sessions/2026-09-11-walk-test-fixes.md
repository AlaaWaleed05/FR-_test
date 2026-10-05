# S8-17 — Walk-test fixes: nine comments, one session, four commits

**Date:** 2026-09-11 (walk comments given 2026-09-10)
**Tier:** mobile only. No backend, no back-office, no generated file, no new package — so no iOS
support check is owed.
**Branch:** `main` throughout, per CLAUDE.md. Four commits, pushed individually.

## Scope and shape

Nine product-owner comments from a device walk. Comments **1 and 5 are the same report** (the
progress bar), so nine comments are eight pieces of work.

The plan was four separate *sessions*; the product owner pushed back and was right. Three of the
four pieces sweep the same screen list — `stage3`–`stage8`, `account_entry`, `contact_channels`,
`channel_verification` — so separate sessions would have opened the same files three times and run
the same gates three times. What splitting actually buys is a focused `@agent-reviewer` diff and a
bisect point, and **commits give both**. One session, four commits, each gated and reviewed
independently.

Mid-session the product owner also stopped work that had begun on commit 2 before commit 1 was
pushed. That was the correct call: the two share `stage7_screen.dart` and `stage8_screen.dart`, so
once both were in the tree they could not be committed separately without splitting hunks. The
commit-2 work was parked, both files reverted to their commit-1 state, and commit 1 closed first.

## The four commits

| Commit | Walk comments | SHA |
|---|---|---|
| 1 — unreachable Next button, Sudan default, splash direction | 9, 3, 10a | `b5164ae` |
| 2 — document choice advances on tap, and is named correctly | 4, 8 | `c6c8262` |
| 3 — the keyboard's next key, a controlled phone field | 2, 6 | `421005f` |
| 4 — the journey progress bar | 1, 5 | `c8cc2a2` |

## Product-owner rulings taken in session

1. **Item 4** — tap advances, with the change-document path kept as the safety net.
2. **Items 1/5** — the progress strip covers the whole journey.
3. **Item 10** — direction fix only; the build-up half deferred → [[BL-129]].
4. **Thirteen segments, not the design's twelve.** Recorded as **AD-012 fork 7**.
5. **The strip sits below the banner, not inside it.** Recorded as **AD-012 fork 8**.

Rulings 4 and 5 went into `PROJECT_PLAN.md` rather than staying in a Dart doc comment, which is
the stated reason AD-012 exists at all.

## Design decisions with reasoning

**Walk comment 2's cause was not what it looked like.** Every field already declared
`TextInputAction.next`, so "no handler" was the obvious diagnosis and the wrong one. Flutter's
default action for `next` **is** `FocusScope.nextFocus()` — it was moving focus to the next
*focusable* widget, which on these screens is routinely a `PickerField`, a dropdown or a checkbox.
Focus went to a button, the keyboard closed, the screen looked inert. The fix is a screen-declared
`textFieldOrder` on the mixin that already owned the focus nodes, with `fieldAction` and
`fieldSubmit` derived from that one list so the key's label and its behaviour cannot disagree.

**The «التالي» button hopping between fields was left alone.** That is `FieldErrorState`
focusing the field that failed validation — correct behaviour, and only noticeable because the
keyboard's own key was dead.

**Ten digits is the server's rule, not a guess** — `PhoneNumberNormalizer` takes a trunk `0` plus
`SUDAN_LOCAL_DIGITS_AFTER_ZERO` (9). Where client and server *can* drift is recorded in the code:
the server also accepts a `00`-prefixed international form that this cap refuses.

**Item 4 reversed a documented decision, and the reasoning was answered rather than overruled.**
`stage7_screen.dart` argued the card must only select, because the per-type scan budget (BL-039)
makes a wrong choice expensive. Stage 8 already offers «تغيير الوثيقة» on its preparation and
rejection views, and the budget is spent by *launching the scanner*, not by arriving at Stage 8 —
so a mis-tap costs a screen transition and no attempt. That button was verified at source, not
assumed; the whole safety argument rests on it.

## Gate output — final commit, verbatim

```
Analyzing mobile...
No issues found! (ran in 29.2s)
===== TEST =====
02:30 +646: All tests passed!
===== COVERAGE =====
03:30 +646: All tests passed!
Line coverage: 85.81% (4348/5067 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

620 tests at session start → 646. Coverage 85.50% → 85.81%. Every commit ran all three gates
before it was pushed; the per-commit numbers are in the commit messages.

## Revert-restore proofs

Each is an indirect or geometric assertion, so a revert is owed under CLAUDE.md's rule — none is a
direct wrong-value assertion that could not pass against the bug.

| Guard | Fix reverted | Result |
|---|---|---|
| `StageActionBar` inset | `SafeArea` removed | `Expected: >= 48.0 / Actual: 16.0` |
| Stage 9 body inset | `SafeArea` removed | `Expected: >= 48.0 / Actual: 16.0` |
| Stage 7 double-submit | both guards removed | `Expected: <1> / Actual: <3>` |
| Keyboard focus chain | chain disabled | `Expected: true / Actual: <false>` |
| Stage 5 order invariant | conditional order removed | `Actual: ['city','area','street','block','houseNumber']` — no `stateText` |
| Stage 12 fold | scroll fix reverted | `A RenderFlex overflowed by 308 pixels on the bottom` |

## Three of this session's own guards proved nothing until reverted

Worth recording as a pattern, not three incidents — every one *looked* right and asserted nothing.

1. **A Stage 8 inset guard passed with the fix removed.** Stage 8's views are all top-aligned or
   centred, so its buttons were never near the navigation bar. Its `SafeArea` is precautionary and
   now says so; the real guard moved to Stage 9, whose review view is bottom-anchored.
2. **The Stage 9 guard then also passed with the fix removed** — clearance 128 dp. It measured the
   accept button, but two `OutlinedButton`s sit below it in the same column, so the control being
   measured was never the one at risk. Retargeted to the bottom-most control.
3. **The Stage 7 double-submit test measured the wrong thing entirely.** The repository holds a
   per-stage mutex, so a second submission waits *there* and never reaches the fake while the gate
   is held. Asserting mid-flight passed with both screen guards deleted; asserting after the gate
   releases gives `1` against `3`.

## Reviewer findings that changed the work

`@agent-reviewer` ran against each commit's diff separately. Selected, by what they cost:

- **Commit 1** — the inset reached **13 screens, not the 9 first counted** (the reference picker's
  138-row list was the one that mattered), and **none of the 13 sites had a guarding test**:
  deleting any one left the suite green. Account entry's error branch still had an uninset retry
  button, after the identical reasoning had been applied to the terminal screen.
- **Commit 2** — the rename was **2 of 3 ruled sites**. `values-ar/strings.xml` overrides the Uqudo
  SDK's own camera screen, which the customer reads seconds after ours; that file's comment states
  the invariant the first pass broke. Separately, **the double-submit guard was open across an
  awaited draft write** — `_submitting` was raised inside `_onNext`, after the await, and both
  "independent" guards read the same latch. Real on a device: the session DB uses
  `createInBackground`, so the write is an isolate round trip.
- **Commit 3** — **the fix for the dead keyboard key reintroduced the dead keyboard key.** Outside
  Sudan, Stage 6's state and locality become mandatory free-text fields *between* employer and
  city, and the constant order jumped both. Worse than a skip: they had no focus node, so a later
  Next could neither focus them nor render its summary — the customer presses Next and nothing
  happens, which is walk comment 2's symptom exactly.
- **Commit 4** — **the recorded mechanism for the Stage 4 crash was wrong in two files and enforced
  by a new test.** The first explanation blamed visibility; `testWidgets` enables semantics by
  default and the existing test already ticked two sources, so both were in the tree all along. The
  real gate is `SemanticsNode._addToUpdate`, which asserts `_dirty` before running role checks — a
  role is validated only on an update where *that node* is dirty, and ticking a checkbox dirties
  the tile, never the group. The test asserting "no semantics container here, because a container
  broke Stage 4" was **deleted rather than re-worded**: the rule was false and would have blocked a
  legitimate accessibility option on a wrong diagnosis.
- **Commit 4** — **RTL was correct but unproven.** Every colour assertion walked the widget *tree*,
  and tree order is identical under either direction, so all of them would have passed against a
  strip filling left-to-right. Given walk comment 10 in this same session was a direction defect,
  it is now asserted by *position*: step 1 sits on the right.

## Two defects the walk never reported

Both surfaced because the progress strip moved every screen's content down 44 dp.

**[[BL-130]] — Stage 4 could crash.** Its income-source list nests a `Radio` inside a
`CheckboxListTile` inside a `RadioGroup`; a checked checkbox reports `isChecked` exactly as a
selected radio does, so two ticked sources trip «Radio groups must not have multiple checked
children». Confirmed pre-existing by substituting a plain `SizedBox(height: 44)` and seeing the
identical assertion — so a larger text scale would reach it too. Mitigated by scoping the
`RadioGroup` per radio. The mitigation **removes the framework's detector, not the malformed
tree**, and also costs arrow-key selection and the group announcement; all three costs are on the
row, along with the redesign (split the row's two questions into two).

**Stage 12's submit button was already unreachable at a 1.3× text scale.** Measured at 360×640:
**228 px** of overflow before this session, **287 px** after the strip. A non-scrolling column on
the last checkpoint before an irreversible action — off-screen, not clipped-but-scrollable. Fixed
here rather than filed, because that screen is the worst place in the journey for it. A separate
177 px horizontal overflow in its summary chips is fixed too.

## What was skipped, and why

- **The device check for walk comment 9.** A widget test can now simulate the navigation-bar inset
  via `MediaQuery.padding`, which is what the new guards do — but it cannot replace a look at a
  real 3-button handset. Owed.
- **Walk comment 10's second half** (the splash line building up rather than looping) — product
  owner took direction-only. [[BL-129]].
- **[[BL-130]]'s redesign** — a UI change to a control the walk did not complain about.

## Commit proof — captured after the push

```
$ git status -sb
## main...origin/main

$ git rev-parse HEAD origin/main
c8cc2a24a8b44791b89642d1e0d48c528d0d5cb2
c8cc2a24a8b44791b89642d1e0d48c528d0d5cb2

$ git log --oneline -5
c8cc2a2 Walk fixes 4/4: the journey progress bar, and two defects it exposed
421005f Walk fixes 3/4: the keyboard's next key, and a controlled phone field
c6c8262 Walk fixes 2/4: the document choice advances on tap, and is named correctly
b5164ae Walk fixes 1/4: unreachable Next button, Sudan default, splash direction
da6f6b6 S8-16 follow-on: the pearl launcher, a held splash, a device script, BL-126
```
