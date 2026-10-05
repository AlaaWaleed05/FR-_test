# 2026-09-04 — BL-032: profile identity is the account number alone

Task: BACKLOG.md BL-032, implemented from a fully-specified plan carried in from a prior session.
Product-owner decision, given with the task: the account number alone is the profile's identity;
the branch the customer selects is descriptive data. Backend only. No mobile or backoffice file
was touched, so no mobile or backoffice gate was run.

Plan mode: the session was autonomous and the plan was already fully specified, so the written
plan was treated as the approved plan and implemented as written; the state check in step 0 found
no drift (S3-02 committed at `1616fd8`, BL-032 open as filed, every named file as described).

## What was built

| Layer | Change |
|---|---|
| Schema | `V0061__app_profile_identity_by_account.sql`: `profile_one_per_account` dropped and re-added as `UNIQUE (account_number)` under the **same name**; `COMMENT ON CONSTRAINT` and `COMMENT ON COLUMN branch_code` (descriptive, refreshed on re-entry). Schema `app` only — no R-035 flag. Zero rows, nothing migrated. |
| Port | `ProfileRepository.findExisting(String accountNumber)` — branch parameter dropped, Javadoc rewritten; `ExistingProfile` Javadoc updated. New `updateBranchCode(UUID, String)` — no `row_version` bump, same posture as `touchLastActivity`. |
| JDBC | `FIND_EXISTING` is `WHERE p.account_number = ?::text`; `UPDATE_BRANCH_CODE` is `UPDATE app.profile SET branch_code = ?::text WHERE profile_id = ?::uuid`. |
| Stage 1a | `AccountCheckService` calls `findExisting(accountNumber)`; branch stays in both audit payloads. |
| Stage 1b | `ContactChannelsService`: both `findExisting` calls account-only; inside the re-entry transaction, after `lockAndCheckStillEligibleForReentry` and the `session_reentered` event, `updateBranchCode(profileId, branchCode)` beside `updateContactDetails`. Skipped on the mid-transaction rejection path like every other write. |
| Docs | BACKLOG BL-032 closed; PROJECT_PLAN Constraints bullet and OQ-024; customer.md Stage 1a; field-provenance field 2; core-banking.md "Profile identity follows the check". |

## Deviations from the plan text

| Deviation | Why |
|---|---|
| `JdbcProfileRepositoryTest` updated (not in the plan) | It stubbed the two-arg `findExisting`; would not compile otherwise. Its lookup test now also asserts the SQL contains no `branch_code`; a statement-shape test for `updateBranchCode` added (no `row_version`, no `status`). |
| `ContactChannelsIntegrationTest` range extended to 0000000101–0000000112 | All ten numbers 0000000101–0000000110 were already in use. 0000000111 (re-entry under branch 22) and 0000000112 (direct insert under branch 99) were free against every range in `AbstractPostgresIntegrationTest`'s Javadoc, which was updated. |
| `AccountCheckIntegrationTest` uses new account 0000000004 with its own stub fixture | Reusing 0000000501 would have made the test depend on method order (its profile is terminal after the sibling test runs, so creating it again returns 409). |
| `COMMENT ON CONSTRAINT` added to V0061 | The constraint is the thing that changed; V0005's inline comment cannot be edited. |
| `AbstractPostgresIntegrationTest` and `ContactChannelsIntegrationTest` class Javadocs | Both cited `UNIQUE (branch_code, account_number)`; now cite the account-only form. |

## Gates

Mobile and backoffice: untouched, no gate run — stated plainly.

Backend: `./mvnw spotless:apply` (exit 0, reflowed two Javadoc paragraphs it touched), then
`./mvnw verify -Pdb-integration-test` (JAVA_HOME = Android Studio JBR 21, Docker 29.7.2 running).
Final output, verbatim:

```
2026-09-04T15:42:19.086+02:00  INFO 5560 --- [backend] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "0060 - app omni check corebanking contract"
2026-09-04T15:42:19.119+02:00  INFO 5560 --- [backend] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "0061 - app profile identity by account"
2026-09-04T15:42:19.154+02:00  INFO 5560 --- [backend] [           main] o.f.core.internal.command.DbMigrate      : Successfully applied 61 migrations to schema "public", now at version v0061 (execution time 00:01.162s)
...
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 28.77 s -- in sd.gov.bank.fruserupdate.accountcheck.AccountCheckIntegrationTest
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.543 s -- in sd.gov.bank.fruserupdate.accountcheck.service.AccountCheckServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.776 s -- in sd.gov.bank.fruserupdate.AppSchemaConnectivityIntegrationTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.618 s -- in sd.gov.bank.fruserupdate.contactchannels.ContactChannelsIntegrationTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.462 s -- in sd.gov.bank.fruserupdate.contactchannels.service.ContactChannelsServiceTest
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.083 s -- in sd.gov.bank.fruserupdate.profile.jdbc.JdbcProfileRepositoryTest
...
[INFO] Results:
[INFO] 
[INFO] Tests run: 708, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] 
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 301 classes
[INFO] 
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO] 
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO] 
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 402 files clean - 0 needs changes to be clean, 0 were already clean, 402 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 301 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:39 min
[INFO] Finished at: 2026-09-04T15:43:27+02:00
[INFO] ------------------------------------------------------------------------
```

708 tests = 703 at the end of S3-02 + 5 new (`git diff acdeb57 HEAD -- backend/src/test` adds
five `@Test` and removes none): 1 ContactChannelsServiceTest, 1 JdbcProfileRepositoryTest,
2 ContactChannelsIntegrationTest, 1 AccountCheckIntegrationTest; AccountCheckServiceTest's
branch-keyed test was rewritten in place, not added.
`AppSchemaConnectivityIntegrationTest` (branch '2' / 'ACCT-JAVA-TEST', rolled back) still passes: 6/6.

## Proof — why a passing test suffices here

These run against the real PostgreSQL 18 with all 61 migrations applied, so naming the test and
what it asserts is the proof (CLAUDE.md "Live proof versus a passing test"):

- `ContactChannelsIntegrationTest.reEntryUnderADifferentBranchReusesTheProfileAndRefreshesItsBranchLive`
  — profile created under branch 16 for 0000000111, re-entered under branch 22: same `profileId`
  returned, `branch_code` reads `22`, `count(*) WHERE account_number = ?` is 1, and the
  `session_reentered` payload carries `"branch":"22"`. Under V0005's composite constraint the second
  submission would have inserted a second row with a different `profileId` — a direct wrong-value
  assertion, so no revert-restore is needed.
- `ContactChannelsIntegrationTest.aSecondProfileRowForTheSameAccountUnderAnotherBranchIsRejectedByTheDatabase`
  — a direct `INSERT` for 0000000112 under branch 99 inside a rollback-only transaction throws
  `DataIntegrityViolationException` whose message names `profile_one_per_account`, and the row count
  stays 1. This is the constraint-name assertion the task asked for: it proves V0061 landed under the
  intended name and is account-only, since the composite constraint would have accepted that insert
  (`assertThrows` would fail) — again a direct assertion, no revert needed.
- `AccountCheckIntegrationTest.anActiveAccountWithATerminalProfileIsTerminalWhenCheckedUnderADifferentBranch`
  — 0000000004 completed under branch 16, checked under branch 22 → `ACTIVE`/`TERMINAL`; Stage 1a
  leaves `branch_code` at `16` (it mutates nothing).
- Unit: `AccountCheckServiceTest.theProfileExistenceCheckIsKeyedOnTheAccountNumberAlone` (two branches,
  one stub, TERMINAL both times, `findExisting("0000000001")` verified twice);
  `ContactChannelsServiceTest.reEntryUnderADifferentBranchFindsTheSameProfileAndRecordsTheNewBranch`
  (`updateBranchCode(existingProfileId, "22")`, no `insertProfile`, `"branch":"22"` in the re-entry
  event); the mid-transaction rejection and brand-new-profile tests now also verify `updateBranchCode`
  is never called on those paths.

## Design decisions

- **Same constraint name.** Every prior report, BL-009/BL-010 and V0005's comment refer to
  `profile_one_per_account`; keeping the name makes V0005's "one update per account" literally true
  and leaves the name-based assertions meaningful.
- **`updateBranchCode` does not bump `row_version`.** It is a descriptive-column refresh, not a
  status or identity change — the same posture as `touchLastActivity`. The previous branch is not
  lost: the `session_created` and `session_reentered` events both carry `branch`.
- **Branch stays in every audit payload** (Stage 1a attempt/request events, Stage 1b created,
  re-entered and rejected events). Identity changed; evidence did not.
- **BL-010 untouched.** Its wording ("advisory lock keyed on `(branch_code, account_number)`") is a
  future-fix sketch; if it is ever built, the key is now the account number alone. Not edited here —
  the item is open and its resolution is not this task's.

## Review

@agent-reviewer ran against the diff and BL-032 (read-only, in parallel with the gate).

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | SHOULD FIX | `docs/components/persistence.md` §"Stage 1a/1b profile existence and re-entry" still showed `findExisting(branchCode, accountNumber)` and `WHERE p.branch_code = ? AND p.account_number = ?`, and its `app.profile` bullet omitted the new branch refresh. | **Fixed.** Signature and SQL updated to the account-only form; the `app.profile` bullet gained the `updateBranchCode` write with its no-`row_version` reasoning. |
| 2 | SHOULD FIX | BL-010's proposed fix was "a `pg_advisory_xact_lock` keyed on `(branch_code, account_number)`" — after V0061 that key would not serialise two concurrent first submissions for the same account under different branches, which now collide on the account-only constraint instead of silently creating two profiles. | **Fixed** in BACKLOG.md BL-010: key changed to the account number alone and the widened racing pair recorded. BL-010 itself stays open — its resolution is a locking-strategy decision, not this task's. |
| 3 | NOTE | The closed BL-032 row pointed at this report before it existed. | **Resolved** by this file. |

Checked clean by the reviewer, recorded because each was a specific concern of the plan:
`updateBranchCode` sits after `lockAndCheckStillEligibleForReentry` and is skipped by the early
return on the mid-transaction rejection path (asserted in `ContactChannelsServiceTest`); R-035
correctly not needed (schema `app` only); no FK references the composite unique, so the
drop/re-add has no dependents; `fru_app` already holds `UPDATE ON app.profile` (V0010); the
surviving `branch_code = ? AND account_number = ?` queries are test helpers whose fixtures all
submit under branch 16; new account numbers 0000000004, 0000000111, 0000000112 collide with no
listed range; no secrets, no real account numbers, no generated files touched.

The two fixes are documentation only; no code changed after the gate above, so it was not re-run.

## Commit proof

Committed straight to `main` (this project's norm), then pushed:

```
fc1f799 BL-032: profile identity is the account number alone; branch is descriptive data. V0061 re-declares profile_one_per_account as UNIQUE (account_number) under the same name; findExisting(accountNumber); updateBranchCode refreshes the branch on Stage 1b re-entry; AccountCheckService and ContactChannelsService moved to the account-only lookup; live proofs in ContactChannels/AccountCheck integration tests; docs and BL-010's lock key updated
```

followed by a docs-only commit appending this proof (same pattern as the S3-02 report).
