# S8-31 — Plan-file reconciliation

**Date:** 2026-09-14
**Scope:** PROJECT_PLAN.md, EXECUTION_PLAN.md, BACKLOG.md, RISKS.md, docs/journeys/customer.md,
docs/journeys/operator.md, docs/journeys/journey-open-items.md, docs/road-to-production.md, CLAUDE.md,
docs/bank-hosting-specification.md
**Method:** list every contradiction first, get rulings, then fix. Validate against source where a
document's claim was checkable.

---

## Gates

**Planning and documentation only — no code in any tier changed, so no test or analyze gate
applies.** Same basis as S8-18/19/20/21/25/26/30. `git diff --stat` at the end of this report is the
proof that the change set is documentation.

CLAUDE.md is 245 lines, under the 250 cap. No rule was removed; the S1-08 bullet grew by 3 lines
because it had to carry the correction. If the next session needs a rule, that bullet is the
shortest thing worth cutting — its correction will have stopped mattering once S1-08 is scheduled.

---

## What was found

18 items. Three of them each gave a different answer to "what do I do next", which is the harm S8-30
was written to end.

### Validated against source, not against the documents

| Claim | Where | Verdict |
|---|---|---|
| Nothing schedules `seal_create`/`purge_abandoned_artifacts` | R-037, road-map §3.1 | **TRUE, still live.** Only `@Scheduled` in the backend is `OutboxDispatchScheduler`. Every `seal_create`/`purge_abandoned_artifacts` occurrence in `backend/src/main/java` is a comment. It is now the sole launch-stopper. |
| backoffice and mobile "have no feature code yet" | S1-08, CLAUDE.md, PROJECT_PLAN | **FALSE.** `mobile/lib/features/` 6 features, `mobile/lib/core/` 17 modules, 102 Dart files across `mobile/lib`; `backoffice/src/` 5 dirs, 44 TypeScript files. Stale since S5-05/S6-01. |
| Stage 1b has WhatsApp selected by default | road-map §4.4, R-042 | **FALSE.** `entry_repository.dart:44` `whatsappSelected = false`, `contact_channels_screen.dart:47` matching, comment naming BL-086. Landed S8-05 (`2b0cd9f`, 2026-09-08). `customer.md:105` was amended the same day; the two registers were not. |
| App has no identity — label `"mobile"`, default icon | road-map §2.3 blocker 4 | **FALSE.** `android:label="@string/app_name"` = «بياناتي»; `mipmap-anydpi-v26` + five densities. Contradicted §3.4 of its own page for a week. |
| Release build signed with the debug key; no AAB | road-map §2.3 blockers 2–3 | **TRUE.** `build.gradle.kts:63-65` still carries `signingConfigs.getByName("debug")` and its `TODO`. |
| R-052's closure condition unmet | R-052 | **MET.** S5-07 ✅; codes consumed in `stage8_screen.dart`/`stage9_screen.dart`; no client counter returned — `stage8_screen.dart:71` says so outright. |
| Operator authentication still open | journey-open-items | **CLOSED 2026-09-02.** `backend/.../auth/{config,domain,jdbc,service,web}` + `backoffice/src/auth/` full set. |
| Corrected admin-divisions dataset still open | journey-open-items, OQ-012 | **STILL OPEN.** `V0016__seed_admin_division.sql` has no غرب كردفان / وسط دارفور / شرق دارفور. South Sudan correctly lives in `V0022__seed_country.sql` as a country. |
| Dashboard metric list still open | journey-open-items, OQ-014 | **STILL OPEN.** No dashboard in either tier. |
| AD-015 (per-field entry) built | — | **NOT BUILT.** Only `ManualCompletionController`; no per-field edit path, no `overridden` provenance in schema or code. |

### The three that misdirected work

1. **The bank domain.** PROJECT_PLAN's hosting decision (2026-09-10) claimed *"Nothing else in the
   plan files now treats the hostname as a blocking bank ask for V1 — checked 2026-09-11."*
   road-to-production §0.1 filed it under **Still needed** and made it **step 1** of the suggested
   order, "the only thing on the critical path with an external lead time". The check had no grep
   behind it.
2. **BL-089.** PROJECT_PLAN Phase 2 entry gates: *"RULED 2026-09-10: V1 BLOCKER … Blocks V1
   outright."* BACKLOG: *"NO LONGER A V1 BLOCKER — product-owner ruling, 2026-09-12."* The later
   ruling never reached the list that exists to be the single pre-go-live check.
3. **PROJECT_PLAN's own AD-002d bullet** still carried R-051 as *"a hard Phase 2 entry gate …
   STILL OPEN"* and R-026 as *"Still 🔴 Live"* after S8-30 closed both on 2026-09-14. **A fourth
   copy of the exact divergence S8-30 was written to end, inside the file that recorded the fix.**
   S8-30 updated the decisions log, RISKS and road-to-production; the narrative prose in the same
   file was not swept.

---

## Rulings taken, and what each changed

### Hosting — we host, the bank decides

**We host on AWS. The bank gets `docs/bank-hosting-specification.md` and decides if and when it
takes hosting in-house. We carry nothing further.**

- New "Hosting ownership" section in PROJECT_PLAN.md; the 2026-09-10 "checked 2026-09-11" claim is
  struck with the reason it was wrong.
- road-map §0.1 dissolved: the domain half is **not a V1 item** (BL-074 stays V2, explicitly "do
  not re-raise"); the app-name/identifier half closed at S8-06; **only the privacy-policy URL
  survives**, and it does not need the bank's domain, so it decoupled from BL-074.
- `docs/bank-hosting-specification.md` was **untracked and unreferenced by any plan file** — a
  third hosting model sitting outside the plan. Now filed, with a paragraph stating it is
  requirements, not a migration plan, and distinguishing it from §3.6's account handover. The two
  had been conflatable.
- **Suggested order rewritten.** Its first three steps were completed or dissolved work (1.1 closed
  2026-09-07, 2.1 done 2026-09-08, domain off the path); only step 4 had been maintained.

### Core banking — stubbed, and the cutover is the bank's call

BL-089's entry-gate row inverted. The exposures stay recorded on BL-089 itself (integration
unproven through V1, seeded accounts only so V1 is a closed list, seeder-assured account linkage).
Marked **"must not be re-filed as a gate"**, since it has now been re-filed once.

### AD-015 narrowed — customer-entered fields only

This is a **new product-owner decision that narrows a closed AD**, so it was recorded as an
amendment to the AD-015 row rather than applied silently to operator.md.

Maps cleanly onto the existing provenance matrix, so the scope is exact rather than descriptive:

- **Editable: the 36 fields whose Source is S3** in `docs/journeys/field-provenance.md`.
- **Never editable: 6 Civil Registry fields** (full name Arabic, full name English, national
  number, mother's name, sex, date of birth), **7 Uqudo fields** (nationality, birth city, document
  number, issue date, place of issue, expiry date, issuing country), and the system form date.

Three consequences that reverse text written on 2026-09-13:

1. **R-054's bound is RESTORED.** Its own text recorded the loss: *"it cannot invent a Uqudo scan or
   a Civil Registry result. That bound is precisely what every-field editability removes."*
   Narrowing puts it back. **AD-018 accepted less than it thought it was accepting** — it is not
   re-opened, but R-054 is one control stronger than when it was accepted. Amended in RISKS.md.
2. **BL-135's `overridden` rendering changes meaning** — it was designed for identity-field edits
   that can no longer happen, so it must render divergence between an edited S3 field and its own
   prior value, not divergence from the registry.
3. **BL-135's open question is answered** — *"which fields are editable and which never are (a
   registry-supplied national number?)"* → no. Scope drops from ~40 fields to 36.

**Raised, not settled:** field 3 (customer number) is S3 but **is** the Stage 1a account number, so
editing it re-keys the profile to a different bank account. Flagged on BL-135 as a decision the
build must take explicitly rather than inherit from the S3 classification.

operator.md's *What operators cannot do* list: bullet 1 struck (with the reason the provenance
model is preserved, not broken, and a note that it still describes the running system until BL-135
ships); **bullet 2 stands and is now deliberate rather than accidental**. The Provenance section
gained the derived-flag rule.

### iOS — dropped completely

Every live statement dispositioned rather than parked. PROJECT_PLAN's platform line (which read
"iOS + Android **(fixed)**"), the iOS 15.0/Xcode 26 pins, module map, OQ-003, OQ-009, the AD-003
prerequisite entry, and the AD-003 bullet's *"deliberately left open and stale"* sentence — which
was itself stale, two of its three rows having been closed on 2026-09-14. CLAUDE.md:28 had
contradicted CLAUDE.md:80 **in the same file**. road-map's release-scope table said "not yet
decided" while §5.1 of the same page said closed. **BL-088 closed** — but only part (1); part (2),
the three-identity-tokens communications point, is Android-and-SMS and survives the closure.

`docs/components/*.md` iOS evidence columns deliberately untouched: they are dated records of
observations, and rewriting them would make a report claim a check it never made.

### journey-open-items — re-assessed against code

"Only three items remain" → two. Item 2 (operator auth) had been closed for twelve days when the
file was last edited. Items 1 and 3 confirmed open at source and given their evidence inline.

---

## Register hygiene

- **R-055 never existed.** BL-135 pointed at it. It was filed at S8-20, caught in review as a
  duplicate of R-054, and withdrawn before commit — the row kept pointing at the phantom.
  Corrected to R-054.
- **R-094 never existed.** PROJECT_PLAN's V1-scope table cited "R-094's non-Sudanese numbers".
  That is **BL-094**. Corrected.
- **8 rotted `customer.md:NNN` citations**, found by resolving all 33 line references against the
  current file. `:1139-1140` (cited 4×) landed on the approved/rejected status table; `:1224-1226`
  (2×) on the Stage 13 heading; `l.1137` on a table separator; `l.1162` on a blank line; `:1005` on
  a Stage 10 Back bullet. **All replaced with section names**, per CLAUDE.md's own "name sections,
  not whole documents" rule — re-pinning numbers would only rot again.
- **EXECUTION_PLAN status markers.** 13 Sprint-8 rows used **🟢**, which is not in the file's own
  legend; normalised to ✅. 3 rows (S8-12/13/14) had prose where the marker goes — moved to the
  notes cell and given ⚠️/⬜/✅. **S8-01 was stuck 🔵 In Progress** although S8-02 closed the item
  and road-map §1.1 has been CLOSED since 2026-09-07.
- **R-042** re-based on the surviving half (BL-086's flag-level disable), not the fixed half.
- **R-052 retired** — closure condition met and unnoticed.
- **R-014 / road-map §0.3 reconciled** — RISKS had it accepted-for-V1/V2-scope, road-map had it
  under "Still needed". RISKS position adopted.
- **road-map §2.3** "needs 0.4 first" → **0.1**. 0.4 is the closed SMS password rotation.
- **road-map §0.7** — "one consequence to decide separately" was decided on 2026-09-11 (BL-083:
  national ID stays offered, rollout gated). Struck.

---

## What was deliberately not done

- **No code changed.** Every finding that implies a build (BL-086's flag disable, BL-135, R-037's
  scheduling, the keystore and AAB) stays filed, not fixed.
- **No open architecture decision settled in passing.** AD-015's narrowing is a product-owner
  ruling taken explicitly in session and recorded as an amendment; AD-011 was left open and
  untouched.
- **Session reports and component cards not rewritten.** They are dated evidence.

---

## Integrity check

All four table files re-parsed after editing: **EXECUTION_PLAN 114 rows, BACKLOG 144, RISKS 56,
PROJECT_PLAN 35 — 0 malformed.** Citation sweep re-run: **0 references resolving beyond EOF**, and
every previously-broken target now names a section. Status-marker census re-run: every `S<n>-<nn>`
row carries a legend marker.

---

## `@agent-reviewer` pass — findings and dispositions

Run against the staged diff before commit, per CLAUDE.md. It verified every source claim
independently (all confirmed) and returned 8 fixes plus 6 notes. **Every one was applied.** The
three that mattered:

| Finding | Disposition |
|---|---|
| **Directory counts wrong** — "backoffice/src holds 9 directories" counted top-level *entries*, 4 of which are files; `mobile/lib/core` is 17 subdirs not 18; "102 Dart files" is all of `mobile/lib`, not `core` (73). Claim was labelled "verified in the tree". | **FIXED in 4 places** (CLAUDE.md, PROJECT_PLAN, EXECUTION_PLAN, this report). A miscount inside a correction is worse than the staleness it replaced. |
| **R-052 retired against a superseded condition.** The cell had been narrowed twice; the operative closure condition is the later one, naming two residuals — (i) the uncoded arm, tracked nowhere else, and (ii) BL-051, still open. | **REVERTED to 🟡 Watching.** The consumption half is recorded as discharged, both residuals named. Retiring it would have silently dropped residual (i). |
| **AD-015 and R-054 still carried unstruck prose reversing the narrowing** — AD-015's tail said "enter every field … whose stated bound this decision removes"; R-054's *Impact* cell said "**THAT BOUND IS GONE**" in bold, with the amendment only in the *Mitigation* cell, so a reader of the Impact column got the opposite answer. | **Both struck and annotated.** This was the same failure mode the session was convened to fix, committed by the fix itself. |

The other five: **BL-074** asserted the V1 framing the hosting ruling removes — a *second*
counter-example to PROJECT_PLAN's "nothing else treats the hostname as a blocking bank ask",
after road-map §0.1, now annotated V2 with the exposure restated; **R-008** kept a live
macOS/iPhone leg while this session claimed every iOS row was dispositioned; **PROJECT_PLAN's
settled-facts prose** still required a "physical iPhone" as test equipment; **operator.md:257**
carried the same "only two operator actions that write" sentence corrected in the module map;
**road-map §2.3** cited `build.gradle.kts:45-48` for the debug signing config three lines above
the corrected `:63-65`.

Notes applied: CLAUDE.md:33 qualified with "customer-entered only"; the "only item that would
stop a launch" line distinguished data-protection grounds from 2.3's Play-submission
prerequisites; §0.1's struck bullet restored to keep-and-strike rather than elide.

**Three field-classification gaps the S3/S1/S2 split does not resolve**, added to BL-135 rather
than decided: field 43 (document type) is S3 but "S3 chooses, **S2 confirms**", so an edit could
contradict a completed scan; field 23 (birth city) is S2 and therefore never-editable, but its
own cell is "S2 `placeOfBirth`, **else free text**", leaving it permanently unfillable when
Uqudo supplied nothing; and rows 51-54 are artifacts, in neither list.

**One consequence of the narrowing, recorded because nobody had stated it.** A manually-completed
profile has no scan and no registry lookup, so the 6 S1 and 7 S2 fields — name, national number,
date of birth, sex, document number — can never be populated on it **by anyone**. That is the
ruling working as intended: an operator cannot assert an identity. But it means manual completion
yields a profile with no verified identity data, and the back office must render that as an
absence, not an empty field. Filed on BL-135.

---

## Commit proof

Captured AFTER the push, per CLAUDE.md.

```
$ git diff --stat HEAD~1 HEAD
BACKLOG.md                                         |  16 +-
 CLAUDE.md                                          |  16 +-
 EXECUTION_PLAN.md                                  |  47 +--
 PROJECT_PLAN.md                                    | 122 ++++--
 RISKS.md                                           |   8 +-
 docs/bank-hosting-specification.md                 | 443 +++++++++++++++++++++
 docs/journeys/journey-open-items.md                |  27 +-
 docs/journeys/operator.md                          |  29 +-
 docs/road-to-production.md                         | 164 +++++---
 .../2026-09-14-s8-31-plan-reconciliation.md        | 236 +++++++++++
 10 files changed, 979 insertions(+), 129 deletions(-)
```

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   988c7b3..06c08cf  main -> main

$ git log -1
06c08cf 2026-09-14 S8-31: reconcile the plan files against five product-owner rulings

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
