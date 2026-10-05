# 2026-09-14 — plan-file cleanup

Reduce the four plan files read at every session start. They totalled 841,790 bytes across
1,425 rows, and roughly three quarters of that was the history of items already settled.

Two convention changes were put to the product owner before any editing, because both are
stated norms with stated rationale and overriding them is not a session's call. Both were
approved: closed items collapse to a one-line tombstone, and the strike-through-rather-than-delete
convention is retired.

## Result

| File | Before | After | Cut | Commit |
|---|---|---|---|---|
| BACKLOG.md | 362,368 B | 148,106 B | 59% | `b5f4835` |
| EXECUTION_PLAN.md | 241,680 B | 26,004 B | 89% | `b69e499` |
| RISKS.md | 104,372 B | 41,500 B | 60% | `4169696` |
| PROJECT_PLAN.md | 133,370 B | 69,939 B | 48% | `2944a03` |
| **Total** | **841,790 B** | **285,549 B** | **66%** | |

Row counts: BACKLOG 145 ids (89 open, 56 tombstoned), RISKS 54 (39 live or accepted, 15
tombstoned), EXECUTION_PLAN 108 tasks (100 finished, 8 not), PROJECT_PLAN 26 AD and 26 OQ ids.

## The four rules

1. A closed item keeps one line in a `## Closed` table at the foot of its file: id,
   one-sentence subject, disposition with date and slice, and the session report holding
   the detail.
2. A finished EXECUTION_PLAN row loses its Notes essay, keeping id, task, status and
   report. The Notes column was re-telling the session report one abstraction worse.
3. No strike-through archaeology and no "original row follows" appendices. A row states
   what is true now; git holds every previous version.
4. Rows that stay keep every fact, file:line citation, test name and decision.

Rule 1 was checked before it was applied, not after: every closed BACKLOG id and every
closed risk was confirmed present in at least one of the 140 reports under
`docs/sessions/`, so no tombstone points at nothing.

## Findings — rows that were asserting things the code had stopped doing

These are the reason the cleanup was worth more than its byte count. Each was found by
reading a row and checking it, and each is verified at source rather than inferred.

**BL-021, BL-022 and BL-028 were filed as "backend half closed, mobile half open". All
three mobile halves had shipped.** BL-021: `AccountContinuation` carries `blocked`
(`mobile/lib/core/entry/entry_models.dart:15`), `dio_entry_api.dart:38` decodes
`blockedUntil`, and both surfaces route to `/blocked` (`entry_repository.dart:482`,
`account_entry_screen.dart:108`). BL-022: `dio_data_entry_api.dart:166` posts
`/api/v1/salary-certificate`, with `salaryCertificateUploadedAt` persisted. BL-028's own
text already said "both halves of BL-028 now exist" and the row stayed open anyway. All
three are tombstoned.

**BL-089's stub-account inventory was two sessions stale.** It read 30 seeded with 23 free.
S8-32 seeded 100 more on 2026-09-14, so `backend/src/main/resources/application.properties`
holds 132, of which `0000002001`-`0000002100` are new and unused.

**BL-086 and BL-092 were not rendering as table rows at all.** An unescaped `||` — from a
Dart null-check in one and a SQL concat in the other — split them into 7 and 5 cells.
Both are rewritten without the literal.

**BL-091 was decided and built on all three of its comments** and still sat open.

## Findings — carried from the review passes

`@agent-reviewer` ran twice: once against BACKLOG (six findings) and once against the other
three files (one real loss plus four notes). Two passes rather than four is a deliberate
proportion call for documentation-only diffs, and is recorded here rather than left to look
like a skipped gate.

**BL-088 — the one that would have cost something.** Its own text said "Part (2) SURVIVES
this closure and is NOT closed": one journey shows the customer three identity tokens, the
Latin `SFB` as SMS sender id, «البنك السوداني الفرنسي» in the body and «بياناتي» on the
launcher, which belongs in the V1 release brief so a bank reviewer does not meet it cold.
My tombstone dropped it, and the reviewer confirmed by grep that it appears in no other
plan file. BL-088 is now open, scoped to that half; the iOS half stays closed not
applicable.

**R-025 — the same shape, in RISKS.** Its retirement carried a second disclosed residual:
the Stage 6 salary certificate is a plain file under `getApplicationSupportDirectory()`,
outside the encrypted database, so "every table is encrypted" never covered it and it sits
unencrypted for the life of an in-progress session. Verified still true at
`mobile/lib/core/entry/entry_repository.dart:582-586`. It lived in no other plan row, and
the report my tombstone first named predates it by a day, so it would have survived only as
a source comment — the state the original row existed to avoid. The tombstone now carries
it and names both reports.

**BL-106 and BL-101 — obligations that had nowhere to land.** BL-106's close owed three
things when BL-005 is approved: the Arabic label per REJ-01..07, a reason field on
`JourneyPointer` (none exists), and a decision between the operator's internal label and the
customer-facing SMS text. BL-005 covered only the first. The other two now ride with it.
BL-101 owed a 5-inch keyboard-overlap check on the email edit control; it now rides with
BL-111, which already owes a device check on the same screen.

**BL-023 and BL-089 — rule 3 failures I carried over rather than introduced.** BL-023 said
`OperatorIdentityFilter:53` filters admin out and must change or AD-013 does not hold;
S8-28 removed that filter, and `accessLevelOf` now reads `case OPERATOR, ADMIN`. Deleted.
BL-089 told the reader to extend a spent account block when `application.properties:76-79`
was amended on 2026-09-14 to say the opposite, because a partly-spent range hands a tester
dead numbers with nothing on screen to distinguish them. Corrected, and the product owner's
reseed threshold of 10 restored.

**BL-104** keeps the note that `channel_verification_screen.dart:36-38` still frames the
defect as a process-restart artefact, which is the narrower half and is wrong.

**AD-002e — a decision this cleanup reversed.** AD-013 asked that AD-002e be left unedited
so its reasoning and its 403-for-admin proof stayed readable as what was true until that
day. The cleanup compressed it. That is a reversal, and the row now says so and points at
git and the two reports, rather than leaving the reversal silent.

## Deliberate moves, stated because they are judgement rather than record

**R-053 and R-041 out of "Delivery notes — outside the deliverable".** That section holds
bank commercial matters. R-053 is about our own `git add -A` session-end staging and was
never one. R-041 is about silent partial delivery by network on the Airtel gateway, and V1
ships SMS-only on that gateway, so it is a failure of what we built. R-043, which is
genuinely about per-message commercial terms, stayed. The reviewer was asked to challenge
both moves and endorsed them.

**AD-002 closed as a dangling id.** It is cited in 15 files and has never been a row: an
umbrella that split into lettered parts. PROJECT_PLAN now lists the parts so each resolves.
This was dangling at baseline, not caused here.

**BL-055 reworded on its own instruction.** The row asked to be changed from "zero
`I/flutter` lines" to "no application-level Flutter logging", because the stronger claim was
falsifiable and had been falsified by three Impeller notices in the walk capture. Done
rather than carried forward.

**BL-069 marked ready for CLAUDE.md.** The row set itself a condition — fold the device-run
capture guidance into CLAUDE.md if a third run confirms it — and the third run did. Noted
for the CLAUDE.md pass, not actioned here.

## Gates

No tier was touched. No Java, Dart or TypeScript changed, so the mobile, backend and
backoffice test and analyze gates do not apply. Stating that rather than skipping it
silently.

The gate that does apply is id integrity: every `BL-###`, `R-###`, `AD-nnn`, `OQ-###` and
`S#-##` cited anywhere in the repository must still resolve to a row. Run before and after,
by `idcheck.py`, over every `.md`, `.java`, `.dart`, `.ts`, `.tsx`, `.sql`, `.yml`, `.sh`
and `.xml` file outside build output.

Before:

```
resolvable ids in plan files: 357
distinct ids cited repo-wide: 366
CITED BUT UNRESOLVABLE: 9
  AD-002   cited in 15 file(s)
  AD-012   cited in 28 file(s)
  OQ-027   cited in 1 file(s)
  R-055    cited in 4 file(s): BACKLOG.md, EXECUTION_PLAN.md, + 2 session reports
  R-094    cited in 2 file(s): EXECUTION_PLAN.md, + 1 session report
  S9-01 S9-02 S9-03 S9-04   cited in 1 file each
```

After:

```
resolvable ids in plan files: 357
distinct ids cited repo-wide: 366
CITED BUT UNRESOLVABLE: 9
  AD-002   cited in 15 file(s)
  AD-012   cited in 28 file(s)
  OQ-027   cited in 1 file(s)
  R-055    cited in 2 file(s): both session reports
  R-094    cited in 1 file(s): one session report
  S9-01 S9-02 S9-03 S9-04   cited in 1 file each
```

357 before and after. The unresolvable set is unchanged in membership and improved in
location: `R-055` and `R-094` are no longer cited by any live plan file, only by historical
session reports. Neither was ever a row. `R-055` was filed, caught in review as a duplicate
of R-054 and withdrawn before commit, which BACKLOG's own text explained; `R-094` has no
origin I could find and looks like a typo. Both are left for the product owner to confirm
rather than invented into existence.

One regression caught by re-running the gate rather than by review: `AD-002g` stopped
resolving after the PROJECT_PLAN rewrite, because I had folded it into prose where the old
file had it as a bullet. The umbrella note is now a list, and it resolves again.

## Still open, and what a decision would close

Compiled while reading every row, and handed to the product owner separately. Ready to
close today on material already on file: AD-011 (two screens or one, research and a
recommendation exist), OQ-011 (no subject since 2026-09-06), OQ-008, OQ-015 and OQ-001's
routing to the bank. One ruling closing several: the V1 messaging channel set, where
BL-086, R-042, BL-094 and OQ-016's residual are entangled. Needing a document the project
holds: OQ-026, which unblocks BL-063. Scope calls: OQ-012, BL-005 and BL-071 (unreviewed
Arabic customer copy), BL-127 (no monitoring at all), BL-082, BL-141, BL-067 and BL-126.

None was settled here.

## Commit proof

Captured after the push, per CLAUDE.md.

```
To https://github.com/Osmantou/Fr_user_update
   b5f4835..f4030d9  main -> main
=== PROOF, AFTER PUSH ===
## main...origin/main
f4030d9 S8-34: the plan-file cleanup session report
2944a03 Plan-file cleanup: PROJECT_PLAN.md, 133 KB to 70 KB
4169696 Plan-file cleanup: RISKS.md, 104 KB to 42 KB
b69e499 Plan-file cleanup: EXECUTION_PLAN.md, 242 KB to 26 KB
b5f4835 Plan-file cleanup: BACKLOG.md, 362 KB to 147 KB
```

`## main...origin/main` with no ahead or behind marker: up to date with `origin/main`.
The S8-34 execution-plan row and this proof block follow in one further commit, whose own
push is the last line of this session.
