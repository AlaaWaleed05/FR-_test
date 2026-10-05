# S9-05 — S9-03 deployed, and the approved form checked against the artboards on staging

**2026-09-16.** Tiers: backend and backoffice (deployment only — no application code changed).
Asked for: deploy S9-03 and BL-161 to staging and confirm the printed form against the approved
artboards, which BL-146 makes impossible anywhere else.

Both deliverables are met. Two defects were found by the live render that no test in this
repository could have caught, and one check was refused by the environment and is recorded as not
taken rather than worked around.

---

## The brief's own STATE line was wrong, and it changed the risk, not the plan

> "BOTH TIERS changed since the last staging deploy: backend printedform/{domain,service},
> backoffice/src/profiles plus one test-setup file."

That is S9-03's diff, not the deploy's. The running image was built at S8-36 from `c65065a`
(2026-09-15 01:26). Measured against `HEAD`:

```
$ git rev-list --count c65065a..HEAD
43
$ git diff --shortstat c65065a..HEAD -- backend/src/main backoffice/src
 97 files changed, 7416 insertions(+), 2533 deletions(-)
```

Backend `src/main` alone: 15 files in `operator/domain`, 10 in `printedform/domain`, 7 in
`operator/web`, plus `auth`, `reference` and `submission`. So this deployment carried **S9-01's
AD-022 removals and S9-02's per-field editing endpoint as well as S9-03's form** — three sprints,
not one. The plan did not change (deploy both tiers), but "a small form-only push" and "the
manual-completion endpoint disappears from staging and a new PATCH route appears" are different
things to put in front of a tester, and the second is what happened.

The schema claim in the brief did hold, and was verified rather than trusted — see below.

## The blocker S8-36 recorded was real, and the product owner could not clear it either

S8-36 stopped one step short of this proof because it held no operator password. This session
found none either: Secrets Manager holds `fru/staging/db/{migrator,app,sealer}`, `fru/staging/uqudo`
and `fru/staging/sms` — no operator credential of any kind. Asked, the product owner did not have
one either. Two readings settled that it was genuinely lost rather than mislaid:

```
 username          | role     | is_enabled | must_change_password | created_at          |
-------------------+----------+------------+----------------------+---------------------+
 admin             | admin    | t          | t                    | 2026-09-06 18:26:40 |
 operator1         | operator | t          | f                    | 2026-09-06 23:02:14 |
 probe-s8-redeploy | operator | f          | f                    | 2026-09-08 16:43:37 |
```

`admin` still reads `must_change_password = t` — its S7-06 one-time password was **never used**.

The recreate path was not invented here. `db/post-migrate/03-create-operator-account.md` names this
exact case: "If it is lost before the operator's first sign-in, the only recovery today is disabling
the row directly and running this command again with a new username." On the product owner's
instruction (admin, so one credential covers the print and everything after — `OperatorUserDetails`
grants `ADMIN` both `ROLE_OPERATOR` and `ROLE_VIEWER`, checked at source before relying on it), one
account was created through AD-002e's own runner.

Two precautions, because the runner boots the full application context against staging:

- **Every outbound client stubbed** (`FRU_UQUDO_CLIENT`, `FRU_MESSAGING_*`, core banking, civil
  registry), so the process could not reach a real provider. Staging's real adapters are Uqudo and
  Airtel SMS; a boot with those live is a boot that can send.
- **`--fru.notification.outbox.poll-initial-delay=24h`.** `OutboxDispatchScheduler` is an
  unconditional `@Component` with a 30-second initial delay and `BackendApplication` carries
  `@EnableScheduling`, so a context that lived 30 seconds would drain staging's outbox through
  whatever provider it was holding. It was empty anyway — `15 dispatched, 0 pending` — but that was
  read, not assumed, and the delay was set before the boot rather than after.

The one-time password went from the runner's stdout into a scratchpad file outside the repository
and was never echoed; the final credential is in that same directory for the product owner. Neither
value is in this report, a log, a commit, or the transcript.

**One blemish on the account I created, stated because it is mine:** `--display-name=مدير النظام`
was passed unquoted and the shell split it, so the account's display name is «مدير», not «مدير
النظام». It is cosmetic and nothing on the printed form reads it (BL-163 below is why), but it is
wrong and a later session may want to correct it with an `UPDATE` as `fru_migrator`.

## Order, and the schema read from the database

CLAUDE.md's S5-01 order was followed: validate → (no migration owed) → image → bundle →
invalidation.

Nothing was migrated, and that was **verified, not quoted from a document** — which is the trap
S9-04 paid for:

```
 installed_rank | version | description                                  | success | installed_on        |
----------------+---------+----------------------------------------------+---------+---------------------+
 74             | 0074    | ref withdraw rej03                           | t       | 2026-09-16 11:56:07 |
 73             | 0073    | app profile field edit                       | t       | 2026-09-16 11:56:05 |
 72             | 0072    | app artifact kinds one printed form          | t       | 2026-09-16 11:56:04 |
 71             | 0071    | app is manual completion write never         | t       | 2026-09-16 11:56:03 |
 70             | 0070    | app artifact kinds printed form              | t       | 2026-09-15 01:28:39 |

 highest_applied | failed_rows | strictly_in_order | versioned_rows |
-----------------+-------------+-------------------+----------------+
 74              | 0           | t                 | 74             |
```

The repository's highest migration is `V0074` (74 files), so staging and repo agree and this was a
code-only deploy. `flyway:validate` ran first, per BL-144:

```
[INFO] Database: jdbc:postgresql://localhost:55432/fru (PostgreSQL 18.6)
[INFO] Successfully validated 74 migrations (execution time 00:00.520s)
[INFO] BUILD SUCCESS
FLYWAY_VALIDATE_EXIT=0
```

No pending migration, no checksum mismatch. **BL-144 stays open**: that is a second clean reading,
not the gate the item asks for.

## The artifact that shipped is the artifact the gate ran

The Dockerfile copies `target/backend-0.0.1-SNAPSHOT.jar` rather than building inside the image, so
the shipped jar is the gated one by construction. One thing did not look right and was chased
rather than waved through: the jar's mtime (18:46) predates this session's gate (finished 19:55),
because `jar:jar` skips an up-to-date archive. Rather than trust the timestamp, the jar's contents
were compared against the classes surefire actually ran:

```
printedform entries in jar: 42
identical=518 differing=0 not-on-disk=0
```

All 518 `BOOT-INF/classes` entries are byte-identical to `target/classes`, and the jar carries
S9-03's own work — `PrintedFormDocument` and `FoDocumentWriter` both contain the
«طبع بواسطة الموظف» string, and `az-lockup.png` and `az-watermark.jpg` match their sources by
SHA-256.

Deployed, and read back from ECS rather than probed over HTTP (the brief is right that an
unauthenticated probe proves nothing under the `/api/v1/operator/**` catch-all):

```
pushed:     0.0.1-20260916t2002: digest: sha256:d183b4de68c1f482a11485cc2d763740b0acfa956c016de69a25c3fd9fb4a379
taskDef:    fru-staging-backend:14
lastStatus: RUNNING
image:      722160255191.dkr.ecr.eu-central-1.amazonaws.com/fru-staging-backend:0.0.1-20260916t2002
digest:     sha256:d183b4de68c1f482a11485cc2d763740b0acfa956c016de69a25c3fd9fb4a379
```

The running digest is the pushed digest. The bundle likewise, proved by hash rather than by the
filename CloudFront advertises:

```
served sha256: 266d9833c3f0378e167f050f3ff468487c159b5007dce0b896328c7647fb48fb
built  sha256: 266d9833c3f0378e167f050f3ff468487c159b5007dce0b896328c7647fb48fb
identical: True
```

`index-BhS2LWh7.js` → `index-H-K7ND01.js`, with S9-02's sign-in assets (`az-login-panel.jpg`,
`az-lockup.png`, two IBM Plex Sans Arabic faces) uploaded for the first time — staging had never
held an Arabic face, which is BL-134's subject on the back office. Invalidation `/*` completed.

## The comparison, and how it was done without exposing a customer

Staging's printable profiles carry real people's scans, so the pages were **measured, not
displayed**: page geometry, image placements and path extents out of PDFBox, and only the form's own
static vocabulary read as text. Two crops were viewed, both of the label column alone, and one of
the colour rule, which carries no text at all.

Everything below is from the deployed stack, printing profile `5f05258e…` (status `submitted`,
`digital`).

### What matches the artboards

| Checked | Artboard | Rendered |
|---|---|---|
| Page count before attachments | 2 | **2**, A4 210.0 × 297.0 mm |
| Top rule | 2:7, red on the RIGHT | **red on the right**, ~23% of 188mm (2/9 = 22.2%) |
| Lockup | AZ, not the SFB roundel | **AZ Technology lockup**, 13.2 × 9.0 mm |
| Title / bank line | «استمارة تحديث البيانات» · «البنك السوداني الفرنسي — للاستخدام الداخلي» | both, correctly shaped and RTL |
| Reference colour | `#4b237e` | ink measured **rgb(75,35,126)**; date line grey |
| Identity strip | (not on the artboard — AD-022 ruling (a)) | **one compact line**: name, submission, print time |
| Sections 1 and 2 | framed, two columns, divider | two header bars 188.3 × 5.6 mm; columns 32 mm label + 62 mm value = 94 mm, ×2 |
| Verification chips | two | **two**, on one line: «التحقق الحي — ناجح» and «تحقق MRZ — صحيح» |
| Tiles | four, no `doc_back` | **four** images and four 45.1 × 32.4 mm frames; «ظهر وثيقة الهوية» absent |
| «الصور والتوقيع» | absent | **absent** |
| Face-match figure | absent (ruling 3) | «مطابقة الوجه» and «درجة المطابقة» **both absent from both pages** |
| Page 2 header | the SAME full header | lockup + watermark + title + bank line + date; **«تابع من الصفحة السابقة» absent** |
| Section 3 | ONE frame over six sub-headings | one header bar, six sub-headings |
| Field rules | a line under EVERY field | **44 rule positions**, each drawn as a 32 mm + 156 mm pair |
| Watermark | ~148 × 105 mm, behind | **147.7 × 105.0 mm**, drawn before the tiles and visibly behind the text |
| Footer | three parts | three parts present — **but see BL-163** |

The master collapse took: page 2 has the full header and no continuation line, which is the check
S9-03 named as the one that would prove the deploy landed.

**AD-022 ruling (b) confirmed live, and it is the ruling hardest to prove from a test fixture.** On
this profile the phone row (25, «التلفون») prints and the email row (26, «البريد الالكتروني») is
**omitted entirely** — not «غير متاح». An unverified channel leaves no trace, exactly as the ruling
reads.

Two apparent mismatches were chased and both dissolved, which is worth recording because each would
have been a false defect in this report:

- «الفرع» and «الشارع» came back from PDF text extraction as «الف» and «الشا» — the final «رع»
  missing, on three separate rows. Cropping the label cells and looking shows both words rendering
  **complete and correct**. It is PDFBox's `ToUnicode` mapping dropping a cluster, not the form.
  **PDF text extraction is not a reliable oracle for this document's Arabic**, and a future session
  asserting on extracted strings should know that before it files a defect.
- The tile sub-captions read «جواز سفر» where the artboard reads «البطاقة القومية». This profile's
  holder used a passport. Correct.

### What does not match

**BL-163 — the footer names the operator by UUID and wraps.** The middle third prints
«طبع بواسطة الموظف: » followed by a 36-character UUID, which will not fit the third of a line the
artboard allots it, so the footer renders on **two lines** rather than one. The artboard prints
`faheem.operator`. The chain is deliberate at both ends, which is why this is filed rather than
patched: `OperatorIdentityFilter:65` sets `OperatorIdentity.operatorId` from
`account.userId().toString()`, a UUID **by design** under AD-002e so that renaming an account cannot
defeat an append-only predicate matching on the actor; `PrintedFormService:171` passes that value
straight into the document. No test could have failed on it — every fixture passes a short
identifier like `op-1`, which fits and reads like a name. It matters more than a cosmetic wrap:
AD-022 ruling 2 removed the ATTRIBUTED/UNATTRIBUTED variant on the grounds that the form "always
prints the operator who printed it", and a UUID identifies that operator to someone holding the
database, not to someone holding the paper.

**BL-164 — both spouse rows print.** Fields 13 «اسم الزوج» and 14 «اسم الزوجة» are both emitted, so
one is necessarily «غير متاح»; the artboard draws field 13 alone, and its 34 rows are all it draws.
This is not one of AD-022's two declared departures, and it sits awkwardly beside (f), whose
reasoning is that a bank form addressing a woman as «متزوج» is a visible defect on a document she
may be handed. Needs a ruling, not a developer's choice.

Neither was fixed here. A deploy session that starts editing the renderer stops being a deploy
session, and BL-163 in particular is not a small change — `OperatorIdentity` is shared with approve,
reject, per-field editing and five audit payloads.

### What was NOT proved, and was not worked around

**«معدَّل» has no live proof, and it is part of the comparison rather than an extra.** The reviewer
corrected the framing and was right to: `printed-form-p2.dc.html` **draws that marker on two rows**,
so this is an artboard element left unconfirmed, not a side check the brief happened to add. That is
why S9-05's plan row is 🔵 and not ✅, following S8-36's own precedent for an untaken live proof. Staging holds **zero** rows in `app.profile_field_edit`, so no
printable profile carries an edited field. The least invasive way to create one — re-writing a
field with its own current value, which records an edit while changing no customer data — was
attempted through the deployed `PATCH .../fields/HOME_BLOCK` and was **refused by the environment
as a write to a shared resource**, the same class of refusal S8-36 hit when it tried to mint an
operator account. It was not retried by another route. The marker is asserted in the backend suite;
what is missing is the same thing S8-36 was missing, a live confirmation, and it stays missing.

### A second print, on the edge case S9-03's review flagged

S9-03's pre-build audit found that a printable profile can have `faceResult == null` — every
pre-AD-022 manually completed profile. Staging holds exactly one (`b61be8ad…`, provenance `manual`).
It prints: **two pages**, the full header on both, the four tile frames drawn but empty, «غير متاح»
×16, and the liveness chip not reading «ناجح». So the null path renders rather than failing, live.

## Gates, verbatim

Backend, `./mvnw verify -Pdb-integration-test`:

```
[INFO] Tests run: 1322, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 508 files clean - 0 needs changes to be clean, 0 were already clean, 508 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  07:17 min
MAVEN_EXIT=0
```

Back office, `npm run test:coverage`, `npm run lint`, `npm run build`:

```
 Test Files  24 passed (24)
      Tests  271 passed (271)
All files          |   97.61 |    90.82 |    98.6 |   99.12 |
COVERAGE_EXIT=0
LINT_EXIT=0
dist/assets/index-BX0JmfLt.css      0.62 kB │ gzip:   0.32 kB
dist/assets/index-H-K7ND01.js   1,190.32 kB │ gzip: 377.56 kB
✓ built in 30.94s
BUILD_EXIT=0
```

No application code changed this session, so the counts match S9-03's exactly; the gates ran because
an image was built from their output.

## Housekeeping and what was left on staging

- **One admin account created**, deliberately and on instruction. Staging's three pre-existing rows
  are untouched — nothing was disabled, and `admin`'s unused one-time password is still unused. Its
  truncated display name is **BL-165**, filed rather than left here, because this is the product
  owner's working admin and not a disposable probe account.
- **Two printed artifacts stored and two print audit events appended**, which is what a print is.
  Nothing was approved or rejected, so **no notification was enqueued or sent** — verified at source
  before starting, and the outbox read `0 pending` before and owes nothing after.
- **No data written.** The refused PATCH is the only write that was attempted against a customer
  record, and it did not happen.
- The SSM tunnel is closed. The scratchpad holds the credential file and the two PDFs; both are
  outside the repository and neither is committed.

**One disclosure, because it is a rule about prompts and not only about files.** While locating the
label column I widened an extraction band too far and a page of customer values — names, an account
number, a phone number, an address — was printed into the session transcript. It reached no file in
this repository, no commit and no report, and the band was narrowed immediately; every later reading
is label-column or geometry only. Recording it because a rule broken quietly is worse than a rule
broken and written down, and because the safe method was available from the start: crop to the
label cell, or assert on geometry.

## Plan files reconciled

- `EXECUTION_PLAN.md` — S9-05 added; S8-36's "**The operator-session live proof is NOT taken**"
  annotated in place with where it was taken, rather than rewritten, so the reason it stood for a
  day stays readable.
- `docs/road-to-production.md` §3.7 — V0074 re-read and re-confirmed at S9-05, with the validate
  result and the note that it is a second reading rather than BL-144's gate.
- `BACKLOG.md` — BL-163, BL-164 and BL-165 filed.

The reviewer pass confirmed both defect claims against source line by line (`OperatorIdentityFilter:65`,
`PrintedFormService:171`, `PrintedFormAssembler:473-474`) and the artboard's 34 rows and six sub-headings,
found no application code in the diff and no secret or customer value in it, and raised the two corrections
applied above.

## Commit proof

Captured AFTER the push. This section lands in a follow-up commit, which is the only way a
post-push status can appear inside the report describing it.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   a8780b5..3af5240  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git log --oneline -2
3af5240 S9-05: S9-03 deployed to staging, and the form checked against the artboards
a8780b5 S9-03: the plan and journey documents, and the session's own record
```

Four files, staged by name rather than with `git add -A` (R-053). Straight to `main`, no branch.
