# S8-23 — BL-075's front-end half: the operator contact sheet

**Date:** 2026-09-13 · **Tier:** backoffice (plus comment and plan-file corrections)
**Outcome:** BL-075 is CLOSED. An operator opening a submitted profile now sees the identity images.

At S7-12 a real operator approved a real profile without seeing the passport, the registry
photograph or the liveness capture. S8-22 removed the cause on the server. This removes it on the
screen.

---

## What shipped

| Layer | File |
|---|---|
| Tile set, captions, src, selection (pure) | `backoffice/src/profiles/artifactTiles.ts` |
| The contact sheet | `backoffice/src/profiles/ArtifactContactSheet.tsx` |
| Page integration | `backoffice/src/profiles/ProfileDetailPage.tsx` |
| Deleted | `backoffice/src/profiles/portraitPlaceholder.ts` |

Ticket 02's Variant B, as decided: five uniform tiles, `repeat(auto-fill, minmax(190px, 1fr))`,
click-to-enlarge in one `Image.PreviewGroup`, **no attachments table at all**, caption is the image
NAME rather than metadata. Colours from `Design_3/tokens/` — navy `#0b1c47`, well `#eef2f7`, rule
`rgba(11,28,71,0.14)`, radius 0, no shadows. The artboard's card chrome, heading «المرفقات» and
hint «انقر لتكبير الصورة» are built too; ticket 08's wider redesign (queue strip, sticky action
bar, collapsible groups) and ticket 10's editable fields are not, and were deliberately left alone.

Only the five viewable kinds are linked. The profile-detail listing is **not** filtered server-side,
so it still returns an `artifactRefId` for `doc_back`, `salary_certificate` and both capture frames;
`artifactTiles.VIEWABLE_KINDS` is the whole control on the client, and two tests pin it.

The artifact id stays in the `<img src>` and out of the SPA address bar (ticket 01), so the enlarge
modal is component state and never a route.

---

## A decision taken, not settled in passing

**The five tile captions are ARABIC.** Ticket 02 and ticket 08 both shipped them as English
(`ID Document` / `Document Photo` / `CR` / `Liveness` / `Signature`) and both said, explicitly, that
the language had never been put to the product owner. It was put to them this session and answered:
«وثيقة الهوية» · «صورة الوثيقة» · «السجل المدني» · «إثبات الحياة» · «التوقيع».

The written defence for English was consistency with the backend's system-origin
`ArtifactRefView.label`. That argument does not survive this commit on its own terms: `label` is no
longer rendered on any screen, because the table that showed it is gone. Recorded on ticket 02,
ticket 08 and `map.md`, all three of which carried the open question.

---

## Three defects in the session brief

Found by checking the brief against source before writing code. `@agent-reviewer` ran twice — once
against the brief's claims with no diff to look at, once against the diff.

**1. "Every absence is one 404" is not the whole status map.** A malformed, non-UUID id is **400**,
not 404 — `ProfileImageController.java:96-102`, asserted at `OperatorImageIntegrationTest:168-178`.
Harmless for an `<img>`, but a client that read 4xx as "not reached this stage yet" would be wrong.

**2. "Trim the stale Arabic" understated it.** The brief and BL-075 both asked for the R-046
parenthetical to be trimmed. Read in context, all three of that paragraph's clauses are false the
moment tiles render — "showing real images is out of scope", "where the images are stored and how to
fetch them is undecided", and "the items below are placeholders with a local substitute source".
The whole paragraph and its «الصور الشخصية» divider are gone. BL-075's "trim the parenthetical, or
sooner" was written as advice for a pre-tiles commit and does not survive contact with this one.

**3. The brief's stale-location list was one off in both directions.** `docs/components/persistence.md`
was already corrected at S8-22 and needed nothing. Three locations the brief did not name were
stale and are corrected here: `ProfileDetailPage.tsx`'s file-level comment, and — found by the
review — `EXECUTION_PLAN.md:158`, which said the on-screen Arabic "stays accurate". It did not, and
that sentence contradicted both BACKLOG.md and S8-22's own report.

---

## The defect no ticket predicted

**This page showed the OLDEST artifact of each kind, and would have shown a superseded passport
photograph the moment real bytes loaded.**

`JdbcProfileViewRepository`'s `ARTIFACTS` query ends `ORDER BY ar.created_at` — ascending, no
`DESC`. `app.artifact_ref` is `UNIQUE (cycle_id, kind)`, so a profile with several identity cycles
carries several committed rows of one kind, and the FIRST of them is the superseded scan rather than
the one that replaced it. The page's existing code was
`detail.artifacts.find((a) => a.kind === 'portrait_uqudo')` — first match.

It was invisible while every `src` was a placeholder: the wrong artifact's metadata and the right
one's rendered the same grey silhouette. It stops being invisible on the first re-scanned profile an
operator reviews, and it fails silently — nothing on screen distinguishes a current portrait from a
superseded one, and `ArtifactRefView` carries neither `cycleId` nor a timestamp, so the client has
no second signal to check against. `selectTiles` takes the last match; two tests assert the
superseded id is **absent**, not merely that the current one is present.

**The residual, recorded rather than fixed:** kinds are resolved independently, so a cycle that
committed some kinds and not others can produce a mixed set — a `doc_front` from cycle 2 beside a
`portrait_uqudo` from cycle 1 — which this tier cannot detect, let alone warn about, without a wider
response. Stated in `selectTiles`'s own doc comment.

---

## What a stubbed run does not prove

`portrait_registry` is the 32-byte ASCII string `stub-photograph-not-a-real-image` stored under
`content_type: image/jpeg` whenever `fru.civil-registry.client=stub`
(`StubCivilRegistryClient.java:37-39`, `IdentityScanService.java:586`). `image/jpeg` is on the
endpoint's allow-list, so the endpoint serves those 32 bytes with a 200 and the `<img>` breaks.

That is correct behaviour and not this commit's to fix. It does mean **a stubbed environment proves
the LAYOUT only**: the Uqudo-vs-registry face comparison — which ticket 02 calls "the comparison the
review stage exists to make" — cannot be judged at all without a real Civil Registry response. Any
acceptance walk that intends to prove the review stage needs one. No live browser proof is claimed
here for that reason; the named tests are the proof for everything that is provable without it.

---

## BL-136 is more urgent now, not less

`salary_certificate` carries real bytes and is not one of the five. When BL-136 was filed at S8-22,
an operator could at least see that the document existed — it appeared as a metadata row in the
attachments table. Variant B deletes that table, so **as of this commit the income evidence the
customer uploaded is invisible to an operator in every surface the system has.**

The decision BL-136 asks for is unchanged and is still not this session's to take: widening a
product-owner ruling without the product owner is the same mistake as narrowing one. What changed is
the cost of leaving it open, and the row now says so.

Related: BL-075 requirement (b) — "attachments should be clickable and open in a pop-up, either all
of them, or at least those not already displayed above the attachments list" — is **partially
retired rather than met.** The tiles are clickable into a pop-up, which satisfies the first half.
The second half becomes unsatisfiable when the table goes: for `doc_back` that is ticket 02's
decision, for the frames it is unavoidable (no bytes), and for `salary_certificate` it is BL-136.

---

## Review findings and dispositions

The pre-build pass produced the three brief defects above. The diff pass returned nine findings, all
accepted, all fixed in this commit.

| # | Finding | Disposition |
|---|---|---|
| 1 | `road-to-production.md` §1.2 was flipped to DONE while its own "Done means: an operator can see **every** stored image" stayed — a criterion the code contradicts, since `doc_back` and `salary_certificate` are unviewable. Its next paragraph also said "the attachments table lists the front frame as a 1.97 MB item **today**", of a table this diff deleted. | **Fixed.** Done-criterion narrowed to the five viewable kinds with a pointer to BL-136; the frame sentence rewritten past-tense. |
| 2 | `.scratch/backoffice-remaining/map.md` still recorded the caption language as undecided. It is the index a later session reads first, so it is the copy most likely to be believed. | **Fixed.** Caveat struck and the Arabic decision recorded. |
| 3 | `EXECUTION_PLAN.md:158`'s S8-22 row — amended by this diff — still ended "the back office still renders placeholder silhouettes and does not call the endpoint", present tense, in the commit that falsified it. | **Fixed.** |
| 4 | Ticket 02's Context still pointed at `portraitPlaceholder.ts` and "today's metadata-only attachments table". | **Fixed** as struck-through history, since the ticket is a charting snapshot. |
| 5 | A test named "mirrors `OperatorImagePolicy.viewableKinds()` exactly" asserts a TypeScript literal against a TypeScript literal — nothing in it can fail if the Java set changes, and the comment called the Java side "the source of truth". Two definitions, silent drift. | **Fixed as an honest claim, not as a mechanism.** Test renamed to say it pins the client list; `VIEWABLE_KINDS` now states in capitals that no gate detects drift and that whoever answers BL-136 server-side must add the kind here in the same commit. A real cross-tier check is a bigger piece of work than this row. |
| 6 | `ArtifactContactSheet.tsx`'s comment justified keying failures by artifact id with "a re-scanned profile holds several rows of one kind" — a state `selectTiles` cannot produce, since it yields at most one artifact per kind. | **Fixed.** The choice stands; the reason given for it now says it is behaviourally identical today. |
| 7 | "`Cache-Control: no-store, private` makes every render an origin request" overstates it — a re-render of a mounted `<img>` with an unchanged `src` issues nothing. | **Fixed.** Rewritten to say it stops a later fetch being served from browser cache, with the render case named explicitly so nobody reads an event count as renders. |
| 8 | The no-silhouette assertion used `queryAllByRole('img').every(...)`, which passes vacuously on an empty array — it would have held on a page rendering no images at all. | **Fixed, and it caught a second thing.** Pinned to exactly three images, which then failed at 4: `getAllByRole('img')` matches every Ant Design icon, since each is a `<span role="img">`. Every count assertion in the new tests now uses real `<img>` tags. |
| 9 | Last-match-per-kind gives no cycle coherence, and nothing said so. | **Fixed as documentation.** Recorded on `selectTiles` and above. |

The reviewer separately confirmed, against source, three things this session had argued rather than
measured: that `ORDER BY ar.created_at` is genuinely ascending; that no path through the component
can give a non-viewable kind an `<img src>`; and that a stale failure flag cannot survive a reload,
because `load()` sets `loading` synchronously and the page returns a `Spin` early, unmounting the
sheet and discarding its state on every refresh including after approve and reject.

---

## Gates

Backoffice, with the isolated Node at `C:\Users\DELL\.local-tools\node-v24.19.0-win-x64` (global
Node 22.22.2 is refused by `engine-strict`). Backend untouched, so no backend gate applies.

`npm run lint` — exit 0. Ten pre-existing warnings, none in the new files.

`npm run test:coverage`, verbatim tail:

```
 Test Files  20 passed (20)
      Tests  156 passed (156)
   Start at  17:04:10
   Duration  92.77s (transform 1.25s, setup 10.65s, import 79.95s, tests 119.84s, environment 49.49s)

 % Coverage report from v8
-------------------|---------|----------|---------|---------|-------------------
File               | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s
-------------------|---------|----------|---------|---------|-------------------
All files          |   97.16 |     86.1 |   97.53 |   98.93 |
 src/api           |   98.14 |    94.11 |     100 |     100 |
  reference.ts     |   95.74 |       70 |     100 |     100 | 75-81
 src/auth          |     100 |    98.52 |     100 |     100 |
  AuthContext.tsx  |     100 |       90 |     100 |     100 | 57
 src/layout        |     100 |       80 |     100 |     100 |
  AppShell.tsx     |     100 |       75 |     100 |     100 | 33-59
 src/profiles      |   95.18 |    81.09 |   96.07 |   97.89 |
  ...leteModal.tsx |   83.33 |      100 |      80 |   83.33 | 43
  ...etailPage.tsx |   92.15 |    80.39 |   94.59 |    97.7 | 589-593
  ...eListPage.tsx |   97.64 |    73.91 |     100 |     100 | 109-145,171
  RejectModal.tsx  |      92 |    94.11 |    90.9 |   91.66 | 55-56
  detailLabels.ts  |     100 |     87.5 |     100 |     100 | 70,81
-------------------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 97.16% ( 515/530 )
Branches     : 86.1% ( 316/367 )
Functions    : 97.53% ( 158/162 )
Lines        : 98.93% ( 465/470 )
================================================================================
```

Both new files are absent from the per-file table because they are at 100% — the table lists only
files with uncovered lines. That matters here: Vitest counts a source file only if a test imports
it, so an untested new component would have left the percentage unmoved rather than lowered it
(R-009). Both are imported by their own tests, not only through the page.

`npm run build`, verbatim tail:

```
dist/index.html                     0.53 kB │ gzip:   0.37 kB
dist/assets/index-MI8JscGq.css      0.30 kB │ gzip:   0.20 kB
dist/assets/index-N48w5vO7.js   1,255.04 kB │ gzip: 396.74 kB

✓ built in 7.08s
```

One intermediate run after the review pass: `npx vitest run` on the three affected files — 3 files,
40 tests, passed.

**One gate run failed and is worth keeping:** the first full coverage run after the review fixes
failed on `expected [ …(4) ] to have a length of 3 but got 4`, which is finding 8's fix finding the
`role="img"` problem. No revert-restore proof is owed for any test here — each is a direct
wrong-value assertion (a refused artifact id is absent from every `src`, the superseded id is
absent, the failed tile's `alt` is gone), and none could pass against the behaviour it guards.

---

## No real data

Every fixture id is synthetic (`doc-front-1`, `11111111-1111-4111-8111-111111111111`). No account
number, no national number, no image, and nothing read from any database. No live browser capture is
included — the dev database holds a real identity document from a developer's own test walk, and
nothing from it may appear in a session report.

---

## Commit proof

Captured AFTER the push, per CLAUDE.md. Single commit, straight to `main`, no branch.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   4d2a538..3b4a1bc  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	docs/bank-hosting-specification.md

nothing added to commit but untracked files present (use "git add" to track)
```

`docs/bank-hosting-specification.md` was untracked before this session started and is not this
session's work; it is deliberately left alone rather than swept into this commit.
