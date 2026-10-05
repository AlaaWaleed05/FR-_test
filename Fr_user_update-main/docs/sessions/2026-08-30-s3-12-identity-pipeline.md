# S3-12 — Identity pipeline: stages 7, 8, 9

Session date: 2026-08-30. Backend only — backoffice and mobile were not touched, so their gates
were not run.

## 0. Commit check

`git log --oneline -3` at session start showed `7a9db84` (HEAD, "docs: record S3-11 commit/push
proof") on top of `b35e37d` ("feat: S3-11 — data-entry endpoints..."), both ancestors of `main`,
tree clean. S3-11's stated hash confirmed present via `git merge-base --is-ancestor b35e37d main`.

## 1. What this builds

Stage 7 (identity type) is one field, folded into the existing `dataentry` package rather than a
new one — `DataEntryService.submitStage7`, reusing `runStageSubmission`'s existing frame exactly as
stages 3-6 do. No new package, no migration (the column already existed, V0006).

Stages 8-9 are the identity pipeline proper. New shared-integration packages `uqudo` and
`civilregistry` (port + config-selected stub, no default, mirroring `CoreBankingClient`/
`MessageSender`), and a new feature package `identityscan`
(`domain`/`service`/`jdbc`/`web`) implementing:
- **Stage 8 token issuance** — mints `sessionId`/`nonce`, persists them (`recordPendingSession`),
  enforces the 3-per-document-type / 6-total retry budget and 24h block.
- **Stage 8 scan submission** — verifies the JWS against the *stored* session/nonce, downloads
  every image before accepting, queries the Civil Registry, persists the cycle/scan
  result/artifacts/registry result atomically, purges the Uqudo session only after that commits.
- **Stage 8 cancel** — a countable failed attempt with no JWS at all.
- **Stage 9** — accept, "wrong number" (supersedes the cycle, counts against the budget), "wrong
  details" (terminal), and a registry retry (no new token, no rescan).

Two migrations: **V0039** (`scan_attempts_national_id`/`_passport`/`_total`,
`scan_blocked_until` on `app.profile`) and **V0040** (`pending_scan_session_id`/`_nonce`, added
under review — see §3). No new grants either time: `app.profile` already carries
`SELECT, INSERT, UPDATE` for `fru_app` (V0010), table-level, not column-scoped.

`StubUqudoClient` is R-034's quarantined parser. It builds and verifies a genuine compact JWS —
HS256, `com.nimbusds:nimbus-jose-jwt`, a stub-only fixed key, never a credential — rather than a
fabricated stand-in, so the shape being exercised is a real three-segment
`header.payload.signature` string. It exposes `fabricateJws`/`fabricateExpiredJws`/
`fabricateTamperedJws` beyond the `UqudoClient` interface, standing in for the missing device/SDK
purely for tests, the same way `StubMessageSender.recordedSends()` is autowired for inspection.
`StubCivilRegistryClient` similarly exposes a mutable `overrideOutcome` test hook.

**Design decision, not in the original plan**: `AuditEventWriter` gained a second method,
`appendWithArtifact(event, artifact)`, writing to `audit.audit_artifact` (which already existed,
built at S2-01, but had no application writer until now) before the event, so a raw JWS is stored
byte-identical *beside* the hash-chained event, never inside its permanently-unerasable
`payload_json`. Forced by a review finding — see §3.

## 2. The retry-budget arithmetic

customer.md states two counters that read as contradictory in isolation ("switch to a fresh
per-type budget" vs. "6 bounds the whole stage"). Reconciled in `ScanAttemptBudget`: because
`TOTAL_LIMIT` (6) equals exactly `2 * PER_TYPE_LIMIT` (3), the total can only reach 6 once *both*
per-type budgets are separately spent — so exhausting the first document type alone only forces a
switch (`ScanTypeExhaustedException`, 409, distinct from the full block); the 24h block
(`ScanTemporarilyBlockedException`) can only fire once the second type is also spent. Proven by
`ScanAttemptBudgetTest` and `IdentityScanIntegrationTest.exhaustingBothDocumentTypesTriggersThe-
TwentyFourHourBlock` (3 national_id + 2 passport → type-exhausted 409; the 6th, on passport → the
real block).

## 3. Review — `@agent-reviewer`, two passes, both with real findings

**First pass** — 2 BLOCKER, 5 SHOULD-FIX, all fixed before the second pass:

| # | Finding | Fix |
|---|---|---|
| BLOCKER | Raw JWS inlined into `payload_json` (permanently hash-chained, never erasable), foreclosing the 90-day PII purge for a JWS that can carry the document holder's name/DOB/national number | New `AuditArtifact` record + `AuditEventWriter.appendWithArtifact`; `scan_accepted` and JWS-carrying `scan_jws_rejected` events now write the JWS to `audit.audit_artifact` (kind `uqudo_scan_jws`), payload carries only `jti` |
| BLOCKER | `submitScan` validated a JWS's `sessionId`/`nonce` against the *request's own claim*, not what the backend issued — any valid JWS (harvested, another customer's) could be bound to an attacker's profile | V0040 + `IdentityScanRepository.recordPendingSession`; `submitScan` compares the request against the stored value and passes the *stored* value into `verifyAndParse` |
| SHOULD FIX | An expired-`exp` JWS was counted against the retry budget, contradicting R-012/R-021 ("a stale artifact is a system-timing fact, not a scan-quality problem") | New sibling exception `ArtifactExpiredException`; `submitScan` catches it before `JwsVerificationException` and does not call `recordFailedAttempt` |
| SHOULD FIX | `awaiting_registry` could reach an undefined V0020 transition via a new scan attempt, or via an ungated `reportWrongDetails` call | `issueToken` refuses to proceed while `awaiting_registry`; `reportWrongDetails` gained the same registry-ok guard `acceptRegistryReview` already had |
| SHOULD FIX | `ImageIntegrityException` (checksum mismatch) was thrown and documented but caught nowhere — a 500 with no audit trail for the failure mode most likely to indicate tampering | `submitScan` now catches it alongside `ImageUnavailableException`, audits and counts it (`EVENT_SCAN_IMAGE_INTEGRITY_FAILED`) |
| SHOULD FIX | A redundant registry retry could write an `awaiting_registry -> in_progress` history row for a transition that never happened | Guarded on the *previous* registry state, not just the new outcome |
| SHOULD FIX | `app.scan_result.uqudo_jti`'s UNIQUE replay guard surfaced as an unmapped 500 | Caught (as `DataIntegrityViolationException` at the time) and mapped to a new `JwsAlreadyAcceptedException`, 409 |

**Second pass, against the fixed diff, found the first pass's own fixes incomplete** — this
project's sixth consecutive session where the re-review catches a defect in the fix itself
(CLAUDE.md already names this pattern):

| # | Finding | Fix |
|---|---|---|
| BLOCKER | The `awaiting_registry`/`blocked_scan` guard was added to `issueToken` only. `cancelScan` needs no pending session and reaches `recordFailedAttempt` directly, so it could still fire an illegal `awaiting_registry -> blocked_scan`/`blocked_scan -> blocked_scan` transition | Moved the guard into `recordFailedAttempt` itself — the actual shared choke point for cancel, JWS-rejection and wrong-number, not each caller's own pre-check |
| BLOCKER | `reportWrongNumber` had no registry-ok guard (unlike its sibling `reportWrongDetails`, fixed in pass one). Reachable while `awaiting_registry`, it would supersede the profile's only `identity_cycle`, leaving **no active cycle and no way back in** — a permanently stuck profile | Added the identical `REGISTRY_STATE_OK` guard `acceptRegistryReview`/`reportWrongDetails` already have |
| SHOULD FIX | The 24h block was bypassable: `pending_scan_session_id`/`nonce` are deliberately never cleared, so a customer blocked mid-attempt could relaunch the SDK with the same session and submit a fresh JWS through it | `submitScan`'s own pre-check now rejects `blocked_scan` and `awaiting_registry` before validating the session at all |
| SHOULD FIX | The `uqudo_jti` catch (`DataIntegrityViolationException`) was too broad — it also catches V0020's own CHECK-constraint illegal-transition exception, mis-reporting a real state-machine defect as "JWS already accepted" | Narrowed to `DuplicateKeyException` (unique-violation only) |
| SHOULD FIX | The registry-retry race was only half-closed: guarding on the pre-transaction `context` read still let two concurrent retries both see stale state and both transition | Now re-locks `app.profile` and re-reads the registry context fresh *inside* the transaction before deciding |
| SHOULD FIX | The accepted-scan payload still inlined `identityNumber` (the national number) even after the JWS itself moved to `audit.audit_artifact` — defeating the purge property pass one's fix existed for | Removed; it already lives in `app.scan_result.identity_number` |
| NOTE | Two test assertions checked for the JSON *key* `"jws"`, not the JWS *value* — would pass even if the value moved under a different key | Both rewritten to `!payload.contains(jws)` |
| NOTE | Stale Javadoc (`JwsVerificationException` still listing `exp`; `applyScanAttempt`/`insertAbandonedCycle` not naming the image-integrity caller) | Fixed |
| NOTE | Zero regression tests existed for any of pass one's seven fixes — exactly why pass two found them broken | Ten tests added (see §4) |

Not changed: the JDBC layer's `applyScanBlock`/`transitionToTerminatedMismatch` still hardcode
`from_status`. Left as-is deliberately — with both BLOCKERs above closed, every caller now reaches
these methods only from `in_progress`, so the hardcoding is accurate, not merely unreachable by
luck. Adding a dynamic re-derivation on top would be defense-in-depth with no defect behind it.

## 4. Proofs

- **Full stage 8 chain, both document types, registry OK** —
  `IdentityScanIntegrationTest.fullStage8ChainAcceptsScanQueriesRegistryAndAudits` (passport, live:
  `identity_cycle`/`scan_result`/4 `artifact_ref` rows incl. the Civil Registry's own portrait,
  full audit event sequence).
- **Verified JWS, images gone → NOT accepted, not counted** —
  `IdentityScanServiceTest.verifiedJwsWithUnavailableImagesWritesNothing` (mocked: zero calls to
  `insertAcceptedCycle`/`insertScanResult`/`applyScanAttempt`) and live:
  `IdentityScanIntegrationTest.verifiedJwsWithExpiredImagesIsNotAccepted` (real Postgres: 0 cycle
  rows, `scan_attempts_total = 0`).
- **`DELETE /api/v1/info/{jti}` after storage, never before** —
  `IdentityScanServiceTest.purgeSessionHappensOnlyAfterImagesAndCycleAreStored`, a Mockito `InOrder`
  verifier across `downloadImage` ×N → `insertAcceptedCycle` → `purgeSession`.
- **Registry unreachable → pause → retry succeeds, no new token, no rescan** —
  `IdentityScanIntegrationTest.registryUnreachablePausesThenRetrySucceedsWithNoNewTokenOrRescan`
  (live: status `awaiting_registry`, `scan_result.identity_number` unchanged, stub flipped via
  `overrideOutcome`, retry endpoint alone brings status back to `in_progress`).
- **Both Stage 9 rejections** —
  `wrongNumberSupersedesTheCycleAndCountsAgainstTheBudget` (cycle → `superseded`,
  `scan_attempts_passport` incremented) and `wrongDetailsIsTerminal` (status →
  `terminated_registry_mismatch`, a further token request then rejected 409).
- **Both document types, incl. the older SDN_ID card** — `StubUqudoClientTest`: latest card has
  `nameEn`/`bloodType`, the older card has neither, passport has no back image.
- **Raw JWS stored byte-identical, in the artifact, never in `payload_json`** —
  `IdentityScanIntegrationTest.rawJwsIsStoredByteIdenticalInTheAuditTrail` (live: `audit_artifact`
  body equals the literal input string; `payload_json` contains neither the JWS value nor
  `identityNumber`).
- **New regression tests from the second review pass** (unit, mocked):
  `mismatchedSessionIsRejectedWithoutCallingUqudo`, `noPendingSessionAtAllIsRejected`,
  `submitScanWhileBlockedIsRejectedEvenWithAValidPendingSession`,
  `submitScanWhileAwaitingRegistryIsRejected`, `expiredArtifactIsNotCountedAgainstTheBudget`,
  `duplicateJtiIsMappedToJwsAlreadyAcceptedException`,
  `cancelWhileAwaitingRegistryIsRejectedWithoutMutatingCounters`,
  `cancelWhileAlreadyBlockedIsRejectedWithoutReapplyingTheBlock`, `wrongNumberRequiresRegistryOk`,
  plus `StubUqudoClientTest.expiredJwsThrowsArtifactExpiredNotJwsVerificationFailure`.
- **Regression — named, not re-proven live**: S3-11's stage 3-7 proofs and BL-016 E.164 proofs
  (`DataEntryIntegrationTest`, extended this session with `stage7ValidSubmissionPersistsAndAudits`);
  S3-06's three-channel/deselection and S3-07's re-entry (`ContactChannelsIntegrationTest`); S3-08's
  lockout escalation (`OtpVerificationIntegrationTest`); the four-eyes rule and status-transition
  guard (DB-level, untouched); audit append-only (S2-01, untouched). All re-ran unchanged below.

## 5. Flyway and gates — final output, verbatim

Flyway from scratch: every integration test run applies all 40 migrations to an empty
Testcontainers database (confirmed live in every run this session, e.g. "Successfully applied 40
migrations to schema public, now at version v0040") — this *is* the from-scratch proof per prior
sessions' convention. No re-run needed for idempotency since these two migrations are pure
`ADD COLUMN`, no data rewrite.

`./mvnw test -Pdb-integration-test` (Docker running, live PostgreSQL 18):

```
[INFO] Tests run: 426, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

426 against the S3-11 baseline of 354 — 72 new tests across `ScanAttemptBudgetTest`,
`StubUqudoClientTest`, `StubCivilRegistryClientTest`, `IdentityScanServiceTest`,
`IdentityScanControllerTest`, `IdentityScanIntegrationTest`, plus stage 7 additions to
`DataEntryServiceTest`/`DataEntryControllerTest`/`DataEntryIntegrationTest`.

`./mvnw verify` (Spotless + JaCoCo, standard/non-integration tier):

```
[INFO] Tests run: 366, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 191 files clean - 0 needs changes to be clean, 0 were already clean, 191 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Line coverage 92.5% (2467/2668), against the 80% gate. backoffice/ and mobile/ gates were not
run — this task touched only backend/.

## 6. Documentation updates

- `docs/components/uqudo-sdk.md` — new section recording the stub's assumed payload shape
  (`data.documents[0].scan`, per-field names) as [UNVERIFIED], explicitly not changing any
  existing [UNVERIFIED] marker — S1-02 still rewrites the parser wholesale against a real JWS.
- `docs/components/civil-registry.md` — new section recording that "not found" (empty `Optional`)
  and "unreachable" (thrown exception) are both invented, not observed, contracts — R-031 stands.

## 7. Plan-file updates

- `EXECUTION_PLAN.md`: S3-12 row added, ✅, with both review passes' dispositions summarised.
- No new BACKLOG.md/RISKS.md entries — every finding from both review passes was fixed this
  session, not deferred.

## 8. Commit and push proof

```
$ git log --oneline -1
32a07fc feat: S3-12 — identity pipeline, stages 7-9 (Uqudo scan + Civil Registry review)

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `7a9db84..32a07fc  main -> main`.
