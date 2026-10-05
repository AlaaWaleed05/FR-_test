# S8-21 — The back office's design pass, and the map closed

2026-09-13. Design and documentation. No application code changed. Ticket 08 resolved, which makes
the wayfinder map **ten of ten**.

---

## The walk came first, because the ticket insisted on it

Ticket 08's own instruction: "Before redesigning anything, establish what is actually wrong — walk
it and write down the specific failures, rather than restyling on instinct." So the running back
office was walked with a real browser and a real session before a pixel was drawn.

| Finding | Evidence |
|---|---|
| Login carries **no brand at all** | default Ant blue `#1677ff`, white card on white, no logo, no bank name; navy appears only after sign-in |
| **No Arabic face bundled** | computed `font-family: system-ui, "Segoe UI", Tahoma, Arial` on every screen — `BL-134` |
| **No page ground** | `body` background is `rgba(0,0,0,0)`, fully transparent |
| Detail page is **four screens tall** | 3,556 px at 1440×900 |
| List uses **a third of the screen** | page size 10; six filters in one row, labelled only by placeholder |
| List **fetches twice on load** | two identical `200`s; matches `set-state-in-effect` at `ProfileListPage.tsx:98` |

**And what is not broken, checked rather than assumed:** RTL is correct throughout — `dir=rtl`,
sidebar right, columns right-to-left — with no horizontal overflow anywhere. Ticket 08's question 4
expected RTL trouble in Ant Design and there is none.

One first impression was **wrong and worth recording as a method note**: an early screenshot showed
the profile list empty, reading «لا توجد بيانات». That was my capture firing before the fetch
resolved, not a defect — a follow-up run measured rows at 500 ms (0), 1500 ms (10) and 5000 ms (10).
It would have been an easy and embarrassing thing to report. The re-check is what found the genuine
double-fetch underneath it.

## The design

Eight artboards on two pages, sources at `Design_3/backoffice/` beside the mobile app's own design
files. The standalone canvas is generated from them, so every later edit re-seeds from the sources.

**Screens** — profile detail (home base), queue, login, admin user management, and the reject and
manual-completion modals. **Print** — A4 pages one and two, plus the attributed variant.

Nothing here was invented. `Design_3/tokens/` already fixes navy `#0b1c47`, steel `#5980a6`, ground
`#f2f2f3`, and IBM Plex Sans Arabic — and carries two rules that contradict Ant Design's defaults,
which the design follows in preference to the framework: **`--radius: 0`** ("square corners are
deliberate") and **no shadows at all** ("depth comes from the steel glow, the pearl ring and the
dune curve").

**The mark is the circular pearl**, not the square master — a product-owner correction mid-pass, and
a better fit than I had it: `spacing.css` says "the only radius in the system is the pearl", so the
one round thing on screen is now the one the system sanctions. It also removes a duplication, since
the square master has the bank's name baked into the artwork and the print header states that name
anyway. Taken from the 1318 px source down to 192 px at 9.8 KB, alpha intact, because it sits on
navy in the app bar and on white on paper.

The operator's day settled the structure: **low volume, minutes each** (product owner), so the
detail page is home base with the comparison and the decision both above the fold, and the list is
only a queue.

## Two defects the design pass found in its own output

Both were caught by rendering the artboards in a browser before handing them over, which is the
only reason they are not in the deliverable.

**A leading `+` on a phone number is dragged to the wrong end by RTL** — an E.164 number rendered
with its leading `+` at the far end. This is the same bug `ProfileDetailPage` already fixed with `<bdi>`, recurring in
the PRINT layout where there is no React helper to lean on. It is precisely what ticket 08's
question 4 asked about, and it is a live warning for whoever writes the FOP renderer.

**The print menu was anchored to the wrong side.** `inset-inline-start` put it over the fields
rather than above its own button, which in RTL sits at the left end of the action bar.

A third constraint shaped the print design but was **not** found here: `app.artifact_ref`'s
`UNIQUE (cycle_id, kind)`, which forces a printed form to be profile-keyed with `cycle_id` NULL or a
profile's SECOND print violates the constraint. That was found at review in the PREVIOUS session and
already sits on ticket 05 decision 2; this session only designed against it.

## The canvas was not saved online

The publish was refused by this session's auto-mode permission classifier — not by the design, and
not by the user. Per the design skill a refusal is final for the session, so it was not retried.
The canvas was handed over as a file instead (`C:\\Users\\DELL\\Documents\\bayanati-back-office.html`,
which opens in a browser as a view-and-export canvas), and the sources committed to the repo. Worth
recording so a later session does not assume a link exists.

## Gates

No tier was touched. This session changed `.scratch/` tickets, `Design_3/backoffice/` (design
sources), `EXECUTION_PLAN.md` and this report — no `backend/`, `backoffice/` or `mobile/` source —
so no tier gate applies.

`Design_3/backoffice/` is design material, not application code: eight `.dc.html` artboards,
`canvas.json` and one 9.8 KB PNG, ~99 KB in total, in the directory where this project's design
files already live.

---

## Review

`@agent-reviewer` against the diff. **One blocker, five should-fixes, four notes.** The blocker is
the reason this report exists in this shape, and it is worth stating plainly rather than burying.

### BLOCKER — I copied a real identity into the design files

The artboards' sample profile was `FRU-000000004` / account `0000000200` — **the profile from the
S5-08 guided-live device run**, whose `doc_front` that report records as a "real passport capture"
against the project's real Uqudo tenant. With it came the real Arabic and English names, national
number, passport number and date of birth.

Four things made it identifiable as real rather than synthetic, and they are worth keeping because
they are how the next one gets caught: the reference number and account tie to a named live run; only
THAT row carried a full identity set while every other invented queue row carried a bare name; the
repo's genuine synthetic fixtures look nothing like it (`NID-1`…`NID-9`, `000-0000-0001`); and the
S5-08 report itself had deliberately printed the account and reference but NO name, national number
or document number — so this diff would have been the first thing to bring them into the repository.

**The failure was mine and it was not ignorance of the rule.** Earlier in the same session I found
this PII, said so, and deleted the screenshots — then used the same values as "sample data" a few
hours later. Knowing a rule at the moment you trip over it is not the same as applying it when you
are busy doing something else.

Fixed: every value replaced with an invented identity, and a residual sweep for eleven markers of
the real one (both spellings of the name, the national number, the passport number, the email, the
phone, the reference, the account, the DOB, the employer string) returns **NONE** across all eight
artboards. One hit was a false positive on my own over-broad sweep: an unrelated invented queue row
happened to share one very common Sudanese given name with the real identity. Renamed anyway, so the
check reads clean rather than reading "clean apart from one I decided was fine". The same real phone number had also reached THIS report, illustrating the bidi bug; it
is now described rather than quoted.

The logo was checked and is clean: 192×192, palette PNG, chunks `IHDR/PLTE/tRNS/IDAT/IEND` only — no
`tEXt`, no `eXIf`, no photograph. The one-time password shown on the admin artboard is invented, not
leaked: `CreateOperatorAccountRunner` emits unpadded Base64URL, whose possible lengths are
16/18/19/20/22, and the sample is 17 characters — a length the real generator cannot produce.

### Four more the artboards would have taught a builder wrong

- **The reject modal invented a rejection reason the system forbids.** It offered «المستند منتهي
  الصلاحية», but the seeded list is `REJ-01`…`REJ-07` (`V0019`) and contains no expiry reason —
  and field-provenance field 47 says expiry is "recorded but never checked: expired documents are
  accepted". Now uses three seeded labels verbatim, which is also what CLAUDE.md's
  never-hardcode-reference-lists rule requires.
- **Two invented print fields.** «نوع الحساب» is not among the 54, and field 20 is ONE multi-select
  income source, not a field plus a second "additional source". Both dropped.
- **Renamed fields.** «الأصل» for ethnicity was a third name for a field the form calls «الجنس» and
  the back office calls «القومية» — the one field-provenance flags specifically because a literal
  reading gets it wrong. That and nine other labels now come from field-provenance.
- **Three departures from settled decisions** are now recorded ON ticket 08 rather than left implicit:
  the print section order, the dual-source rule being demonstrated twice rather than sixteen times
  (including a genuine substitution — the seven home-address rows shown as one composite row, which
  belongs back on ticket 04 if adopted), and the absence of a viewer variant of the detail screen.

### Declined, with reason

The reviewer suggested the artboards' `<script src="./support.js">` should point one level up so they
re-render from their own directory. **Not changed.** The design format requires that line verbatim —
the editor replaces it with an inline runtime at render time — so editing it would break seeding to
fix a preview convenience.

`docs/bank-hosting-specification.md` remains untracked and is **deliberately not in this commit**: it
belongs to the deferred hosting effort, not this one, and R-053 is specifically about that content
entering a commit by default.

## Commit proof

Captured AFTER the push.

```
$ git status -sb
## main...origin/main
?? docs/bank-hosting-specification.md

$ git log --oneline -1 origin/main
e611b7c S8-21: the back office's design pass — ticket 08 resolved, map closed
```

`## main...origin/main` carries no "ahead" marker, which is the proof (S8-12).
`docs/bank-hosting-specification.md` is untracked and deliberately excluded — see the Review.

**A note on how this session ended, because it nearly ended badly.** The machine lost power while
the reviewer was running and before any of this was committed. Nothing was lost — every artboard
still closed cleanly and `canvas.json` still parsed — but the review had never reported, and the
blocker above was still sitting in the working tree. Had the product owner not asked whether the
work had finished properly, a diff carrying a real passport holder's name, national number and
document number would have been committed on the assumption that it had.

This report's own proof commit follows it, the same shape S8-18 through S8-20 used.
