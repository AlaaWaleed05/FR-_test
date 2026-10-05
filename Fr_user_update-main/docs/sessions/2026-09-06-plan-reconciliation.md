# Plan-file reconciliation — applying the pre-production audit's §1

Documentation only. No feature code, no test code, no build file touched — so **no gates
were run and none are owed**. Source of every correction:
`docs/sessions/2026-09-06-pre-production-audit.md` §1; each fix was already established
there against source with file/line, and none was re-investigated here.

| | |
|---|---|
| Repo | Fr_user_update · branch `main` · pushed |
| Date | 2026-09-06 |
| Files | `CLAUDE.md`, `BACKLOG.md`, `EXECUTION_PLAN.md`, `RISKS.md`, this report |
| Not actioned | T-9 (deployment sprint) — product-owner decision, see below |

---

## What was corrected

| T | Correction | Where |
|---|---|---|
| T-8 | The uncommitted 11-line BL-039 budget-rule block is **committed** — unchanged, as authored. It was the only record of the 5/10 limits and the 20-mint lifetime caps outside a session report, and was one `git checkout` from loss. | `CLAUDE.md` Hard rules |
| T-1 | Numbering collision resolved. BL-056/BL-057 were each filed twice; the **S5-08 device-run pair renumbered to BL-069 / BL-070**, the S5-13 pair kept its numbers. | `BACKLOG.md` |
| T-2 | **BL-061 closed as NOT REPRODUCIBLE against source — explicitly not "fixed".** | `BACKLOG.md` |
| T-3 | BL-061's nine-line, three-column row rebuilt as a single four-column row. | `BACKLOG.md` |
| T-4 | Three blank lines removed; the file is one continuous table again. | `BACKLOG.md` |
| T-7 | S4-05 and S4-06 **moved** from the Sprint 5 section into Sprint 4. Not renumbered. | `EXECUTION_PLAN.md` |
| T-5 | The BL-039 build (`a041a19` Slice A, `959fa07` Slice B) had no task row; filed as **S5-15**, ✅. | `EXECUTION_PLAN.md` |
| T-6 | **V0065** was named in no plan file; now named in S5-15 and beside V0064 in BL-039's row. | both |
| T-10 | R-002's ~176 Arabic Uqudo-SDK override strings filed as **BL-071**, deferred post-demo. | `BACKLOG.md`, `RISKS.md` |

### T-1 — why the S5-08 pair moved and the S5-13 pair did not

Renumbering is normally forbidden (IDs are permanent); a genuine collision is the one case
it is correct, and only the un-cross-referenced side may move. Confirmed before editing,
by grep across all `*.md`: the only references to BL-056/BL-057 outside the four colliding
rows are `BACKLOG.md`'s BL-058 ("BL-057's read supplies the channels instead"), `RISKS.md`
R-052 ("(BL-056) … resume read (BL-057)") and the S5-13 session report — **all three mean
the S5-13 pair**. Nothing anywhere cites the device-run pair, so renumbering it broke no
reference. BL-051's cross-reference, which the audit cited, is not literal text in its row;
the other three are, and they point the same way.

### T-2 — closed, not fixed

BL-061 claimed `check_coverage.dart` exits 0 when `flutter test` fails. The script does the
opposite at [check_coverage.dart:50-55](mobile/tool/check_coverage.dart#L50-L55) and always
has: `git log -S'exit(testResult.exitCode)'` on that file returns exactly one commit,
`47d69f6` (2026-08-19, S1-07), its first version, and `git diff 47d69f6 HEAD` on the file
touches no `exit` line. The closed row records that provenance and states plainly that
nothing was fixed, because nothing was broken — an item closed as "fixed" here would
manufacture a false repair in the record. It was a planner filing from an unverified
report claim, never checked against the file it named.

## Verification after the edits

Both checks scripted against the files, not read by eye.

- `BACKLOG.md` — **71 rows, 71 unique IDs, BL-001…BL-071, no duplicates and no gaps.** The
  collision is resolved without opening one: BL-069/BL-070 filled the two numbers after the
  old high-water mark BL-068, and BL-071 is the next free number, taken by T-10.
- `BACKLOG.md` renders as **one table**: the only blank lines left in the file are line 2
  (under the title) and the trailing newline. Every `| BL-` row is one physical line.
  Four rows carry extra `|` characters inside prose (BL-033, BL-034, BL-035, BL-039) —
  pre-existing, untouched by this session, and not what T-3 was about.
- `EXECUTION_PLAN.md` — **62 rows, no duplicate IDs, zero misfiled rows**: every `S<n>-`
  row now sits under `## Sprint <n>`. Per sprint: 13 / 10 / 14 / 6 / 15 / 4.

## Deliberately not done

- **T-9 — no deployment/Phase-2 sprint was seeded.** Product-owner decision: the sprint is
  added when the hosting phase actually begins. T-9 stays noted and unactioned;
  `EXECUTION_PLAN.md` still has zero open tasks, which remains an accurate picture rather
  than a corrected one.
- **No code change of any kind**, including the tempting T-2-shaped ones. This was
  plan-file reconciliation; the audit's code-level findings stay filed as backlog rows.
- **No new backlog or risk items** beyond the two the audit named as needing a home
  (T-5's task row, T-10's BL). Nothing was re-investigated and no new issue was hunted.

One edit beyond the literal list, and why: `RISKS.md` R-002 said the override-string task
was "not yet filed", which T-10 made false the moment BL-071 existed. The clause now names
BL-071. That is the same correction, not a new item — leaving it would have replaced one
untracked truth with one stale falsehood.
