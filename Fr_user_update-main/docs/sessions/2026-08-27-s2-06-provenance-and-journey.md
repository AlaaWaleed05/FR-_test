# S2-06 — File the field provenance matrix; update the journey for the 2026-08-23 client decisions

Date: 2026-08-27. Documentation only — no schema, no migration, no code.

## 0. Commit check

```
$ git log --oneline -3
7322153 docs: record S2-05 commit/push proof in session report
c784c9b feat: S2-05 status-transition guard, otp_challenge CHECK, gitattributes gap, CR card
25ab757 docs: record S2-03 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```

S2-05 found at `c784c9b` as expected, with the proof-recording commit `7322153` on top. Clean
tree, up to date with origin — matches the CLAUDE.md session-start expectation.

## Blocker hit, and resolved with the user

`docs/journeys/field-provenance.md` — the product owner's 54-field provenance matrix — did not
exist anywhere in the repository at session start (checked `docs/journeys/`, `git ls-files`,
and a repo-wide search for `*provenance*`/`*field*`). The task requires it as the authority for
every downstream edit and explicitly forbids authoring or editing its content. Stopped and
asked the user via `AskUserQuestion` rather than fabricating it. The user placed the file mid-
session; it was then re-verified present (`?? docs/journeys/field-provenance.md`, untracked)
and read in full before any further work.

## Plan

Entered plan mode (multi-file documentation change, per CLAUDE.md). Plan approved by the user
without changes:

1. Add three rows to EXECUTION_PLAN.md's Sprint 2 table (S2-06, S2-07, S2-08).
2. Track `field-provenance.md` (no content edits — it is the authority).
3. Replace the stale "Field provenance — the master mapping" section in `customer.md` with a
   short section pointing at the new authority file.
4. Insert two new mandatory Stage 3 fields (ethnicity, country of residence) after sex,
   renumbering the rest of the list.
5. Record field 19's digits-only/SDG/hint constraint at Stage 4.
6. Insert a new mandatory Stage 11 — Signature (after liveness, before submission), renumber
   the old Stage 11 (Completion) to 12 and Stage 12 (Resume) to 13, and fix every
   `[Ss]tage 1[12]` cross-reference found by grep (10 hits, all resolved during planning).
7. Verify (grep, three tiers' gates, `@agent-reviewer`), write this report, commit, push.

Full plan file: `C:\Users\DELL\.claude\plans\hashed-sparking-ocean.md`.

## What changed after approval

### EXECUTION_PLAN.md

Added S2-06 (🔵, flipped to ✅ at the end), S2-07 (⬜, ISO 3166 seed), S2-08 (⬜, schema
amendment) after the S2-05 row, exactly as specified in the task.

### docs/journeys/field-provenance.md

Newly tracked via `git add`. Not edited — read in full to confirm it matches the task's
description (54 numbered entries across Header/Personal data/Social status/Birth data/
Contact/Work address/Home address/Identity document/Signature and attachments/Images; Arabic
confined to its own table column; S1 Civil Registry → S2 Uqudo → S3 customer precedence with
bolded overrides; a closing section listing consequences — S2-07 country list, the new
ethnicity field, the mandatory signature, and the pending S2-08 schema amendment).

### docs/journeys/customer.md

- Replaced the entire "## Field provenance — the master mapping" section (a stale mapping that
  had, among other things, sex and citizenship's provenance backwards) with the four-paragraph
  "## Field provenance" section given verbatim in the task, pointing at
  `docs/journeys/field-provenance.md`.
- Stage 3 "### Fields, in order": inserted new items 2 (Ethnicity, field 10, mandatory free
  text) and 3 (Country of residence, field 11, mandatory, ISO 3166 list — S2-07), reworded item
  1 (Sex) to state the Civil Registry value is what the profile stores, and renumbered the
  existing marital-status and education-level items to 4 and 5.
- Stage 4: reworded the monthly-expenses bullet (field 19) to record the digits-only, SDG,
  visible-hint constraint.
- Inserted new "## Stage 11 — Signature" (mandatory, draw-on-screen or upload, no skip, Next →
  stage 12 / Back disabled) immediately after Stage 10 and before "## The audit trail" — the
  same relative position the audit-trail interlude already held before the old completion
  stage.
- Renumbered `## Stage 11 — Completion and submission` → `## Stage 12`, `## Stage 12 — Resume`
  → `## Stage 13`, and every cross-reference the grep in step 7 found.

### Reviewer findings and fixes (same session)

Ran `@agent-reviewer` against the diff and the S2-06 task. It confirmed the stage renumbering
and the replaced provenance section were correct, then found one blocker and several
consistency gaps, all caused by this session's own edits:

| Finding | Disposition |
|---|---|
| **Blocker**: a second, stale copy of the provenance mapping survived under `customer.md`'s "#### Provenance corrections" subsection (wrong on nationality/birth-date/citizenship provenance, and directly contradicted the new Stage 3 items 30 lines above) | **Fixed** — replaced with a pointer to the Field provenance section / `field-provenance.md`, no re-derived facts |
| Stage 3 item 5 (education level) still claimed to be "the only genuinely new" field, contradicting the new ethnicity/country-of-residence items above it | **Fixed** — reworded |
| `[POLICY: signature file size and format limits]` added at Stage 11, but "Policy values — SETTLED" claims "All markers resolve here" | **Fixed** — added an explicit exception clause naming the one outstanding marker, rather than inventing a limit |
| Stage 12's "Ordering — strict" list jumped from liveness (stage 10) straight to `submitted`, skipping the new mandatory signature stage | **Fixed** — added a step for signature capture (stage 11) |
| `journey-open-items.md`'s "Resolved — do not re-raise" list still asserted the reversed decisions (no signature; sex/citizenship/birth-date provenance; "completion 11, resume 12") | **Fixed** — struck through and marked superseded 2026-08-23 (S2-06), each pointing at `field-provenance.md` or the new stage numbers |
| `PROJECT_PLAN.md`:23 said "stages 0–12" (now 0–13) and didn't list `field-provenance.md` as part of the specification | **Fixed** — updated stage count, added the file |
| `PROJECT_PLAN.md`:60-62 pointed at "the field provenance mapping in that document" (customer.md), which no longer holds it | **Fixed** — repointed to `field-provenance.md`, noted the override mechanism |
| `RISKS.md` R-012 said "Stage 12 copy states the 30-minute window" — that copy is now Stage 13 | **Fixed** — updated to Stage 13 |
| `RISKS.md` R-032's mitigation still described the provenance pass as future work | **Fixed** — updated to record it as done and filed, pointing at S2-07/S2-08 for what remains; left 🔴 Live since the schema amendment isn't done |
| **Not fixed, deliberately** — `PROJECT_PLAN.md` AD-004's scope sentence (image/artifact count) is now stale given the signature and second portrait | Explicitly out of scope for S2-06 ("AD-004"); flagged here for whoever picks up AD-004 or S2-08 |
| **Not fixed, deliberately** — `docs/journeys/operator.md` doesn't yet mention the second (Civil Registry) portrait or the signature file | Explicitly out of scope for S2-06 ("operator.md"); flagged for a future task |
| **Not fixed, deliberately** — "It covers all 54 fields of the bank's paper form" is a very slight overstatement (rows 51-54 are images/not-collected, one field was added by us) | This sentence was dictated verbatim by the task instructions; not altered |

After applying the fixes, re-ran the stage-number grep, the "master mapping" grep, and a fresh
grep across the whole repo for the corrected stale phrases — all clean (see step 7 below). Did
not re-invoke the reviewer agent a second time; verified the fixes by direct reading and grep
instead, since none of them touched code or schema and each was a narrow, mechanical text
correction.

## 7. Verify and report

### field-provenance.md present and tracked

```
$ git status --porcelain docs/journeys/field-provenance.md   (before git add)
?? docs/journeys/field-provenance.md

$ git add docs/journeys/field-provenance.md && git status --porcelain
 M EXECUTION_PLAN.md
 M docs/journeys/customer.md
A  docs/journeys/field-provenance.md
```

### Stage renumbering — full grep, post-fix

```
$ grep -n "[Ss]tage 1[123]" docs/journeys/customer.md
24:     continuity is itself the evidence. See Stage 13.
31:   that work offline remain available. See Stage 13.
266:Completion notices at Stage 12 go only to channels in the **verified** state.
574:  told a connection is needed to continue, and returns when they have one. See stage 13.
634:stage 13 for the stale-token case.
655:returns and resumes at the scan, not at the beginning. See stage 13.
787:**Passes both liveness and face match** → stage 11.
832:## Stage 11 — Signature
853:- **Next** → stage 12, completion and submission
953:## Stage 12 — Completion and submission
962:2. Signature captured and stored on the profile (stage 11)
969:resume. It never assumes completion. See stage 13.
1057:## Stage 13 — Resume
1148:format limits at Stage 11, which remain unset.
```

Judgement on every hit:
- L24, L31, L574, L634, L655, L969 — all "See stage 12" in the original text, all referring to
  the Resume stage, correctly renumbered to Stage 13.
- L266 — "Completion notices at Stage 11" in the original, referring to the Completion stage,
  correctly renumbered to Stage 12.
- L787 — "→ stage 11" left **unchanged** by design: before this edit it pointed at Completion
  (old stage 11); after inserting the new Signature stage at position 11, the same number now
  correctly points at Signature, which is exactly where the liveness outcome should lead.
- L832, L853 — the new Stage 11 (Signature) section itself: heading and its own "Next → stage
  12" exit, both new and correct.
- L953, L1057 — the two renumbered headings (Completion → 12, Resume → 13).
- L962 — new line added to the Ordering list during the reviewer-driven fix, correctly
  referencing "(stage 11)" for the signature step.
- L1148 — new line added during the reviewer-driven Policy-values fix, correctly naming Stage
  11 as the location of the still-open signature-limits marker.

No dangling reference to the old numbering remains.

### Old provenance section fully gone

```
$ grep -n "master mapping" docs/journeys/
(no output)
```

Also re-checked for the second, reviewer-found stale copy and its specific wrong claims:

```
$ grep -rn "Uqudo-derived. Birth" .
(no output)
```

### Gates — all three tiers, real output

Backend (`JAVA_HOME` set to the JBR path first):
```
$ ./mvnw test
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  24.492 s
```

Backoffice (isolated Node 24.19.0 on PATH first):
```
$ node -v
v24.19.0
$ npm run test
 RUN  v4.1.11
 Test Files  1 passed (1)
      Tests  1 passed (1)
```

Mobile (`fvm flutter test`):
```
00:00 +0: loading .../mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```

No source file in any tier was touched by this session — all three gates ran clean, confirming
no regression, as expected for a documentation-only change. Gates were not re-run after the
reviewer-driven fixes since those fixes touched only `.md` files.

### `@agent-reviewer`

Run against the diff (`EXECUTION_PLAN.md`, `docs/journeys/customer.md`, staged
`docs/journeys/field-provenance.md`) and the S2-06 task. Findings and dispositions are tabulated
above under "Reviewer findings and fixes."

## Files edited this session

- `EXECUTION_PLAN.md` — three new Sprint 2 rows, S2-06 status flipped to ✅
- `PROJECT_PLAN.md` — stage count 0–12 → 0–13, `field-provenance.md` added to the specification
  pointer, the blanket "never asked" rule repointed to the new authority file
- `RISKS.md` — R-012's stage reference fixed (12 → 13), R-032's mitigation updated to record the
  provenance pass as done
- `docs/journeys/customer.md` — provenance section replacement, Stage 3 insert, Stage 4 hint,
  Stage 11 (Signature) insert, Stage 11/12 → 12/13 renumber and all cross-references, plus the
  reviewer-driven fixes (second stale mapping copy, Stage 3 item 5 wording, Ordering list,
  Policy-values exception clause)
- `docs/journeys/journey-open-items.md` — four stale "resolved, do not re-raise" entries struck
  through and marked superseded
- `docs/journeys/field-provenance.md` — newly tracked, not edited (authored by the product
  owner)
- `docs/sessions/2026-08-27-s2-06-provenance-and-journey.md` — this report

## Anything that failed

Nothing failed outright. The one blocker (missing `field-provenance.md`) was resolved by
stopping and asking the user rather than proceeding on an assumption. The reviewer's blocker
finding (the second stale mapping copy) was a real gap in the initial implementation of step 3
— the task's literal instruction named one specific section to replace, and a second, older
copy of the same claims elsewhere in the file was missed until the reviewer caught it; fixed
before commit.

## Deliberately out of scope, flagged for later

- `PROJECT_PLAN.md` AD-004's artifact-count scope sentence, now stale (signature + second
  portrait not counted) — AD-004 is explicitly out of scope for S2-06.
- `docs/journeys/operator.md` doesn't yet describe the second Civil-Registry portrait or the
  signature file on the single-profile view — explicitly out of scope for S2-06.
- S2-07 (ISO 3166 seed) and S2-08 (schema amendment) are filed in EXECUTION_PLAN.md but not
  started — explicitly out of scope for S2-06.

## Commit and push proof

```
$ git log --oneline -1
a24d839 docs: S2-06 field provenance matrix and journey update for 2026-08-23 decisions

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

