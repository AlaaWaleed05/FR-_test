# S3-13 — Stage 10 liveness/face match, Stage 11 signature, Stage 12 completion/submission

## 0. Commit check

`git log --oneline -3` confirmed `32a07fc` (S3-12) present and an ancestor of `main`; `git status`
clean at session start.

## 1. The gap found before writing any code, and the agreed fix

Stage 10 must create a Uqudo Face Session from `documents[0].scan.faceImageId` — the portrait
extracted at stage 8. S3-12's `IdentityScanService.submitScan` downloads every image, checksums it,
and discards ALL bytes once verified (only a `sha256` hash and a synthetic storage key survive), and
purges Uqudo's own session right after acceptance — before stage 9's Civil Registry review, of
unbounded duration, lets the customer reach stage 10. By stage 10 the portrait is gone from both
Uqudo and this backend.

Agreed fix, confirmed with the user before writing code (not an AD-004 resolution): one narrow,
transient bytea column, `app.scan_result.face_reference_image` (V0041), holding the portrait bytes
`submitScan` already has in hand. Stage 10 re-uploads them to Uqudo fresh on every attempt (a new
Face Session is required each time regardless — Uqudo deletes it after 600s). Two conditions the
user attached:

1. **The column must actually be cleared** — on face-match success, on the identity cycle being
   superseded, and on the profile reaching any terminal status — each proven by a test reading the
   column back as `NULL`, not merely that a Java method was called. Closed with two `AFTER UPDATE`
   triggers (V0042: one on `app.identity_cycle` for supersede, one on `app.profile` for any
   `is_terminal` status, joined via `status_code` rather than hardcoded) plus one explicit clear on
   face-match success (the only path with no natural row-transition to hook). Proven in
   `LivenessIntegrationTest.faceReferenceImageIsClearedOnFaceMatchSuccess` /
   `…WhenTheIdentityCycleIsSuperseded` / `…WhenTheProfileReachesATerminalStatus` — each a direct
   `SELECT face_reference_image` assertion, not a mock verification.
2. **A new RISKS.md entry, not only a code comment** — filed as R-047, naming what AD-004 must
   revisit when it closes (this column sits outside AD-004's nine-artifact-kind object-storage
   model). `PROJECT_PLAN.md`'s AD-004 entry also gained one sentence pointing at it.

Framing correction from the user: purging a Uqudo session promptly is ordinary data-minimisation
hygiene (the solution ships its own credentials at delivery), not a control over a borrowed tenant —
applied to this session's new code only; S3-12's own already-shipped comments citing R-001 were left
untouched (reverted once by mistake during review, see §3).

## 2. What was built

Three new feature packages, each `domain`/`service`/`jdbc`/`web`:

- **`liveness`** (Stage 10) — `LivenessService` mirrors `IdentityScanService`'s shape exactly:
  external Uqudo calls outside any lock-holding transaction, a rejection before any transaction
  audited immediately, a rejection under a re-check lock audited after the (empty) transaction
  commits. `POST /api/v1/liveness/{token,result,terminated}`. `UqudoClient` gained
  `createFaceSession`/`verifyAndParseFaceSession` + `ParsedFaceResult`. Retry budget: 5, single
  counter (no per-document-type split — one identity cycle, not a re-scan), 24h block, shared
  between liveness and face-match failure per customer.md ("Same budget as liveness, no separate
  block"). `face_result`/`face_audit_trail` are `ON CONFLICT DO UPDATE` upserts — one row per cycle
  reflects the latest attempt, full history lives in the audit trail (V0008 already shaped this way).
- **`signature`** (Stage 11) — `POST /api/v1/signature`, base64-in-JSON (no multipart precedent
  anywhere in this backend). Two capture routes, mandatory, gated on a passed `face_result` for the
  active cycle. Stage 11's own `[POLICY: size/format limits]` marker in customer.md stays
  unresolved — 5MB/PNG-or-JPEG is a stated operational placeholder, not a resolution.
- **`submission`** (Stage 12) — `POST /api/v1/submission`. Idempotent on
  `submitted`/`approved`/`rejected` (customer.md Stage 13: "the app reconciles on resume, never
  assumes completion") rather than erroring on a resubmit — no new idempotency-key infrastructure
  needed since the profile's own status already carries that information. Assigns a reference number
  (`FRU-` + 9 digits, V0045's sequence — a placeholder format, no bank spec exists, filed as BL-019),
  sets `submitted`, and enqueues to `app.notification_outbox` for VERIFIED channels only, all inside
  one transaction — the first real caller of that port outside S3-05's own proof-of-shape test.
  Rejects a submission with no signature or no passed liveness (this task's explicit proof
  requirement).

Six migrations: V0041 (bridging column), V0042 (its two auto-clear triggers), V0043/V0044 (liveness
retry-budget + pending-session columns, mirroring V0039/V0040), V0045 (reference-number sequence),
V0046 (partial unique index closing a reviewer-found defect, see §3).

## 3. Review — `@agent-reviewer`, two passes, both with real findings

**First pass**, 1 BLOCKER + 7 SHOULD-FIX, all fixed:

- **BLOCKER** — `LivenessService.recordFailedAttempt` (the shared choke point behind
  `reportLivenessTerminated` and two `submitFaceResult` failure paths) was missing the
  `awaiting_registry` guard S3-12's own reviewer forced into its stage-8 sibling — a profile paused
  at stage 9 could reach an undefined `awaiting_registry → blocked_liveness` V0020 transition,
  surfacing as an unmapped 500. Fixed with the guard plus a new `RegistryReviewPendingException`,
  and a `pendingFaceSessionId` match check added to `reportLivenessTerminated` itself.
- `issueFaceSessionToken`'s second phase resumed from `blocked_liveness` unconditionally; a block
  applied by a concurrent request in the unlocked external-call window between phases could be
  silently cleared. Fixed: phase 2 now re-checks `livenessBlockedUntil() == null || isAfter(now)`.
- The face-session JWS parser never checked `exp` (the enrolment parser does, per uqudo-sdk.md's own
  design rule). Fixed: same `ArtifactExpiredException`, not counted against the budget.
- New code had reframed `UqudoClient.purgeSession`'s Javadoc away from the still-live R-001 tenant
  rationale in `RISKS.md`. Reverted; the framing correction from §1 applies only to new comments.
- A lost-audit-trail-image event recorded only `jti`/reason, discarding `match`/`matchLevel` — the
  exact fraud signal R-016 exists to preserve. Fixed: both values added to that payload.
- A migration comment claimed a RISKS.md entry that did not exist. Filed (R-047, §1).
- Repeat signature submissions silently multiplied retained-PII rows (`UNIQUE(cycle_id, kind)`
  doesn't dedupe NULL `cycle_id`). Fixed with V0046's partial unique index + an upsert.
- No test covered the block-resume path. Added `LivenessServiceTest` (mocked collaborators, real
  fixed `Clock`, mirrors `IdentityScanServiceTest`'s shape).

**Second pass, against the fixed diff, found a second BLOCKER inside the first pass's own fixes** —
this project's seventh consecutive session where the re-review catches a defect in the fix itself:

- **BLOCKER** — `reportLivenessTerminated` had no guard against a profile that had already PASSED
  face-match. `pending_face_session_id` is never cleared, so a client replaying the terminated call
  five times drives an already-complete stage 10 into `blocked_liveness` with `passed=true` —
  `issueFaceSessionToken`'s own already-passed guard then refuses to ever resume it, permanently
  stranding the profile with ordinary API calls, no race required. Fixed: the same `facePassed`
  guard added to the shared `recordFailedAttempt` choke point.
- `submitFaceResult`'s own re-lock re-checked only `terminal()`, missing the identical
  `blocked_liveness` re-check its sibling phase had just gained in the first pass. Fixed.
- A retried upload of the exact same JWS (customer.md's own documented retry path: "a returned JWS
  is retained and the upload retried rather than repeating the check") spent a second draw from the
  budget for one real-world attempt, since `FaceJwsAlreadyAcceptedException` only fires across
  different cycles. Fixed with a new `currentFaceResultJti` idempotency check.
- The `exp` fix and the duplicate-signature fix both shipped with no test exercising them. Added
  `StubUqudoClientTest.expiredFaceJwsThrowsArtifactExpiredNotJwsVerificationFailure` and
  `SignatureIntegrationTest.resubmittingASignatureReplacesTheRowInPlace`.
- Three documentation notes: `AbstractPostgresIntegrationTest`'s stale "six classes" wording;
  `UqudoClient.purgeSession`'s Javadoc claim ("no byte copy of any image anywhere") made false by
  V0041 (one clause added); AD-004 not naming V0041 (added, §1).
- The Face-Session-id-into-the-enrolment-purge-endpoint cross-API assumption (uqudo-sdk.md documents
  `DELETE /api/v1/info/{jti}` against the *enrolment* session, not stated to accept a Face Session
  id) was left marked `[UNVERIFIED]` in the component card and at the call site, not resolved from
  documentation alone — per CLAUDE.md's integration rule.

All fixed same session; every fix re-verified by the gate run in §5.

## 4. Proofs

- **Face-match failure vs. liveness termination, distinct event types, both with/without an audit
  artifact**: `LivenessIntegrationTest.faceMatchFailureAndLivenessTerminationAreRecordedAsDistinctEventTypes`
  — asserts `face_match_evaluated` has exactly one `audit_artifact` row (the raw JWS) and
  `liveness_attempt_terminated` has zero.
- **Server-side threshold rejects `matchLevel` below 3 despite a verified JWS**:
  `serverSideThresholdRejectsMatchLevelBelowThreeDespiteVerifiedJws` (`match=true, matchLevel=2`).
- **Retry reuses the accepted scan, no rescan**: `retryAfterFailedAttemptReusesTheAcceptedScanWithNoRescan`
  — same `identity_cycle` id before and after a failed attempt and a passing retry.
- **Budget exhaustion blocks, prior stages intact**: `exhaustingTheLivenessBudgetBlocksWithPriorStagesIntact`
  — `blocked_liveness`, a further token request refused, `scan_result.identity_number` still queryable.
- **The three clearing paths**: §1/§3, `LivenessIntegrationTest`, direct SQL `NULL` assertions.
- **Signature stored via both routes; a submission without one rejected**:
  `SignatureIntegrationTest.signatureStoredViaTheDrawnRoute`/`…UploadedRoute`;
  `SubmissionIntegrationTest.submissionWithoutASignatureIsRejected` (409, profile stays `in_progress`).
- **Stage 12's ordering — reference number exists before return, outbox not sent inline**:
  `SubmissionIntegrationTest.submissionSetsStatusAssignsReferenceNumberAndEnqueuesOnlyVerifiedChannels`
  — re-reads `app.profile` directly after the HTTP call (the state a dropped connection would leave
  for the app to reconcile from) and asserts the outbox row's `state='pending'`, never dispatched.
- **Notifications enqueued for verified channels only**: same test — only `sms` (OTP-verified in the
  test setup) gets a row; `whatsapp` (selected, never verified) gets none.
- **Idempotent resubmission**: `resubmissionIsIdempotentAndReturnsTheSameReferenceNumber` — same
  reference number twice, outbox count stays 1.
- **Regression**: S3-12's stage 8 chain and both stage 9 rejections (`IdentityScanIntegrationTest`,
  unchanged, still passing), S3-11's data-entry endpoints, S3-08's lockout escalation, S3-07's
  re-entry, S3-06's three-channel selection, the status-transition guard, audit append-only — all in
  the full suite run below with no failures.

## 5. Flyway and gates — final output, verbatim

Flyway from scratch: every integration test run applies all 46 migrations to an empty Testcontainers
database (six of them, V0041-V0046, added this session — all pure `ADD COLUMN`/`CREATE TRIGGER`
/`CREATE INDEX`/`CREATE SEQUENCE`, no data rewrite, so no separate idempotent-re-run proof needed
beyond every test run already doing exactly that).

`./mvnw test -Pdb-integration-test` (Docker running, live PostgreSQL 18):

```
[INFO] Tests run: 468, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

468 against the S3-12 baseline of 426 — 42 new tests across `LivenessAttemptBudgetTest`,
`StubUqudoClientTest` (9 new), `LivenessServiceTest` (13), `LivenessIntegrationTest` (7),
`SignatureIntegrationTest` (5), `SubmissionIntegrationTest` (3).

`./mvnw verify` (Spotless + JaCoCo, standard/non-integration tier):

```
[INFO] Tests run: 393, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 246 files clean - 0 needs changes to be clean, 0 were already clean, 246 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Line coverage 90.19% (16352/18130), against the 80% gate. backoffice/ and mobile/ gates were not
run — this task touched only backend/ (plus PROJECT_PLAN.md/RISKS.md/BACKLOG.md/EXECUTION_PLAN.md
and docs/components/uqudo-sdk.md).

## 6. Documentation updates

- `docs/components/uqudo-sdk.md` — new "S3-13 stub implementation" section: no nonce for the face
  session [UNVERIFIED], the face-session JWS payload shape [UNVERIFIED], `exp` semantics assumed by
  analogy [UNVERIFIED], and the Face-Session-into-enrolment-purge cross-API assumption [UNVERIFIED]
  (found at the second review pass) — four new open items, none resolved from documentation alone.
- `PROJECT_PLAN.md` — AD-004 gained one sentence naming `app.scan_result.face_reference_image` and
  R-047; not a resolution of AD-004 itself.
- `RISKS.md` — R-047 filed (§1).

## 7. Plan-file updates

- `EXECUTION_PLAN.md`: S3-13 row added, ✅, both review passes' dispositions summarised.
- `BACKLOG.md`: BL-019 filed — the reference-number format (`FRU-` + 9 digits) is a placeholder, no
  bank spec exists; revisit before BL-015 (the back-office profile-list/search endpoint) is built.

## 8. Commit and push proof

`git log --oneline -1`:

```
15705e4 feat: S3-13 — Stage 10 liveness/face match, Stage 11 signature, Stage 12 submission
```

`git status`:

```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed straight to `main` (this project's norm, not a feature branch):
`ffdee56..15705e4  main -> main`.
