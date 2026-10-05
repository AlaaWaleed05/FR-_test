# 2026-09-08 — S8-05: pre-pilot mobile look-and-feel (Groups A–E)

Built from the design research (`2026-09-07-research-splash-and-surface.md`, recommended pair
**P2 × S1**) and the walk bundles W-1..W-10 in `2026-09-07-reconciliation-and-walk.md` §2.7.
Mobile only: no backend change, no iOS, no new package, no package-id change. Delivered in two
batches against one row; both are pre-pilot, so **the pilot APK ships only after batch two**.

---

## 1. The pre-build audit — the most valuable thing here

A reviewer pass ran **before any code**, against the brief's claims rather than a diff. Three were
wrong, and two would have produced a silent no-op.

**(a) The theme set none of the surface slots.** The brief said canvas and containers were
"near-identical tones today". True, but not for the stated reason: `ThemeData` named no surface
role at all, and the tones came from SDK defaults. Group A *adds* slots rather than retuning them.

**(b) "Zero screen edits" was false.** Two hand-rolled `Container`s — the Stage 1b channel group
and the Stage 2 channel row — carry a border and **no `color:`**, so `cardTheme` cannot reach them.
Two minimal screen edits were approved instead of shipping that inconsistency.

**(c) Both WhatsApp sites the brief named are dead paths.** `contact_channels_screen.dart`'s field
initializer is overwritten by `_load()` on every open behind the `_draftLoaded` gate; `saveDraft`
always writes an explicit value, so the Drift column default is unreachable outside tests.
**A build editing only those two sites would have passed every gate and changed nothing on a
handset.** The governing site is `EntryDraft`'s constructor default in `entry_repository.dart`.

Two governance conflicts were also surfaced before building — BL-086 fences this change, BL-091
records all three Group E items as unsettled — and both were decided explicitly (§5).

---

## 2. Group A — surface treatment

Six slots on `app_theme.dart`, every value a role off the seeded `ColorScheme`. Flat fills only,
`elevation: 0` throughout. No tiering: a flat fill costs the same on Impeller and the legacy
renderer, so the API-28 handset's verdict transfers everywhere — which is not true of the splash.

The tint is **`surfaceContainerHigh` (`#E7E8EE`)**, not the research's `surfaceContainerLow`, a
product-owner call to err toward visible separation. Measured, since the research left these
`[UNVERIFIED]`: `surfaceContainerLow` 1.10:1 against white containers, `surfaceContainer` 1.17:1,
the chosen `surfaceContainerHigh` 1.22:1, `surfaceContainerHighest` 1.29:1.

### The accessibility trade, stated rather than softened

The PO asked to be told if the visible tint crossed a guideline. It does — but not where expected,
and not because the tint was pushed down.

Text contrast is comfortably fine and *improved* by pairing a darker canvas with white containers:
`onSurface` 13.98:1 on canvas and 17.10:1 on a container; `onSurfaceVariant` 7.63:1 and 9.33:1.

The real issue: WCAG 1.4.11 wants **3:1** for what identifies a control, and the canvas-versus-field
fill difference is 1.22:1. **No tone on M3's light ladder reaches 3:1** — the darkest is 1.29:1 — so
a fill difference can never identify a field boundary however far the tint is dialled. That is
inherent to the whole tinted-canvas approach, and the research's own default is worse.

Fix applied: a **1 dp `outline` (`#74777F`) hairline on the resting field** — 3.66:1 against the
canvas, 4.48:1 against the field's own fill. Costs nothing visually and lets the tint be chosen for
looks rather than compliance. The research specified `borderSide: none`; that is wrong on this one
point. The contrast maths is now an executable test.

**Guard tests.** Before this pass not one test asserted any surface colour. Four were added: canvas
and containers must differ; the resting field must be identifiable by its border at 3:1 both ways;
body text must clear 4.5:1 on the tinted canvas; depth must come from tone, never elevation.

---

## 3. Group B — the splash

The sweep is promoted from the slogan alone to one light pass over the whole lockup. Preserved
exactly: mask/opacity reveal over already-shaped Arabic (**never** a growing substring — the
canonical paragraph moved across verbatim), one controller, fire-and-forget navigation, and D7.3's
no-minimum-display-time rule. No hold was added; that reversal is unapproved.

Two latent problems fixed in passing: `Opacity` in an animation became `FadeTransition`, and the
hardcoded `Alignment.centerRight → centerLeft` became `AlignmentDirectional.centerStart/centerEnd`
resolved through `createShader(..., textDirection:)`. The old literal happened to suit Arabic and
would have been silently wrong the moment anything rendered LTR.

**Deliberately not tiered, reversing the research.** It prescribes SDK_INT ≥ 29 for the masked band.
The pilot Huawei is **API 28** — below that line — so tiering would guarantee the PO never saw the
motion they are judging on the device they judge it on. Built untiered so the device run measures
the real thing; the fallback stays available if it stutters. This also removed the MethodChannel work.

**Walk comment 1a.** The wordmark is two spans **split on the space** — the correctness point, since
Arabic shapes letters from neighbours and a mid-word style change would re-shape glyphs either side,
while a space is already a join boundary. The name carries four U+0640 tatweel at the points where a
letter joins forward: the script's own stretching mechanism, not letter-spacing, which would pull
the joins apart. A `semanticsLabel` restores the plain name for screen readers — the kashida is
typography, not part of the bank's product name.

---

## 4. Groups C, D, E — applied, and deliberately not

Applied: W-1; W-3 (title overflow fixed structurally, shipped with the 5a rename so the rename
could not appear to fix it); W-4 (4a, 4b, 4d, 5a, 10a, 11a, 11b); W-5 (6a dropdowns, 7a cards, 12a
route chooser); W-6 (4c — 5c already existed, so the private icon helper was promoted rather than
duplicated); W-8 (2a, 2b, 2c); W-9's 1a; all three W-10 items.

**Six things deliberately not done as asked:**

1. **10b** — the Civil Registry label stays; undecided disclosure policy. ⚠ 10a's rename does remove
   «السجل المدني» from the **title**; the section label 10b targets is untouched.
2. **8b** — the "meaningless" red text was **kept and de-reddened**, not deleted. It is the only
   place the customer learns that leaving the camera *spends* one of BL-039's five attempts, after
   which the profile blocks for 24 hours. What read as meaningless is likelier the error-red on a
   screen where nothing has gone wrong. PO to confirm.
3. **6a's income-source half** — multi-select *plus* a designated primary. A dropdown cannot express
   that; converting it would be a data-model change in presentation costume.
4. **4d** kept the «لن يتم حفظ…» clause. Deletion was offered, but it is the only place the customer
   is told which details will *not* be stored, added deliberately under review at S5-02.
5. **7a** uses Material glyphs, not document artwork — CLAUDE.md forbids identity-document images in
   the repo, so any asset must be a commissioned generic illustration. The structural half is built;
   the card *selects* and Next still advances, since making it navigate would remove the last chance
   to change a document type whose scan budget is capped per type.
6. **W-2, W-7, 9a, 15b** untouched. W-2's swipe-back half reopens the settled
   `FadeUpwardsPageTransitionsBuilder` RTL decision.

**The 2c defect.** A failed reference-list load used to swap only the branch picker for an error
line, leaving the account field and Next live underneath. The walk evidence sharpens it: the
endpoint answered 200 in 0.259 s and the failure was the handset's connectivity
(`OBTAINING_IPADDR`) — the ordinary condition of this market, not a rare fault. It now takes the
whole screen: connectivity icon, emphasised message that does not name the list, and a retry.

---

## 5. Governance: two conflicts resolved on the record

**BL-086** prescribes the opposite sequencing and warns against a "presentation costume". The change
built is **narrower** than the D9.1 disabled row it fences: unticking a default needs no enablement
flag, so it does not touch R-042 and D9.1 stays fenced.

**BL-091** records all three Group E items as unsettled. The PO confirmed the decisions, so each was
built **and recorded as a reversal**: `customer.md` Stage 2 "Corrections" was amended, since hiding
the correction link closes a repair path that document listed as unconditional; Stage 1b's "both
selected by default" was amended likewise. The end screen says the **session** finished while
describing the request as still under review — customer.md forbids the app claiming approval, and a
screen headed "you have finished" is exactly where that slips.

---

## 6. Defects the tests found that review would not have

**The channel-group ink ripple.** Adding `color:` to that `BoxDecoration` — the obvious way to
satisfy Group A — hid the ink splash of every `CheckboxListTile` inside it, because a `ListTile`
paints onto the nearest `Material` ancestor. It surfaced as twelve failures in an apparently
unrelated file. Reworked to a `Material` with a `shape`. The *visual* result of the broken version
was correct; only touch feedback was dead, which a screenshot review would never catch.

**The submit button below the fold.** BL-087's completed-steps block was first five full-height
`ListTile`s. It filled the sparse screen and pushed the irreversibility warning and submit control
off the viewport — on a 5-inch handset, a worse defect than the sparseness it fixed, on the one
screen whose job is to be the last checkpoint. Caught by four `stage12` tests failing with
"derived an Offset … that would not hit test". Rebuilt as a compact `Wrap`. ⚠ The device run should
still confirm the ready state fits at 1.3× text scale.

**The document cards' lost accessibility state.** A `RadioListTile` reports "selected" to a screen
reader for free; a `Card` does not. Swapping them for 7a dropped that with nothing visibly broken.
Now carries an explicit `Semantics(selected:, button:)`, asserted alongside the visible emphasis
because the two are separately losable.

**A 280 MB log.** The card `Row` used `CrossAxisAlignment.stretch` inside a scroll view, asking
children for infinite height; Flutter asserted ~4,000 times. Fixed with `IntrinsicHeight`.

**One over-correction, reversed.** The route chooser was briefly built with *neither* route
preselected, on customer.md's "Neither is preferred". It broke seven tests and would have made
drawing cost one tap where it previously cost none — a regression nobody asked for. Reverted to
preselecting draw, which preserves the original balance exactly (draw immediate, upload one tap).

---

## 7. Review dispositions

**Batch one.** Scope creep on the wordmark restyle — **overruled**: the brief explicitly placed
comment 1a in Group B, and the reconciliation says it "sits naturally with the P2 splash work"; the
reviewer supplied that counter-evidence itself. Missing `semanticsLabel` — **accepted, fixed**. Code
citing a non-existent task ID — **accepted**; the S8-05 row was added to `EXECUTION_PLAN.md`.

**Batch two, five findings, all accepted:**

1. **The S8-05 completion note landed on the wrong backlog row.** A fallback anchor in my edit
   script matched **BL-084** — an unrelated backend defect about OTPs retrying forever — making it
   read as largely done while BL-090 stayed stale. The worst class of documentation error, since a
   reader would think a live production hazard was handled. Moved; BL-084 restored verbatim.
2. **All three Group E behaviour changes shipped with no test.** Both batches reported 498 tests, so
   batch two added none. One test each was written: the correction link disappears once a phone
   channel verifies (pinning the journey-rule reversal, which was otherwise unpinned); the end
   screen's copy, terminality and route registration; and the 2c whole-screen error plus its retry,
   a branch no test had ever executed.
3. **The S8-05 plan row contradicted its own diff** ("in progress", batch one only) — updated.
4. **A stage-11 test comment claimed neither route was preselected**, which the code deliberately
   contradicts — reworded to the code's own reasoning. Assertions were not weakened.
5. **`channel_labels.dart` claimed the WhatsApp brand-mark question was "filed"** when it was filed
   nowhere — now actually recorded on BL-090 with the document artwork.

Noted and accepted as-is: `ScreenTitle` is applied to five screens, not all; W-3 is read as the
OTP-screen defect it was filed as, and the widget is available for the rest.

Verified clean by review: the three load-bearing theme facts; no substring reveal; fire-and-forget
with no minimum hold; reduced motion; `requireValue` unreachable in loading/error states and the
retry unable to wedge; the end screen unreachable-backwards with no state writes; generated Drift
output regenerated not hand-edited; no new package, no backend/iOS/package-id change; no open
PROJECT_PLAN decision silently settled; no secret or customer data.

---

## 8. Gates

```
###### fvm flutter analyze ######
Analyzing mobile...
No issues found!

###### fvm flutter test ######
+504: All tests passed!

###### fvm dart run tool/check_coverage.dart ######
Line coverage: 84.46% (3739/4427 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

Four full-suite rounds were needed; each found a real defect (§6), not churn.

**A process failure worth recording.** Mid-session I reported the suite clean when it was not — the
claim came from a piped run whose output file was empty, and I read "empty" as "no failures" rather
than re-running plainly. There were 19 failures. The lesson is specific: **never infer a gate result
from absent output; read the pass/fail line.**

---

## 9. Not proven, and owed

- **Nothing has run on a handset.** Every visual claim here is from source and tests. The priority
  device check is Group A: do fields lift off the canvas. The splash is untiered, so the API-28
  Huawei will show the real masked band.
- **The AWS-staging APK was not built**, so the baked base URL is unverified this session.
- **The Arabic wording gate is open.** Strings were put to the PO; none are treated as final.
- **Backend session owes:** FRU→SFB reference prefix (W-11, two sites that must change together),
  Sudan-first ordering (W-12), and BL-086's documentation half — no correctness change is required,
  but `ContactChannelsRequest`'s Javadoc now contradicts the app. The
  `fru.messaging.whatsapp.enabled=false` staging flip was also not made.
- **Two artwork items open**, both needing a licensing decision, neither blocking: generic document
  illustrations for 7a, and the WhatsApp brand mark 4c names.

## 10. Note on `strartup.mp4`

The brief required this untracked root file not be committed and forbade `git add -A`. **It is no
longer on disk.** Nothing in this session removed it — the only deletion was a temporary colour
probe under `mobile/test/` — and it was never tracked, so nothing was lost from the repository and
git cannot restore it. Recorded because a file named in the brief vanishing is worth stating. The
commit uses explicit paths regardless.
