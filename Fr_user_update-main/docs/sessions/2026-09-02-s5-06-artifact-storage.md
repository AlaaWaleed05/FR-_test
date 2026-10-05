# S5-06 — Close AD-004: artifacts stored in the database

## 0. Commit check

`git log --oneline -3` at session start:
```
285b5b2 docs: record S5-05 commit/push proof in session report
338e953 feat: S5-05 — Stage 3-6 data-entry screens, offline queue, Arabic fold
3f6aafd docs: record S5-04 commit/push proof in session report
```
`git merge-base --is-ancestor 338e953 main` → `338e953 IS ancestor of main`. `git status`: clean.

## 1. The decision (product owner)

Artifact bytes are stored in PostgreSQL, in the profile database — **no object store**. Reasoning
recorded in PROJECT_PLAN.md's AD-004 Decisions-log row: the delivery model decides it ("one
PostgreSQL instance, restore it and run" is a materially simpler bank handover than PostgreSQL
plus an object store plus bucket policies); the usual BLOB objection only half-applies (backups
enlarge, but PostgreSQL's TOAST keeps out-of-line bytes out of ordinary query paths, live-proven
below); ~4.8 MB/completed profile, ~350–500 GB at full campaign scale, 1–1.5 TB provisioned.

## 2. Schema (V0053–V0056)

- **V0053** — extends `app.artifact_ref` (not a new table) with `body bytea` (`STORAGE EXTERNAL`
  — JPEGs are already compressed, `EXTENDED`'s default compression pass would burn CPU for
  nothing) and `derived_from_artifact_ref_id` (self-referencing FK, schema only — no derivative
  generator this session, matching V0048's own empty-until-later precedent). `storage_key`'s
  comment narrowed: no longer "AD-004 owns its meaning", now reserved for R-046's still-open
  addressing scheme.
- **V0054** — `app.artifact_read(p_artifact_ref_id) RETURNS bytea`, a checksum-verified read
  pushed into the database (matching `ref.ar_fold()`/`audit.chain_append()`'s precedent).
  Redesigned mid-session (review finding #2, below): a checksum **mismatch** raises; an
  **absent** body (never stored, or purged) returns SQL `NULL` — absence and corruption are
  different in kind, and only the latter is refused. `REVOKE FROM PUBLIC` then `GRANT TO fru_app`.
- **V0055** — `app.purge_abandoned_artifacts(p_as_of DEFAULT clock_timestamp()) RETURNS int`,
  nulling `body`/setting `state='purged'` for a profile `status='abandoned'` and
  `last_activity_at` past 90 days. No grant to `fru_app` — the bulk, time-based sweep stays
  admin-only (not "the application can't touch its own evidence": `fru_app` already holds
  `UPDATE`, corrected during review — see finding #3). Mirrors `audit.seal_create()`: no
  scheduler, an operational requirement not yet met.
- **V0056** — drops `app.scan_result.face_reference_image` and V0042's two triggers/functions (see
  §4).

## 3. What is stored

Document front/back, capture frames (row+checksum only — see §5), Uqudo's portrait, the registry's
own portrait, the liveness audit-trail image, the signature. Salary certificate: `kind` already
valid (V0026), no upload endpoint exists (BL-022, unchanged). Originals byte-identical, never
re-encoded. Encryption stays disk/volume-level (R-026, unchanged, still assigned to AD-002d).

## 4. `face_reference_image` — replaced, not left alongside

Confirmed with the user before implementing (a genuine architecture-adjacent call the task asked
this session to make): the portrait has been inserted into `app.artifact_ref`
(`kind='portrait_uqudo'`) in the same transaction as `scan_result` since S3-12 — but before this
session that row held metadata only, no bytes. V0053 gives it a `body` column, so **going
forward**, not retroactively, the insert that used to populate the bridge column also durably
stores the bytes. Checked individually why each of V0042's three clearing paths is now
unnecessary: face-match-success clearing was pure "shed the copy once used" hygiene, now moot
since AD-004 retains the portrait permanently; supersede-clearing never actually prevented reuse
(the query already excluded non-`active` cycles) and was inconsistent with every sibling artifact
on the same cycle, which already survived supersede uncleared; terminal-status clearing
implemented no retention policy either, since nothing else in this codebase nulls a column on
reaching a terminal (non-abandoned) status. `LivenessRepository
.currentAcceptedCycleReferenceImage` now reads via `app.artifact_read()`; `clearFaceReferenceImage`
removed outright. RISKS.md R-047 retired with this reasoning.

## 5. Persist what was being discarded — the one behaviour change

Stage 8 (`IdentityScanService`), stage 10 (`LivenessService`) and stage 11 (`SignatureService`)
already downloaded and checksum-verified every artifact; each now passes the already-in-hand
`byte[]` through to its repository insert/upsert instead of discarding it. **Raw capture frames
excluded** (`isCaptureFrame()`, `IdentityScanService`): the row, checksum and byte size are still
written (unchanged S3-12 behaviour), the bytes are not — doubling the volume for no evidentiary
gain, since the cropped document is what the JWS attests to. Found missing entirely from the first
draft (review finding #1, BLOCKER — the docs claimed this exclusion while the code stored every
image unconditionally).

## 6. Proof

- **Stage 8 chain, byte-identical**: `IdentityScanIntegrationTest
  .storedArtifactBodiesAreByteIdenticalToWhatWasServed` — `doc_front`'s stored body vs.
  `StubUqudoClient.downloadImage()` recomputed independently; `portrait_registry`'s body vs.
  `StubCivilRegistryClient`'s fixed photograph; `doc_front_frame`'s body is `NULL` while its
  `sha256`/`byte_size` are populated (added under second review pass).
- **`pg_column_size`/TOAST out-of-line — live, not a test** (a green test can't prove physical
  storage layout): against a fresh throwaway container, one real row inserted with a 60,000-byte
  body via `app.artifact_ref`'s real FK chain (profile → identity_cycle → artifact_ref, `psql`,
  `fru_migrator`):
  ```
   attname | attstorage
  ---------+------------
   body    | e
  (1 row)
   main_table_size | toast_relation_size | row_count
  -----------------+---------------------+-----------
   8192 bytes      | 64 kB               |         1
   byte_size | octet_length | pg_column_size
  -----------+--------------+----------------
       60000 |        60000 |          60000
  ```
  The main relation stays one page regardless of body size; the bytes are genuinely in TOAST.
  `EXTERNAL` applies zero compression (`pg_column_size = octet_length`).
- **Checksum mismatch refused, absence is not an error**:
  `ArtifactStorageIntegrationTest.artifactReadReturnsVerifiedBytesAndRefusesATamperedBody` (a
  tampered `body` — `sha256` left untouched — makes `app.artifact_read()` raise
  `"...failed checksum verification on read"`) and
  `.artifactReadReturnsNullForAPurgedBodyRatherThanRaising` (a purged row returns `NULL`, not an
  exception).
- **90-day purge, scoped correctly, audit untouched**:
  `.purgeAbandonedArtifactsNullsOnlyProfilesAbandonedOver90DaysAndNeverTouchesAuditArtifact` — a
  profile abandoned 92 days has its artifact bodies nulled/`state='purged'`; one abandoned only 10
  days is untouched; `audit.audit_artifact`'s row count is unchanged before/after (structurally
  impossible for this function to touch it — different schema, never referenced in its body).
- **Regression**: `LivenessIntegrationTest`'s `passingFaceMatchDoesNotClearThePortraitArtifact`,
  `portraitArtifactSurvivesWhenTheIdentityCycleIsSuperseded`,
  `portraitArtifactSurvivesWhenTheProfileReachesATerminalStatus` (rewritten from the old
  "clears on X" S3-13 assertions to "survives X"); `SignatureIntegrationTest
  .signatureStoredViaTheDrawnRoute` extended for byte-identical body. Status-transition guard,
  four-eyes and audit append-only: unchanged code, existing suites re-run clean as part of the
  full gate below.

## 7. Review — two passes, both found real defects

`@agent-reviewer` ran twice per CLAUDE.md's rule, explicitly asked whether refactoring
`face_reference_image` weakened any of the three previously-proven guards.

**First pass**, 1 BLOCKER + 7 SHOULD-FIX/NOTE, all fixed:
1. **BLOCKER** — the decision record claimed raw capture frames are excluded from storage; the
   code stored every image unconditionally. Fixed: `isCaptureFrame()` withholds only the bytes.
2. A purged-then-reactivated abandoned profile's stage 10 token request threw an uncaught 500
   (`abandoned → in_progress` reactivation is real, wired in `ContactChannelsService`). Fixed:
   `app.artifact_read()` redesigned (absence → `NULL`, mismatch → raise);
   `LivenessService.issueFaceSessionToken` now maps a purged reference image to
   `NoAcceptedIdentityCycleException` (409), distinct from the genuine invariant-violation case
   (an entirely missing row, still `IllegalStateException`/500).
3. "The application must not be able to purge its own evidence" was false — `fru_app` already
   holds `UPDATE` on `app.artifact_ref`. Reworded to state what the grant withholding actually
   enforces: the bulk sweep is admin-only, not that the application can't touch a row.
4. R-046 still said "AD-004, which is open" — reworded now that AD-004 is closed without settling
   R-046.
5. Every new doc cited a session report that didn't exist yet (expected — written last) and the
   EXECUTION_PLAN.md row's Notes carried the task description, not an outcome summary — fixed
   last, after final numbers were known.
6. "A governed copy always already existed" — false before this session (the row existed,
   the bytes did not). Reworded to "redundant going forward" in V0056, R-047 and persistence.md.
7. Nothing in this codebase transitions a profile into `abandoned`, and no 30-day sweep exists —
   the purge's precondition is not produced today. Disclosed explicitly in persistence.md and
   R-013.
8. A `LivenessServiceTest` comment claimed a non-existent indirect assertion — reworded to state
   the guarantee structurally (the method no longer exists on the interface).

**Second pass, against the fixed diff, found the first pass's own fixes incomplete** — this
project's pattern continues:
- persistence.md's `artifact_read()` paragraph still said absence raises, contradicting the very
  redesign it was documenting (fixed).
- The frame-body exclusion had no test — reverting `isCaptureFrame()` left all tests green
  (closed: `doc_front_frame` assertion added to `IdentityScanIntegrationTest`).
- `ArtifactStorageIntegrationTest`'s own class Javadoc still carried finding #3's inaccurate claim
  (fixed).
- `UqudoClient`/`IdentityScanService`/uqudo-sdk.md claimed every image survives `purgeSession()`
  without exception — frames don't, since their bytes are never stored (all three corrected).
- `NoAcceptedIdentityCycleException`'s Javadoc didn't name its new second cause (fixed).

Every reviewer-driven fix got its own regression test in the same pass, per CLAUDE.md's rule
(the frame-body precondition fix in `ArtifactStorageIntegrationTest.artifactBodies()` also caught
a genuine pre-existing-test regression the frame exclusion itself introduced — a live failure,
fixed before the second review pass ran).

## 8. Gate output (final, pasted verbatim)

Flyway from scratch (fresh throwaway container, post-fix migration content):
```
[INFO] Successfully applied 56 migrations to schema "public", now at version v0056 (execution time 00:01.073s)
[INFO] BUILD SUCCESS
```
Idempotent re-run:
```
[INFO] Current version of schema "public": 0056
[INFO] Schema "public" is up to date. No migration necessary.
[INFO] BUILD SUCCESS
```
`./mvnw test -Pdb-integration-test`:
```
[INFO] Tests run: 556, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
(up from S4-04's 549 — 6 new tests net of one rename)

`./mvnw verify`:
```
[INFO] Tests run: 427, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 336 files clean - 0 needs changes to be clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```
Line coverage: 91.51% (4352/4756), up from S4-04's 91.47%.

Mobile and backoffice tiers untouched this session (backend/documentation only, per task scope) —
no gate re-run needed there.

## 9. Out of scope (per task, unchanged)

Signature/salary-certificate upload endpoints (BL-022, storage side now unblocked, endpoint still
missing) · mobile/backoffice code · stages 10/11 themselves · authentication (AD-002e) · iOS
(AD-003) · BL-021, BL-012 · derivative-image generation (schema only).

## Commit

```
$ git log --oneline -1
3e2bfba feat: S5-06 — close AD-004, artifacts stored in the database
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
