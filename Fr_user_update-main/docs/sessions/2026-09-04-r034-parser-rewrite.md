# R-034 — the quarantined Uqudo JWS parser rewritten against S1-02's real shapes (2026-09-04)

Bundled: BL-028's backend half (partial face-session JWS on the terminated path), R-012's stale
"30 minutes" copy, and the two live defects S1-02 found. Evidence: the S1-02 session report's
"Redacted shapes" and "Findings that change the backend", the card's "S1-02 observed" section,
and the redacted `*.shape.json` files under `backend/target/uqudo-spike/` (keys, types and
**string lengths** only — the captured `.jws` / `.payload.json` files were not opened; every
fixture value is synthetic). No credentials touched, no `.gitignore` change, nothing outside the
repository.

## What changed, in one table

| Area | Before (S3-12/S3-13 stub) | After (observed at S1-02) |
|---|---|---|
| Enrolment envelope | top-level `documentType` | `data.documents[0].documentType`; `data = {source, nonce, documents[], verifications[]}` |
| OCR fields | flat `scan.*` | `scan.front` / `scan.back` per the country field tables (passport: all on `front`; SDN_ID: MRZ block, `placeOfIssue`, `issueDate`, English name on `back`) |
| Anti-spoof scores | `scan.idPrintScore` (ints) | `verifications[i].{idPrintDetection,idScreenDetection,idPhotoTamperingDetection}.score` (decimals: 17.1 / 10.54 / 0.68), rounded to the `smallint` columns |
| Image checksums | `frontImageChecksum` (invented) | `<imageKey>Checksum` — `faceImageIdChecksum`, `frontImageIdChecksum`, … ; `backImageId: null` on a passport |
| Dates | `LocalDate.parse(scan.dateOfBirth)` | `dateOfBirth` is the 6-char MRZ form; `*Formatted`/`*Full`/`*FullFormatted` are 10 chars → ordered, never-throwing fallback |
| SDN_ID English name / version | `scan.nameEn` (does not exist) | `back.fullName` (FIB) or `back.name` (docs); version from `front.bloodType`; `identityNumber` fail-closed |
| Face binding | `jti == expectedFaceSessionId` | `data.sessionId == expectedFaceSessionId`; `jti` is a separate UUID |
| Face purge | `purgeSession(parsed.jti())` — silent 204 no-op | `purgeSession(parsed.sessionId())` |
| Vendor fields | — | `face.error` surfaced (nullable) into the audit payload; `falseAcceptRate`, `data.source`, `deviceAttestation` tolerated, never read |
| BL-028 | — | `LivenessTerminatedRequest.partialJws` (optional) → `UqudoClient.verifyAndParseIncompleteFaceSession` → `uqudo_face_jws` artifact on `liveness_attempt_terminated`, match detail nullable in the payload; spike builder calls `returnDataForIncompleteSession()` |
| `exp - iat` in fabricated JWS | 1800 / 600 | 7200 / 600 (observed) |

Files: `uqudo/domain/{UqudoClient, ParsedFaceResult, ParsedEnrolmentResult, ParsedIncompleteFaceResult(new)}`,
`uqudo/stub/StubUqudoClient`, `liveness/service/LivenessService`, `liveness/web/{LivenessController,
LivenessTerminatedRequest}`, `identityscan/service/IdentityScanService` (comment only), the three
tests, `mobile/lib/spike/uqudo_spike_screen.dart`, and the plan files listed under "Plan files".

## Design decisions (each with the reason)

- **Scores rounded, no migration.** `app.scan_result.id_*_score` is `smallint` (V0008) and the
  documented rejection thresholds are 50/50/70 — integer resolution loses nothing an operator or a
  future gate would act on. Recorded on `ParsedEnrolmentResult`'s Javadoc.
- **Dates never throw.** The redacted shape gives lengths, not separators: `dateOfBirth` = 6
  (MRZ `yyMMdd`), `dateOfBirthFormatted`/`Full`/`FullFormatted` = 10. Order tried per base key:
  `FullFormatted` → `Formatted` → `Full` → bare; per value: ISO, `dd/MM/uuuu`, `dd-MM-uuuu`,
  `uuuu/MM/dd`, `uuuuMMdd`, then MRZ `yyMMdd` with a past-leaning window for birth/issue and a
  future-leaning one for expiry. Nothing parses → `null`. A date gates nothing (customer.md Stage 9
  RESOLVED); the S3-12 `LocalDate.parse` on the real 6-character value would have been an uncaught
  500 on the first real submission.
- **`ParsedFaceResult` keeps `jti` and gains `sessionId`.** `jti` stays the replay key
  (`app.face_result.uqudo_jti` UNIQUE, retry detection); `sessionId` is the binding and the purge
  id. Both are ours to name; the parser is the only place that knows where they come from.
- **`faceError` in the audit payload only.** A `String`, nullable — `CanonicalJson` accepts flat
  String/integral/Boolean/null, so `falseAcceptRate` (a double) is deliberately not extracted.
- **Partial-JWS rejection is recorded, never thrown.** On `/terminated`, the raw `partialJws` is
  hash-chained as a `uqudo_face_jws` artifact on EVERY branch (via `recordFailedAttempt`'s new
  optional `AuditArtifact` parameter, mirroring `IdentityScanService.recordFailedAttempt`'s `jws`
  for a rejected scan JWS), and the attempt is counted exactly as without one — the termination is
  the primary fact. Verified → `partialJwsStatus:"verified"`, `partialJti`, `partialMatch` /
  `partialMatchLevel` / `partialFaceError` (each nullable). Failed signature/`exp`/binding, or any
  other parser failure → `partialJwsStatus:"rejected"` + a fixed `partialJwsReason` category
  (`verification_failed` / `expired` / `parser_failure`), never the exception text (review
  findings 2 and 3). No `face_result` upsert, no image
  download, no purge — the Face Session self-deletes at 600 s and the artifact is evidence, not
  an outcome.
- **Enrolment purge unchanged** (`jti` == our minted session id, S1-02 finding 1) — a comment now
  says why, beside the line.
- **Mobile: the spike screen is the only `FaceSessionConfigurationBuilder`** in `mobile/` (S5-08
  does not exist). It now calls `returnDataForIncompleteSession()` (present on the 3.10.0 builder,
  `uqudosdk_flutter.dart:759`) and forwards `jsonDecode(e.code)['data']` raw as `partialJws` to
  the spike backend. No package added → no iOS check applies. S5-08's real screen still owes both
  (its EXECUTION_PLAN note already says so).
- **Face nonce not minted.** S1-02 proved `data.nonce` is echoed when set; the backend mints none
  for face sessions and the parser neither reads nor requires it. Wiring one is a stage-10 contract
  change — follow-up, not this diff.
- **No real RS256/JWKS adapter.** R-034's scope is the parser; the stub remains the only
  `UqudoClient` and `UqudoClientConfiguration` still refuses to start without an explicit selection.

## Defect proofs

**Defect 1 (face binding)** — `StubUqudoClientTest.faceJwsBindsOnDataSessionIdWhileJtiIsADifferentUuid`
asserts `parsed.sessionId() == sessionId` and `parsed.jti() != sessionId` on a JWS whose `jti` is
a fresh UUID. A direct wrong-value assertion: against the old `jti == expected` check the fabricated
JWS is rejected outright, so no revert run is needed (CLAUDE.md's revert rule).

**Defect 2 (face purge)** — `LivenessServiceTest.purgeUsesTheFaceSessionIdNeverTheJti` asserts the
ID PASSED to `purgeSession` (`verify(uqudoClient).purgeSession("face-session-1")` and
`never().purgeSession("3d7c2c1e-jti-not-the-session")`). This is the indirect case the task
named — "purge returns success" passes against either version — so the fix was reverted and the
test run:

```
$ sed -i 's/purgeSession(parsed.sessionId())/purgeSession(parsed.jti())/' LivenessService.java
508:      uqudoClient.purgeSession(parsed.jti());
=== REVERTED: running LivenessServiceTest ===
[ERROR] Tests run: 19, Failures: 2, Errors: 0, Skipped: 0 <<< FAILURE! -- in sd.gov.bank.fruserupdate.liveness.service.LivenessServiceTest
[ERROR] LivenessServiceTest.purgeUsesTheFaceSessionIdNeverTheJti -- <<< FAILURE!
Argument(s) are different! Wanted:
-> at ...LivenessServiceTest.purgeUsesTheFaceSessionIdNeverTheJti(LivenessServiceTest.java:390)
Actual invocations have different arguments:
-> at ...LivenessService.submitFaceResult(LivenessService.java:508)
[ERROR] LivenessServiceTest.passingResultTouchesActivityAndDoesNotClearTheReferenceImage -- <<< FAILURE!
=== RESTORED ===
508:      uqudoClient.purgeSession(parsed.sessionId());
```

(The second failure is the pre-existing pass-path test, which now also uses a distinct `jti`.)
Restored, `LivenessServiceTest`: 19/19 green (in the gate run below).

## Gates

Backend, `./mvnw verify -Pdb-integration-test` (JAVA_HOME = Android Studio JBR 21.0.8, Docker
29.7.2 running). Three runs; the first two failed on my own diff, each fixed before the next:

1. Run 1 — `Tests run: 657, Failures: 0, Errors: 1`:
   `LivenessIntegrationTest.aPurgedReferenceImageIsRefusedAsConflictNotA500 … ERROR: more than one
   row returned by a subquery used as an expression`. Cause: my new integration test reused account
   `0000000419`, already used by that pre-existing test in the same class, so its cycle subquery
   found two cycles. Fixed by moving the new test to `0000000420` (the last free number in the
   class's documented `0000000411–0000000420` range).
2. Run 2 — `Tests run: 657, Failures: 0, Errors: 0`, then `spotless-check` failed: the
   `sed`/`perl` edits made after the earlier `spotless:apply` (the revert-proof, the account fix,
   the enrolment-purge comment) wrote LF into files Spotless keeps as CRLF. `spotless:apply` re-run.
3. Run 3 — green before the review: `Tests run: 657, Failures: 0, Errors: 0, Skipped: 0`,
   Spotless clean, `All coverage checks have been met`, `BUILD SUCCESS` (one line, per the
   intermediate-run rule).
4. Run 4 — after the reviewer's fixes (findings 1–3, three new tests), final, verbatim:

```
[INFO] Tests run: 660, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 393 files clean - 0 needs changes to be clean, 0 were already clean, 393 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

JaCoCo line ratio from `target/site/jacoco/jacoco.xml`: 4967 / 5393 = **92.10 %** (S1-02: 92.00 %).
Test count 639 → 660: `StubUqudoClientTest` 19 → 35, `LivenessServiceTest` 14 → 20,
`LivenessIntegrationTest` 9 → 10.

Mobile (one file touched, no package added), `fvm flutter analyze`:
```
Analyzing mobile...
No issues found! (ran in 55.7s)
```
`fvm flutter test` (run after the reviewer's finding 5 — the rule is test **and** analyze):
```
01:25 +225: All tests passed!
```
Coverage script not re-run: `lib/spike/` is imported by no test and does not appear in lcov
(S1-02 report), so the 85.03 % figure is unchanged from S1-02 by construction.

Back office: untouched.

## Review

`@agent-reviewer` (read-only, against the diff and the task text), one pass; no BLOCKER.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | SHOULD FIX | `optionalText`/`requiredText` called `asString()` with a null guard only; Jackson 3 (`jackson-databind` 3.1.4) throws `JsonNodeException` on an object/array node — unchecked, neither `JwsVerificationException` nor `ArtifactExpiredException`, so an SDN_ID payload with any read key as an object would escape `IdentityScanService`'s catch as an unaudited, uncounted 500 — the same failure class the diff removed from `LocalDate.parse`, one layer down. | **Fixed.** Every read is type-guarded (`isValueNode()`; `isBoolean()`/`isNumber()` for `face.match`/`matchLevel`/`exp`; `readTree` wrapped). A non-scalar reads as absent: `null` for an optional field, a typed rejection for a required one. Tests `nonScalarValuesReadAsAbsentNeverAsAThrow`, `faceFieldsOfTheWrongTypeAreATypedRejectionNotAThrow`. |
| 2 | SHOULD FIX | `partialJwsReason` copied `rejected.getMessage()` into the audit payload: nimbus's text can quote client-controlled header bytes (an `alg` with U+0000 survives `LivenessController.clean`, since a compact JWS is pure base64url), and `CanonicalJson` refuses control characters *inside* `recordFailedAttempt`'s transaction — rolling the whole termination record back, uncounted, 500. Only two exception types caught. | **Fixed.** Three fixed categories (`verification_failed` / `expired` / `parser_failure`), never the message; a `RuntimeException` arm so the termination is recorded whatever the optional artifact does to the parser. Test asserts the category and that the message text is absent. |
| 3 | NOTE | On rejection the partial artifact was dropped, contradicting `IdentityScanService`'s "a rejected JWS is stored the same way an accepted one is" and the new overload's own "same shape" claim; for BL-028 the artifact is the evidence, and if our binding is what is wrong the only copy would be gone. | **Fixed.** The artifact is built before verification and stored on every branch. Test `terminatedWithARejectedPartialJwsStillCountsTheAttemptAndKeepsTheArtifact`. |
| 4 | NOTE | Four stale lines left self-contradicting: uqudo-sdk.md's "retry window … **30 minutes**, and stage 12's resume copy states that", its "JWS verification" checklist (still `jti == sessionId` and top-level `documentType`, no face-session carve-out), its open-item "what is the real image-retention bound", and PROJECT_PLAN.md's "`DELETE /api/v1/info/{jti}`" one bullet below the corrected one. | **Fixed**, all four. |
| 5 | SHOULD FIX | Mobile tier touched, only `flutter analyze` run; CLAUDE.md requires the test gate too. | **Fixed** — `fvm flutter test` run, output under Gates. |

Checked and clean per the reviewer: no caller binds or purges a face session by `jti`; `firstText(front, back, …)` cannot pick a wrong-side SDN_ID value (`name` is the only key on both sides and both are read side-explicitly); `score()`/`parseDate` cannot throw; the terminated path calls `recordFailedAttempt` exactly once on every branch and writes no `face_result`, downloads nothing, purges nothing; audit payload values are String/Boolean/Integer/null only; defect 2's revert-proof is the indirect case the rule requires it for; no secrets, real account numbers or captured values anywhere; `domain`/`service` packages plain.

## Plan files

- `RISKS.md`: R-034 → ✅ Retired (points here); R-012 → ✅ Retired (both stale copies corrected);
  R-016 stays 🔴 with a pointer to BL-028's built backend half (mitigation, not proof).
- `PROJECT_PLAN.md` Constraints: "30 minutes" → 2 hours from `iat` (measured, S1-02); the
  "`jti` is the session id" and "`DELETE /info/{jti}`" settled facts corrected to enrolment-only /
  204-for-any-UUID. AD-002a row untouched (premise already marked contradicted at S1-02).
- `docs/journeys/customer.md` Stage 13 stale-artifact case: "within 2 hours", citing S1-02.
- `BACKLOG.md` BL-028: backend half + spike builder call closed; S5-08's real screen and the
  `[UNVERIFIED]` contents of the partial artifact still open.
- `docs/components/uqudo-sdk.md`: the two S3-12/S3-13 "stub implementation — what it assumes"
  sections replaced by one "rewritten at R-034" section; BL-028 section gets the built line; the
  "CLOSED at 30 minutes" retention bullet and the matching open-item line marked superseded.
- `EXECUTION_PLAN.md`: no change — R-034 is a risk row, not a task; S5-08's note already carries
  the mobile obligation.

## Skipped / not done, deliberately

- The captured real JWS files were not read; SDN_ID front/back placement therefore remains
  unobserved (UAT residual, recorded on the card and in R-034's retirement text).
- No purge on the terminated-with-partial-JWS path (600 s self-expiry; out of BL-028's scope).
- No face nonce on the backend; no RS256/JWKS adapter; no schema change.
- Mobile `flutter test`/coverage not re-run (reason above).

## Commit proof

```
$ git log --oneline -1
baf0867 R-034: rewrite the quarantined Uqudo JWS parser against S1-02's observed shapes; fix face binding and purge id; BL-028 backend half; R-012 retired

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

This proof block was appended in a follow-up docs commit (the report cannot carry its own hash),
the same pattern the S1-02 report used.
