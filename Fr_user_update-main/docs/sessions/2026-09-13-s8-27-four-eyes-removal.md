# S8-27 — the four-eyes rule is removed (AD-013, BL-131)

**Date:** 2026-09-13
**Task:** EXECUTION_PLAN.md S8-27 · BACKLOG.md BL-131 (now closed) · PROJECT_PLAN.md AD-013
**Tiers touched:** backend, backoffice. Mobile untouched (zero hits for `canApprove` or four-eyes).

An operator may now approve a profile they themselves manually completed. The rule is gone from
the SQL, the port, the service, the wire and the back office.
`app.profile_status_history.is_manual_completion` is kept and still written, which is what makes
the decision reversible with no migration.

---

## The brief was wrong in four places, found before building

CLAUDE.md requires `@agent-reviewer` against a third-party integration; this session also ran it
against the *brief's own claims* before writing code, which is where all four came from.

**1. `approveBlockedReason` does not exist.** BL-131 and the session brief both instructed
stripping `canApprove`/`approveBlockedReason`. Five hits repo-wide, all prose, none in any `.java`,
`.ts`, `.tsx`, `.dart` or `.sql` file. The trail is traceable and worth recording because it is a
reusable failure mode: the field was proposed in the 2026-08-30 client-component research (l.568),
explicitly **declined** at S6-02 — BACKLOG.md:30 says so in BL-013's own text — and the decline was
then lost when wayfinder ticket 06 built its blast-radius table (l.96) from BL-013's *headline*
rather than from source. BL-131 inherited it from the ticket, and the brief from BL-131. A
blast-radius table assembled from backlog prose instead of `git grep` manufactures work.

**2. There were FOUR failing tests, not three.** Neither BL-131 nor the brief named
`ManualCompletionIntegrationTest#fourEyesRuleAfterManualCompletionEndToEnd`, which manual-completes
as `op-manual-2` and asserts `isForbidden()` on that same operator's approve. It fails the build
exactly like the auth test the row *did* name.

**3. `OperatorReviewService` is a compile-forced edit.** BL-131 mentions it only as "an open
question for ticket 09" about the audit payload at l.278. It is also where
`FourEyesViolationException` is imported (l.10), where the predicate is called (l.270-271) and
where the exception is thrown (l.287-288). Deleting the exception class without editing this file
does not compile — a session following the row literally would have hit that wall.

**4. Two files nothing named.** `JdbcProfileViewRepository` constructs `ProfileDetail` at l.164 and
l.208, each passing a trailing positional `false`; and `JdbcReviewRepository` l.118 bound a third
`operatorId` argument that the dropped `NOT EXISTS` was the only placeholder for. Both
compiler-caught, so neither could have shipped silently — recorded for the blast-radius table's
accuracy, not as a near-miss.

**The brief's line-number warning was unfounded.** It predicted stale numbers for
`ProfileDetailPage.tsx` and `api/types.ts` because S8-23 and S8-24 both touched them. BL-131 gives
no line numbers for either file. Every line number it *does* give verified correct:
`ReviewController` l.4/l.37, `ReviewRepository` l.60 (doc from l.55), l.74-75, l.81-82, `V0009`
l.16, `OperatorReviewService` l.278, `ManualCompletionService` l.41, the auth test l.189.

---

## Two traps in the tests, and why "invert, don't delete" was applied unevenly

**The raw-SQL blocks were DELETED, not inverted.** `OperatorReviewIntegrationTest` l.327-345 and
`ManualCompletionIntegrationTest` l.137-152 each ran a *verbatim copy* of V0009's conditional
`UPDATE` through `jdbcTemplate.update` and asserted zero rows. Inverting them the obvious way — by
dropping the `NOT EXISTS` conjunct to match the new production SQL — would have made the raw
`UPDATE` **actually approve the profile, behind the service layer**. Every assertion after it
("a different operator succeeds", `isOk()`, `status = approved`) would then have been passing
against an idempotent already-approved response instead of a real approve. Green, and testing
nothing. They also pinned SQL that no longer exists in any production path. Deleted.

**The rename test could not be inverted at all.**
`OperatorAuthenticationIntegrationTest#fourEyesWithRealIdentitiesSurvivesARenameBecauseActorIdIsTheUuid`
was AD-002e R1's only live proof that `operatorId` is the UUID and not the username — but four-eyes
was merely its *vehicle*: it renamed A's login mid-test and re-asserted the 403. Invert the
assertions in place and the test proves nothing, because once A's first approve succeeds the
post-rename approve returns an idempotent already-approved 200 whether `actor_id` is a UUID or a
username. The property still matters — it now underwrites the whole audit trail, which is R-054's
sole compensating control — so the proof was re-pointed rather than lost: after the rename, the
append-only `app.profile_status_history` row and the `profile_manually_completed` audit event are
asserted to still carry A's UUID, with two `assertNotEquals` pinning that neither spelling of the
username may appear. Renamed to `actorIdIsTheUuidSoARenameDoesNotRewriteHistory`.
`docs/components/backoffice-auth.md` l.46-47 cited the old name and was updated in the same commit.

**One backoffice test was deleted rather than inverted, deliberately.**
`ProfileDetailPage.test.tsx`'s "disables Approve when `canApprove` is false" describes a state that
no longer exists. The case below it ("approves via the confirm popup") already pins the positive
behaviour an inversion would have asserted, so nothing goes unasserted — which is the actual
purpose of the invert-don't-delete rule. That case was renamed and given an explicit
`toBeEnabled()` assertion so the new behaviour is pinned rather than implied.

---

## The audit event survives, and is now tested

`OperatorReviewService` wrote `"reason": fourEyesViolation ? "four_eyes_violation" :
"profile_not_reviewable"` into `profile_approve_refused`, and `ManualCompletionService`'s javadoc
justified that event's existence *solely* by four-eyes. **Whether the event survives is wayfinder
ticket 09 / BL-133's call, not this session's**, so it was kept rather than deleted by inference.
The ternary collapses to the constant `"profile_not_reviewable"` — the only reason left — and both
justifying comments now say BL-133 owns the question.

It could not be left unexercised. `OperatorReviewIntegrationTest` l.305-311 was the event's *only*
assertion anywhere, and inverting that test removed it; the event would have shipped in main code
with zero coverage. `approvingAnIneligibleStatusIsRefusedAndRecorded` was added to pin the one
refusal arm AD-013 leaves standing: approve an `in_progress` profile → 409, exactly one
`profile_approve_refused` row with `reason = profile_not_reviewable`.

Two self-inflicted failures on the way there, both caught by the gate and both worth recording
because they are cheap to repeat: the new test first reused account `0000000459`, already taken at
l.401 of the same class (409 in setup, not in the assertion); and it queried
`ae.payload_json->>'reason'`, which is `text` (V0002:52) — the queryable `jsonb` column is the
generated `ae.payload` (V0002:53).

---

## V0009 was not edited. V0067 carries the correction.

V0009 documents the rule as enforced in three places: l.1 ("the four-eyes rule's own table"), l.16
(the column's trailing comment) and l.35-50 (the illustrative `UPDATE`, with its closing claim that
"this makes the control impossible to bypass by calling the API directly"). All three are now
false.

V0009 is applied, and Flyway's `validateOnMigrate` defaults to true, so editing its bytes breaks
migration on every already-migrated database — and **no gate catches it**, because Testcontainers
starts from an empty database and re-applies the edited file, so its checksum always matches in
test and only the deployed database refuses. Same reasoning and same resolution as V0066.
`V0067__app_profile_status_history_four_eyes_removed.sql` is comment-only: one `COMMENT ON COLUMN`
plus a header naming each superseded line of V0009. No DDL, no grant, no data change.

**No migration was otherwise needed, verified rather than assumed.** Every four-eyes hit across all
67 migrations is a comment or commented-out illustrative SQL; the only non-comment hit is the
`is_manual_completion` column declaration itself (V0009:16). V0020's status-transition triggers do
not reference the column; no CHECK, trigger, function, rule, policy or view depends on it; grants
are table-level (V0010:47).

Two other applied migrations name the rule in passing and V0067 supersedes those lines too, since a
reader grepping the migrations would otherwise find them uncorrected. **V0042:4** cites four-eyes
as an example of "an invariant this important does not live in Java alone" — four-eyes turned out
to be the one item in that list that *did* live in Java alone, which is precisely why removing it
needed no DDL. **V0057:71** justifies "an operator account is NEVER deleted" by the four-eyes
predicate *plus* every audit attribution; that conclusion is unchanged and must not be relaxed —
only the first of its two reasons is gone.

---

## Live proof — the one a passing test cannot give

The four-eyes removal itself needs **no** live proof: `OperatorAuthenticationIntegrationTest
#actorIdIsTheUuidSoARenameDoesNotRewriteHistory` drives a real sign-in through the real security
filter chain against a real Testcontainers PostgreSQL 18, manual-completes and then approves *as
the same operator*, asserting 200 and `status = approved`. Three integration tests assert
`is_manual_completion` is still written. Those are direct wrong-value assertions
(`isForbidden` → `isOk`, `"submitted"` → `"approved"`), so **no revert-restore is owed** — they
cannot pass against the old behaviour.

What a green test *structurally* cannot prove is the migration decision, because Testcontainers
always starts empty. So the live run targets exactly that: V0067 applied to a real,
**already-migrated** database — the `docker-compose.yml` Postgres, brought to V0065 with
`flyway:migrate -Dflyway.target=0065` to stand in for a deployed one, then migrated forward.

```
[INFO] Successfully validated 67 migrations (execution time 00:00.253s)
[INFO] Current version of schema "public": 0065
[INFO] Migrating schema "public" to version "0066 - app artifact ref storage key dead r046 closed"
[INFO] Migrating schema "public" to version "0067 - app profile status history four eyes removed"
[INFO] Successfully applied 2 migrations to schema "public", now at version v0067 (execution time 00:00.036s)
[INFO] BUILD SUCCESS
```

This run was redone from a wiped volume after V0067's text was extended mid-session (it had already
been applied once, so its recorded checksum was stale). The output above is against the committed
bytes — a proof taken before the file's final state would have proved the wrong file.

`Successfully validated 67 migrations` is the proof that V0009's checksum is intact — had it been
edited, this line would have been a validation failure, and it is the only place that failure can
surface. The retained column and its new comment, read back from that same live database:

```
     column_name      | data_type | is_nullable | column_default
----------------------+-----------+-------------+----------------
 is_manual_completion | boolean   | NO          | false
(1 row)
```

```
 True on the history row written when an operator manually completes a profile at a branch. Recorded permanently as provenance, and shown to operators on the status-history timeline. Until 2026-09-13 it also DROVE THE FOUR-EYES RULE: approve's conditional UPDATE carried an AND NOT EXISTS (...) conjunct refusing an approve by the same actor_id. AD-013 removed that rule; the column is retained and still written so it can be restored without a migration. See V0009 for the original predicate, and RISKS.md R-054.
```

---

## AD-013's second half is NOT built — filed as BL-139

AD-013 has two halves. BL-131 is only four-eyes. The other — **admin becomes a superuser** — is a
closed decision with no implementation and, until now, no backlog row. Verified at source:
`auth.web.OperatorIdentityFilter` l.54 filters `account.role() != OperatorRole.ADMIN`, so an admin
never receives an `OperatorIdentity` and `OperatorIdentityArgumentResolver` refuses every operator
endpoint — this is the single change without which "admin is a superuser" does not work, whatever
`SecurityConfiguration` says. Alongside it: `OperatorAccessLevel` is two-valued with no admin
mapping, `V0057`'s `operator_role.is_back_office` is `false` for admin, and
`OperatorAuthenticationIntegrationTest#authenticatedAdminGets403OnOperatorEndpoint` pins the
current behaviour and will need inverting.

Not built here, deliberately: separate change, separate test surface, and genuinely separable —
nothing in the four-eyes removal touches those three classes. `OperatorRole`'s javadoc asserted the
now-false "an admin account never receives an `OperatorIdentity` at all" and was corrected to point
at BL-139. `.scratch/backoffice-remaining/map.md` now records that ticket 06's two halves have
diverged, so the ticket is not read as discharged.

---

## Documentation debt — five living docs the task list missed

The reviewer's pre-build pass found these; neither BL-131 nor the brief listed any of them.

- **`CLAUDE.md` l.35-36** stated the rule as a live project constraint, in the one file every
  session is told to read first. Deleted (product-owner decision this session), freeing a line
  against the 250-line cap. The removal stays discoverable in operator.md, V0067, AD-013 and R-054.
- **`docs/journeys/operator.md`** l.19-30 (the rule's own section) and l.270 — annotated as removed
  with the original text kept readable, matching how AD-002e itself was preserved.
- **`docs/journeys/journey-open-items.md`** l.128-131 — said "Enforced by the system, not by
  policy". Corrected, and now notes that the operator/supervisor role collapse it justified is left
  with no compensating constraint.
- **`docs/road-to-production.md`** l.416 (gate 4.5) — l.422's gate 4.7 was already annotated for
  AD-013 but 4.5 was not. Gate 4.5 is now **harder, not discharged**: the audit trail is the only
  control left to confirm sufficient, and R-054's own prerequisite R-037 (seals produced, never
  exported) is named there.
- **`docs/components/backoffice-components.md`** l.23 and **`docs/components/persistence.md`**
  l.170/567/570 — the `actor_id`-is-a-UUID reasoning keeps its conclusion and loses four-eyes as
  its justification; attribution in append-only tables is now the stated reason.

Two source javadocs did the same and were corrected: `auth/domain/OperatorAccount.java` l.11-14 and
`auth/domain/OperatorRole.java` l.5-9. Session reports under `docs/sessions/` are historical
records and were **not** edited.

---

## Gates

Docker was running; `JAVA_HOME` set to the Android Studio JBR; backoffice run under the isolated
Node at `C:\Users\DELL\.local-tools\node-v24.19.0-win-x64`.

One intermediate failure worth naming rather than hiding: the first full backend run failed
**Spotless**, not tests — the Python rewrite scripts normalised CRLF to LF across 17 files.
`./mvnw spotless:apply` fixed it; the resulting diff shows no line-ending churn.

### backend — `./mvnw verify -Pdb-integration-test`

```
[INFO] Running com.sfbank.bayanati.uqudo.stub.StubUqudoClientTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.099 s -- in com.sfbank.bayanati.uqudo.stub.StubUqudoClientTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1133, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 338 classes
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
[INFO] Analyzed bundle 'backend' with 338 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:14 min
[INFO] Finished at: 2026-09-13T19:12:46+02:00
[INFO] ------------------------------------------------------------------------
EXIT=0
```

### backoffice — `npm run lint`

Exit 0. Only pre-existing warnings, none in code this session changed except
`ProfileDetailPage.tsx:145`, an untouched `useEffect` that already carried it.

```
src/auth/AuthContext.test.tsx:145:7: warning react(globals): Cannot reassign variables declared outside of the component/hook ...
src/auth/AuthContext.test.tsx:170:7: warning react(globals): Cannot reassign variables declared outside of the component/hook ...
src/auth/AuthContext.test.tsx:199:7: warning react(globals): Cannot reassign variables declared outside of the component/hook ...
src/profiles/ProfileDetailPage.tsx:145:5: warning react(set-state-in-effect): Calling setState synchronously within an effect can trigger cascading renders ...
LINT_EXIT=0
```

### backoffice — `npm run test:coverage`

```
 Test Files  20 passed (20)
      Tests  163 passed (163)
   Start at  19:13:05
   Duration  114.72s (transform 1.66s, setup 13.90s, import 143.19s, tests 115.90s, environment 51.78s)

 % Coverage report from v8
-------------------|---------|----------|---------|---------|-------------------
File               | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s
-------------------|---------|----------|---------|---------|-------------------
All files          |   97.21 |    86.32 |   97.59 |   98.95 |
 src/api           |   98.14 |    94.11 |     100 |     100 |
  reference.ts     |   95.74 |       70 |     100 |     100 | 75-81
 src/auth          |     100 |    98.52 |     100 |     100 |
  AuthContext.tsx  |     100 |       90 |     100 |     100 | 57
 src/layout        |     100 |       80 |     100 |     100 |
  AppShell.tsx     |     100 |       75 |     100 |     100 | 33-59
 src/profiles      |   95.32 |    81.55 |   96.22 |   97.95 |
  ...leteModal.tsx |   83.33 |      100 |      80 |   83.33 | 43
  ...etailPage.tsx |   92.15 |    80.13 |   94.59 |    97.7 | 581-585
  ...eListPage.tsx |   97.64 |    73.91 |     100 |     100 | 109-145,171
  RejectModal.tsx  |      92 |    94.11 |    90.9 |   91.66 | 55-56
  detailLabels.ts  |     100 |     87.5 |     100 |     100 | 70,81
-------------------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 97.21% ( 523/538 )
Branches     : 86.32% ( 322/373 )
Functions    : 97.59% ( 162/166 )
Lines        : 98.95% ( 473/478 )
================================================================================
EXIT=0
```

### backoffice — `npm run build`

```
vite v8.2.1 building client environment for production...
transforming...✓ 1512 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                     0.53 kB │ gzip:   0.37 kB
dist/assets/index-MI8JscGq.css      0.30 kB │ gzip:   0.20 kB
dist/assets/index-BobCgIp-.js   1,256.27 kB │ gzip: 397.13 kB

✓ built in 10.03s
(!) Some chunks are larger than 500 kB after minification. [pre-existing, unrelated to this change]
EXIT=0
```

---

## Review findings and dispositions

Two reviewer passes: one against the *brief's claims* before any code was written (which produced
the four corrections above), one against the diff.

**1. SHOULD FIX — `PROJECT_PLAN.md` l.360-362 and l.411-413 still asserted the rule as enforced.
FIXED.** Both sit in the live "Settled facts" and system-map sections, not in an AD row, so neither
is legitimately historical: *"The four-eyes rule is enforced in the database as a single conditional
UPDATE with a NOT EXISTS clause... Calling the API directly cannot bypass it"* and *"Two access
levels — viewer and operator — with a four-eyes rule"*. This is the worst of the documentation
misses because PROJECT_PLAN.md is one of the four files CLAUDE.md requires reading at session
start — the next session would have read a control that does not exist. Both annotated in the
operator.md style; the AD-002e and AD-013 rows left untouched. The second one also now names
BL-139, since the same sentence's "two access levels" is what AD-013's unbuilt half changes.

**2. NOTE — V0057:71 not listed in V0067. ALREADY FIXED before the finding arrived.** V0067 was
extended mid-review to name both V0042:4 and V0057:71 (see the migration section above). The
reviewer read an earlier snapshot. Its substantive point is preserved in V0067's own wording:
V0057's conclusion — never delete an operator account — is unchanged and must not be relaxed, since
only one of its two reasons is gone.

**3. NOTE — the tree changed after the gate output quoted to the reviewer. ACTED ON.** The auth
test gained an `assertNotEquals` import, two assertions and a rewritten javadoc after that run, and
a new import is exactly what Spotless gates. The backend gate was re-run afterwards and its output
is what appears above; the backoffice tier was untouched by those edits.

**Checked with nothing to report** (the reviewer's own list, condensed): V0009 untouched and V0067
comment-only; both raw-SQL blocks gone with no orphaned helpers; the rename test genuinely
discriminates — if `operatorId` were the username, `actor_id` would hold the literal `s405.opa`
(V0009:11 is plain `text`, no FK, no cascade) and all four assertions fail; `profile_approve_refused`
still written and now tested; **no stranded dead code** — checked mechanically, since
googleJavaFormat does not remove unused imports; `is_manual_completion` still written and still read
onto the timeline; no behavioural regression on the arms four-eyes was never part of (viewer 403,
401 without identity, wrong-status 409, idempotent already-approved, `rejected -> approved` with
`from_status = 'rejected'`); `ReviewRepository`'s lock-ordering invariant javadoc byte-identical;
no open architecture decision settled in passing; no secrets, and no account number outside the
reserved test ranges.

---

## Commit proof

Committed to `main` directly, per this project's norm (no feature branch).

```
[main c660e49] S8-27: remove the four-eyes rule (AD-013, BL-131)
 36 files changed, 764 insertions(+), 357 deletions(-)
 delete mode 100644 backend/src/main/java/com/sfbank/bayanati/operator/domain/FourEyesViolationException.java
 create mode 100644 backend/src/main/resources/db/migration/V0067__app_profile_status_history_four_eyes_removed.sql
 create mode 100644 docs/sessions/2026-09-13-s8-27-four-eyes-removal.md
```

```
To https://github.com/Osmantou/Fr_user_update
   6c80b12..c660e49  main -> main
```

Captured AFTER the push:

```
On branch main
Your branch is up to date with 'origin/main'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
        docs/bank-hosting-specification.md

nothing added to commit but untracked files present (use "git add" to track)
```

```
c660e49 S8-27: remove the four-eyes rule (AD-013, BL-131)
6c80b12 Close out today's rows properly: BL-075, BL-136, BL-132, ticket 09, R-051
7e667ff S8-26: record the push proof in the session report
```

```
$ git rev-parse HEAD origin/main
c660e49a39ecfa76bb3800c7f264a356d947a073
c660e49a39ecfa76bb3800c7f264a356d947a073
```

`up to date with 'origin/main'` and identical SHAs for `HEAD` and `origin/main`.

Two notes on the tree. `docs/bank-hosting-specification.md` was untracked at session start, is
unrelated to this task, and was deliberately left unstaged rather than swept into this commit.
And this work sits on top of `6c80b12`, a peer session's commit that landed on `main` today and
was already in the local tree — so the BACKLOG and plan-file edits here were made against that
base, and both gate runs and the reviewer pass saw it. The push was a fast-forward, no merge.
