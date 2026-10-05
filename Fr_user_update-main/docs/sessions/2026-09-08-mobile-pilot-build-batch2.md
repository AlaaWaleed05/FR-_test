# 2026-09-08 — S8-05, second session: the per-screen walk comments

Mobile only. No backend, no iOS, no package id, no new dependency.

⚠ **Numbering.** The brief calls this "batch 2". It is actually this row's **third** delivery —
the first 2026-09-08 session ran two internal batches against S8-05. That collision is not
pedantry: it is what produced the brief's wrong premise, below. Referred to by date in the plan
files.

---

## 1. The pre-build audit — most of the brief was already built

The brief credited the previous session with "surface treatment, splash, WhatsApp-default,
finish end-screen, error-handling" and asked me to build the per-screen comments on top. A
source audit, run before any code and independently confirmed by `@agent-reviewer`, found that
premise wrong. The reviewer's words: *"the brief's premise is REFUTED … understates it by five
bundles."*

Already shipped, verified at file:line, and therefore **not rebuilt**:

| Brief item | Evidence |
|---|---|
| Dropdowns — gender / marital / education | `stage3_screen.dart:332`, `:368`, `:514` |
| Document types as tappable picture cards | `stage7_screen.dart:245` — `Semantics(selected:,button:)` › `Card` › `InkWell` |
| Signature draw-vs-upload as a visible choice | `stage11_screen.dart:278` `SegmentedButton` |
| Channel icons, both screens | `channel_labels.dart:27`, used at 3 call sites |
| W-4 copy and renames | applied last session |
| Phone-link hide **+ the journey-doc amendment** | `channel_verification_screen.dart:401`, test at `:507-511`, `customer.md:260` |

The brief asked me to "note in the report that the phone-link hide supersedes customer.md
Stage 2". **That supersession was already made and recorded**, in the same commit that built it.
Nothing was owed there.

Building the brief as written would have re-done finished work and, on income source, reverted
a deliberate decision. Scope was re-cut to what was genuinely unmet, and the product owner
approved the re-cut plus four additions (3a, 15b, W-7, and a decision on income source).

**This is the second consecutive session where a pre-build audit changed the plan.** The
previous one caught three wrong claims. That is now a pattern worth naming rather than a
coincidence: briefs for this row are being written from earlier reports rather than from source.

---

## 2. Surface inheritance — three measured gaps

The brief's real intent was that every component inherit the established surface language. The
previous session set the rule — a resting control must be identifiable at WCAG 1.4.11's **3:1**
via a 1 dp `outline` hairline — and then added three components that did not obey it. Measured
on the resolved palette (canvas `#E7E8EE`, container `#FFFFFF`, `outline` `#74777F` = 3.66:1):

**(a) Document cards — the strongest.** `outlineVariant` `#C4C6CF` = **1.39:1**. The rule was
being applied everywhere *except* the one screen where a container **is** the control (comment
7a made the whole card the action). Fixed to `outline`. **Stated honestly:** the card also
carries a 48 px glyph and a label, so it was never as bare as an empty input — the argument is
internal inconsistency more than an outright 1.4.11 failure, and the code comment says so.

**(b) Open dropdown menus.** `dropdown.dart:341` paints the menu
`dropdownColor ?? Theme.of(context).canvasColor`; `canvasColor` defaults to `colorScheme.surface`
= `#F9F9FF`, **1.17:1** against the canvas. So a dropdown's *closed* state was a white container
and its *open* state was not — one control disagreeing with itself. **Material has no
`DropdownButtonTheme`**, so this is the one surface that genuinely cannot be reached through the
theme, which is exactly the "flag where a component can't inherit cleanly" the brief asked for.
Passed per-site via a documented `AppTheme.dropdownMenuSurface` rather than by moving the global
`canvasColor`, which backs other Material surfaces nothing here has examined.

*Correction to my own first reading:* the menu defaults to `elevation: 8`, so it floats on a
shadow and was never invisible. This is a consistency fix, not a legibility rescue. That shadow
is also the only surface still contradicting "depth from tone, never elevation" — deliberately
left, since an unanchored popup is where elevation earns its keep.

**(c) `segmentedButtonTheme` — the weakest, and "inherits nothing" would have been wrong.** M3's
defaults already supply `side: BorderSide(color: colorScheme.outline)` at 3.66:1 and
`elevation: 0`. Only the unselected *background* diverged (transparent, so the canvas showed
through). Fixed, and described accurately rather than inflated.

---

## 3. What else was built

- **10b — and it was TWO strings, not one.** The «بيانات السجل المدني» section heading is gone,
  and the registry portrait's «صورة السجل المدني» caption became «الصورة المسجّلة» (PO-confirmed).
  **The registry mention in the mismatch guidance is deliberately KEPT** — there it is not a
  label but the *reason* the customer must visit a branch, and stripping it would leave "go to a
  branch" with nothing behind it, which is precisely what customer.md's "explain rather than
  refuse" rule forbids. Pinned by a test.
- **10c** — the review screen's field list and image block became white-container sections. This
  is not decoration bolted onto 10b: deleting the only heading would otherwise have left an
  unlabelled loose run of fields, a worse screen than before.
- **8a** — icons on the four scan instructions, words untouched.
- **3a** — the sparse account screen: an explanatory line, the inputs boxed, and the action
  button pinned outside a scroll view.
- **15b** — the customer is now asked to save the reference number, and told why.
- **2a's one missed site** — `stage4`'s income-source error still named its list.
- **Income source (PO decision).** Comment 6a asked for a dropdown. It stays a multi-select with
  a designated primary — two independent pieces of state a single-value dropdown cannot express —
  and got the density 6a actually wanted instead: compact rows, boxed as a section. Converting it
  would have dropped capability while every existing test still passed, which is the trap BL-086
  exists to name. Pinned at the **wire**, not the widget.

---

## 4. W-7 was not built, because its premise does not hold

The PO asked for W-7. I did not build it, and this is the finding I would keep if I could keep
only one. **Both halves of comment G4 are already true:**

- *"the keyboard's next key should move between fields"* — it already does.
  `editable_text.dart:3948-3951`: the default `onEditingComplete` for `TextInputAction.next`
  calls `focusNode.nextFocus()`, and 23 fields already set that action.
- *"the action button should only move screen to screen"* — it already only does that.
  `_onNext()` (`stage3_screen.dart:216-221`) either fails validation and focuses the **offending**
  field, or submits and navigates. It never advances field-to-field as a feature.

So a build "as briefed" would have been a no-op that passed every gate and changed nothing on a
handset — the same failure mode the previous session's audit caught with the WhatsApp default.
The likeliest explanation of what the walk saw is the **validation focus-jump** reading as
arbitrary navigation, in which case the fix is failure legibility, not focus plumbing.

**PO decision: deferred for device verification before anything is built.**

---

## 5. Review — eight findings, all accepted and fixed before commit

Two were substantive:

1. **A genuinely vacuous test.** My 10b test asserted `find.text('صورة السجل المدني')` is
   `findsNothing` — but no fixture in the repo ever lists `portraitRegistry`, so that branch
   never renders and **the assertion passed against the unchanged code too**. Its own comment
   claimed "both label sites are asserted". Fixed with a payload that actually contains the image
   kind, now asserting the new caption renders and the old one does not.
2. **The fourth dropdown was missed.** `_BranchPicker` in `account_entry_screen.dart` is a
   production `DropdownButtonFormField` — in the very file I restructured — and got no
   `dropdownColor`. Caught by review, not by me or by any gate.

The rest: a comment describing a `Spacer` the code deliberately does *not* use (I reworked the
mechanism and left the prose behind); a stage3 test comment claiming it proved the colour reaches
the *rendered menu* when it reads the argument off the closed button; an `account_entry`
assertion that passed trivially on any screen with no scroll view; the 2a error branch having no
test at all (which is *why* the site was missed — an untested branch leaves coverage unmoved
rather than lowering it); and a comment overstating R-019, which has two halves — the number
*is* also sent to every verified channel, so the customer does have a durable copy.

Review also confirmed the account-screen restructure is safe: `Column [Expanded(scroll), Padding
(button)]` bounds the height and lays the button out before the remainder, so the keyboard
shrinks the viewport rather than pushing the control off it. That mattered — the previous session
hit exactly that defect on Stage 12, and a `Spacer` (my first attempt) would have reproduced it.

**On revert-restore:** every new assertion here is a *direct wrong-value* assertion — a role
identity, a string that did not previously exist, a wire value — so per CLAUDE.md none requires a
revert proof, because none can pass against the pre-change code. The one place that reasoning
failed is finding 1 above, where the assertion was direct but the code path was never executed;
that is the case the rule does not cover, and review caught it.

---

## 6. Gates

```
###### fvm flutter analyze ######
Analyzing mobile...                                             
No issues found! (ran in 145.5s)
###### fvm dart run tool/check_coverage.dart ######
03:36 +515: All tests passed!
Line coverage: 84.67% (3790/4476 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

504 tests at session start → **515**. No new package, so no iOS support check is owed.

---

## 7. The APK, and what it does and does not prove

Built for sideload against AWS staging:
`fvm flutter build apk --debug --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net`
→ `build/app/outputs/flutter-apk/app-debug.apk`, exit 0, rebuilt **after** the review fixes.

The staging host appears in the extracted `kernel_blob.bin`, which it could not if the
`--dart-define` had not been passed. **It is not conclusive on its own** and is not claimed as
such: the `http://localhost:8080` default literal is still present in the blob (it is the
`defaultValue` argument in `AppConfig`'s source), so a string search cannot distinguish "baked"
from "present as a default". S8-03 proved this properly with a reverse-tunnel probe; the
definitive check here is the device run, which is owed anyway.

*(Aside: `strings` is not installed on this machine and returned empty for both patterns, which
reads exactly like "not found". `grep -a` on the extracted blob is what actually answers.)*

---

## 8. Not proven, and owed

- **Nothing has run on a handset this session.** Every visual claim is from source, measurement
  and tests. The PO judges the whole result on device.
- **W-7 needs the device check** described in §4 before any build.
- **The wording gate is closed for what shipped:** «الصورة المسجّلة» and the 15b sentence were
  both put to the PO and confirmed. The 3a explanatory line and the reworded 2a error were not
  separately confirmed — the latter is character-for-character the string already approved and in
  use at two other sites.
- **Still open on BL-090:** W-2 (only G2's swipe-back half carries the RTL transition conflict —
  G1's progress line and back-as-icon are pure presentation deferred on **size**, not blocked on
  a decision, and the backlog said otherwise until now); G3 (titles are arguably *less* distinct
  since the app bar joined the canvas tone); G5's button theme; comment 9a; both artwork items,
  which need a licensing decision.
- **Backend session still owes:** FRU→SFB (two sites, must change together), Sudan-first country
  ordering, and BL-086's documentation half. None touched here.
