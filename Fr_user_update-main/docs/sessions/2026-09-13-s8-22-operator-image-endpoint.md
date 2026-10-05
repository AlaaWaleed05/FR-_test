# S8-22 — BL-075's backend half: the operator image endpoint

**Date:** 2026-09-13 · **Tier:** backend (plus comment-only edits in backoffice and docs)
**Outcome:** the endpoint ships. The back office still renders placeholder silhouettes — that half
is a separate commit and BL-075 stays open for it.

At S7-12 a real operator approved a real profile without seeing the passport, the registry
photograph or the liveness capture. That is what this session removes the cause of.

---

## What shipped

`GET /api/v1/operator/profiles/{profileId}/artifacts/{artifactId}` — an ordinary
cookie-authenticated GET, rendered straight into an `<img>`. R-046 closed by refuting its own
premise (the back office is same-origin and uses a session cookie, so the
"`<img>` cannot carry an Authorization header" dilemma never applied), so there is no signed URL,
no blob plumbing and no new key material.

| Layer | File |
|---|---|
| Policy (two allow-lists) | `operator/domain/OperatorImagePolicy.java` |
| Value + port | `operator/domain/OperatorImage.java`, `OperatorImageRepository.java` |
| Adapter | `operator/jdbc/JdbcOperatorImageRepository.java` |
| Service (audit) | `operator/service/OperatorImageService.java` |
| Handler (headers) | `operator/web/ProfileImageController.java` |
| Migration | `V0066__app_artifact_ref_storage_key_dead_r046_closed.sql` |

`ArtifactRefView` gained `artifactRefId` — the only thing that addresses the bytes. Not cosmetic:
`app.artifact_ref` is `UNIQUE (cycle_id, kind)`, so a profile with several identity cycles has
several committed rows of the same kind and `kind` alone cannot tell a superseded scan from its
replacement. That is also why ticket 09 keys the audit payload on the id.

### The binding requirements, and how each is met

| Requirement | Where | Test |
|---|---|---|
| (i) `Cache-Control: no-store, private` | `ProfileImageController` — `CacheControl.noStore().cachePrivate()` | header asserted literally |
| (ii) authorise per artifact AND per profile | `JdbcOperatorImageRepository` — one SELECT | cross-profile fetch, both directions |
| (iii) read through `app.artifact_read()` | same SELECT's target list | corrupted `sha256` → loud failure |
| (iv) pin content type from an allow-list | `OperatorImagePolicy.pinContentType` | `text/html` row → 404 |
| (a) refuse the byte-less frame kinds | kind allow-list, applied in SQL | frame → 404 with an empty body asserted |
| ticket 09 — one event per fetch | `OperatorImageService` | 3 fetches → 3 events, payload asserted |
| ticket 06 — a viewer may view | falls through to the `hasRole("VIEWER")` catch-all | viewer fetch → 200 |

**On (i), the detail that would have silently broken it:** `CacheControl.noStore()` alone emits
`no-store` and nothing else — `IdentityScanIntegrationTest:1445` already asserts that bare string
for the customer endpoint. `cachePrivate()` is not decoration. Without it the browser is free to
cache, and an `<img>` re-rendered from browser cache never reaches the origin, so the audit event
is lost without any error anywhere.

**On (ii), the part that is a security property rather than a style choice:** ownership and the
byte read are ONE statement, and `app.artifact_read()` sits in its target list. PostgreSQL
evaluates a target list only for rows that survive `WHERE`, so the function is never called for an
artifact this profile does not own — a nonexistent id and a foreign id both produce zero rows and
both become the same 404. Read the bytes first and check ownership afterwards and the endpoint
becomes an existence oracle: a nonexistent id raises (500) while a foreign id is refused (404),
which tells an attacker which artifact ids are real. Same shape as
`JdbcIdentityScanRepository`'s Stage 9 read.

The ownership predicate itself is the one already proven in `JdbcProfileViewRepository`'s
`ARTIFACTS` listing, narrowed to one artifact. Both halves are needed — scan images and portraits
are cycle-keyed, the signature and salary certificate are profile-keyed with a NULL `cycle_id`.

**The listing and the fetch share the OWNERSHIP predicate only** — corrected at review, where an
earlier draft of this report and the repository's own javadoc both said "the same predicate", which
is not true and would have misled the deferred front-end half. The fetch also applies the kind
allow-list; the listing deliberately does not, so it still returns an `artifactRefId` for
`doc_back`, `salary_certificate` and both capture frames. Whoever builds the contact sheet must
link the five allowed kinds, not every listed row — otherwise the largest row in the table becomes
a broken image again, which is requirement (a)'s failure one level up. Now stated in
`JdbcOperatorImageRepository`'s javadoc rather than only here.

`state = 'committed'` refuses the other three. `superseded` (V0063) deserves a note: it is set on
exactly one thing, the profile-keyed signature of a customer a device-less re-entry replaced,
retained as audit evidence precisely so it is not read as this profile's current one. Refusing it
contradicts neither AD-008 nor BL-041 — a rescan opens a new cycle whose artifacts are
`committed`.

**The kind allow-list is applied in SQL**, not by the service refusing a row it already fetched.
Bodies are TOASTed and one real row measures 1.7 MB; pulling a `doc_back` out of TOAST only to
throw it away is work for a request that was never going to be served. The list keeps one
definition — `OperatorImagePolicy.viewableKinds()`, bound as a parameter, never restated in SQL.

---

## Four defects in the session brief

Found by checking the brief against source before writing code, not after.

**1. "The row lists all eight locations" — it does not, and two of them cannot be corrected.**

The seven Java line numbers were all accurate and all still stale. The V0053 entry was not. It
names three lines as "the column COMMENTs" and only **one** is: `V0053:33` closes the live
`COMMENT ON COLUMN app.artifact_ref.storage_key`. `V0053:20` and `:28` are plain SQL `--` file
comments inside an applied migration.

Editing them would change V0053's checksum and break migration on every already-migrated database
— `validateOnMigrate` defaults to true and nothing here turns it off. **The trap is that no gate
would catch it:** Testcontainers starts from an empty database and re-applies the edited file, so
its checksum always matches in test, and only the deployed database refuses. V0066 replaces the
live comment and records the other two as superseded, with that reasoning in the file so the next
reader does not try again.

**2. Seven further stale locations were missing from the row.** Four in `backoffice/`
(`api/types.ts`, `portraitPlaceholder.ts`, `ProfileDetailPage.tsx`) and the docs
(`components/backoffice-components.md`, `components/persistence.md` ×2,
`road-to-production.md` ×3). The backoffice ones are exactly what CLAUDE.md's frozen-comment rule
targets — shipping the endpoint and leaving a comment saying no endpoint exists is the
self-confirming freeze that stops the next session checking. Corrected in this commit.

Deliberately left: the on-screen Arabic at `ProfileDetailPage.tsx:591` is user-visible copy, not
a comment, so it was deferred to the UI commit. **Corrected at review — it is partially stale, not
accurate**, and this report said otherwise in its first draft. Its first half is true (the back
office does still show no images); its parenthetical claims the storage location and the fetch
mechanism are undecided, and this same commit decides and records both. Recorded on BL-075 as
stale-and-deferred rather than checked-and-fine, so the next session does not skip it on this
report's word. Also left: the wayfinder tickets (historical records of the decision) and V0053's
two file comments (above). The built bundle at `backoffice/dist/` also embeds that Arabic string;
it is gitignored, untracked and generated, so it is correctly untouched and will carry the
corrected copy whenever the UI commit rebuilds.

**3. `salary_certificate` carries real bytes and was excluded by omission, not decision.**
Ticket 02 opens "Six artifact kinds carry bytes" and lists six; there are seven —
`JdbcSalaryCertificateRepository` writes a `body` for it exactly as the others do. The ticket then
decided "the set is the set" without ever weighing the seventh, because its own premise had
dropped it. So an operator can see the passport, both portraits, the liveness capture and the
signature, and cannot see the income document.

Refused here, matching ticket 02's written five, and **filed as BL-136** rather than settled in
passing — widening a product-owner ruling without the product owner is the same mistake as
narrowing one. `OperatorImagePolicyTest` asserts the refusal with the reasoning in its comment, so
the next reader finds the question rather than inferring an answer.

**4. Ticket 09 settled the audit payload but never the chain.** It loosely describes all eleven
existing operator event types as reaching "its own operator chain", which is not what the code
does: profile-scoped events use the **profile** chain with the profile as subject
(`OperatorProfileViewService`, `OperatorReviewService`, `ManualCompletionService`), and only the
list and export events use the operator chain. The profile chain was followed as the established
precedent. Recorded here as precedent-following, not as a decision this session took.

Minor, verified rather than assumed: `event_type` has **no DB CHECK** (`V0002:45` is plain
`text NOT NULL`), so a new event type needed no migration. And AD-013 (admin as superuser) is
closed as a decision but **not implemented** — `OperatorIdentityFilter` still filters admins out,
so an admin 403s at the `hasRole("VIEWER")` catch-all and never reaches this handler. Out of
scope; BL-131 owns it.

---

## One test was wrong and the code was right

The first gate run failed with a single error:
`aChecksumMismatchFailsLoudlyAndServesNoBytes`. The behaviour was correct —
`app.artifact_read()` raised `artifact … failed checksum verification on read` and no bytes were
served. The test asserted `status().isInternalServerError()`, which MockMvc never produces here:
nothing handles that exception by design, so on a real server it reaches Spring Boot's default
error handling and the browser gets a 500, while MockMvc rethrows out of `perform()` instead.
Asserting a rendered 500 was asserting a behaviour the harness does not simulate.

Rewritten to assert what is true at this layer — the request dies inside the handler carrying
V0054's own verification message — plus the sharper consequence: **the audit count is unchanged**.
That second assertion is the better half. It proves no event may claim an operator viewed an image
they were never given, and it pins the ordering, since an implementation that appended the event
before reading the bytes would leave a second event behind and fail.

No revert-restore proof is owed for any test here: each is a direct wrong-value assertion against
a real database (a real foreign row 404s, a really-corrupted checksum fails, real event rows are
counted), not an indirect assertion that could pass against the broken version.

---

## Review findings and dispositions

`@agent-reviewer` ran twice: once against the brief's claims before any code (which produced the
four brief defects above), once against the diff. The diff pass returned four findings, all
accepted and all fixed in this commit.

| # | Finding | Disposition |
|---|---|---|
| 1 | `road-to-production.md` — the paragraph under the updated heading still said, in the present tense, "there is no operator-facing image endpoint at all". The exact frozen-comment class, in the document that was being corrected for it. | **Fixed.** Rewritten past-tense and scoped to S7-12, with a pointer to the Status paragraph. |
| 2 | The on-screen Arabic at `ProfileDetailPage.tsx:591` was justified here and on BL-075 as "remains accurate". Half of it is: the parenthetical claims the storage location and fetch mechanism are undecided, and this same commit decides both. | **Fixed, as a claim rather than as code.** The string stays for the UI commit (it is user-visible copy, not a comment), but BL-075 and this report now call it partially stale, so the next session is not told it was checked and cleared. |
| 3 | `JdbcOperatorImageRepository`'s javadoc claimed the listing and the fetch use the same predicate, which stops the table offering a link the endpoint refuses. The fetch adds the kind allow-list and the listing has none — so the comment contradicted this session's own recorded decision, and would have told the front-end half it may link every row. | **Fixed** in the javadoc and above. A comment asserting a guarantee the code does not give is worse than no comment. |
| 4 | Spring MVC routes `HEAD` to the `@GetMapping` handler and discards the body, so an authenticated `HEAD` writes a `profile_image_viewed` event and delivers zero bytes — breaking the service's stated "no event without an image served". | **Fixed as documentation, deliberately not as behaviour.** No `<img>` issues a `HEAD`, and the caller must already be an authorised viewer who could simply `GET` it; refusing `HEAD` with a 405 would trade a faithful-enough record for a broken HTTP verb. Stated in the javadoc so nobody reads an event count as "images actually looked at". |

The reviewer separately confirmed the load-bearing mechanism this endpoint rests on, which was
argued from first principles rather than measured: PostgreSQL evaluates a node's qual before its
projection, and because the ownership test sits inside an `OR` the `IN` subquery cannot be pulled
up into a semijoin — it stays a `SubPlan` in the scan filter, so all four conditions are one filter
and `app.artifact_read()` is projected only for surviving tuples. `VOLATILE` helps rather than
hurts here: it forbids the planner from pre-evaluating the call. And the property is tested, not
only reasoned — V0054 raises for a nonexistent id, so a random UUID would 500 if this were wrong,
and the test asserts 404.

---

## Gates

Backend — `./mvnw verify -Pdb-integration-test`, verbatim tail:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 1122, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 339 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 467 files clean - 0 needs changes to be clean, 0 were already clean, 467 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 339 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:32 min
[INFO] Finished at: 2026-09-13T16:39:13+02:00
[INFO] ------------------------------------------------------------------------
EXIT=0
```

That is the FINAL run, taken after the review dispositions below. Intermediate runs, one line each
as the rules allow: `./mvnw verify -Pdb-integration-test` first run — 1122 tests, 1 error (the test
defect above), whose failure output is pasted in that section because it is evidence.
`./mvnw test -Pdb-integration-test -Dtest=OperatorImageIntegrationTest` after that fix — 12 tests,
0 failures, pass. `./mvnw verify -Pdb-integration-test` before the review fixes — 1122 tests, 0
failures, pass (re-run after them because they touched Java comments, and the output above is that
re-run).

Backoffice — three comment-only files plus their fixtures, so the tier's gates apply.
`npm run test`, verbatim tail:

```
 Test Files  18 passed (18)
      Tests  140 passed (140)
   Start at  16:24:17
   Duration  169.61s (transform 2.66s, setup 30.52s, import 227.92s, tests 90.72s, environment 145.09s)
```

Backoffice was NOT re-run after the review dispositions: those touched only `BACKLOG.md` and this
report, no `backoffice/` file, so the run below still covers the committed state.

`npm run lint` — oxlint, 0 errors, 10 warnings. Ten pre-existing warnings, all
`react(set-state-in-effect)` / `react(only-export-components)` / `react(globals)` in files this
session did not touch except `ProfileDetailPage.tsx`, whose warning is at `:147` and predates this
change (it is the same double-fetch class of warning S8-21 recorded at `ProfileListPage.tsx:98`).

Mobile — untouched, no gate applies.

---

## Tests

`OperatorImageIntegrationTest` (12, `@Tag("integration")`, real Postgres) — one per DONE criterion:
stored bytes served byte-for-byte with all four headers; a viewer may fetch; another profile's
artifact refused in both directions while each is fetchable under its own; unknown profile and
unknown artifact are the same 404; a malformed id is 400; the byte-less capture frame 404s with an
empty body asserted; `doc_back` and `salary_certificate` refused despite carrying bytes; a
`text/html` content type refused; `superseded` and `purged` refused; the checksum mismatch above;
one event per origin fetch with the payload asserted and refusals adding none; and the profile
detail listing's own `artifactRefId` fetching the bytes.

`OperatorImagePolicyTest` (9, plain JUnit, no Spring, no database) — both allow-lists, including
that the 32-byte stub `portrait_registry` still pins `image/jpeg` because the policy governs the
declared type and never the bytes. Sniffing is out of scope and would contradict the `nosniff`
header that makes pinning meaningful.

Branch 16, accounts 0000000570–0000000582, claimed in `AbstractPostgresIntegrationTest`'s range
list with a note that several of these profiles carry extra seeded artifact rows and that
0000000580's checksum is deliberately corrupted and never readable again.

**No real data anywhere.** Every byte is fabricated by the Uqudo stub or written by the fixtures.
Nothing was read from the local dev database, and no account number, national number or image from
it appears in code, fixtures or this report.

---

## Decisions recorded, not taken in passing

- The backend `ARTIFACTS` listing is **not** filtered to the five viewable kinds. Ticket 02's
  "everything else is absent rather than shown-and-refused" is a front-end ruling; filtering here
  would change `ProfileDetailResponse` and the backoffice fixtures. Stated rather than left to
  fall out of the implementation.
- Derivative rows (`derived_from_artifact_ref_id`) are still unpopulated. V0053 assigned that
  writer to "whoever builds the backoffice image-viewing endpoint" — this session is that
  successor, and it serves originals. Whether the contact sheet ever needs downscaled copies is an
  open question, not an owed deliverable. Recorded in V0066 and in `persistence.md`.
- `app.artifact_ref.storage_key` is dead, not dropped. Dropping a column is its own migration and
  its own review.

---

## What is NOT done

The back office does not call this endpoint. `portraitPlaceholder.ts` still renders a grey
silhouette and `ProfileDetailPage.tsx` still shows a metadata-only attachments table with its
Arabic notice that images are out of scope. **An operator's experience is unchanged by this
commit.** The contact-sheet tiles (ticket 02's Variant B, designed at
`Design_3/backoffice/Main.dc.html`) are the half that makes the review stage work, and BL-075 stays
open until they land.

Also outstanding and named so they are not rediscovered: BL-136 (`salary_certificate`), BL-133's
remaining two surfaces (the print event and the five account-administration event types), and
ticket 02's flagged question of whether the five English tile captions should be Arabic.

---

## Commit proof

Captured AFTER the push, per CLAUDE.md. Single commit, straight to `main`, no branch.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   42496a7..5c26cc8  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	docs/bank-hosting-specification.md

nothing added to commit but untracked files present (use "git add" to track)

$ git log --oneline -1 origin/main
5c26cc8 S8-22: the operator can finally see an identity image
```

`5c26cc8` — 28 files, 1,712 insertions, 55 deletions. The endpoint, the migration, all nine stale
comments, both test classes and the plan files are in ONE commit, which is what the frozen-comment
rule requires.

`docs/bank-hosting-specification.md` is pre-existing untracked and is deliberately not in this
commit — it belongs to R-053 and has been excluded by the last several sessions. Files were staged
by path rather than with `git add -A` precisely so it could not drift in.

