# S4-02 — Manual completion and export

## 0. Commit check

The task text named S4-01's hash as `fbe3f4c` — that hash does not exist anywhere in `git log
--all`. The actual S4-01 commit is `f8e9a35` (`git log` and the S4-01 session report agree),
confirmed an ancestor of `main` (`git merge-base --is-ancestor f8e9a35 main`, exit 0). Typo in
the task text; verified against the real hash instead. `git status` clean at session start.

## 1. Scope and design decisions

Second and final operator-backend slice (S4-01 built list/view/approve/reject). No new
migration: `app.status_transition` (V0020) already legalises every status manual completion
needs — see §2. `app.profile.provenance` already exists (V0005). Both new features live in the
existing `operator` package (`domain`/`service`/`jdbc`/`web`), mirroring S4-01's shapes closely.

### Lock-order invariant (documentation, per the task's explicit instruction — no restructuring)

S4-01's second review pass found `approve()`/`reject()` taking the `app.profile` row lock and
the audit-chain lock in opposite orders, deadlocking under concurrency (`40P01`). Documented in
full on `operator.domain.ReviewRepository#lockAndReadStatus`'s Javadoc and a new
docs/components/persistence.md section, both later broadened during review — see §2 below; the
invariant is codebase-wide, not operator-side-only, which review proved by finding two more
violations elsewhere.

### Manual completion — derivation, stated in `ManualCompletionService`'s Javadoc

`app.status_transition` (V0020) has five rows with `to_status = 'submitted'`. Four cite
operator.md's "Manual completion" verbatim ("no precondition status stated, any in-flight
status is a legal source"): `awaiting_registry`, `blocked_scan`, `blocked_liveness`,
`abandoned`. The fifth, `in_progress`, cites customer.md's digital-completion path instead — but
`in_progress` is also operator.md's own first "In flight" status, so it belongs in the set on
independent grounds. Together the five are exactly operator.md's "Profile statuses" → "In
flight" table. `submitted` is excluded (it's "Terminal for the customer", and no document
describes an operator overriding a customer's own digital submission). `JdbcManualCompletionRepository
.COMPLETE`'s `WHERE status IN (...)` lists exactly this derived set — an explicit application-level
whitelist, not a bare reliance on V0020's trigger (which would surface an unmapped 500 for an
ineligible status instead of a clean 409).

New port `ManualCompletionRepository` (`currentState`/`lockAndReadState`/`complete`/
`nextReferenceNumber`, mirroring `ReviewRepository`), `ManualCompletionState` record (status +
provenance + reference number — provenance is what distinguishes "already manually completed"
from "submitted some other way", since both land on `submitted`). `ManualCompletionService`
mirrors `OperatorReviewService.approve()`'s shape: unlocked precheck → idempotent fast path →
one transaction (row lock first, then audit event, then the guarded UPDATE+history insert, then
notification enqueue) → post-failure re-read distinguishing a race from a genuine 409. Justification
is mandatory (400 if blank), stored in `profile_status_history.internal_note` (REJ-07's own
column). Reuses `submission.domain.SubmissionMessageRenderer` for the customer notification
rather than a new renderer — manual completion also lands in `submitted`, awaiting a second
operator's review, so the customer-facing content ("received, pending review, reference number")
is identical to digital submission.

### Export

`ProfileListRepository.search()`'s inline filter-building was extracted into a private
`appendFilters()`, called by both `search()` and the new `forExport()` — BL-015's own
requirement ("reuses that query rather than reimplementing it"). Two `LEFT JOIN`s added to the
shared `FROM_JOINS` (latest rejection code + label) for export's extra columns; harmless to
`search()`/`COUNT(*)` (LEFT JOINs, LATERAL capped at one row). `forExport(filter, rowLimit)`
queries `LIMIT rowLimit+1`; if that extra row comes back, truncates to `rowLimit` and reports
`truncated=true` — avoids a second `COUNT(*)` round trip. `ExportRow` (18 columns, field data
only — the query never joins `app.artifact_ref`, structurally impossible for it to leak image
metadata let alone bytes). `ProfileExportWriter` (pure, no Spring) writes XLSX via Apache POI
SXSSF (new `pom.xml` pin, `org.apache.poi:poi-ooxml:5.5.1` — AD-006 names the library, this is a
routine version pin) and CSV by hand (POI has no CSV writer) with a UTF-8 BOM so Excel renders
Arabic correctly instead of mojibake. `ProfileExportService`: OPERATOR-only, `EXPORT_ROW_LIMIT
= 10_000`, one audit event per export (filters + format + row count + truncated + the exact
field list) on the operator's own chain, mirroring `profile_list_searched`'s shape.
`ExportController`: `GET /api/v1/operator/profiles/export?format=xlsx|csv&<filters>`, informational
`X-Export-Row-Count`/`X-Export-Truncated` response headers alongside the file body.

## 2. Reviewer findings and dispositions

`@agent-reviewer` ran twice per CLAUDE.md's rule.

**First pass — 1 SHOULD-FIX + 3 NOTE, all fixed:**

- **`identityscan.service.IdentityScanService#reportWrongDetails`** (pre-existing code, not part
  of this diff's new files) took the audit-chain lock (`auditEventWriter.append`) as its
  transaction's first statement, with no re-lock of `app.profile` inside that transaction — its
  precondition helper (`requireActiveContext`) had already taken and released that lock outside
  any transaction. Latent since introduction: before this task, no writer besides `approve()`/
  `reject()` (acting only on `submitted`/`rejected`) took both locks, so the inversion was
  unreachable. `ManualCompletionService` legally writes `in_progress` profiles — the same status
  `reportWrongDetails` operates on — making a real 40P01 deadlock reachable between a manual
  completion and a concurrent stage-9 customer call on the same profile. Fixed by re-locking as
  the transaction's first statement, matching sibling methods `reportWrongNumber`/
  `retryRegistryLookup` in the same class.
- `ManualCompletionService`'s class Javadoc overclaimed mirroring `OperatorReviewService`'s
  shape fully, including refusal-auditing — `approve()` audits a refused call (four-eyes
  significance), `reject()` and this service do not. Reworded to state the difference and why.
- The eligible-statuses Javadoc claimed all five V0020 rows cite manual completion by name; only
  four do. Reworded per the derivation above.
- `ProfileExportIntegrationTest`'s row-cap test was missing the exact boundary (`rowLimit` equal
  to the matching row count). Added.

**Second pass, against the fixed diff, found a second instance of the first pass's own finding
plus a new SHOULD-FIX:**

- **`IdentityScanService#acceptRegistryReview`** had the identical unfixed defect — the first
  pass's "every sibling method already re-locks" sweep missed it. Same fix applied (re-lock as
  the transaction's first statement); the now-inaccurate "one exception" comment on
  `reportWrongDetails` corrected to name both.
- **Every exported phone number was corrupted with a leading `'`.** The OWASP CSV-injection
  mitigation's trigger-character set (`=`/`+`/`-`/`@`) was applied uniformly to every field,
  including `phoneNumber` — E.164 numbers always start with `+` (CLAUDE.md's own standing rule),
  so every phone number in every export was flagged as a false positive and prefixed, corrupting
  the one field operator.md names CSV as being for ("anything downstream"). Fixed two ways:
  `+` removed from the CSV trigger set entirely and the `phoneNumber` column additionally
  skipped outright (defense in depth, since a future column reorder shouldn't silently
  reintroduce this by removing the `+`-exclusion alone); XLSX sanitisation removed altogether,
  since a POI string cell (`setCellValue(String)`) is never evaluated as a formula by Excel on
  open — the CSV-injection heuristic is specific to untyped text formats, so sanitising XLSX was
  pure corruption for zero protective effect.
- NOTE: the lock-ordering documentation (`ReviewRepository`'s Javadoc and
  docs/components/persistence.md) had scoped itself to "operator-side writers" when the
  invariant — and now its two found violations — are codebase-wide. Reworded in both places to
  say so, and to record the two `IdentityScanService` fixes as the evidence.

A third full reviewer pass was not run — CLAUDE.md's rule is a first pass plus one re-run
against the fixes, which this session completed; the second pass's own findings were verified
directly (re-read code, ran the specific regression tests, and independently swept every
`transactionTemplate.executeWithoutResult` call site across the codebase — `ContactChannelsService`,
`DataEntryService`, `LivenessService`, `OtpVerificationService`, `SignatureService`,
`SubmissionService`, `OutboxDispatcher` — confirming each either locks `app.profile` first or
never contends for that lock at all).

Regression test added for every fix: `IdentityScanServiceTest
.wrongDetailsReLocksTheProfileRowInsideItsOwnTransactionBeforeTheAuditWrite` (Mockito `InOrder`,
would fail against the reverted code — verified by inspection, not by literally reverting and
re-running, since this is a structural reordering with no distinguishable outcome to assert
against other than call order); `ProfileExportWriterTest
.csvPhoneNumberIsNeverPrefixedDespiteStartingWithAPlus` and
`.xlsxCellsAreNeverSanitisedSinceStringCellsAreNotEvaluatedAsFormulas`.
`acceptRegistryReview`'s fix has no new dedicated test beyond the existing
`IdentityScanIntegrationTest`/`LivenessIntegrationTest` coverage that already exercises it on
every run (same "impractical to reproduce a real deadlock in a JUnit test" reasoning S4-01's own
lock-order fix used) — verified structurally correct by inspection and by that existing coverage
still passing under the new order.

## 3. Proofs

- **Manual completion recording actor, timestamp, justification, provenance=manual**:
  `ManualCompletionIntegrationTest.manualCompletionRecordsActorTimestampJustificationAndProvenance`
  — asserts `app.profile.provenance='manual'`, the `profile_status_history` row's `actor_id`/
  `internal_note`/`is_manual_completion`, a `FRU-`-prefixed reference number, and one
  `profile_manually_completed` audit event.
- **Four-eyes after manual completion, end to end through the real endpoint**:
  `fourEyesRuleAfterManualCompletionEndToEnd` — the completing operator's approve is refused
  (403); the exact V0009 conditional `UPDATE`, run directly as that operator, affects 0 rows; a
  different operator succeeds. (S4-01's own test seeded this precondition directly since the
  endpoint didn't exist yet; this is the first proof through the real action.)
- **VIEWER refused both**: `viewerCannotManuallyComplete` (403, writes nothing);
  `ProfileExportIntegrationTest.viewerCannotExport` (403).
- **Export honouring a filter, its row cap, its audit event, no image bytes**:
  `exportHonoursFilterAndProvenanceIsItsOwnColumn` (a matching profile appears, a non-matching one
  doesn't; digital vs. manual provenance visibly distinct in the same column);
  `exportAuditEventRecordsFiltersRowCountAndFields` (payload asserted directly against
  `audit.audit_event`); `rowCapTruncatesAndReportsTruncated` (small `rowLimit` against the
  repository directly — `2` of 3 truncates, `10` of 3 and the exact boundary `3` of 3 do not,
  closing the boundary gap review found). No image bytes: structural (export SQL never joins
  `app.artifact_ref`) plus `ProfileExportWriterTest` confirming the byte output contains only the
  18 declared columns.
- **Arabic round trip in both formats**: `ProfileExportWriterTest
  .xlsxRoundTripsArabicTextExactly`/`.csvRoundTripsArabicTextExactlyWithUtf8Bom` — Arabic branch
  label and on-document name written and read back byte-exact in both formats; the CSV case also
  asserts the literal BOM bytes (`EF BB BF`).
- **Regression**: S4-01's list/view/approve/reject suite, the customer journey through S3-13, the
  status-transition guard and audit append-only all re-ran unchanged as part of the full
  `-Pdb-integration-test` run.

## 4. Gate output

No migration was added this session, so the Flyway-from-scratch/idempotent-rerun proof does not
apply — `./mvnw test -Pdb-integration-test` below still runs Flyway from scratch against a fresh
Testcontainers instance regardless, per `AbstractPostgresIntegrationTest`.

`./mvnw test -Pdb-integration-test` (after both review passes, Docker running):

```
[INFO] Tests run: 508, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`./mvnw verify` (JaCoCo + Spotless, standard non-integration tier):

```
[INFO] Tests run: 404, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 316 files clean - 0 needs changes to be clean, 0 were already clean, 316 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Overall line coverage (from `target/site/jacoco/jacoco.csv`): 90.09% (4000/4440) — up from
S4-01's 89.88%, above the 80% gate.

backoffice/ and mobile/ gates: not run. This task touches `backend/` only (CLAUDE.md OUT OF
SCOPE list: "any backoffice or mobile code"); nothing in either tier changed.

## 5. Left open / out of scope

- **Auth (AD-002e)**: still not built, unchanged from S4-01 — both new endpoints 401 for a real
  HTTP client until AD-002e's filter exists.
- **Dashboard (BL-001)**, **image bytes/signed URLs (AD-004/R-046)**, **reference-data delivery
  (AD-002f)**: untouched, per the task's OUT OF SCOPE list.
- **BL-020 filed**: the export field set (18 columns) is inferred from operator.md's general
  "field data only" description, not a bank-specified list — same category as BL-019.
- **A third reviewer pass was not run** — see §2 for why the second pass's findings were instead
  verified directly.

## 6. Commit and push

`git log --oneline -1`:

```
64c352f feat: S4-02 — operator backend: manual completion, export
```

`git status`:

```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed straight to `main` (`git push`: `49b9ec9..64c352f  main -> main`), per CLAUDE.md's
session-end rule — no feature branch.
