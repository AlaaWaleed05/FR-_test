# S8-07 — backend pre-pilot: FRU→SFB, Sudan-first (deferred), BL-086's backend half

Date: 2026-09-08. Tier: backend + reference-data + plan files. No mobile, no namespace change.
Task row: EXECUTION_PLAN.md S8-07 (added this session — it did not exist).

## Scope as delivered, and why it differs from the brief

| Item | Brief | Delivered |
|---|---|---|
| 1 — `FRU-` → `SFB-` | build | **Done**, both mint sites |
| 2 — Sudan-first country ordering | build | **Deferred by PO decision**, design + costs recorded on BL-092 |
| 3 — BL-086 backend half | reconcile docs | **Done**, and doc-only as the brief predicted |

`@agent-reviewer` ran a **pre-build baseline audit** before anything was written — the same
discipline S8-05 and S8-06 each needed, and it again changed the work. It produced the Item 2
deferral, and caught four breaking tests plus one delivery hazard the backlog row did not carry.

## Item 1 — reference-number prefix, generation point only

Both mint sites changed in one commit; review confirmed the two constants are byte-identical and
did not diverge:

- `backend/.../submission/jdbc/JdbcSubmissionRepository.java:52`
- `backend/.../operator/jdbc/JdbcManualCompletionRepository.java:35`

Both port Javadocs updated (`SubmissionRepository`, `ManualCompletionRepository`) to carry the two
rules not visible from either call site alone: the sites must always change together, and issued
numbers are never rewritten.

### The parse-the-prefix grep — the brief's named real risk — came back empty

Repo-wide, all three tiers plus migrations, `db/` and `docs/`. **No production code in any tier
reads the prefix.** Evidence rather than assertion:

- operator search is a whole-value match, not a prefix match — `JdbcProfileListRepository.java:113`
  (`p.reference_number ILIKE :searchLike`)
- `SubmissionMessageRenderer` interpolates only
- the column is a bare `text UNIQUE` — `V0005__app_status_and_profile.sql:30`. No CHECK, no regex,
  no column comment, no DB-level pattern
- backoffice touches it only as interpolation (`ProfileDetailPage.tsx:284`); mobile only via `LtrValue`

Every surviving `FRU` occurrence was classified: preserved history, legacy-value test fixture, or
comment. **Zero defects.** The `FRU_` environment-variable prefix is a different namespace and was
correctly left alone.

### Two deliberate non-changes

**`V0045` was NOT edited**, though its header documents the old format. The pre-build audit
recommended editing it; **that recommendation was wrong and was refused.** Flyway runs with
`validateOnMigrate` at its default `true` and no `ignoreMigrationPatterns` anywhere in the tree,
and Flyway checksums the file's bytes including comments — so editing an applied migration would
break `flyway:migrate` against staging. Review re-checked and now agrees.

**`docs/road-to-production.md:7` was NOT edited.** It records a real historical run that issued
`FRU-000000001`; rewriting it would falsify the record — the same principle as not renumbering.
Test fixtures carrying `FRU-` values in all three tiers were also left alone; they now stand as
legacy values proving old numbers still flow and render.

### Existing FRU- numbers are preserved — stated explicitly

**Already-issued `FRU-` numbers are untouched by this change.** Nothing rewrites them: there is no
backfill, no UPDATE path, and the change is at the generation point only. `FRU-` and `SFB-` numbers
coexist permanently by design. Guarded by a new test,
`referenceNumberIssuedUnderTheOldFruPrefixIsNeverRewritten`
(`ManualCompletionIntegrationTest.java:273-310`), which stamps `FRU-000000001` — the S7-12
acceptance-walk profile's number — onto a completed profile and asserts the re-call returns and
stores it unchanged.

**Revert-restore: NOT owed, and the reason matters.** This is a *direct wrong-value assertion* — a
reminting implementation yields `SFB-nnnnnnnnn`, and `assertEquals("FRU-000000001", …)` on both the
response body and the stored column cannot pass against it. CLAUDE.md scopes revert-restore to
indirect assertions and identical-happy-path ordering defects; this is neither.

**Honest limit of that test, recorded rather than glossed:** it would also have passed against the
pre-change code, because the pre-change `alreadyDone` path returned the stored value unchanged too.
It guards the *never-rewrite* invariant, not the prefix flip. The flip is guarded by the two
updated shape assertions, which do fail against pre-change code. Both now check the whole
`SFB-\d{9}` shape rather than `startsWith` — a change that altered the prefix but disturbed the
`lpad` would still pass a `startsWith` check.

## Item 2 — Sudan-first: deferred, nothing half-built

No migration exists, no `reference/` code changed, `country` remains at version 1. The design was
settled and recorded on **BL-092** so it need not be re-derived. Three findings drove the deferral.

**(a) The approach had to be a new version, not an in-place edit — for a harder reason than
purity.** Mobile's `_isImmutabilityViolation` (`reference_repository.dart:109-120,197-205`)
actively *refuses* a changed hash under an existing version and records a poisoning failure. So an
in-place rewrite of v1 would not merely violate AD-005 — **it would fail to propagate and would
degrade every installed handset, including the walk device.** Editing `V0022` is barred by the same
Flyway checksum rule as `V0045`.

**(b) A migration without the publication step hard-blocks Stage 3 on a FRESH install.** The
manifest serves only `is_current` (`JdbcReferenceDocumentStore:31`), so it advertises `country/2`
the instant the migration lands, while `GET /lists/country/2` 404s until publication runs
(`ReferenceController:72-76`). Mobile classifies that as a network error, caches nothing,
`prepareCatalog()` returns false, and that is the Stage 2→3 gate
(`channel_verification_screen.dart:319`). An existing install is unaffected — it keeps v1 cached.
**A fresh pilot APK is not.** Recorded against **R-033**, whose consequence this upgrades from
stale data to a blocked journey.

**(c) Four existing tests break** and would need deliberate fixes, chief among them
`AppSchemaConnectivityIntegrationTest:91`, which counts `ref.reference_item` rows for `country`
with **no version predicate** (249→498).

Compounding all three: the country field **already defaults to Sudan** at all three sites
(`stage3_screen.dart:106`, `stage5_screen.dart:104-105`, `stage6_screen.dart:87`), so ordering only
changes the *opened* picker.

**Product-owner decisions, 2026-09-08:** deferred on that basis; and when built it is to be Sudan
**plus a small Gulf/regional group**, not Sudan alone. **The exact country list was not supplied
and must be confirmed before the migration is written.**

## Item 3 — BL-086 backend half: documentation, as the mobile report said

The no-change conclusion was **re-verified at source rather than taken on trust**, because the whole
claim rests on the client never omitting the field: `entry_api.dart:26` and `dio_entry_api.dart:48`
declare `required bool whatsapp` (non-nullable), `:54-61` writes the key into the map literal
unconditionally, and `:53` is the *only* call site of `POST /api/v1/contact-channels` in
`mobile/lib`. Dio cannot omit a non-null key, so `whatsapp == null || whatsapp` is unreachable from
the shipped app.

`ContactChannelsRequest`'s Javadoc corrected — **doc only; the record body is unchanged**. It now
states SMS defaults selected, WhatsApp does not since S8-05, that the null-tolerant branch is wire
compatibility for a pre-flip client rather than the journey's default, and that
`fru.messaging.whatsapp.enabled=false` is the deployment-level control.

## Stale claims found and corrected

Three claims in the project record were false when this session started:

1. **BL-092: "a prefix divergence is one no test would catch."** Wrong — both sites were already
   asserted (`SubmissionIntegrationTest:87`, `ManualCompletionIntegrationTest:79`), confirmed
   against `HEAD`.
2. **BL-086 and EXECUTION_PLAN S8-05: "`customer.md:103` still says both channels default on."**
   Stale — `customer.md:105-113` already carried the S8-05 amendment.
3. **`customer.md`'s own amendment block** claimed the Javadoc was still owed. True this morning;
   discharged here, and the line struck in the same commit. Caught by review *after* I had asserted
   in two plan files that customer.md needed no edit at all — those two claims were corrected too.

## Gates — verbatim

Intermediate run, before the review-driven test comment: `./mvnw verify -Pdb-integration-test` —
1092 tests, 0 failures, BUILD SUCCESS.

**A run then FAILED, and it is pasted because a failure is evidence.** Adding the three-line
comment review asked for was done with a scripted rewrite, which re-flattened that file's CRLF
endings to LF, so `spotless:check` rejected the whole file:

```
[ERROR]         -import org.springframework.jdbc.core.JdbcTemplate;\n
[ERROR]         -import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;\n
...
[ERROR]     ... (860 more lines that didn't fit)
[ERROR] Run 'mvn spotless:apply' to fix these violations.
```

Nothing was wrong with the code — the intended diff was three comment lines. `spotless:apply`
restored the endings. **The lesson, recorded because it cost a second full run: `spotless:apply` is
owed after EVERY scripted rewrite, including a small late one following a review pass; an earlier
passing gate does not immunise the next edit.** A second trap alongside it: piping the gate through
`tail` masks its exit code, so the run reported "exit code 0" while actually failing — redirect to
a file and echo `$?`.

Final, `./mvnw verify -Pdb-integration-test`:

```
GATE_EXIT=0
[INFO] Results:
[INFO] 
[INFO] Tests run: 1092, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] 
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO] 
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] 
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO] 
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 456 files clean - 0 needs changes to be clean, 0 were already clean, 456 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:47 min
[INFO] Finished at: 2026-09-08T15:36:34+02:00
[INFO] ------------------------------------------------------------------------
```

Baseline at S8-04 was 1091 tests; 1092 here is exactly the one test this session added. Mobile and
backoffice were not touched, so their gates were not run.

## Review

`@agent-reviewer` twice: a pre-build baseline audit (which re-cut the scope) and a post-build review
of the diff.

Post-build verdict: **no blocker, no should-fix.** Three NOTEs, with dispositions:

| NOTE | Disposition |
|---|---|
| `customer.md:111-112` now claims owed work that is done | **Fixed** — struck and marked done at S8-07; the two plan-file claims saying customer.md needed no edit were corrected too |
| `V0045` header still documents `FRU-` | **Accepted, no change** — editing it breaks Flyway validation; `SubmissionRepository`'s Javadoc names V0045 and carries the correction |
| The test writes a literal into a `UNIQUE` column | **Fixed** — comment added naming the collision hazard if the prefix were ever reverted |

Review confirmed independently: both mint sites byte-identical; the prefix grep empty; the new test
non-vacuous and owed no revert-restore; the regex escaping correct; no Item 2 code present; every
Javadoc claim accurate; the R-033 chain real and not overstated (including that an existing install
is genuinely unaffected); and no out-of-scope change.

## Owed

- **A staging redeploy, before the pilot APK is rebuilt.** The running task definition still mints
  `FRU-` numbers, so a staff test today gets the old prefix. Not done here.
- **The `fru.messaging.whatsapp.enabled=false` staging flip** (BL-086 step 1) — infrastructure, not
  repository code. Until made, staging still mints a WhatsApp challenge for anyone who ticks the
  box, handed to a `stub` provider that delivers nothing.
- **W-12 / Sudan-first** — deferred, not dropped. Needs the PO's regional country list, and its
  migrate-plus-publish must land as one indivisible deployment step.
- **BL-089 stays deferred** by PO decision — `CheckAccount` was not flipped to real.
