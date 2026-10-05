# Component: Reference data delivery

Status: backend schema (S4-03), endpoints/wire-contract version pinning/`profile_reference_version`
writer (S4-04), and the mobile fetch/verify/cache client (S5-01) all built · Last verified:
2026-09-01
Source: docs/sessions/2026-08-31-research-ad-002f-reference-data.md;
docs/sessions/2026-08-31-s4-03-reference-documents.md;
docs/sessions/2026-09-01-s4-04-reference-endpoints.md;
docs/sessions/2026-09-01-s5-01-mobile-foundation.md

Seven lists, 583 items, all at version 1: occupation 135, branch 25, admin_division 151,
income_source 9, education_level 7, rejection_reason 7, country 249. [OBSERVED V0014-V0019,
V0022]

## The one integrity primitive
`content_hash` = SHA-256 over the exact UTF-8 bytes of the document served at
`GET /api/v1/reference/lists/{listCode}/{version}` (endpoint built S4-04), before any
transport content-coding. Envelope = the whole document. The client hashes what it received;
**no client-side canonical JSON is needed anywhere**. Documents are stored
(`ref.reference_list_document`, V0048), not regenerated, so the served bytes exist before
serving and cannot drift with a serialiser upgrade — the same reasoning that produced
`audit.domain.CanonicalJson`. One Java class, `reference.domain.ReferenceDocumentGenerator`,
owns generation and hashing; `reference.jdbc.ReferenceDocumentPublisher` owns reading the rows
and writing the document + hash, invoked only by the publication step (below). [AD-002f, V0048]

Supersedes the SQL `string_agg` canonicalisation in the seed migrations, which V0014's own
header marked as "NOT a settled wire format" and pre-authorised AD-002f to replace.
[OBSERVED V0014:34-43]

## The cascade root
`ref.reference_list_version.root_item_code` / `root_country_version` (V0049) — declared data,
not a coincidence. `root_country_list GENERATED ALWAYS AS ('country') STORED` plus a composite
FK onto `ref.reference_item (list_code, version, item_code)` (the same generated-constant
pattern `app.profile_customer_data.occupation_list` already uses) guarantees the declared root
is a real, published country code. A `DEFERRABLE INITIALLY DEFERRED` constraint trigger
(`ref.assert_list_root`/`ref.trg_assert_list_root`) asserts a hierarchical list's single
parentless row carries exactly that code — deferred to commit because every seed migration
inserts a whole list via one data-modifying CTE statement. A plain `CHECK` cannot express this:
PostgreSQL does not support a `CHECK` referencing other rows
[DOC postgresql.org/docs/18/ddl-constraints.html]; a constraint trigger is restore-safe because
`pg_dump` emits triggers in the post-data section, after `COPY`
[DOC postgresql.org/docs/18/app-pgdump.html]. `admin_division`'s current version declares
`root_item_code = 'SD'`, `root_country_version = 1` — matching the `country` list's own Sudan
row. `DataEntryService.SUDAN_CODE` is gone; the "is this Sudan" check now resolves through
`ReferenceCatalog.currentRootItemCode(LIST_ADMIN_DIVISION)`. [AD-002f, V0049, R-045]

## Endpoints (built S4-04)
| Endpoint | Mutability | Caching |
|---|---|---|
| `GET /api/v1/reference/manifest` | mutable | `no-cache` + ETag (`catalogHash`). Not `no-store` — that forbids the 304. |
| `GET /api/v1/reference/lists/{listCode}/{version}` | **immutable** | `public, max-age=31536000, immutable` + strong ETag = `contentHash` |

`reference.web.ReferenceController`, backed by a new port `reference.domain.
ReferenceDocumentStore` (`reference.jdbc.JdbcReferenceDocumentStore`) — deliberately separate
from `ReferenceCatalog`, which stays scoped to `dataentry`'s per-item validation reads. The
manifest's `catalogHash` is computed fresh per request by `reference.domain.ManifestHasher`
(pure, unit-tested) over the currently-`is_current` rows — there is no stored "manifest
document," only the seven per-list documents V0048 already stores. The list endpoint serves
`ref.reference_list_document.document_json` verbatim as the response body; `fru_app` needed a
new `SELECT` grant on that table (V0052) to read it. No authentication (AD-002e, out of scope;
noted on BL-007 — see below).

`public` is safe on these two and **on nothing else in this system** — every other
endpoint carries PII and is `no-store`.

Does NOT use `ShallowEtagHeaderFilter`: it MD5-hashes the fully rendered body and "saves
network bandwidth but not CPU" [DOC Spring Framework 7.0.9, web/webmvc/filters]. The stored
`contentHash`/`sha256` is read before rendering; the controller sets the ETag explicitly and
answers 304 without ever reading `document_json` for a conditional request.

There is no HTTP response cache in `dart:io HttpClient` — it caches connections and cookies
only [DOC api.flutter.dev/flutter/dart-io/HttpClient-class.html]. Conditional requests are
explicit client code. HTTP caching is a bandwidth optimisation on top of the drift cache,
never a substitute for it.

## The publication step (built this session)
Not an admin endpoint (would drag in AD-002e) and not a Flyway migration (deterministic
document generation needs one Java class to own it end to end — see
`ReferenceDocumentGenerator`'s Javadoc for why SQL-side canonicalisation was rejected).
`ReferenceDocumentPublicationRunner`, a `CommandLineRunner` gated
`@Profile("publish-reference-documents")`, invoked via
`java -jar backend.jar --spring.profiles.active=publish-reference-documents` — connects as
`fru_migrator` (the only role with `INSERT`/`UPDATE` on `ref.reference_list_document` and
`ref.reference_list_version.content_hash`; `fru_app` holds neither). Full runbook:
`db/post-migrate/02-publish-reference-documents.md`. Idempotent — re-publishing the same
`(listCode, version)` regenerates byte-identical output from unchanged `ref.reference_item`
rows and overwrites with the same values.

## Version pinning on the wire (built S4-04)
Each of the four data-entry endpoints that validate a reference code (stages 3-6) carries its
own nullable pinned-version field(s) per list it touches — `countryListVersion`,
`adminDivisionListVersion` (stages 3/5/6), `occupationListVersion`, `incomeSourceListVersion`
(stage 4). There is no server-tracked "session": the backend only bounds and validates whatever
a caller asserts on each call, through one choke point, `DataEntryService.
resolvePinnedVersion(listCode, pinnedVersion)`:
- `null` → falls back to `ReferenceCatalog.currentVersion(listCode)` — today's pre-S4-04
  behaviour, unchanged, since no mobile client exists yet to send a pin (mobile is OUT OF
  SCOPE this session; making the field mandatory would have been a breaking change with no
  producer to test it against).
- a supplied pin must exist (`ReferenceCatalog.versionExists`, new S4-04 port method — matches
  ANY published row, including one not yet `is_current`), must not be **newer** than
  `currentVersion` (found by a second review pass: without this, a pin could target a version
  inserted but not yet activated), and must not be **older** than `currentVersion -
  fru.reference.pin-floor-versions-behind` (default **2**, a `@Value`-injected constructor
  parameter on `DataEntryService`, same inline-default pattern `LivenessService` already uses
  for its face-match threshold) — bounding how stale a pin can be, so a client that never
  refreshes cannot pin indefinitely.

**Divergence from AD-002f's closed decision, recorded not silently substituted:** the closed
decision (PROJECT_PLAN.md) accepts staleness "up to the abandonment threshold" — a time-based
window. The floor implemented here is version-count-based (at most N republications behind),
not time-based — simpler to implement and reason about, and equivalent in the common case
(lists change rarely), but a list republished more than N times during one long-lived session
would reject a pin the closed decision would still have accepted. Narrow in practice; not
identical to the decision as written.

`sudanCode()` (the "is this country/admin_division code Sudan" check) deliberately stays on
`currentRootItemCode` — current, not pinned. It is the country-vs-Sudan branch decision made
before any `admin_division` version is chosen, the declared root is guarded stable across
versions by R-045's FK + trigger, and pinning it would need a new
`rootItemCode(listCode, version)` port method for a correspondingly theoretical gap. Recorded
as a scope boundary, not silently assumed.

Submission (`/api/v1/submission`) carries no pin of its own: `SubmissionService` performs no
reference-code validation itself, so it has nothing to pin — see the writer section below.

## `ref.profile_reference_version` writer (built S4-04)
Written in `SubmissionService`'s existing transaction (same one as the status update and the
notification-outbox enqueue), one row per list the profile actually used — read back from
`app.profile_customer_data`'s own version columns (`occupation_version`, the shared
`admin_div_version`, the new `income_source_version` (V0051, mirroring `admin_div_version`'s
no-FK precedent), and `country_of_residence_version` — falling back to
`birth_country_version`/`home_country_version`/`work_country_version` if the first is null,
since `country` has four independent per-field version columns but
`ref.profile_reference_version`'s primary key is `(profile_id, list_code)` and can hold only
one version per list; documented as a deliberate representative-value simplification, not a
silent assumption). Being in the same transaction means a rollback leaves no rows — no extra
machinery needed.

**Second writer — REMOVED 2026-09-16 by AD-022, which deleted `ManualCompletionService`. `SubmissionService` is once again the ONLY path into `submitted`, which simplifies this rule rather than complicating it. Original note follows.** Found by a second review pass and closed same session: `operator.service.
ManualCompletionService` is the OTHER legal path into `submitted` (V0020) besides
`SubmissionService` — a profile can enter stages 3-6, abandon, and later be manually completed
by an operator, still carrying reference-list versions worth recording. It now injects and
calls the same `submission.domain.SubmissionRepository` methods inside its own existing
transaction, right after its guarded `UPDATE` succeeds — the same reuse pattern it already uses
for `submission.domain.SubmissionMessageRenderer`.

## Client rules (apply once mobile consumes the endpoints)
1. **Manifest, then whole lists.** Fetch only lists whose `(version, contentHash)` differ.
   List granularity, never item-level deltas — a merged list is one no server published and
   no hash can verify.
2. **Stage, verify, swap in one drift transaction.** A partial download simply never
   verifies. No `Range`/206 resumption.
3. **The catalogue gate is the stage 2 → stage 3 boundary.** The customer is necessarily
   online through 1a and 2; stages 3-6 are the offline ones. No list snapshot ships in the
   app binary.
4. **Versions are pinned per session at entry to stage 3** (settled as a product decision this
   session — session-pinning staleness accepted, closing the research report's §8 open
   question; the wire-contract mechanics are next session's work). A newly verified version
   stages and activates at the next session boundary. Back-navigation across 3-6 is free, so a
   mid-journey swap could invalidate an already-chosen code. **Accepted staleness window:** a
   device with an in-flight profile may run on a superseded list up to the abandonment
   threshold — acceptable because completion is terminal, one submission per account, and
   lists change rarely.
5. **Hash mismatch: discard, keep the last verified version, bounded backoff, poison the
   version after two failures, report it, block stage 3 if there is no prior cache.** Never
   log and continue; never adopt unverified data — the version is recorded on the profile,
   so adopting unverified data writes an unverifiable version onto a bank record.
   **Divergence, recorded not silently substituted (S5-01):** "two failures" is implemented as
   two *verification* failures specifically (hash mismatch, or hash-verified-but-unparseable) —
   a transient transport failure (a dropped connection, a 5xx) paces the same bounded backoff but
   does NOT count toward poisoning. Found necessary under review: a single counter poisoned a
   version after two ordinary network blips, a condition the Sudan-market context this project
   targets makes routine, not exceptional — indistinguishable in the mobile client from two
   genuine "this artifact is bad" verdicts, which is what poisoning is supposed to mean.
6. The device folds only the query string. `search_ar`/`search_en` arrive server-computed
   and are inside the hash envelope. `sort_ordinal` likewise — the device never collates
   Arabic.
7. `rootItemCode` will come from the manifest once it exists. **Never hardcode `'SD'` in
   Dart** — the backend no longer does either (`DataEntryService.SUDAN_CODE` removed, S4-03).

## Manifest fields (built S4-04)
`catalogHash`, `generatedAt`, and per list: `listCode`, `version`, `itemCount`,
`contentHash`, `isHierarchical`, `rootItemCode`, `rootCountryVersion`, `publishedAt`,
`documentPath`. Plus `verifiableChannels` — R-042's half, reserving the field; the journey
decision R-042 asks for is NOT made by AD-002f.

Per item: `itemCode`, `parentCode`, `labelAr`, `labelEn`, `searchAr`, `searchEn`,
`sortOrdinal`, `isActive`, `extra` — exactly `ref.reference_item`'s shape, so no schema
change was needed to carry them. [OBSERVED V0011 + V0038]

**`labelEn`/`searchEn` are nullable on the wire — found live at S5-01, not previously documented
anywhere.** Several `branch` items have no English name seeded (`label_en IS NULL`), so
`search_en` (`GENERATED ALWAYS AS (ref.ar_fold(label_en)) STORED`) is null for those same rows
too. `labelAr`/`searchAr` stay NOT NULL (AD-005). The mobile client's first live fetch against
all seven lists crashed on this (`type 'Null' is not a subtype of type 'String'`) before the DTO
and drift schema were corrected to treat both as nullable; see the session report.

## Back office
Same endpoints (now built), no local persistence. Fetch at app load, hold in memory, rely on
ETag revalidation. Must fetch **historical** versions (a profile submitted under occupation v1
renders v1's label) — the URLs are version-addressed, so this works with no extra contract.
Renders rejection-reason customer-facing messages from `extra`, never hardcoded
(AD-002f (g)); those messages are now inside the hash envelope. Still does no client-side
sorting or filtering (AD-006).

## Backend consequences
Built at S4-03:
- `ref.reference_list_document` (V0048); `ReferenceDocumentGenerator` (pure, unit-tested) +
  `ReferenceDocumentPublisher` (jdbc); an integration test asserting stored `content_hash` ==
  recomputed, for all seven current versions.
- `root_item_code`/`root_country_version` + composite FK + deferred constraint trigger
  (V0049, R-045).
- The publication step (`ReferenceDocumentPublicationRunner`, Spring-profile-gated
  `CommandLineRunner`; `db/post-migrate/02-publish-reference-documents.md`).
- `DataEntryService.SUDAN_CODE` removed.

Built at S4-04:
- The two read endpoints (`reference.web.ReferenceController`), the `ReferenceDocumentStore`
  port + JDBC adapter, and `ManifestHasher`. V0052 grants `fru_app` `SELECT` on
  `ref.reference_list_document`.
- Version pinning on the data-entry contract; `DataEntryService.resolvePinnedVersion` is now the
  one choke point every one of its four call sites (`validateExists` for country/occupation,
  `validateAdminDivisionState`, `validateIncomeSources`) goes through instead of calling
  `currentVersion` directly. `ReferenceCatalog.versionExists` added.
- `ref.profile_reference_version` written at submission, in `SubmissionService`'s existing
  transaction, and (found by a second review pass) in `ManualCompletionService`'s existing
  transaction too — the other legal path into `submitted`. `app.profile_customer_data.
  income_source_version` added (V0051) so `income_source` has somewhere to record which version
  its multi-row replace-set was validated against.

Built at S5-01 (mobile side):
- `mobile/lib/core/reference/reference_repository.dart` — fetches the manifest, fetches only
  lists whose `(version, contentHash)` differ from cache, SHA-256-verifies every list's bytes
  (`package:crypto`) before any JSON parse, writes list+items in one drift transaction (no
  half-replaced cache on failure), and implements client rule 5's bounded-backoff/poison-after-
  two-failures behaviour (`ReferenceListFailures` table). Proven live against a real running
  backend for all seven lists — see the session report.
- The AD-002f staging requirement: `ReferenceLists.isActiveVersion`, a partial unique index
  (`WHERE is_active_version = 1`), and `activateStagedVersion()` promoting the newest staged row
  — a manual stand-in for the real stage-2→stage-3 session-boundary hook, since no journey stage
  exists yet to call it automatically.
- `mobile/lib/core/database/session_database.dart`'s `PinnedReferenceVersions` table — the
  session-pin half of client rule 4, similarly called manually pending a real stage-3 entry
  point.
- `crypto` 3.0.7 added to `mobile/pubspec.yaml` (was admitted at S4-03 but not yet added).

Still not built:
- A client path to report a verification failure (§5.5 point 5 of the research report) — no
  wire field or endpoint exists for this yet; deferred, no session has needed it.
- Any journey-stage code actually consuming these endpoints — S5-01 built one demo screen
  (occupation list) proving the pipeline, not a journey stage. The back office still consumes
  nothing.
- Real stage-2→stage-3 and stage-3-entry hooks calling `activateStagedVersion`/
  `pinCurrentSessionVersion` automatically — both exist as repository methods, called manually
  from the demo screen's init only.

## Open
- [ ] `ManifestHasher.catalogHash` does not cover `verifiableChannels` — safe today because that
      field is a hardcoded constant in `ReferenceController`, but whoever wires R-042's real
      per-deployment channel configuration into it must also feed it into the hash, or a cached
      client will keep 304-revalidating against an unchanged `catalogHash` and never see the
      change. See `ManifestResponse`'s Javadoc.
- [ ] OQ-012's corrected dataset root code — decides whether the R-045 FK is satisfiable for
      a republished `admin_division`. **Ask before that republication migration is written.**
      If the corrected dataset has no ISO-mappable root, the FK fails loudly at publication —
      a defined failure mode, not a silent empty picker.
- [ ] Serialised catalogue size — never measured; all size figures in the research report are
      arithmetic.
- [ ] Does `package:http` cache responses? Only `dart:io HttpClient` was verified.
- [ ] Skipping the publication step leaves `content_hash`/`reference_list_document` silently
      stale for a republished list — the root-invariant trigger still fires regardless, but
      nothing today fails loudly about a missing document. See
      `db/post-migrate/02-publish-reference-documents.md`.
- [ ] `ref.assert_list_root` also reads `ref.reference_list.is_hierarchical`, and no trigger
      guards an `UPDATE` to that column the way V0049's two triggers guard `reference_item`
      rows and `reference_list_version.root_item_code`. Found under review; not built,
      because nothing in this codebase's migration history ever updates
      `is_hierarchical` after a list's seed-time INSERT — a real write path would need to
      exist before a third trigger earns its place, and `ref.trg_assert_list_root()` cannot
      be reused as-is (that table has no `version` column). See V0049's closing comment.
