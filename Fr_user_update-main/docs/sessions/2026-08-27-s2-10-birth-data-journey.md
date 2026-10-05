# S2-10 — Close the birth-data journey gap

Date: 2026-08-27. Sprint 2. Documentation only — no schema, no migration, no code.

---

## 0. Commit check

```
$ git log --oneline -3
11d9f46 docs: record S2-09 commit/push proof in session report
12f0a0f feat: S2-09 field 22 source correction; provenance matrix v2
ce54c96 docs: record S2-08 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

HEAD is `11d9f46`, S2-09's proof-recording commit on top of `12f0a0f` — matches the task's
expectation exactly. Tree clean before starting.

---

## 1. The gap

`docs/journeys/field-provenance.md` (Version 2, S2-09) assigns three customer-entered birth
fields — 22 (birth country), 23 (birth city), 24 (birth state) — but no stage in
`docs/journeys/customer.md` ever collected them. Found by `@agent-reviewer` during S2-09
(NOTE 3), pre-existing since S2-06, extended but not introduced by S2-08 and S2-09.

Added to EXECUTION_PLAN.md as instructed, then flipped to ✅ once step 5's gates passed.

---

## 2. Plan (approved before implementation)

Extend Stage 3 rather than add a new stage: three fields do not justify a third
renumbering, and the paper form's own order puts بيانات الميلاد immediately after social
status, which is where Stage 3 already ends. Concretely:

- Rename the Stage 3 heading to reflect the widened scope.
- Append three fields to the end of Stage 3's field list, after education level, in
  country → state → city order (mirrors the cascading pattern already used for both
  address hierarchies): birth country (22, mandatory, ISO 3166 list, defaults to Sudan),
  birth state (24, mandatory, Sudan-state-list-or-free-text depending on birth country),
  birth city (23, mandatory, free text, always asked — with the reasoning for "always"
  spelled out explicitly, since it isn't obvious).
- Grep `customer.md` for "Stage 3\|stage 3" and fix anything the addition renders stale,
  without renumbering any stage.
- Verify all 54 fields now have a home; run all three tiers' gates; run `@agent-reviewer`;
  write this report; commit and push.

The full plan is preserved at the plan-mode checkpoint and was approved unchanged before
any edit was made.

---

## 3. What changed

### `EXECUTION_PLAN.md`

Added the S2-10 row after S2-09, status ✅ (flipped from 🔵 once §6 gates passed clean),
citing this report.

### `docs/journeys/customer.md` — Stage 3 extension

- Heading: `## Stage 3 — Personal and social` → `## Stage 3 — Personal, social and birth
  data`.
- Three new numbered items appended after item 5 (education level):
  6. **البلد — birth country** (field 22) — mandatory, ISO 3166 country list (S2-07),
     defaulting to Sudan. No source supplies it: MRZ `issuer` is the document's issuing
     country, not birth country, and reads `SDN` regardless of where the customer was
     actually born. Same list/alphabet as country of residence (11) and both address
     hierarchies (28, 35).
  7. **Birth state** (field 24) — mandatory. Sudan state list when birth country is
     Sudan, free text otherwise — same non-Sudan fallback pattern as the address
     hierarchy (Stage 5).
  8. **Birth city** (field 23) — mandatory, free text, always asked. Reasoning spelled
     out per the task: the field's primary source is Uqudo's `placeOfBirth`, but Uqudo
     data doesn't exist until the scan at Stage 8, so at Stage 3 there's no way to know
     whether the customer's fallback answer will even be needed. The customer therefore
     always answers, and Uqudo's value supersedes theirs when the scan lands (S2 already
     outranks S3 for this field). Same shape as sex (item 1): customer answers for the
     interface, the authoritative source is what the profile stores.
- No stage renumbered. Stage 3's exits (Next → 4, Back disabled) untouched.

### Consequence fixes in `customer.md` (see §4 for the grep and judgement)

- L531 (Provenance-corrections paragraph): extended to also name birth country and birth
  state as new customer-entered fields, alongside ethnicity and country of residence.
- L136 (device-less-resume review-flow description): inserted "birth data" into the
  enumeration of restored customer-entered content.
- L1091 (Stage 13 ownership table): inserted "birth data" into the customer-entered row's
  enumeration.

### `docs/journeys/journey-open-items.md` — one additional fix, found by the reviewer

Not part of the task's literal scope (the task's consequence-check grep was scoped to
`customer.md`), but the reviewer flagged that this file's own "New segmentation" line
(stage 3 field list) is now stale, and this file's established convention — set by S2-06 —
is to annotate drift with a `**Superseded [date] (S[[sprint]]-[[nn]]):**` clause rather
than leave it silently wrong. Added one such clause after the segmentation line, naming
the three new fields and pointing at field-provenance.md. See §6 for the reviewer's
reasoning and §7 for why this was accepted.

### One correction made after the reviewer's pass (see §6)

The first draft of item 6 labelled field 22 with `بيانات الميلاد` (the paper form's
**section** header, "birth data") instead of the field's own Arabic label. Corrected to
`البلد`, matching `field-provenance.md`'s row for field 22 and the `ArabicLabel — english
name` convention already used by items 4 and 5 in the same list.

---

## 4. Stage 3 grep — full output and per-hit judgement

```
$ grep -n "Stage 3\|stage 3" docs/journeys/customer.md
292:## Stage 3 — Personal, social and birth data
370:Offline-capable. Free back-navigation to stage 3.
388:- **Next** → stage 5 · **Back** → stage 3
531:new customer-entered fields the paper form introduces (see Stage 3) — education level is no
```

- **L292** — the heading itself. Updated per §3.
- **L370** — Stage 4's own "free back-navigation to stage 3" line. Unaffected: it's a
  cross-reference to Stage 3 existing at all, not to its contents.
- **L388** — Stage 4's exits ("Back → stage 3"). Same reasoning, unaffected.
- **L531** — "Ethnicity and country of residence are also new customer-entered fields the
  paper form introduces (see Stage 3)." This became incomplete once birth country and
  birth state joined the same category (no-source, newly-added Stage 3 fields). **Fixed**
  — see §3.

Two further enumerations of Stage 3's contents were found by a broader search (they don't
contain the literal string "stage 3" so didn't show in the required grep, but they
describe Stage 3's customer-entered fields by category) and were also fixed — see §3,
L136 and L1091.

---

## 5. Field coverage — all 54 fields

Cross-referenced every row of `docs/journeys/field-provenance.md` against
`docs/journeys/customer.md`. Every field now has either a collecting stage or a source
that means the customer is never asked. No gaps found beyond the one this session closes.

| Field(s) | Collected at | Field(s) | Collected at |
|---|---|---|---|
| 1 | Not collected — system (server timestamp) | 28–34 | Stage 6 (work address) |
| 2 | Stage 1a | 35–42 | Stage 5 (home address) |
| 3 | Stage 1a | 43 | Stage 7 (S3 chooses); S2 confirms |
| 4 | Not collected — S2 | 44 | Not collected — S2 |
| 5 | Not collected — S1 | 45 | Not collected — S2 |
| 6 | Not collected — S1 | 46 | Not collected — S2 |
| 7 | Not collected — S1 | 47 | Not collected — S2 |
| 8 | Not collected — S1 | 48 | Not collected — S2 |
| 9 | Stage 3 (item 1); S1 stores the value | 49 | Stage 11 |
| 10 | Stage 3 (item 2) | 50 | Stage 6 (optional attachment) |
| 11 | Stage 3 (item 3) | 51 | Not collected — S2 (Uqudo scan satisfies it) |
| 12 | Stage 3 (item 4) | 52 | Not collected — S1/S2 (system-derived image) |
| 13 | Stage 3 (conditional table) | 53 | Not collected — S2 (system-derived image) |
| 14 | Stage 3 (conditional table) | 54 | Not collected — S2 (system-derived image) |
| 15 | Stage 3 (conditional table) | | |
| 16 | Stage 3 (conditional table) | | |
| 17 | Stage 3 (item 5) | | |
| 18 | Stage 4 | | |
| 19 | Stage 4 | | |
| 20 | Stage 4 | | |
| 21 | Not collected — S1 | | |
| **22** | **Stage 3 (item 6) — new this session** | | |
| **23** | **Stage 3 (item 8) — new this session** | | |
| **24** | **Stage 3 (item 7) — new this session** | | |
| 25 | Stage 1b | | |
| 26 | Stage 1b | | |
| 27 | Stage 6 | | |

No other field lacks a home. Nothing else to report.

---

## 6. `@agent-reviewer` findings and disposition

Invoked against the diff (`EXECUTION_PLAN.md` and `docs/journeys/customer.md`) and the
S2-10 task text.

**SHOULD-FIX 1 — wrong Arabic label on field 22.** The first draft's item 6 read
`**بيانات الميلاد — birth country**`. `field-provenance.md` row 22 gives the field's own
Arabic label as `البلد`; `بيانات الميلاد` is the paper form's **section** header (the
"Birth data" table heading in field-provenance.md), not the field label — a real
mismatch against the matrix, and exactly the class of drift the matrix exists to prevent.
**Fixed:** relabelled to `البلد — birth country`, matching the matrix and the
`ArabicLabel — english name` convention already used by items 4 and 5 in the same list.
Re-verified by inspection against `field-provenance.md:79` and against items 4/5's
formatting.

**NOTE — EXECUTION_PLAN row cited a session report that didn't exist yet at review time.**
Expected mid-session under CLAUDE.md's session-end ordering (report is written before the
commit that makes the reference resolve). No action needed beyond writing this file, which
is what closes it.

**NOTE — stale Stage 3 description in `docs/journeys/journey-open-items.md`.** Outside the
task's literal scope (its consequence-check grep was scoped to `customer.md`), but that
file's own established convention — set by S2-06 — annotates exactly this kind of drift
with a `**Superseded [date] (S[sprint]-[nn]):**` clause rather than leaving stale text
unmarked, and S2-06's own EXECUTION_PLAN row records this same file as one it had to fix
for the same reason. **Fixed:** appended one such clause after the stale "New
segmentation" line, naming the three new fields and pointing at
`field-provenance.md`. Judged in scope as a documentation-consistency fix, not a schema
or journey-stage change.

**NOTE — ownership-table and review-flow "birth data" insertions were the right call, not
overreach.** Both enumerate Stage 3's customer-entered content by category and would have
gone stale otherwise. Reviewer flagged a possible tension between the ownership table
(device wins on conflict) and birth city's "Uqudo supersedes" rule, and concluded there is
none: the table governs device-vs-backend reconciliation of the *working copy*; Uqudo's
result is system-derived and covered by the table's other row. The identical shape already
exists, un-flagged, for sex (field 9). No correction needed.

All findings that required a fix were fixed and re-verified by inspection against
`field-provenance.md` and the pre-existing formatting conventions in both affected files.
No finding required a code or schema change (none was in scope).

---

## 7. Gates — all three tiers

Docs-only change; no tier's source was touched. Ran the exact invocations from CLAUDE.md
to confirm no regression against S2-09's last clean baseline.

### `backend/` — `./mvnw test`

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.27 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### `backend/` — `./mvnw verify`

```
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 3 files clean - 0 needs changes to be clean, 0 were already clean, 3 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 0 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```
0 classes — unchanged from every prior session this sprint; no entity/service/controller
code exists yet.

### `mobile/` — `fvm flutter test`

```
00:00 +0: loading .../mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:01 +1: All tests passed!
```

### `mobile/` — `fvm dart run tool/check_coverage.dart`

```
Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

### `mobile/` — `fvm flutter analyze`

```
Analyzing mobile...
No issues found! (ran in 37.3s)
```

### `backoffice/` — `npm run test`

```
 Test Files  1 passed (1)
      Tests  1 passed (1)
```

### `backoffice/` — `npm run test:coverage`

```
Statements   : 100% ( 1/1 )
Branches     : 100% ( 0/0 )
Functions    : 100% ( 1/1 )
Lines        : 100% ( 1/1 )
```

### `backoffice/` — `npm run lint`

```
(clean, no output)
```

No regression anywhere — every number matches S2-09's last recorded run exactly.

---

## 8. Files created / edited

**New:**
- `docs/sessions/2026-08-27-s2-10-birth-data-journey.md` (this file)

**Edited:**
- `EXECUTION_PLAN.md` — S2-10 row added, → ✅.
- `docs/journeys/customer.md` — Stage 3 heading and field list extended; three
  consequence fixes (§3, §4).
- `docs/journeys/journey-open-items.md` — one supersession clause added (§3, §6).

**Deliberately not staged:** `.claude/settings.json` picked up an unrelated,
harness-generated permission-allowlist entry (a `grep` invocation pattern) during this
session, with no relation to S2-10. Committed by file name rather than `git add -A` to
keep that noise out of this commit.

## 9. iOS package check

Not applicable — no Flutter package was added this session (mobile tier untouched).

## 10. What failed, summarized

Nothing failed. One real defect was caught by `@agent-reviewer` (wrong Arabic label on
field 22) and fixed and re-verified before commit; one documentation-consistency gap in a
sibling file, outside the task's literal scope, was also fixed on the reviewer's
recommendation (§6).

---

## Commit/push proof

```
$ git log --oneline -1
e6240ed docs: S2-10 close the birth-data journey gap

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   .claude/settings.json

no changes added to commit (use "git add" and/or "git commit -a")
```

Pushed to `origin/main`: `11d9f46..e6240ed  main -> main`.

The tree is clean with respect to this task's deliverables — all four intended files
(`EXECUTION_PLAN.md`, `docs/journeys/customer.md`, `docs/journeys/journey-open-items.md`,
this report) are committed and pushed. The one remaining unstaged change,
`.claude/settings.json`, is a harness-generated permission-allowlist entry unrelated to
S2-10 (see §8) and was deliberately left out of this commit.

