# 2026-09-05 — Filing the identity-scan sweep + AD-008

Plan-file edits only. **No code was written or changed, so no gates apply** — nothing was
touched that `./mvnw verify`, `fvm flutter test` or `npm run test` measures. The findings
themselves were produced by the earlier investigation session and are already committed at
`docs/sessions/2026-09-05-identity-scan-sweep.md`; this session turns them into tracked
items and records the sequence.

## Numbering, confirmed against the on-disk state

Read fresh at session start: PROJECT_PLAN.md, BACKLOG.md, RISKS.md, EXECUTION_PLAN.md.

- **AD-008 was free.** The decisions log held AD-001, AD-002 (with lettered variants a–g),
  AD-003, AD-004, AD-005, AD-006, AD-007. No renumbering needed.
- **BL-041 was free.** BACKLOG.md held BL-001 … BL-040 contiguously, so the sweep's expected
  BL-041+ block was available exactly as anticipated. No id reused, retired or otherwise.
- R-052 was the highest risk id; no new risk was filed, so it stays the highest.

## What was filed

**PROJECT_PLAN.md — AD-008**, appended as the decisions log's last row. The approved text is
a prose block with headings and the log is a five-column table, so the wording is verbatim
but distributed across the columns: *Decision* carries Status / Closes / Decision / Out of
scope / Build; *Rationale* carries the Context paragraphs including the rejected in-journey
OTP alternative; *Source* cites the sweep report and this one. No sentence was altered,
added or dropped in the mapping. AD-008 was never listed under "Open architecture decisions"
— it is opened and closed on the same day — so no bullet was added there; the log row is the
whole record.

**BACKLOG.md — BL-041 … BL-046**, appended in the order the sweep's §1 table lists the
findings' triage, each sourced from the sweep's own text rather than re-derived:

| New | Sweep | Substance |
|---|---|---|
| BL-041 | F-1 | AD-008's build half — supersede the identity artifacts on a device-less re-entry, cap the resume at identity-type, reset the per-type scan budget. Cross-references AD-008 and subsumes **BL-018**'s open question (nothing writes `resume_stage`), which the resume-point cap has to answer either way. |
| BL-042 | F-2 | Terminality guard on Stage 9's `accept`/`wrong-number`/`wrong-details`. **Before S5-07.** |
| BL-043 | F-4 | `reportWrongNumber` must re-check `blocked_scan`/`awaiting_registry`. Same slice as BL-042. |
| BL-044 | F-5 | Move the already-`ok` retry decision inside the transaction — the concurrent form of BL-038. Same slice, needs a concurrency test. |
| BL-045 | F-3 | Operator view/list resolve the latest cycle by `seq`, ignoring state. Back-office slice. |
| BL-046 | F-6 | Repeated verification failure never surfaces to operators. Back-office slice, with BL-045. |

All six are marked **FIX BEFORE PRODUCTION** and none is demo-blocking, matching the sweep's
triage exactly — no severity was revised in the filing.

Two relationships were written into the rows rather than left implicit, because both would
otherwise read as re-filings of closed items: **BL-044 is not BL-038 reopened** (BL-038's
fix is correct for the sequential case it describes; this is the form it does not reach),
and **BL-043 is not BL-039** (fixing BL-039's missing `canAttempt` check does not touch
`reportWrongNumber`'s path).

**EXECUTION_PLAN.md — S5-07's row**, note appended. **Status left at ⬜ and no row
reordered.** The note records that BL-042 + BL-043 + BL-044 are one backend guard-parity
slice sequenced *before* S5-07, and says why only BL-042 actually changes what the screens
see (`PROFILE_TERMINAL` instead of a 500 or a silent success on a finished profile). It also
records that BL-041, BL-045 and BL-046 are *not* pre-work and follow S5-07, so a future
session does not read "six new items" as six new blockers. Deliberately worded as a
sequencing note, not a new blocker in the S5-09/S5-10/S5-11 sense — those were genuine
preconditions and this is not.

**RISKS.md — R-052**, note appended, **row left open at 🟡 Watching**, no clause of it
narrowed or removed. It records that BL-042 is part of what S5-07 needs a *correct* contract
for, not merely a *distinguishable* one: R-052's own closing condition is that the eight
codes map cleanly onto the five screens, which presupposes each path emitting the right code
in the first place.

## What was deliberately not done

- No BL/R item closed, and no existing row's substance edited — only the two additive notes
  above.
- No EXECUTION_PLAN row status changed, added or reordered.
- BL-018 left open. BL-041 subsumes its question but has not answered it, and closing it on
  a filing session would lose the standing record that `resume_stage` is still written by
  nothing.
- No risk filed for F-1. AD-008 decides it and BL-041 builds it, so a risk row would track a
  gap that already has both an owner and a decision.
