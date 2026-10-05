# S4-06 — Artifact uploads, phone-lock signal, corrected email on resend

## 0. Commit check

`fa42d42` (S4-05, feat: close AD-002e, operator authentication) confirmed present and an ancestor
of `main`; `git status` clean at session start.

## 1. Scope correction: two upload endpoints assumed, only one missing

The task brief assumed the mandatory signature (stage 11) had no upload path. Investigation of the
actual code (not just docs) found this false: `POST /api/v1/signature` already existed, built at
S3-13 and extended at S5-06 to store bytes. It already checksums, stores byte-identical bytes in
`app.artifact_ref.body`, refuses submission on a non-terminal/liveness-not-passed profile
(`ProfileNotEditableException`/`LivenessNotCompleteException`), allows re-submission in place
(`ON CONFLICT (profile_id) WHERE kind='signature'`, V0046), and `SubmissionService.submit()`
already refuses without one (`SignatureMissingException`). "Can the signature be replaced after
submission" was already answered **no**, by existing code — confirmed, not built this session.

Only the **optional salary certificate (BL-022)** was genuinely unbuilt: no field on
`Stage6Request`, no controller, no writer. That, plus BL-021 and BL-012 (both real, confirmed
unbuilt), is this session's actual scope.

## 2. BL-022 — salary certificate upload

New `salarycertificate` package (domain/service/jdbc/web), mirroring `signature`'s shape.
`POST /api/v1/salary-certificate`: 10 MB max, JPEG/PNG/PDF — customer.md's own settled Policy
value, not invented (unlike the signature's still-unset policy marker, left alone). Stored
byte-identical in `app.artifact_ref.body` (`kind='salary_certificate'`, `profile_id` set,
`cycle_id` NULL — already a legal `kind` per V0026). Gates nothing: no code path in
`SubmissionService` references it, proven by `submissionSucceedsWithNoCertificateUploadedAtAll`.
Rejected once the profile reaches a terminal status, same rule and reasoning as the signature
(stage 12: "no re-entry, including after a rejection"). Re-upload replaces in place — new migration
V0059, a partial unique index mirroring V0046's signature precedent (plain
`UNIQUE (cycle_id, kind)` does not dedupe NULL `cycle_id`).

## 3. BL-021 — phone-lock signal on account-check

`AccountCheckContinuation` gains `BLOCKED`. `AccountCheckService.check`, for an `ACTIVE` account
with an existing non-terminal profile, now calls the already-existing
`ProfileRepository.currentPhoneLockUntil` (the same column `OtpVerificationService`/
`ContactChannelsService` already read for this lock, V0037/R-044); if still in the future,
overrides `PROCEED` to `BLOCKED` and carries the timestamp through `AccountCheckResult`/
`AccountCheckResponse` as a new `blockedUntil` (ISO-8601, null otherwise). Required injecting a
`Clock` into `AccountCheckService`, which previously had none — CLAUDE.md's business-logic
testability rule (a `service`-package class must be testable with no real clock).

**What is exposed, and why it's accepted** (written into `AccountCheckResult`'s Javadoc): an
unauthenticated prober who already knows an account is `ACTIVE` and has an incomplete profile
(already inferable via `PROCEED` vs `TERMINAL`) additionally learns its phone channels are
currently OTP-locked, and exactly when that lifts. No PII beyond a timestamp. Not new in kind —
the identical value is already returned by the equally unauthenticated
`POST /api/v1/contact-channels` on a re-entry attempt (`SessionTemporarilyBlockedException`,
S3-08). Reaching the state requires having already driven 5 wrong attempts per phone channel.
Accepted under the same "enumeration is an accepted non-concern" posture BL-007/R-038 already
establish for this endpoint.

**Mobile wiring is out of scope** (explicit exclusion) — `LaunchDecision`/the resume screen still
ignore the new field; the app falls back to its existing self-correcting behaviour until wired.

## 4. BL-012 — corrected email on resend

New nullable `correctedEmailAddress` on `OtpResendRequest`, valid only alongside `channel=email`
(`EmailCorrectionNotApplicableException`, 400, otherwise). `OtpVerificationService.resend` applies
the correction inside `reserveResend`'s existing row-locked transaction, before the reservation
outcome is decided — landing even when the reservation is then refused (`TOO_SOON`/
`CAP_EXHAUSTED`/`CHANNEL_LOCKED`), matching customer.md's framing that editing the row and
resending are two separate actions. No-ops (no write, no audit) when the channel is already
`VERIFIED` (defensive — unreachable via the real UI) or the value is unchanged. New
`ProfileRepository.updateEmailAddress` (narrower than `updateContactDetails`, which also requires
and would overwrite the phone number). Every applied correction is audited as
`email_address_corrected` (previous + new address, full — same precedent as `session_reentered`).

## 5. Review — first pass (1 BLOCKER, 4 NOTE)

- **BLOCKER**: the correction updated the stored email but relied on `resend`'s Phase 2 to
  invalidate the OLD address's challenge — Phase 2 only runs on a successful reservation. A
  correction ahead of a `TOO_SOON`/`CAP_EXHAUSTED`/`CHANNEL_LOCKED` refusal left the old
  challenge live for up to 5 more minutes; entering it would mark the email channel `VERIFIED`
  against an address never itself challenged — exactly the failure CLAUDE.md's OTP rule ("a
  single code sent to several channels proves none of them") exists to prevent, and the endpoint
  is unauthenticated and keyed only on `profileId`. **Fixed**: `applyEmailCorrectionIfChanged`
  now calls `invalidateChallengesForChannel` itself, inside the same transaction, right after
  `updateEmailAddress`. Regression test `aCorrectedEmailOnARefusedResendStillInvalidatesTheOldChallenge`.
- **NOTE**: `AccountCheckService`'s new phone-lock read had no audit coverage for a DB failure,
  unlike its sibling `findExisting()` catch block one line up. **Fixed**: same
  `OUTCOME_PROFILE_CHECK_FAILED` treatment. Test `aPhoneLockCheckFailureIsAuditedBeforeItPropagates`.
- **NOTE**: two Javadoc cross-references pointed at a disclosure analysis (`AccountCheckResult`)
  that didn't contain one. **Fixed**: wrote the actual paragraph (§3 above).
- **NOTE**: `JdbcSalaryCertificateRepository`'s upsert didn't reset `state` on re-upload, so a row
  purged by `app.purge_abandoned_artifacts()` and re-uploaded to would stay permanently excluded
  from any future purge. The identical, pre-existing defect exists in `JdbcSignatureRepository`.
  **Fixed both**, `state = EXCLUDED.state` added to each upsert. Tests
  `reuploadingAfterAPurgeResetsStateSoTheRowIsPurgeableAgain` (both classes).
- **NOTE**: stale "nine unauthenticated prefixes" comment (now ten). **Fixed** — reworded to not
  hardcode a count that goes stale again.

## 6. Review — second pass (0 BLOCKER, 5 NOTE — first pass's own fixes checked)

- **NOTE**: the BLOCKER fix's own regression test used a mocked `PlatformTransactionManager`,
  unable to prove the two writes (email update + challenge invalidation) landed in one real
  transaction rather than two autocommit statements — this codebase's own recurring
  mocked-transaction-manager trap (named by S4-05's second pass too). **Fixed**: a live
  integration test drives a real profile to genuine `CAP_EXHAUSTED` (3 real resends against a
  real database) then a 4th resend with a correction, asserting both the updated
  `email_address` and the old challenge's pulled-in `expires_at`
  (`aCorrectedEmailOnACapExhaustedResendStillUpdatesTheAddressAndInvalidatesTheOldChallenge`).
- **NOTE**: the signature-repository sibling fix had no test at all — the salary-certificate test
  cannot catch a regression in the separate SQL string. **Fixed**: mirrored in
  `SignatureIntegrationTest`.
- **NOTE**: `jsonPath(...).doesNotExist()` on `blockedUntil` cannot distinguish an absent field
  from a serialised `null` (no Jackson `NON_NULL` inclusion configured — the field is always
  present). **Fixed**: both assertions (`AccountCheckIntegrationTest`,
  `AccountCheckControllerTest`) tightened to `.value(nullValue())`.
- **NOTE**: `correctedEmailAddress`'s unauthenticated write-scope had no disclosure paragraph,
  unlike the sibling `blockedUntil` change in the same diff. **Fixed**: written into
  `OtpResendRequest`'s Javadoc — same "possession of `profileId`" posture stages 3-6 already
  operate under, not a new exposure class.
- **NOTE**: `docs/components/backoffice-auth.md` still said "nine" and didn't list the new
  endpoint. **Fixed**.

Every reviewer-driven fix got its own regression test in the same pass; fixes staged after
fixing, per CLAUDE.md.

## 7. Proofs (step 5 requirements)

- **Signature stored byte-identical, checksum verified on read**: pre-existing, unchanged —
  `SignatureIntegrationTest` (now 6 tests) and `app.artifact_read()` (existing function).
- **Submission refused without a signature, accepted with one**: pre-existing, unchanged — named
  test `SubmissionIntegrationTest`, not re-proved live (an existing test already asserts exactly
  this).
- **Salary certificate uploaded; submission succeeds when absent**:
  `certificateUploadedAndReadableChecksumVerified` (live `app.artifact_read()` call),
  `submissionSucceedsWithNoCertificateUploadedAtAll`.
- **Oversized/wrong-format rejected at the boundary**: `anOversizedCertificateIsRejectedAtTheBoundary`,
  `anUnsupportedContentTypeIsRejectedAtTheBoundary`.
- **Relaunch during lockout → `BLOCKED`; unrelated probe learns nothing new**:
  `aRelaunchDuringAnActivePhoneLockReturnsBlockedWithTheRealUnlockTimestamp`,
  `aProbeOfAnUnrelatedNeverLockedAccountLearnsNothingNew`.
- **Corrected email → fresh code, prior challenge unusable**:
  `resendWithACorrectedEmailUpdatesTheProfileInvalidatesThePriorChallengeAndSendsToTheNewAddress`
  and the CAP_EXHAUSTED live case (§6).
- **Regression**: full existing integration suite re-run unchanged (customer journey through
  stage 12, operator paths, S4-05 auth, audit append-only) — all still green, see §8.

## 8. Gate output (final, verbatim)

Flyway from scratch (fresh throwaway `docker compose` stack, distinct project/container name,
port 55432 — the persistent dev `fru_postgres` container was left untouched):

```
[INFO] Successfully applied 59 migrations to schema "public", now at version v0059 (execution time 00:01.920s)
[INFO] BUILD SUCCESS
```

Idempotent re-run, same database:

```
[INFO] Successfully validated 59 migrations (execution time 00:00.222s)
[INFO] Current version of schema "public": 0059
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```

`./mvnw test -Pdb-integration-test` (via `verify -Pdb-integration-test`, includes Spotless +
JaCoCo):

```
[INFO] Tests run: 637, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 383 files clean - 0 needs changes to be clean, 0 were already clean, 383 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Count against the 601 baseline from S4-05: **637/637**, +36 (32 new tests across the three
features, 5 more from the reviewer's two passes; count includes the untouched, still-passing
pre-existing suite).

`./mvnw test` (standard, non-integration):

```
[INFO] Tests run: 485, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`./mvnw spotless:check`:

```
[INFO] Spotless.Java is keeping 383 files clean - 0 needs changes to be clean, 0 were already clean, 383 were skipped because caching determined they were already clean
[INFO] BUILD SUCCESS
```

Overall line coverage (from the `-Pdb-integration-test` run's `jacoco.csv`): **92.11%**
(24,572/26,677), up from S4-05's 91.93%.

**Not a new finding, R-050's documented behaviour re-confirmed**: a genuinely clean
`./mvnw verify` (no profile) measures ~60% and fails the bundle-wide JaCoCo check — expected,
per CLAUDE.md's Coverage section; `-Pdb-integration-test` is the real gate and it passed.

## 9. Closed

- **BL-012**: closed. Corrected email on resend built, reviewed, live-proven.
- **BL-021**: backend half closed (the phone-lock signal itself). Mobile consumption of
  `BLOCKED`/`blockedUntil` remains — explicit mobile-change exclusion for this task.
- **BL-022**: backend half closed (the upload endpoint itself). Mobile wiring of the
  already-built local capture (S5-05) to this endpoint remains — same exclusion.

## 10. Out of scope, confirmed untouched

Any mobile file, `backoffice/`, marking profiles `abandoned`, the dashboard, AD-002b/AD-002d/AD-003.

## 11. Commit and push proof

```
$ git log --oneline -1
707dcf4 feat: S4-06 -- salary certificate upload, phone-lock signal, corrected email on resend

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `be37e91..707dcf4  main -> main`.

