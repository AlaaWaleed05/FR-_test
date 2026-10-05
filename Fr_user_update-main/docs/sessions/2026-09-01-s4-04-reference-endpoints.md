# S4-04 — Reference read endpoints, wire-contract version pinning, `profile_reference_version` writer

Closes AD-002f's implementation, deferred by S4-03 (2026-08-31): the two read endpoints, version
pinning on the data-entry wire contract, and the `ref.profile_reference_version` writer.

## 1. Migrations

- **V0051** `app_income_source_version.sql` — `ALTER TABLE app.profile_customer_data ADD COLUMN
  income_source_version int`. Mirrors `admin_div_version`'s existing precedent (one scalar column
  recording the version a whole multi-row replace-set was validated against; no FK, matching
  `admin_div_version`'s own no-FK precedent).
- **V0052** `ref_reference_list_document_grant.sql` — `GRANT SELECT ON ref.reference_list_document
  TO fru_app`. V0048's own comment deferred this to "next session, alongside the endpoints."

Both additive, no data migration. Flyway ran clean from an empty schema across every one of this
session's several fresh-container Testcontainers integration runs (each `-Pdb-integration-test`
invocation starts a brand-new Postgres 18 container and migrates V0001→V0052 from nothing) — the
repeated clean runs are the from-scratch proof. Idempotent re-run needs no separate proof beyond
that: both migrations are simple additive DDL with stable content this session, and Flyway's
checksum tracking refuses to re-apply an already-recorded version regardless.

## 2. Read endpoints

New `reference.web.ReferenceController` — `GET /api/v1/reference/manifest`, `GET
/api/v1/reference/lists/{listCode}/{version}` — backed by a new port, `reference.domain.
ReferenceDocumentStore` (`reference.jdbc.JdbcReferenceDocumentStore`), deliberately kept separate
from `ReferenceCatalog`, which stays scoped to `dataentry`'s per-item validation reads. `reference.
domain.ManifestHasher` (pure, unit-tested) computes `catalogHash` fresh per request over the
currently-`is_current` rows — there is no stored "manifest document," only the seven per-list
documents V0048 already stores. The list endpoint serves `ref.reference_list_document.
document_json` verbatim; V0052 gave `fru_app` the grant to read it. ETag is set from the
already-known hash and answers 304 without ever reading `document_json` — the research report
explicitly rejects `ShallowEtagHeaderFilter` for this reason. `Cache-Control`: `no-cache` on the
manifest, `public, max-age=31536000, immutable` on the list endpoint. No authentication (AD-002e,
explicitly out of scope); noted on BL-007 rather than filed separately, per the task.

## 3. Version pinning

Each of stages 3-6's requests carries its own nullable pinned-version field(s) per list it
touches (`countryListVersion`/`adminDivisionListVersion` on stages 3/5/6, `occupationListVersion`/
`incomeSourceListVersion` on stage 4). There is no server-tracked "session" — the backend only
bounds and validates whatever a caller asserts on each call, through one choke point,
`DataEntryService.resolvePinnedVersion`:
- `null` → falls back to `ReferenceCatalog.currentVersion(listCode)`, today's behaviour unchanged.
  **Design decision**: no mobile client exists yet to send a pin (mobile OUT OF SCOPE), so treating
  absent as "not opted in" is a no-op for every existing caller rather than a breaking mandatory
  field.
- a supplied pin must exist (`ReferenceCatalog.versionExists`, new port method — matches ANY
  published row, including one not yet `is_current`), must not be **newer** than current (added
  under review — see §5), and must not be **older** than `currentVersion -
  fru.reference.pin-floor-versions-behind` (default 2, `@Value`-injected).

**Divergence recorded, not silently substituted**: AD-002f's closed decision accepts staleness "up
to the abandonment threshold" (time-based); the floor implemented is version-count-based. Narrow in
practice — lists change rarely — but not identical to the decision as written. Documented in
`docs/components/reference-data.md`.

`sudanCode()` deliberately stays on `currentRootItemCode` (current, not pinned) — the country-vs-
Sudan branch decision happens before any `admin_division` version is chosen, and the declared root
is guarded stable by R-045's FK+trigger. Recorded as a scope boundary.

## 4. `ref.profile_reference_version` writer

`SubmissionRepository.usedReferenceListVersions`/`recordReferenceVersions`, called inside
`SubmissionService`'s existing transaction. Reads back `app.profile_customer_data`'s own version
columns: `occupation_version`, the shared `admin_div_version`, the new `income_source_version`, and
`country_of_residence_version` (falling back through `birth_country_version`/
`home_country_version`/`work_country_version`) — `country` has four independent per-field columns
but `ref.profile_reference_version`'s PK is `(profile_id, list_code)`, so only one representative
value can be recorded per list; documented inline as a deliberate simplification.

**Found by the second review pass**: `operator.service.ManualCompletionService` is the *other*
legal path into `submitted` (V0020) besides `SubmissionService` — a profile can enter stages 3-6,
abandon, and later be manually completed, still carrying reference-list versions worth recording.
It now injects `submission.domain.SubmissionRepository` and calls the same two methods inside its
own transaction, right after its guarded `UPDATE` succeeds — the same cross-feature port-reuse
pattern it already uses for `submission.domain.SubmissionMessageRenderer`.

## 5. Reviewer disposition

`@agent-reviewer` ran twice, per CLAUDE.md's rule.

**First pass** — 1 BLOCKER, 2 SHOULD-FIX, 4 NOTE, all addressed:
- **BLOCKER**: `JdbcReferenceDocumentStore.document()` used `rs.getObject("published_at",
  Instant.class)`, unsupported by pgjdbc for `timestamptz` — every request for a published document
  500'd, confirmed by a live integration-test failure. Fixed to `rs.getTimestamp(...).toInstant()`,
  the codebase's existing convention (`JdbcOtpVerificationRepository`,
  `JdbcProfileListRepository`, etc). The first fix attempt's `replace_all` edit only matched the
  `currentManifest()` call site (different trailing paren syntax from `document()`'s), leaving the
  second one broken — caught only because the second review pass re-ran the integration gate
  itself rather than trusting the claim.
- **SHOULD-FIX**: `ManualCompletionService` never wrote `ref.profile_reference_version` (§4).
- **SHOULD-FIX**: the `country` four-column collapse had no test exercising it. Extended
  `SubmissionIntegrationTest` to drive stages 3 and 5, not just 4.
- **NOTE**: pin-floor guard was one-sided — closed by rejecting `pinnedVersion > current`.
- **NOTE**: `DataEntryControllerTest`'s new mock verifications used `any()` for the pin arguments,
  which cannot catch a swapped-argument bug — tightened to `eq()` with distinct literals per field;
  a missing stage-5 test was added.
- **NOTE**: `ManifestHasher.catalogHash` doesn't cover `verifiableChannels` — accepted as-is (the
  field is a hardcoded constant today, so nothing can drift yet); documented as an obligation for
  whoever wires R-042's real configuration in.
- **NOTE**: dangling doc references (this report, the EXECUTION_PLAN.md row) — expected mid-session,
  filled at session end.

**Second pass, against the fixed diff, found real defects in the first pass's own fixes** — this
project's eleventh consecutive session where the re-review catches a defect in the fix itself:
- **BLOCKER**: `DataEntryIntegrationTest.pinnedOlderVersionAcceptedThenSameCodeRejectedAgainstCurrent`
  inserts a throwaway `occupation` version 2 into the shared, JVM-lifetime Testcontainers container
  to prove the pinning behaviour. The `finally` block flipped `is_current` back but never deleted
  the rows — `AppSchemaConnectivityIntegrationTest`'s unscoped `count(*) ... WHERE list_code =
  'occupation'` assertion would silently double under a reversed run order. Live-proven by the
  reviewer with `-Dsurefire.runOrder=reversealphabetical`. Fixed by deleting the version-2 rows
  outright (`reference_item` before `reference_list_version`, respecting the FK) instead of only
  flipping the pointer.
- **SHOULD-FIX**: the `ManualCompletionService` fix (§4) had no regression test — added, driving
  stage 4 before manual completion, asserting the two expected rows.
- **SHOULD-FIX**: the two new floor/existence unit tests (`pinNewerThanCurrentIsRejected`,
  `pinForAVersionThatWasNeverPublishedIsRejected`) were indirect — with the class's default stubs,
  reverting either guard would still throw the same exception type via a different rejection path,
  so `assertThrows` alone could not prove the specific guard was doing the work. Rewritten to assert
  on the rejection message with disambiguating stubs (e.g. stubbing `exists(occupation, 2, "86") =
  true` so the newer-than-current test can only pass because of that guard), and the integration
  test's nonexistent-version pin changed from `999` to `0` to isolate it from the newer-than-current
  guard.
- **NOTE**: the extended `SubmissionIntegrationTest`'s Javadoc overclaimed that it would catch a
  transposed column read in the `country` COALESCE (e.g. reading `admin_div_version` for
  `country`) — false, since every seeded list sits at version 1 and every candidate column holds
  the identical value. Reworded to state what the test actually proves (four rows, correct
  collapse) versus what it cannot (column identity, which needs two lists at genuinely different
  versions).

**Applying the BLOCKER fix introduced a second, self-inflicted bug**, caught by re-running the full
integration suite rather than trusting an `echo $?`-chained exit code (which reports the trailing
`echo`'s own success, not Maven's): `restoreOccupationToOnlyVersionOne`'s two `UPDATE`s were in the
wrong order — setting version 1's `is_current` to `true` *before* setting version 2's to `false`
violates `ref_one_current`'s unique partial index, since both would briefly be `true`. The resulting
`DuplicateKeyException` aborted the `finally` block partway through, leaving `occupation` with
**zero** current versions for the rest of that Surefire run — cascading into five unrelated
failures across `JdbcReferenceCatalogTest`, `ReferenceDocumentPublisherIntegrationTest`,
`ReferenceControllerIntegrationTest`, `SubmissionIntegrationTest` and
`ManualCompletionIntegrationTest`. Found by aggregating `target/surefire-reports/*.txt` directly
instead of trusting the shell exit code; fixed by reordering the two statements (version 2 → false,
then version 1 → true).

## 6. Gates — final state, verified via `surefire-reports` aggregation, not shell exit codes alone

- `./mvnw test -Pdb-integration-test`: **549/549** (up from S4-03's 527).
- `./mvnw verify` (standard, non-integration): **426/426**, JaCoCo **91.47%** line, Spotless clean,
  `BUILD SUCCESS`.
- Backoffice and mobile: untouched this session, gates not re-run.

## 7. Proofs

- **List endpoint bytes hash to `content_hash`**: `ReferenceControllerIntegrationTest.
  listEndpointBytesHashToTheStoredContentHash` — SHA-256 of the response body compared to the
  stored `content_hash`.
- **Conditional 304**: both endpoints, asserting the ETag round-trips and the body is empty.
- **The motivating pinning case**: `DataEntryIntegrationTest.
  pinnedOlderVersionAcceptedThenSameCodeRejectedAgainstCurrent` — pin `occupation` v1 (has code
  `"86"`), publish a throwaway v2 (drops it), the pinned call is accepted, the identical unpinned
  call (now validating against current = v2) is rejected.
- **Floor/nonexistent rejections**: unit-level (`DataEntryServiceTest`, message-asserted, not merely
  exception-type) for both the floor and the newer-than-current bound; integration-level
  (`pinningAVersionThatWasNeverPublishedIsRejected`) for a version that was never published.
- **`ref.profile_reference_version` rows**: `SubmissionIntegrationTest.
  referenceListVersionsUsedByThisProfileAreRecordedAtSubmission` (stages 3+4+5, four rows) and
  `ManualCompletionIntegrationTest.manualCompletionRecordsUsedReferenceListVersions` (stage 4 only,
  two rows). Absence on failure: `submissionWithoutASignatureIsRejected` extended to assert zero
  rows for a rejected submission (the transaction that would write them never opens).
- **Regression**: full S3-13 customer journey, S4-01/S4-02 operator paths, status-transition guard,
  audit append-only — all green across both full-suite runs; `DataEntryServiceTest`'s existing
  no-pin/pin-at-current cases prove `DataEntryService`'s validation behaviour is unchanged for a
  caller that doesn't pin or pins the current version.

## 8. Docs updated

`docs/components/reference-data.md` — status line, endpoints/pinning/writer sections moved from
not-built to built, the AD-002f staleness-window divergence recorded, the second writer noted, a new
Open item for `ManifestHasher`'s `verifiableChannels` gap. `BACKLOG.md` — BL-007 gained the
auth/bandwidth note for the two new endpoints; BL-017 noted that wire-contract pinning is now built,
narrowing but not closing its residual gap.

## 9. Commit/push proof

```
$ git log --oneline -1
78e02ec feat: S4-04 — reference read endpoints, wire-contract version pinning, profile_reference_version writer

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

