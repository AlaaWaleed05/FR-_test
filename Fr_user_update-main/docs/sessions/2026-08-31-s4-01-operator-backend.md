# S4-01 — Operator backend: profile list, single profile, approve/reject

## 0. Commit check

`git log --oneline -3` confirmed S3-13's `15705e4` present and an ancestor of `main`
(`git merge-base --is-ancestor 15705e4 main` exit 0); `git status` clean at session start.

## 1. Scope and design decisions

First operator-side (back-office) backend work; everything before this was the customer journey.
No new tables — every field operator.md's list/view needs already exists (`app.profile`,
`profile_customer_data`, `profile_channel`, `identity_cycle`/`scan_result`/`face_result`/
`registry_result`, `profile_status_history`, `artifact_ref`, `ref.reference_item`). One migration,
V0047, adding `audit.ensure_operator_chain` — `audit.audit_chain.chain_kind` already accepted
`'operator'` (V0002) but nothing created one; mirrors V0036's `ensure_profile_chain` exactly
(`SECURITY DEFINER`, `SET search_path`, `ON CONFLICT DO NOTHING`, `REVOKE EXECUTE FROM PUBLIC` before
`GRANT ... TO fru_app`, and the R-035 `SET LOCAL fru.migration_in_progress` flag for DDL in schema
`audit`).

**AD-002e seam.** `operator.domain.OperatorIdentity(operatorId, accessLevel)` is an explicit
parameter on every service/repository method — never invented by a handler, never read from a
header. The web layer resolves it via `OperatorIdentityArgumentResolver`
(`HandlerMethodArgumentResolver`), which reads a **request attribute**
(`fru.operatorIdentity`) — a request attribute can only be set server-side by a filter running
before this resolver, unlike a header, which a client fully controls. No filter in this codebase
sets it; that is exactly AD-002e's undone work. Until it exists, every real HTTP call to an
operator endpoint gets 401 straight from the resolver (`ResponseStatusException`, thrown before any
controller method body runs — a domain exception a controller could catch would never be reached).
Tests populate the attribute directly via a `RequestPostProcessor`. What AD-002e must add, and
nowhere else: the authenticating filter that calls
`request.setAttribute(OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE, identity)`. Every
controller/service/repository below the resolver is unaffected.

**Profile list** (`ProfileListController`/`OperatorProfileListService`/`JdbcProfileListRepository`,
`GET /api/v1/operator/profiles`) does all filtering/sorting/searching/pagination server-side
(AD-006, BL-015), via `NamedParameterJdbcTemplate` — the one repository in this codebase whose query
is dynamic enough (optional filters, one search term referenced many times, a whitelisted sort
column) to need named parameters, unlike every simpler positional-`JdbcTemplate` query elsewhere.
`total` comes from a **separate `COUNT(*)` query**, not `count(*) OVER()` — a window column is
computed per returned row, so a page requested past the end of the result set returns zero rows and,
with them, no count at all, silently reporting `total = 0` for a filter that matches rows elsewhere;
proven live (`OperatorProfileListIntegrationTest`, page 5 of a 3-row result still reports
`total = 3`). Search scope is deliberately named, not literally every column: account number,
reference number, phone, email, branch code/label, national/identity number, on-document name
(Arabic + English), the Civil Registry name chain, status label, and rejection-reason code/label via
status history — Arabic columns compared through `ref.ar_fold` on both sides, everything else a
plain `ILIKE`. Every search call writes `profile_list_searched` on the calling operator's own
`operator` chain (created on demand), not a shared chain — avoids the R-038-style single-row-lock
bottleneck for what will be far fewer operators than customers.

**Single profile view** (`ProfileController`/`OperatorProfileViewService`/
`JdbcProfileViewRepository`, `GET /api/v1/operator/profiles/{id}`) aggregates customer data, income
sources, channel states, the most-recent identity cycle's scan/face/registry results, artifact
references (metadata only — kind, origin label, storage key, content type, size, checksum, **never
bytes**, and now filtered to `state = 'committed'` — see §3), and full status history with the
rejection-reason label joined in. `canApprove` (BL-013) is computed in the service from the same
four-eyes predicate (`ReviewRepository#isManualCompletionActor`) the enforcement itself uses — the
repository always returns `canApprove = false`, since it has no requesting operator to evaluate
against. Every view writes `profile_viewed` on the profile's own (already-existing) chain.

**Review** (`ReviewController`/`OperatorReviewService`/`JdbcReviewRepository`, `POST
.../{id}/approve`, `POST .../{id}/reject`) implements approve, reject, and re-approving a rejected
profile (same `approve()` call — V0009's `WHERE status IN ('submitted','rejected')` already
legalises it). `JdbcReviewRepository.APPROVE` is V0009's documented conditional `UPDATE ... WHERE
status IN (...) AND NOT EXISTS (...)` **verbatim** — safe to call standalone with no preceding lock,
which is the actual enforcement a client cannot bypass by ignoring `canApprove`. Reject requires a
`rejection_reason`-list-validated code (`ReferenceCatalog`, which gained a `find()` method returning
label/`extra` for exactly this); `REJ-07` additionally requires non-blank `internalNote`, checked in
the service for a clean 400 rather than relying on the DB's own `rej07_needs_detail` CHECK. Every
transition enqueues an Arabic-only notification (`ReviewMessageRenderer`, mirroring
`SubmissionMessageRenderer`) to `VERIFIED` channels only, inside the same transaction as the guarded
`UPDATE` and its paired history row. `AccessLevelRequiredException` (403) gates both actions to
`OPERATOR`; `VIEWER` cannot reach either write path.

## 2. Reviewer findings and dispositions

`@agent-reviewer` ran twice per CLAUDE.md's rule.

**First pass — 4 SHOULD-FIX + 5 NOTE, all fixed:**

- A `WITH old AS (SELECT status ...) UPDATE ... FROM old ... RETURNING old.status` CTE, added to
  capture the row's true prior status for the paired history INSERT (the `UPDATE` overwrites
  `status` before any `RETURNING` could read the old value off the same row), could report a
  **stale** `from_status` under PostgreSQL's EvalPlanQual re-check on a concurrent update — tripping
  V0020's deferred `profile_status_requires_history` trigger at COMMIT (an unmapped 500, rolling
  back the audit event too). Fixed: dropped the CTE, restored `APPROVE` to the literal V0009 shape,
  and added `ReviewRepository#lockAndReadStatus` (`SELECT ... FOR UPDATE`) — the caller takes this
  lock as the transaction's first statement and passes the result in as `fromStatus`; while the lock
  is held, `APPROVE`'s own `WHERE` clause cannot see a different row than what was just read.
- Pagination had no tiebreaker column — every incomplete profile shares `submitted_at IS NULL`
  under the default sort, so two separately-issued paged queries had no guarantee of ordering a tied
  group the same way, and a profile could appear on two pages or none. Fixed: `, p.profile_id ASC`
  appended after every sort field.
- The list-search audit-event test asserted only two `200 OK` responses, not the audit event itself.
  Fixed with a real `audit.audit_event`/`audit.audit_chain` count assertion.
- A refused four-eyes/wrong-status approve left **no audit trail at all** — the event was written
  inside the same transaction the refused `UPDATE` then rolled back. operator.md calls four-eyes
  "the strongest control bypass the system permits"; fixed by writing `profile_approve_refused`
  **outside** the (already rolled-back) transaction, mirroring `SubmissionService#auditRejection`.
- Plus: a wrong document-type case comparison (`app.scan_result.document_type` stores Uqudo's own
  `"PASSPORT"`/`"SDN_ID"`, not the app-level `"passport"`/`"national_id"`); a
  `NamedParameterJdbcTemplate` bug where a filter's trailing `:paramName` concatenated directly onto
  the search predicate's leading `AND (` with no separating whitespace, so the parser read
  `:statusAND` as one parameter name; an `int` overflow in `ProfileListPage.offset()` for a
  large client-supplied `page`; a missing `artifact_ref.state = 'committed'` filter; a stale
  Javadoc phone-prefix.

**Second pass, against the fixed diff, found the first pass's own approve() fix had inverted this
codebase's lock order relative to reject()** — this project's eighth consecutive session where the
re-review catches a defect inside the fix itself. `approve()` now took the `app.profile` row lock
before the audit-chain lock (via `lockAndReadStatus` first); `reject()` still appended its audit
event first, then locked the row — the reverse order. Two concurrent reviews of the same profile
(one approve, one reject) could deadlock (PostgreSQL `40P01`), surfacing as an unmapped 500 for
whichever transaction lost. Fixed by reordering `reject()` to call `lockAndReadStatus` first too,
before its audit-chain write — both methods now contend for the same two locks in the same order.
Also found: the list-search audit-event test used the class's **shared** `OPERATOR_ID` constant,
which every other test method in the same class also searches as — since rows persist across test
methods (no truncation) and JUnit 5 doesn't guarantee method order, the count depended on which
methods had already run; fixed with a dedicated per-test operator id. Several new integration tests
left `app.notification_outbox` rows un-backdated after `submit()`/a successful approve, claimable by
`NotificationOutboxIntegrationTest`'s unscoped poll for the rest of the suite run regardless of
class execution order; fixed by backdating inside the `submit()` helper itself and after every
successful review action. The new `profile_approve_refused` event and the offset-overflow fix each
had no test; both gained one (a real assertion added to `fourEyesRuleEndToEnd`, and a new plain-JUnit
`ProfileListPageTest`).

## 3. Proofs

- **List filter/sort/search/pagination, including Arabic fold**: named tests —
  `OperatorProfileListIntegrationTest.searchFiltersServerSideIncludingArabicFold` (a phone-prefix
  search across two fixtures; an `ar_fold`-matched Arabic on-document-name search proven present,
  not asserted unique, since every scan fixture in the shared container gets the same stub name;
  status filter combined with search), `paginationTotalIsCorrectAcrossPages` (3-row set, page
  1/2/past-the-end, `total` stable across all three, page 1/2 rows disjoint).
- **Four-eyes end to end**: `OperatorReviewIntegrationTest.fourEyesRuleEndToEnd` — seeds a
  `profile_status_history` row with `is_manual_completion=true` for operator A directly (S4-02's
  manual-completion endpoint doesn't exist yet; the DB precondition it will eventually write already
  exists in schema). A's `approve()` call is refused (403), writes nothing to `app.profile`, and now
  is itself audited (`profile_approve_refused`); the view for A shows `canApprove=false`; **the exact
  V0009 conditional `UPDATE`, run directly against the database as A, independent of any service
  layer, also returns 0 rows affected** — live proof the guard itself refuses the write, not merely
  this codebase's controller. B, a different operator, succeeds.
- **Reject with a valid code; free text alone refused**: `rejectWithValidCodeSucceedsAndSendsThe...`
  (REJ-01, Arabic customer message asserted, not the English internal reason);
  `rejectWithoutAReasonCodeIsRejected`/`rejectWithAnUnknownReasonCodeIsRejected` (400, no write);
  `rej07WithoutInternalDetailIsRejected` (400 without the note, 200 with it).
- **`rejected → approved`**: `rejectedProfileCanLaterBeApproved` — `from_status = 'rejected'` on the
  history row.
- **Every transition's audit event + outbox to verified-only channels**:
  `approveSucceedsAndNotifiesOnlyVerifiedChannels` asserts the `profile_approved` audit event, the
  history row's `from_status`/`actor_kind`/`actor_id`, exactly one notified channel (`sms`, the only
  verified one; `whatsapp` was never verified), and idempotent re-approval enqueuing nothing further.
- **Regression**: the full 468-test S3-13 customer journey, the status-transition guard, and audit
  append-only all re-ran unchanged as part of the 488-test suite (no shared code touched besides the
  additive `ReferenceCatalog.find()` method and the new migration).

## 4. Gate output

`./mvnw test -Pdb-integration-test` (Docker started fresh this session):

```
[INFO] Tests run: 488, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`./mvnw verify` (JaCoCo + Spotless, standard non-integration tier):

```
[INFO] Tests run: 397, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 296 files clean - 0 needs changes to be clean, 0 were already clean, 296 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Overall line coverage (computed from `target/site/jacoco/jacoco.csv`): 89.88% (3696/4112), branch
90.00% — both well above the 80% gate; down fractionally from S3-13's 90.19% since the new
`operator` package's own untested edges (mostly defensive `orElseThrow`s never exercised by a
passing-path test) slightly outweigh what the new tests added.

## 5. Left open / out of scope

- **Auth (AD-002e)**: not built. Every operator endpoint 401s for a real HTTP client until a filter
  sets the request attribute — see §1.
- **Manual completion and export**: S4-02, per the task's OUT OF SCOPE list. The four-eyes test
  seeds the DB precondition directly rather than through an endpoint that doesn't exist.
- **Image bytes / signed URLs / view-audit timing**: AD-004/R-046, still open. The view returns
  artifact metadata only.
- **Reference-data delivery (AD-002f)**: untouched; `CustomerDataView` returns raw reference codes,
  not resolved labels — the operator UI already has AD-002f's contract for that.
- **Dashboard (BL-001)**: untouched.
- **The lock-order deadlock fix has no dedicated concurrency test** — deterministically reproducing
  a two-transaction PostgreSQL deadlock in a JUnit integration test is impractical; the fix is a
  structural reordering verified by code inspection (both methods now call `lockAndReadStatus`
  first) and by the full suite's every approve/reject test still passing under the new order.

## 6. Commit and push

`git log --oneline -1`:

```
f8e9a35 feat: S4-01 — operator backend: profile list, single profile, approve/reject
```

`git status`:

```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed straight to `main` (`git push`: `518a82a..f8e9a35  main -> main`), per CLAUDE.md's session-end
rule — no feature branch.
