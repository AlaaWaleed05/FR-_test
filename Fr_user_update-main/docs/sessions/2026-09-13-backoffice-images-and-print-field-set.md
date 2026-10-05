# S8-19 — Back-office images: the layout chosen, and the printed form's field set settled

2026-09-13. Back office and documentation. No production code shipped: one throwaway prototype on
its own branch, three wayfinder tickets closed or filed, one backlog row.

---

## What this session was for

It began as "make sure the latest committed backoffice is active and run it". Getting it running
surfaced the real question — the operator sees no identity images — and the rest followed from
there: settle which images an operator sees (ticket 02) by prototype, then settle what goes on the
printed form (ticket 04) by interview, so the UI and print design pass (ticket 08) unblocks.

## Environment, and two things that were not as labelled

The back office runs; the chain is proven end to end rather than merely started. `POST
/api/v1/auth/login` through the Vite proxy returned `200 {"mustChangePassword":true,
"role":"operator"}` — browser path, proxy, backend, Postgres. My first attempt got a 401 because I
sent JSON; the endpoint is Spring `formLogin` and reads form parameters, as `api/auth.ts:33-39`
already says.

**The database was two migrations behind and refused to migrate.** Flyway rejected the run on a
checksum mismatch at V0035. Not repaired blindly: the only commit that ever edited V0035 after it
was applied is `506e7fe` (the `.gov` → `com.sfbank.bayanati` rename), the diff is a **single comment
line** with no SQL change, and that commit touched no other migration. `flyway:repair` then rewrote
exactly one row ("Repairing Schema History table for version 0035", no failed migrations detected),
after which V0064 and V0065 applied and the schema reached v0065, 65 migrations validated. Without
this the jar would have booted happily two versions behind and failed at request time — the S5-01
trap, exactly as CLAUDE.md predicts.

**The packaged jar's mtime is not a freshness signal.** I flagged the jar as stale because its
mtime (10:03) predated the last backend source commit (10:10). That inference was wrong: Maven
reported "Nothing to compile — all classes are up to date", and the mtime never moved across two
builds because `jar:jar` and `spring-boot:repackage` both skip an already-current artifact. Proven
by moving the jar aside and rebuilding — fresh mtime, different hash, same size. The commit
timestamp was simply later than the build. Worth recording because mtime will mislead the same way
next time.

---

## Ticket 02 — which images, and in what arrangement (RESOLVED)

Three structurally different variants, mounted on the real `/profiles/:id` route behind
`?variant=A|B|C`, judged in a browser against real stored artifacts. **Variant B, the flat contact
sheet, was chosen**, then corrected twice by the product owner: the tile caption is the image NAME
(`ID Document`, `Document Photo`, `CR`, `Liveness`, `Signature`) rather than metadata, and only
those five kinds appear at all.

Verified in the browser after the correction — five tiles, captions as above, "metadata still on
tiles: none", modal opens titled `ID Document`, no page errors.

### Two findings the live data produced that the ticket did not ask for

**`doc_front_frame` would have been a guaranteed broken image, on the most prominent row.** It
declares `content_type: image/jpeg` and **1,726,304 bytes** with `body IS NULL` — the largest row
in the attachments table, with no bytes at all. BL-075 requirement (a) predicted this; it is now
measured rather than predicted.

**`portrait_registry` is not a photograph in any stubbed environment.** The civil-registry stub
stores the 32-byte ASCII string `stub-photograph-not-a-real-image` under `content_type:
image/jpeg`. So the Uqudo-vs-registry face comparison — which ticket 02 calls "the comparison the
review stage exists to make" — **cannot be judged at all** with `fru.civil-registry.client=stub`.
Two consequences: a row's `content_type` is DECLARED and can lie, which strengthens BL-075
requirement (iv) to pin the served type from an allow-list rather than trust the column; and any
future acceptance walk intending to prove the face comparison needs a real Civil Registry response,
not the stub.

Ticket 02's question 4 ("what does a viewer see") was **never put to the product owner**. The
written default in `OperatorAccessLevel.java:5` stands, and the closure records that a later
session must not read it as confirmed.

---

## Ticket 04 — the printed form's field set (RESOLVED)

Nine decisions, taken by interview. Full text in the ticket; the load-bearing ones:

- **All 54 fields of `field-provenance.md`**, in its own section order — not `ExportRow.COLUMNS`,
  whose 17 columns were chosen for a spreadsheet, not a filing document.
- **Where two sources exist, print both, tagged by origin, inline.** Exactly **16 of the 51
  numbered field rows** have more than one source; the other 35 print as a single untagged value.
  Item 52 (Portrait) is dual-source as well, but it is an image and decision 4 covers it.
- **Images: signature and both portraits; no document scans.** Both portraits per
  field-provenance item 52's "Store BOTH, display BOTH"; document scans stay off per `operator.md`'s
  export rule. Consequence for ticket 05: the filed form now bears a face.
- **Signature cropped and fitted** — trim to the ink bounding box on a near-white threshold, honour
  EXIF rotation, scale to fit preserving aspect ratio, never upscaling beyond ~2x. Thresholding
  uploaded photos to pure black-on-white was rejected: it destroys a faint ballpoint signature.
  Relevant fact: the signature is stored **byte-identical with no image processing today**, and
  `captureMethod` (`drawn` | `uploaded`) is stored, so the two paths can diverge later.
- **Absent data prints «غير متاح», never a blank** — a blank beside a customer value reads as "the
  registry agreed".
- **Provenance per field**, with the profile labelled `digital` only if every field is digital.

### Two premises in the ticket that did not survive checking

**"The back office masks some values on screen" — it does not.** There is no masking anywhere in
`backoffice/src`. `DestinationMasker` masks an OTP destination on the *customer's* screen, and
`ProfileExportWriter`'s "mask" mention is CSV formula-injection sanitising. The question was
re-framed as redaction (answer: no redaction; it is an internal filing document).

**The registry is absent by a different route than assumed.** The product owner's claim that a
profile cannot be submitted without the Civil Registry is TRUE for the digital path — verified live,
every `submitted` + `digital` profile has a registry result, 2 of 2. The real route is **manual
completion**, which `operator.md` states carries "no scan, no face match, no Civil Registry lookup".
Confirmed live: 3 manual-provenance profiles, **zero registry results and zero artifacts of any kind
between them**. That single case removes the registry column, both portraits and the signature at
once — which is what the «غير متاح» rule covers.

---

## Ticket 10 / BL-135 — per-field manual entry, filed rather than built

The product owner asked, mid-interview, that an operator be able to complete any field manually,
saved as a mobile-supplied value is, with the source flagged and the operator recorded.

**Verified absent before filing, against source rather than recollection:** `app.profile.provenance`
is `CHECK (provenance IN ('digital','manual'))` — one flag for the whole profile; there is no
`source`, `entered_by` or equivalent column on any customer-data field anywhere in schema `app`; and
the "provenance matrix" (V0027/V0029) is **not** per-field provenance despite the name — it versions
which edition of `field-provenance.md` resolved a profile, and its own migration comment says "no
such code exists yet". The back office has exactly three write endpoints **against profile
data**: `manual-complete`, `approve`, `reject` — it also POSTs `/auth/login`, `/auth/logout` and
`/auth/password`, which write session and credential state rather than profile data.

Split out by product-owner agreement because it turns the back office from the read-plus-two-writes
tier CLAUDE.md describes into a **data-entry tier** — a new architecture decision, which CLAUDE.md
forbids settling in passing. Ticket 04 did not need to wait for it: the print renders whatever
provenance the profile carries, so the per-field label degenerates to today's single flag and the
layout does not change when ticket 10 lands.

---

## The prototype is on a branch, deliberately, and must never be merged

`prototype/backoffice-images` (`f1c53aa`) holds all three variants and the switcher. It is **not**
an environment-forced session branch and is **not** to be fast-forwarded into `main` — CLAUDE.md's
branch rule is about session work, and this session's decisions did land on `main`; only the
throwaway artifact deliberately did not.

Two reasons it stays off `main`. The prototype skill's own rule — main keeps the validated decision,
the variants survive as primary source without rotting in the main tree. And the measured one: the
files carry no tests by prototype-skill rule, which drops the back-office coverage gate below
threshold. Variant B gets rewritten under production constraints when BL-075 is built.

It will not run as-is: images came from a throwaway scratchpad server on port 5174, not a real
endpoint, and that server is gone with the scratchpad. None of BL-075's binding requirements
(per-artifact authorization, audit-on-view, `app.artifact_read()`) were implemented — the prototype
answered what the page should look like, not whether the backend works.

---

## PII

The dev database holds a real identity document — a real passport portrait, liveness capture, name,
passport number and national number — from a developer's own test walk. Nothing from it is in this
repository: the exported bytes lived only in the session scratchpad, and the full-page screenshots
taken to judge the variants were deleted. No profile from this database may be used as evidence in
a session report, which is why no capture appears above.

---

## Gates

`main` touches no tier this session — only `BACKLOG.md`, two closed tickets and one new ticket — so
no tier gate applies to its commit. The back-office gates below were run against the prototype
before it was moved off `main`, and are recorded because the coverage failure is the reason it sits
on a branch.

### `npm run test` (back office, prototype present)

```
 Test Files  18 passed (18)
      Tests  140 passed (140)
   Start at  06:30:29
   Duration  171.63s (transform 2.92s, setup 28.26s, import 209.38s, tests 115.13s, environment 141.36s)
```

The 140 passing tests are the evidence that the gated mount left the real page untouched: the edit
wrapped the portraits/attachments section so that with no `?variant=` the page renders exactly as
before, and `ProfileDetailPage`'s own suite exercises that path.

### `npm run test:coverage` (back office, prototype present) — FAILED, and this is why it is on a branch

```
-------------------|---------|----------|---------|---------|-------------------
All files          |   84.17 |    72.16 |   78.68 |   86.96 |
 src/api           |   98.14 |    94.11 |     100 |     100 |
  reference.ts     |   95.74 |       70 |     100 |     100 | 75-81
 src/auth          |     100 |    98.52 |     100 |     100 |
  AuthContext.tsx  |     100 |       90 |     100 |     100 | 57
 src/layout        |     100 |       80 |     100 |     100 |
  AppShell.tsx     |     100 |       75 |     100 |     100 | 33-59
 src/profiles      |   94.94 |    81.14 |   95.91 |   97.77 |
  ...leteModal.tsx |   83.33 |      100 |      80 |   83.33 | 43
  ...etailPage.tsx |   92.85 |    81.21 |   95.34 |   97.87 | 648-652
  ...eListPage.tsx |   97.64 |    73.91 |     100 |     100 | 109-145,171
  RejectModal.tsx  |      92 |    94.11 |    90.9 |   91.66 | 55-56
  detailLabels.ts  |     100 |     87.5 |     100 |     100 | 70,81
 ...iles/prototype |   14.58 |     3.94 |    2.56 |   17.72 |
  ...riantHost.tsx |      50 |        0 |       0 |      50 | 27
  ...eSwitcher.tsx |   13.51 |    16.66 |   11.11 |   16.12 | 24-96
  VariantA.tsx     |    6.66 |        0 |       0 |    8.33 | 21-121
  VariantB.tsx     |   11.11 |        0 |       0 |    12.5 | 30-68
  VariantC.tsx     |    6.25 |        0 |       0 |    7.69 | 27-102
  protoImages.ts   |   29.41 |        0 |       0 |   38.46 | 31-42,79-80
-------------------|---------|----------|---------|---------|-------------------
=============================== Coverage summary ===============================
Statements   : 84.17% ( 516/613 )
Branches     : 72.16% ( 324/449 )
Functions    : 78.68% ( 155/197 )
Lines        : 86.96% ( 467/537 )
================================================================================
ERROR: Coverage for functions (78.68%) does not meet global threshold (80%)
ERROR: Coverage for branches (72.16%) does not meet global threshold (80%)
```

Every non-prototype directory clears 80% on all four measures; the two failures are entirely the
untested prototype files. This is the CLAUDE.md coverage caveat in reverse — the gate normally
cannot see a wholly untested directory, but these files ARE imported by a tested page, so they
counted. Rather than weaken the gate with an exclude or write tests for code the prototype skill
says must not have them, the prototype left `main`.

### `npm run lint` (back office, prototype present)

Warnings only, no errors. Two were new, both on the throwaway switcher and both the same
fast-refresh rule:

```
src/profiles/prototype/PrototypeSwitcher.tsx:5:14: warning react(only-export-components): Fast refresh only works when a file only exports components.
src/profiles/prototype/PrototypeSwitcher.tsx:8:17: warning react(only-export-components): Fast refresh only works when a file only exports components.
```

The rest are pre-existing (`reference.ts`, `ProfileListPage.tsx`, `AuthContext.tsx`,
`ProfileDetailPage.tsx`, and the `AuthContext` tests).

### `npx tsc -b --noEmit`

```
TSC_EXIT=0
```


---

## Review

`@agent-reviewer` against the diff, per CLAUDE.md. It found **no defect in the code** and no
secret or PII leak, and corrected four factual claims — three of which I had asserted confidently
and two of which were in this report. All are fixed above; they are listed because the corrections
are the substance.

**The `ProfileDetailPage` edit: no findings.** With no `?variant=`, `useVariant()` returns null,
the ternary takes the branch holding the byte-identical original section, and a React fragment
emits no DOM — so the rendered tree is unchanged. `useSearchParams()` is called unconditionally at
the top of the component, so there is no hook-order hazard.

**CORRECTED — the two-source count was 16, and it is 17.** Item 52 (Portrait) carries S1 and S2 at
`field-provenance.md:147` and my extraction missed it: the `## Images` table has **no Arabic
column**, so its `S1`/`S2`/`S3` sit one position left of every other table's, and a column-indexed
scan silently reads the wrong cells. The decision now says 16 of the 51 numbered FIELD rows, and
names item 52 separately as the image that decision 4 covers. Worth remembering as a shape of
error: the extraction did not fail, it succeeded against the wrong columns.

**CORRECTED — the section list named nine of the document's ten sections**, omitting `## Images`.

**CORRECTED — "the back office has exactly three write endpoints" is false as written.** It is true
only of writes against *profile* data; the back office also POSTs `/auth/login`, `/auth/logout` and
`/auth/password`. The load-bearing half — that there is no field-edit path — stands. Fixed in
ticket 10, BL-135 and above.

**ADDED at review — the chosen tile captions are English in an Arabic-first RTL UI.** Defensible,
since the backend's own `ArtifactRefView.label` is described as "a system-origin tag rather than
translatable customer-facing copy", but it was never put to the product owner as a language
decision. Recorded in ticket 02's closure, because whoever builds BL-075 will read the ticket and
not the throwaway file that already carried the caveat.

**NOTED, not fixed — the prototype's production gate is on the wrong component.**
`PrototypeSwitcher.tsx:57` gates only the floating switcher chrome; the variants themselves render
on the presence of `?variant=`, in any build. In a production bundle that would replace the real
attachments section with prototype tiles sourced from a hardcoded `http://127.0.0.1:5174`, with
none of BL-075's authorization or audit-on-view. The mitigation is that the branch is never merged
— which is now stated in its commit message, in this report, and in the plan row. If anyone ever
does revive it, the gate belongs on the variants.

**Two process findings, both acted on.** `EXECUTION_PLAN.md` had no row for this session, though
S8-18 set the precedent that a planning-only session still gets one — added. And
`prototype/backoffice-images` existed only locally, which made this report's claim that the
variants "survive as primary source" untrue on any other machine — the branch is pushed, and the
commit proof below covers both.

## Commit proof

Captured AFTER the push, never before.

```
$ git status -sb
## main...origin/main
?? docs/bank-hosting-specification.md

$ git log --oneline -2 origin/main
ccd4a05 S8-19: reviewer corrections, and the plan row that was missing
5e7a027 S8-19: back-office image layout chosen, printed form's field set settled

$ git log --oneline -2 origin/prototype/backoffice-images
2ac0821 prototype: gate the variants on PROD, not just the switcher
f1c53aa prototype: three back-office image layouts (ticket 02), throwaway

$ git branch -r
  origin/HEAD -> origin/main
  origin/claude/s3-01-account-check-slice-38anff
  origin/main
  origin/prototype/backoffice-images
```

`## main...origin/main` carries no "ahead" marker, which is the proof — a status reading "ahead of
'origin/main'" beside a claim of having pushed proves the opposite (S8-12).

`docs/bank-hosting-specification.md` is untracked and **pre-existing**: it was already untracked in
this session's opening `git status` and is not this session's work, so it was deliberately left
alone rather than swept into a commit.

**On the branch, so the next session's branch check is not confused:**
`prototype/backoffice-images` is now on `origin` because a local-only branch does not "survive as
primary source" in any sense this report could honestly claim. It is **not** an
environment-forced session branch and **must not be fast-forwarded into `main`** — CLAUDE.md's rule
exists so that session work cannot be stranded off `main`, and this session's work is all on
`main`. The branch holds a deliberate throwaway artifact and nothing else.

This report's own proof commit follows it, which is the same shape S8-18 used.
