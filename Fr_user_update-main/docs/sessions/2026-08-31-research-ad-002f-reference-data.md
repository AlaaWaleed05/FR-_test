# AD-002f — Reference-data delivery, offline caching, versioning and integrity

Research report · 2026-08-31 · researcher agent (read-only; no code, endpoint, migration or schema was written)

---

## 0. Reading taken, and readings not pursued

The task is unambiguous in scope but two phrasings admit more than one reading. I state the ones I took:

- **"records which list version resolved each submission"** — I read this as **one version per list per profile**, the shape `ref.profile_reference_version` already has (`PRIMARY KEY (profile_id, list_code)`) [OBSERVED `backend/src/main/resources/db/migration/V0011__ref_registry_and_fold.sql:76-83`]. I did **not** pursue per-field version recording (three or six columns for birth/home/work state and locality) — that is BL-017's territory and a schema decision of its own [OBSERVED `BACKLOG.md:16`]. My recommendation happens to remove BL-017's root cause as a side effect; see §6.5.
- **"detects a stale or corrupted cache"** — I read this as covering both *stale* (a newer version exists server-side) and *corrupted* (the bytes the device holds are not the bytes the server published). I did not treat "corrupted" as including SQLite file-level corruption, which is a drift/sqlite3 concern settled under AD-005 and outside this question.
- I did **not** evaluate a push/notification-driven invalidation channel (FCM/APNs), because the messaging port is closed at three channels (`sms`/`whatsapp`/`email`) with a fourth being a migration [OBSERVED `PROJECT_PLAN.md` AD-002c row], and no push infrastructure exists.

---

## 1. Summary and recommendation

**R-045 (cascade root):** enforce it **in the database**, but not as a `CHECK` — PostgreSQL documents that a `CHECK` may not reference other rows [DOC]. Make the root **declared data** rather than a coincidence: add `root_item_code` and `root_country_version` to `ref.reference_list_version`, with a composite foreign key onto `ref.reference_item('country', root_country_version, root_item_code)` using the generated-constant pattern V0006 already uses; and add one `DEFERRABLE INITIALLY DEFERRED` constraint trigger asserting that a hierarchical list's single parentless row carries exactly that `item_code`. Then serve `rootItemCode` in the manifest so the client, the backend and the back office all read the value instead of hardcoding `'SD'` — which today is hardcoded in **at least three independent places** beyond the two lists R-045 names.

**R-033 (integrity envelope):** **widen it — to everything.** Do not widen `content_hash` field-by-field. Redefine `content_hash` as **the SHA-256 of the exact bytes of the list document the server serves**. That makes `extra`, `sort_ordinal`, `is_active`, `search_ar` and `search_en` covered by construction, removes the need for a Dart canonical-JSON implementation entirely (the client hashes what it received), and makes "verify before you swap your cache" a two-line operation on the device. R-033 as currently written is **under-scoped**: `sort_ordinal` and `is_active` are outside the envelope too, and `sort_ordinal` is the one field the device provably cannot recompute.

**Wire contract:** manifest + **whole-list** fetch of only the lists whose `(version, contentHash)` differ from the cache. Not item-level deltas. At 583 items across seven lists, and with two of the seven known to be replaced outright, a delta protocol buys almost nothing and costs the integrity property that makes the whole design work: the bytes verified are the bytes stored.

**One recommendation, stated plainly:** a two-endpoint, version-addressed, immutable-document contract; hash-of-served-bytes as the single integrity primitive; a session-pinned version so a mid-journey republication cannot invalidate a customer's in-flight answers; and `ref.profile_reference_version` actually written at submission, which nothing does today.

---

## 2. What is actually in the repository today

Evidence base for everything that follows.

| Fact | Marker |
|---|---|
| `ref.reference_list` / `reference_list_version` / `reference_item` / `profile_reference_version` exist; `ref_one_current` is a partial unique index guaranteeing at most one current version per list | [OBSERVED `V0011__ref_registry_and_fold.sql:10-83`] |
| `content_hash` is `sha256(convert_to(c,'UTF8'))` over `string_agg(item_code‖'|'‖parent_code‖'|'‖label_ar‖'|'‖label_en, E'\n' ORDER BY item_code COLLATE "C")` | [OBSERVED `V0019__seed_rejection_reason.sql:74-79`; `V0022__seed_country.sql:314-319`] |
| The canonicalisation deliberately excludes `extra` | [OBSERVED `V0022__seed_country.sql:29-32` — "deliberately excluded from the content_hash input below"] |
| It also excludes `sort_ordinal`, `is_active`, `search_ar`, `search_en` — none appear in any `canon` CTE | [OBSERVED all seven seed migrations; `V0038__ref_search_en_generated.sql:18-21` states the exclusion of `search_ar`/`search_en` explicitly] |
| The current format is explicitly **not** a settled wire format, and republishing every list under AD-002f was pre-authorised | [OBSERVED `V0014__seed_occupation.sql:34-43` — "it is NOT a settled wire format … A future AD-002f session may need to change this and republish every list's version — that is fine"] |
| `admin_division`'s root row is `('SD', NULL, 'السودان', 'Sudan')`; the country list's Sudan row is alpha-2 `SD` with `extra = {"alpha3":"SDN"}` | [OBSERVED `V0016__seed_admin_division.sql:35`; `V0022__seed_country.sql:20-27`] |
| `'SD'` is hardcoded a **third** time, in the `app` schema: `CONSTRAINT sudan_uses_codes CHECK (home_country_code IS DISTINCT FROM 'SD' OR home_locality_code IS NOT NULL)` | [OBSERVED `V0006__app_customer_data.sql:42-43`] |
| …and a **fourth** time, in Java: `DataEntryService.SUDAN_CODE = "SD"`, with a Javadoc that already names R-045 and refuses to hide the dependency | [OBSERVED `backend/.../dataentry/service/DataEntryService.java:76-84`, used at lines 183, 268, 294] |
| `extra` is flat on every seeded list: `{"alpha3":…}` (country), `{"customerMessageAr":…,"customerMessageEn":…}` (rejection_reason), one marker on income_source `OTHER` | [OBSERVED `V0022`, `V0019:31-67`, `V0017`] |
| `ReferenceCatalog` is a read-only port with `currentVersion`/`exists`/`parentCode`/`find`; its Javadoc already anticipates a caller validating against a client-supplied older version | [OBSERVED `reference/domain/ReferenceCatalog.java:14-18`] |
| **Every** validation call site resolves `referenceCatalog.currentVersion(listCode)` and validates against *that*, never a client-supplied version | [OBSERVED `DataEntryService.java:255, 263, 353`; `OperatorReviewService.java:161`] |
| S3-11's own note records this as deliberate deferral to AD-002f | [OBSERVED `EXECUTION_PLAN.md` S3-11 row — "Reference-code validation always uses a list's *current* published version, not a client-supplied one (AD-002f, still open, deliberately not designed around here)"] |
| **`ref.profile_reference_version` is written by no application code.** Grep across `backend/src/main/java` returns zero hits; the only references are migrations and session reports | [OBSERVED repo-wide grep, 2026-08-31] |
| `fru_app` holds `SELECT` on the three ref tables and `SELECT, INSERT` on `profile_reference_version`; publication is admin-only and out of scope of the seeds | [OBSERVED `V0012__ref_grants.sql:15-20`] |
| **No publication code path exists.** Publication today is exclusively a Flyway migration run by `fru_migrator`. No admin endpoint, no service, no SQL function | [OBSERVED — no writer to `ref.reference_list_version` outside `db/migration/`] |
| The mobile tier is a bare Flutter scaffold: `mobile/lib/` contains only `main.dart`; `pubspec.yaml` has no dependency beyond `cupertino_icons` | [OBSERVED `mobile/pubspec.yaml`, `mobile/lib/`] |
| Item counts: occupation 135, branch 25, admin_division 151 (1 country + 15 states + 135 localities), income_source 9, education_level 7, rejection_reason 7, country 249 — **583 items total** | [OBSERVED seed migrations and `EXECUTION_PLAN.md` S2-03/S2-07 rows] |
| Spring Boot 4.1.0, Java 21 | [OBSERVED `backend/pom.xml:5-13, 30`] |

**The single most consequential observation:** the mobile tier does not exist yet. Every part of this contract is genuinely greenfield on the client side, and the only shipped code it disturbs is `DataEntryService`'s version resolution. The task's framing — "a contract change is cheap now and expensive once the app is built against it" — is correct and understates it slightly: it is nearly free right now.

---

## 3. R-045 — the cascade root invariant

### 3.1 What the invariant actually is

R-045 as filed describes an equality between two reference lists [OBSERVED `RISKS.md:50`, `docs/components/persistence.md:360-364`]. The code says it is wider than that. `'SD'` is currently asserted, independently and with nothing tying any of them together, in:

1. `ref.reference_item` — `admin_division`'s root `item_code` [OBSERVED `V0016:35`]
2. `ref.reference_item` — the `country` list's Sudan row `item_code` [OBSERVED `V0022`]
3. `app.profile_customer_data`'s `sudan_uses_codes` CHECK constraint [OBSERVED `V0006:42-43`]
4. `DataEntryService.SUDAN_CODE` [OBSERVED `DataEntryService.java:84`]
5. *(future)* the Flutter address picker, which must know when to switch state/locality to free text [OBSERVED the requirement at `docs/journeys/customer.md:415-419`]

A corrected OQ-012 dataset rooted on `SDN` breaks 1↔2, 1↔4 and 1↔5. It leaves 3 intact but now meaningless. Fixing only the two lists would leave a Java constant and a database CHECK still asserting the old value — so "enforce the equality" is the wrong frame. **The right frame is: stop asserting the value in five places and publish it once.**

### 3.2 Options considered

**Option A — a `CHECK` constraint.** Ruled out on documented grounds, not preference: "PostgreSQL does not support `CHECK` constraints that reference table data other than the new or updated row being checked… This would cause a database dump and restore to fail." The documentation directs you to `UNIQUE`, `EXCLUDE` or `FOREIGN KEY` for cross-row restrictions, and to a custom trigger for one-time checks against other rows. [DOC postgresql.org/docs/18/ddl-constraints.html]

**Option B — an assertion in whatever publishes a new list version.** This is the task's stated alternative and it is the weaker one *today*, for a specific reason: **there is no publisher.** Publication is a hand-written Flyway migration [OBSERVED §2]. An assertion "in the publication step" therefore means "a line of SQL a human remembers to add to a migration" — which is precisely the failure mode R-045 describes: a silent omission with no failing test and no error. AD-005 §11's "authenticated admin endpoint" does not exist and is not scheduled. This option becomes strong the moment such an endpoint exists; it protects nothing before then.

**Option C — declare the root as data, guard it with a native FK, and assert the one part an FK cannot express with a deferred constraint trigger.** Recommended.

### 3.3 Recommendation

Enforce **in the database**, in two mechanisms split along what each can actually guarantee:

**C1 — declarative, no trigger.** Add to `ref.reference_list_version`:
- `root_item_code text` (NULL for a flat list),
- `root_country_version int`,
- `root_country_list text GENERATED ALWAYS AS ('country') STORED`,
- `FOREIGN KEY (root_country_list, root_country_version, root_item_code) REFERENCES ref.reference_item (list_code, version, item_code)`.

This is the exact pattern `app.profile_customer_data.occupation_list GENERATED ALWAYS AS ('occupation') STORED` already uses to hang a composite FK on a versioned reference item [OBSERVED `V0006:16-18`, closed at `V0013`]. It gives, natively and with no trigger: *the code this hierarchy is rooted at is a real, published country code.* A dataset rooted on `SDN` fails the FK at publication, because `SDN` is not an alpha-2 `item_code` in the country list — it is the value in `extra.alpha3` [OBSERVED `V0022:29-32`].

Naming `root_country_version` rather than "whatever is current" is deliberate: an `admin_division` version published against country v1 keeps pointing at country v1 forever, and `reference_list_version` rows are never deleted [OBSERVED AD-005 §4.4 as quoted in `docs/sessions/2026-08-22-research-ad-005-persistence.md:415`]. ISO 3166-1 alpha-2 codes are stable, so a later country republication does not invalidate the pointer. [UNVERIFIED: that ISO will not reassign `SD`. Historically alpha-2 reassignment happens — `CS`, `YU` — but not on any timescale this campaign runs on.]

**C2 — one `DEFERRABLE INITIALLY DEFERRED` constraint trigger** on `ref.reference_item`, whose body is a callable function `ref.assert_list_root(list_code, version)` asserting, at commit:
- a hierarchical list has **exactly one** row with `parent_code IS NULL`;
- that row's `item_code` equals the version's declared `root_item_code`;
- a flat list has `root_item_code IS NULL` and no parentless-row requirement beyond every row having `parent_code IS NULL`.

Deferred-to-commit matters: the seed migrations insert the version row and all items in a **single** data-modifying CTE statement [OBSERVED `V0014:30-32` explains why], so a row-level immediate trigger would fire mid-list against an incomplete hierarchy.

**Why a trigger is safe here despite the `CHECK` warning:** the dump/restore hazard the documentation raises applies to `CHECK`, which is enforced during the data load. Triggers are emitted in pg_dump's **post-data** section — "Post-data items include definitions of indexes, triggers, rules, statistics for indexes, and constraints other than validated check and not-null constraints" [DOC postgresql.org/docs/18/app-pgdump.html] — so a constraint trigger is created after the restore's `COPY` statements and cannot fail a restore on partial data. It also, correspondingly, does not validate restored data; that is what makes C3 worth having.

**C3 — the same function, called from a test.** An integration test over every `is_current` version, calling `ref.assert_list_root` and asserting it passes. This catches a bad *existing* state, a restore, and a future publication path that bypasses the trigger. One implementation, three call sites.

**C4 — stop hardcoding the value.** `rootItemCode` goes in the manifest (§5). `DataEntryService.SUDAN_CODE` becomes a lookup through `ReferenceCatalog`. `V0006`'s `sudan_uses_codes` CHECK is left alone but flagged: it is now a stale literal, and it is a `CHECK` on a single row so it is legal, but it silently encodes an assumption that C1–C3 have moved elsewhere. [OBSERVED — recommend a comment, not a migration; it does not break under an `SDN` root, it merely stops meaning anything, and rewriting it would need the cross-row reference PostgreSQL forbids.]

The trigger and the FK belong in schema `ref`, not `audit`, so CLAUDE.md's `SET LOCAL fru.migration_in_progress` rule (R-035) does not apply — the same reasoning V0038 records for itself [OBSERVED `V0038:21-23`].

### 3.4 When this recommendation flips

- **If OQ-012's corrected dataset arrives still rooted on `SD`, and the bank confirms `admin_division` will only ever be published by a Flyway migration**, then C3 alone (an integration test) is defensible and C1+C2 are over-built. I do not recommend betting on it: OQ-012's dataset is externally sourced, and R-014/R-015 already show the supplied file departing from expectation in three separate ways [OBSERVED `RISKS.md:21-22`].
- **If an authenticated admin publication endpoint is built before OQ-012 lands**, Option B's cost collapses and C2's trigger becomes belt-and-braces. C1 (the FK) still earns its place, because it is what lets the manifest carry a root code the client can trust.
- **If the corrected dataset turns out to be rooted on a numeric code with no ISO meaning at all** (a plausible outcome from a national statistics office), the FK in C1 cannot be satisfied and the design must instead carry an explicit mapping row. That is the case I would most want to hear about before building, and it is the reason to ask for the dataset's root row *now* rather than after.

---

## 4. R-033 — the integrity envelope

### 4.1 The problem is larger than filed

R-033 names `extra` [OBSERVED `RISKS.md:40`]. Reading the seven `canon` CTEs, the envelope excludes **five** columns, not one:

| Column | In envelope? | What a silent change to it does |
|---|---|---|
| `item_code`, `parent_code`, `label_ar`, `label_en` | yes | — |
| `extra` | **no** | wrong ISO alpha-3; wrong customer-facing rejection SMS text, which four of seven REJ codes share deliberately [OBSERVED `V0019:19-21`] |
| `sort_ordinal` | **no** | the picker's Arabic ordering is wrong, and **the device cannot detect or recompute it** — `sort_ordinal` exists precisely because Dart cannot collate Arabic [OBSERVED `docs/components/persistence.md:44-45`] |
| `is_active` | **no** | a superseded duplicate becomes selectable, or a live occupation vanishes from the picker |
| `search_ar`, `search_en` | **no** | substring search silently returns nothing for affected items; "the device folds only the query string, not the labels" [OBSERVED `docs/components/persistence.md:51-55`] |

A hash that covers four of nine served fields is worse than no hash, on exactly the grounds CLAUDE.md applies to research reports: it converts a known unknown into an unknown one. A client that verifies and proceeds believes it has verified the list.

### 4.2 Options considered

**Option A — enumerate more columns into the SQL `string_agg`.** Cheapest edit. Rejected for three reasons. First, it must canonicalise `jsonb`: `extra::text` key ordering is PostgreSQL's internal jsonb normalisation, which is deterministic in practice but is an implementation detail, not a contract [UNVERIFIED — I did not find a documented stability guarantee for jsonb text output ordering across major versions]. Second, it leaves the *client* needing an independent canonicalisation to check anything — a Dart RFC 8785 implementation, i.e. a fourth implementation of a shared canonical form in a project that already has three implementations of `ar_fold` and a filed risk about them diverging (BL-014). Third, the existing SQL form already carries two live footguns its own authors documented: the `ORDER BY item_code COLLATE "C"` and the literal `E'\n'` "never a raw embedded newline — see V0014/V0016's header comment for why that distinction matters on this machine" [OBSERVED `V0022:38-41`].

**Option B — declare `extra` outside the envelope and say what that means.** The task offers this. It means, concretely: a client can never trust `extra`; the back office must therefore fetch rejection-reason customer messages *live* rather than from a verified cache; and the alpha-3 code becomes unusable for anything the bank relies on. Given AD-002f note (g) already requires the back office to render REJ messages from `extra` and never hardcode them [OBSERVED `PROJECT_PLAN.md` AD-002f (g)], this option makes a required behaviour unverifiable. Rejected.

**Option C — redefine `content_hash` as the SHA-256 of the served document bytes.** Recommended.

### 4.3 Recommendation

**`content_hash` = SHA-256 over the exact UTF-8 bytes of the list document served at `GET /api/v1/reference/lists/{listCode}/{version}`, before any transport content-coding.**

Everything follows from that one sentence:

- **The envelope becomes "the whole document."** `extra`, `sort_ordinal`, `is_active`, `search_ar`, `search_en` are covered because they are in the bytes. No enumeration to keep in sync with the schema; adding a field to the document automatically extends the envelope.
- **The client needs no canonical-JSON implementation.** It hashes the response body it decoded and compares to the manifest. That is the difference between a Dart JCS port (a fourth divergence risk) and `sha256.convert(bytes)`.
- **"Verify before swap" becomes exact.** The device verifies the artefact it is about to store, not a re-derivation of it.
- **The ETag is free and strong.** `ETag: "<contentHash>"` is definitionally correct.

**To make "the served bytes" a stable thing that exists before serving, store the document.** Add `ref.reference_list_document (list_code, version, document_json text, sha256 bytea, PRIMARY KEY (list_code, version), FK → reference_list_version)`. Serving is one `SELECT`. The alternative — regenerate deterministically in Java on each request — is defensible but rests on a serialiser's output never changing across a Jackson upgrade, and this project already built `audit.domain.CanonicalJson` rather than trust a general-purpose serialiser's key order [OBSERVED `docs/components/persistence.md:245-251`]. Storing the bytes removes the class of problem instead of guarding it. Cost: 583 rows' worth of JSON, a few hundred kilobytes. [UNVERIFIED — I did not measure the serialised size; the item counts are [OBSERVED] and the estimate is arithmetic, not measurement.]

**Who computes it.** One Java class, called at publication, writing both `reference_list_document.document_json`/`sha256` and `reference_list_version.content_hash`. The seed migrations' `canon` CTEs go away. An integration test recomputes the hash from `ref.reference_item` for every current version and asserts it equals the stored value — the guard against a document and its rows drifting apart.

**Migration cost, and why it is near zero right now.** Redefining `content_hash` changes the value stored for all seven v1 rows. Normally that would force a version bump. It does not here, because **nothing references those hashes yet**: `ref.profile_reference_version` has no rows written by any code, the `app` FKs point at `reference_item(list_code, version, item_code)` and not at the hash, and no production data exists. So a one-off migration updating `content_hash` in place for the seven v1 rows is legitimate — and it is legitimate **only until the first real submission**. Say so explicitly in the migration. V0014's own header already pre-authorises the change and even anticipated the more expensive republication path [OBSERVED `V0014:38-43`].

### 4.4 When this recommendation flips

- **If reference documents ever grow past a size where holding one in memory to hash it is a problem on a low-end Android device**, hashing-the-bytes stops being free and a streaming/Merkle form is needed. At 583 items this is not close. The flip point is roughly a single list in the tens of thousands of items — the corrected `admin_division` dataset will grow (three missing states plus their localities, R-014) but by tens of rows, not thousands.
- **If a requirement appears to serve the same list to different clients differently** (e.g. English-only for the back office, Arabic-only for mobile), "the served bytes" stops being one thing and the hash must move back to a canonical form over the rows. Do not introduce per-client shaping of these documents; serve one document and let each client ignore what it does not need.

---

## 5. The wire contract

### 5.1 Shape: manifest + whole-list fetch

Two endpoints. Both are safe, idempotent GETs, so AD-002f requirement (a) — the 409 `REQUEST_IN_PROGRESS` / 422 `IDEMPOTENCY_KEY_REUSED` pair [OBSERVED `PROJECT_PLAN.md` AD-002f (a)] — **does not apply to these endpoints.** It belongs to the mutating journey calls and is noted here only so it is not assumed dropped.

```
GET /api/v1/reference/manifest
200 application/json
ETag: "<catalogHash>"
Cache-Control: no-cache

{
  "catalogHash": "<sha256 hex over the list entries below>",
  "generatedAt": "2026-08-31T09:12:00Z",
  "lists": [
    { "listCode": "occupation",     "version": 1, "itemCount": 135,
      "contentHash": "<sha256 hex>", "isHierarchical": false, "rootItemCode": null,
      "publishedAt": "...", "documentPath": "/api/v1/reference/lists/occupation/1" },
    { "listCode": "admin_division", "version": 1, "itemCount": 151,
      "contentHash": "<sha256 hex>", "isHierarchical": true,  "rootItemCode": "SD",
      "rootCountryVersion": 1, "publishedAt": "...", "documentPath": "..." },
    ... five more
  ],
  "verifiableChannels": ["sms", "whatsapp", "email"]
}
```

```
GET /api/v1/reference/lists/{listCode}/{version}
200 application/json
ETag: "<contentHash>"
Cache-Control: public, max-age=31536000, immutable

{ "listCode":"occupation", "version":1, "itemCount":135,
  "nameAr":"المهنة", "nameEn":"Occupation",
  "isHierarchical":false, "rootItemCode":null,
  "items":[
    { "itemCode":"1", "parentCode":null,
      "labelAr":"طبيب", "labelEn":"Doctor",
      "searchAr":"طبيب", "searchEn":"doctor",
      "sortOrdinal":97, "isActive":true, "extra":null },
    ... ] }
```

The per-item field set is exactly AD-002f requirement (d) [OBSERVED `PROJECT_PLAN.md` AD-002f (d)] and exactly `ref.reference_item`'s shape, so **no schema change is needed to carry it** — only the two additions in §3.3/§4.3, which are about the invariant and the envelope, not about the item fields.

`verifiableChannels` is R-042's half — "a small additive change to the 1b contract and to AD-002f's manifest shape — which channels this deployment can actually verify" [OBSERVED `RISKS.md:48`]. Included as a manifest field because it is deployment configuration with the same lifecycle as the catalogue. **The journey decision R-042 asks for is not made here** and is not mine to make; this only reserves the field.

**Why whole-list, not item-level delta.** Three reasons, in order of weight:

1. **Integrity.** A delta produces a locally-constructed list that no server ever published; its hash cannot be checked against anything the server signed. To verify a merged result you would need the server to publish a hash of the post-merge state — i.e. you would need the whole-list hash anyway, plus a merge you must trust. Whole-list replacement means *the bytes verified are the bytes stored*.
2. **The known change pattern is replacement.** Two of the seven — occupation and `admin_division` — are known to be replaced outright (OQ-012, and "when the bank supplies a better list, it replaces this one" [OBSERVED `docs/journeys/customer.md:479-480`]). A delta is worst-case exactly there.
3. **Size.** 583 items total. [UNVERIFIED size estimate: ~150 KB uncompressed for the whole catalogue, well under 50 KB gzipped, since Arabic UTF-8 label text compresses heavily. I did not measure this; the item counts are [OBSERVED].] A delta protocol's fixed cost — a diff format, a device-side merge, tests for both — exceeds any plausible bandwidth saving at that scale, for a solo developer.

"Fetch only what changed" is preserved, at **list granularity**: the manifest is a few hundred bytes and usually returns 304; a typical check downloads nothing.

### 5.2 How the app learns a newer version exists

- **Poll the manifest** with `If-None-Match` on every foreground-with-connectivity transition, and whenever the last successful check is older than a configured interval. A 304 costs a round trip and no body.
- **The gate is at the stage 2 → stage 3 boundary, not at stage 5.** The customer is necessarily online for stage 1a (account check) and stage 2 (OTP) [OBSERVED `docs/journeys/customer.md:47-192`]; stages 3–6 are the offline-capable ones [OBSERVED `customer.md:297-299`]. So the app requires a complete, verified cache of all seven lists **before** it lets the customer into stage 3, and blocks there with an explicit "preparing" state if it does not have one. This is why **no bundled snapshot of the lists needs to ship in the app binary.** A bundled seed is the fallback if that fetch proves unreliable in the field; it is not hardcoding (the version is recorded, the code contains no list), but it does put a snapshot in an app-store release, which is what the no-hardcoding rule exists to avoid [OBSERVED `CLAUDE.md` reference-list rule], so do not ship one until measurement says you must.
- **Optional, cheap:** an `X-Reference-Catalog: <catalogHash>` response header on the existing journey endpoints, so the app learns of a change without a dedicated round trip. Additive; skip it if it complicates the filter chain.

### 5.3 Version pinning — the part that is easy to miss

**A newly verified version must not become active mid-journey.** Because the version used is recorded on the profile, and because back-navigation across stages 3–6 is free [OBSERVED `customer.md:301-302`], swapping the occupation list between stage 4 and stage 12 can leave a selected code that no longer exists — invalid at submission, with the customer having done nothing wrong.

So: **the session pins one version per list at the moment the customer enters stage 3.** A newer verified version sits staged in the reference database and activates at the next session boundary (no in-progress profile locally, or after completion/abandonment). Two states in drift, one active pointer per list.

This interacts with shipped code. `DataEntryService` validates against `referenceCatalog.currentVersion(listCode)` at four call sites [OBSERVED §2]. If the server publishes occupation v2 while a customer sits pinned to v1, the backend rejects a perfectly valid v1 code as `unknown occupation code`. **The contract must carry the pinned version per list on each data-entry and submission call, and the backend must validate against it** — which is exactly the case `ReferenceCatalog`'s Javadoc already left room for ("a future caller that needs to validate against a client-supplied (possibly older, cached) version can do so without this port changing shape") [OBSERVED `ReferenceCatalog.java:14-18`]. The backend must bound what it accepts: a version that exists in `reference_list_version` for that list and is not older than a configured floor, so a stale client cannot pin forever.

### 5.4 Interrupted downloads

- **Stage, verify, then swap — never write into the live table incrementally.** Download to a staging area in the reference drift database keyed `(list_code, version)`; hash the decoded body; on match, replace the live rows and move the active pointer **in one drift transaction**.
- **A partial download therefore needs no special handling: it simply never verifies.** Discard and retry from the start.
- **No `Range`/206 resumption.** RFC 9110 defines `Range` (§14.2), 206 Partial Content (§15.3.7) and 416 (§15.5.17) [DOC rfc-editor.org/rfc/rfc9110.html], and none of it earns its complexity for a ~40 KB immutable document. Resumption also reintroduces the merge problem §5.1 rejects.
- **Because `(listCode, version)` is immutable, retry is always safe** — a retry cannot fetch different content than the first attempt did, so there is no torn-read window.
- **On an update, the journey never blocks:** the previously verified version stays active until the swap succeeds. **On a first run with no prior cache, there is nothing to fall back to** — hence the stage 2→3 gate in §5.2, which puts the block at the one point where the customer is online anyway.

### 5.5 Hash verification failure — what the client does

**Not "log and continue." Not "log and adopt anyway." Not "retry unverified."** The concrete harm is specific: the version used is recorded on the profile, so adopting unverified data writes an unverifiable version number onto a submitted bank record.

1. **Discard the staged document entirely.** Never partially adopt, never merge, never keep "the items that parsed".
2. **Keep the previously verified version active.** The journey continues on known-good data. If there is no previously verified version (first run), **block entry to stage 3** with a distinct, non-generic state — not the generic offline indicator, because this is not an offline condition and telling the customer to find signal is wrong advice.
3. **Retry with capped exponential backoff, bounded** (three attempts per catalogue check is a reasonable starting point). Not a tight loop.
4. **After the second failure for the same `(listCode, version)`, mark that version poisoned locally** and stop fetching it until the manifest advertises a *different* version. Without this, one bad publication makes every device in the country retry forever.
5. **Report it.** A hash mismatch is either a corrupt publication or an intercepted response; both need to be visible centrally, and a single device's local log is not visibility. This needs a field or a small endpoint — a contract consequence, listed in §6.
6. **The customer-facing message must not blame the network,** because it is not a network problem.

The server-side counterpart that stops this from being a mass-outage mechanism is the §4.3 integration assertion that stored `content_hash` equals the hash recomputed from `ref.reference_item`. If the manifest can advertise a hash the document does not have, every device blocks simultaneously.

### 5.6 What HTTP caching does, and what it does not

**It does part of the job, and it is safe to use — as an optimisation layered on top of the drift cache, never as the cache.**

Where it helps:
- The **list document is immutable at `(listCode, version)`** — rows for a published version are never rewritten under AD-005's never-delete-a-version model [OBSERVED AD-005 §4.4]. So `Cache-Control: public, max-age=31536000, immutable` and a strong `ETag: "<contentHash>"` are both correct. `public` is safe here and only here: reference data contains no PII, unlike every other endpoint in this system, all of which must be `no-store`. Say that explicitly in the contract so nobody copies the header.
- The **manifest is mutable**, so `Cache-Control: no-cache` (revalidate every time, reuse the body if unchanged) plus an ETag. Not `no-store`, which would forbid the 304 and throw away the whole benefit. ETag and `If-None-Match` are RFC 9110 §8.8.3 and §13.1.2 [DOC rfc-editor.org/rfc/rfc9110.html].

Where it does not help, and must not be relied on:
- **There is no HTTP response cache in the Flutter client's stack.** `dart:io HttpClient` documents connection caching and cookie state, and no response cache honouring `Cache-Control`/`ETag` [DOC api.flutter.dev/flutter/dart-io/HttpClient-class.html]. [UNVERIFIED for `package:http` — I did not read its documentation; my expectation is that it has none either, but that is memory, not evidence, and it should be confirmed before anyone depends on it.] So conditional requests will be **explicit code** on the device, storing the ETag alongside the cached list, not automatic behaviour.
- Even with a perfect HTTP cache, it would be the wrong thing: it is opaque and evictable under storage pressure, it cannot answer "which version is this profile pinned to", it cannot serve a `WHERE parent_code = ? ORDER BY sort_ordinal` query for the cascade, and it cannot run the trigram-style substring search stage 4 needs. The drift reference database exists for those [OBSERVED AD-005: two local databases, "session, cleared; reference, kept"].
- **Do not use `ShallowEtagHeaderFilter` for these endpoints.** It "creates a 'shallow' ETag by caching the content written to the response and computing an MD5 hash from it" and "saves network bandwidth but not CPU, as the full response must be computed for each request" [DOC Spring Framework 7.0.9 reference, web/webmvc/filters]. For the list endpoint we already hold a stronger ETag (the stored `contentHash`) *before* rendering anything, so set it explicitly and answer 304 without reading the document at all. Two competing "hashes of the same body" — an MD5 transport ETag and a SHA-256 integrity hash — is exactly the confusion to avoid in a design whose whole point is one integrity primitive.
- If the deployment sits behind a TLS-terminating proxy or CDN (AD-002d, open), `public, immutable` on the list documents is a genuine win and carries no PII exposure. Note it for AD-002d; do not decide hosting here.

---

## 6. What this forces on the backend and the schema

Ordered by cost of doing it later.

**6.1 Version pinning on the mobile→backend contract (changes shipped code).** Data-entry and submission calls carry the pinned version per list; `DataEntryService` validates against it instead of `currentVersion` at its four call sites; the backend bounds acceptable versions. This touches `dataentry` and `submission`, both shipped at S3-11/S3-13. Cheapest now, because no client exists to break.

**6.2 `ref.profile_reference_version` must actually be written at submission.** Nothing writes it today [OBSERVED §2], so the standing constraint "the list version used for a submission is recorded on the profile" [OBSERVED `PROJECT_PLAN.md` Constraints; `CLAUDE.md`] is **not currently met**. `fru_app` already holds `SELECT, INSERT` on it [OBSERVED `V0012:20`], so no grant change is needed — only a writer, in `SubmissionService`'s existing transaction, one row per list the profile actually used.

**6.3 `content_hash` redefined (§4.3).** New `ref.reference_list_document` table; one Java class owning canonicalisation and hashing; the seven `canon` CTEs retired; a one-off in-place update of the seven v1 hashes, legitimate **only** while no submitted profile references them; an integration test asserting stored == recomputed.

**6.4 Root declaration and its guards (§3.3).** Two columns plus a generated constant and a composite FK on `ref.reference_list_version`; one `ref.assert_list_root` function; one deferred constraint trigger; a one-time validation of existing current versions in the same migration; `DataEntryService.SUDAN_CODE` replaced by a lookup; a comment on `V0006`'s `sudan_uses_codes`.

**6.5 Free consequence — BL-017 dissolves.** BL-017 is that `app.profile_customer_data.admin_div_version` is one column shared by birth, home and work addresses, and whichever stage submits last overwrites it [OBSERVED `BACKLOG.md:16`]. With a **session-pinned** `admin_division` version, all three are necessarily validated against the same version, so the shared column stops being able to hold a wrong answer. This does not close BL-017 by itself — the column still cannot express three different versions if pinning is ever removed — but it removes the mechanism that produces the defect. Record the connection on BL-017.

**6.6 Two new read endpoints, and a decision they must not make.** `GET /api/v1/reference/manifest` and `GET /api/v1/reference/lists/{listCode}/{version}`, in a `reference.web` package alongside the existing `reference.domain`/`reference.jdbc` [OBSERVED the package already exists and is correctly shaped under the S3-01 layout rule]. **Authentication is AD-002e and is not settled here.** Two facts for whoever settles it: the payload contains no PII, and these are the first endpoints whose response body is large enough that unauthenticated access is a bandwidth surface — the same class of concern as BL-007's rate-limiting gap, and worth adding to BL-007 rather than opening a new item.

**6.7 A defined publication step.** Today publication is a hand-written migration. The document generation in 6.3 and the assertion in 6.4 both need a defined moment to run. Recommend a documented SQL/CLI step in the `db/post-migrate/` precedent's spirit, invoked by whoever publishes — **not** an admin endpoint, which drags in AD-002e. Note the existing precedent and its known weakness: `db/post-migrate/` is already a manual step outside Flyway that a deployment can skip silently (R-028) [OBSERVED `RISKS.md:35`]. The constraint trigger in 6.4 is what makes skipping it fail loudly rather than silently, which is the reason to prefer a trigger over a publication-time-only assertion.

**6.8 A path for the client to report a verification failure (§5.5 point 5).** Smallest form: a field on an existing call, not a new endpoint.

**6.9 One mobile package addition.** SHA-256 on the device needs `crypto` — **3.0.7, publisher dart.dev (verified), BSD-3-Clause, platforms "Android, iOS, Linux, macOS, web, Windows", pure Dart with `typed_data` as its only dependency, no native/platform code** [DOC pub.dev/packages/crypto, read 2026-08-31]. No CocoaPods surface, therefore no interaction with Uqudo's pinned `OpenSSL-Universal 3.3.3001` (R-003/R-025). CLAUDE.md's rule that iOS support is verified at the moment a package is added still applies and this paragraph is the evidence, restated at add time. See §7 for why this is flagged rather than assumed.

**Not required:** any change to `ref.reference_item`'s column set. Requirement (d)'s field list is already exactly what the table holds [OBSERVED `V0011:53-71` + `V0038`].

---

## 7. Settled-decision conflicts

**AD-005 — no conflict.** The design uses precisely the two local databases AD-005 names (session, cleared; reference, kept) and adds a staging area inside the reference one. The never-delete-a-version rule is what makes version-addressed immutable URLs safe, and the `sort_ordinal`/"device never collates Arabic" rule is preserved and, under §4.3, protected by the hash for the first time.

**AD-006 — one thing to declare, not a conflict.** AD-006 settled a need-by-need package table for the client [OBSERVED `docs/components/mobile-packages.md`]. `crypto` was not on it, because hashing was not one of the needs AD-006 evaluated. Adding it is therefore an **addition to an area AD-006 covered, not a reversal of anything AD-006 decided** — no rejected package is being re-admitted, no approved package is being dropped. I am flagging it explicitly rather than letting it appear silently in a later diff. If the project's reading is that AD-006 closed the client package list entirely, then this needs a one-line amendment to AD-006's decision row before any code is written, and I would rather that be a deliberate line than a surprise.

**AD-002b, AD-002d, AD-002e, AD-004 — untouched.** Hosting and auth are named where they intersect (6.6, §5.6) and explicitly not decided.

**Nothing else in the Decisions log is contradicted.** In particular, AD-001, AD-002a, AD-002c and the S3-01 package layout are unaffected.

**One shipped behaviour is contradicted, deliberately and with its own prior notice:** `DataEntryService`'s always-validate-against-current-version rule. S3-11's own execution-plan note records that this was left to AD-002f rather than designed around [OBSERVED `EXECUTION_PLAN.md` S3-11]. This is a code change, not a settled-decision reversal.

---

## 8. What I could not determine

- **The size of the serialised catalogue.** Every size figure in this report is arithmetic over [OBSERVED] item counts, not measurement. It matters to §5.1's delta-versus-whole-list argument only if it is wrong by an order of magnitude, which the item counts make implausible — but it is not measured.
- **Whether `package:http` implements a response cache.** I verified only `dart:io HttpClient` [DOC]. Marked [UNVERIFIED] in §5.6.
- **The root code of OQ-012's corrected `admin_division` dataset.** This is the input that decides whether §3.3's FK can be satisfied at all. **It is one question to the data supplier and it should be asked before any of §6.4 is built.**
- **Whether jsonb text output ordering is a stable contract across PostgreSQL major versions.** I found no documented guarantee. This is why §4.2 Option A was rejected rather than merely disfavoured, but the absence of a guarantee is not the same as a documented instability, and I did not find the latter.
- **How many items the corrected `admin_division` dataset will contain.** R-014 says three states are missing; their localities are unknown. Affects nothing in this design, but it is the one list whose size could move.
- **Whether the bank has any requirement about how quickly a reference-list correction must reach devices in the field.** No such requirement appears in any journey or plan document. The activation-at-session-boundary rule in §5.3 means a device with an in-flight profile can run on a superseded list for up to the abandonment threshold. If the bank needs faster propagation than that, §5.3 needs revisiting — and that is a product question, not a technical one.

---

## 9. Risks if this recommendation is wrong

| If wrong | What breaks | Cost to reverse |
|---|---|---|
| Whole-list instead of delta | Bandwidth on a metered Sudanese mobile connection, at republication only | Low. Adding a delta endpoint later is additive; the whole-list endpoint stays as the fallback and as the integrity anchor. |
| Hash-of-served-bytes | The server must serve byte-identical documents forever for a given version | Low-to-medium **now** (nothing references the hashes), **high after the first real submission**, because a submitted profile's recorded version becomes unverifiable if its hash definition changes. This is the single strongest reason to settle it before the app is built. |
| Storing documents rather than regenerating | A little duplicated data; a publication step that must not be skipped | Low. Drop the table, generate in Java, add a golden-bytes test. |
| Declared root + FK + trigger | If OQ-012's dataset has no ISO-mappable root, the FK cannot be satisfied and a mapping row is needed instead | Medium. It is a migration and a lookup change, but it is discovered at publication time with a loud failure — which is the entire point, versus R-045's silent empty picker. |
| Session version pinning | A customer can complete a journey on a list version superseded hours earlier | Low to reverse, **high to add later**: it is a wire-contract field on every data-entry call plus a validation change. Adding it after the app ships means a coordinated client/server release. |
| Gate at stage 2→3 rather than a bundled snapshot | A customer whose connection dies exactly between OTP success and stage 3 is blocked at a screen | Low. Shipping a bundled seed later is a build-time asset plus one version record; no contract change. |

---

## 10. Card updates

No `docs/components/reference-data.md` exists [OBSERVED `docs/components/`]. Draft below.

### 10.1 Corrections to `docs/components/persistence.md`

Replace the final open item (lines 360–364) with:

```markdown
- [x] Nothing enforced that `admin_division`'s root `item_code` equals the `country` list's
      alpha-2 code for Sudan (R-045). **Settled by AD-002f (2026-08-31):** the root becomes
      declared data — `ref.reference_list_version.root_item_code` + `root_country_version` +
      `root_country_list GENERATED ALWAYS AS ('country') STORED`, with a composite FK onto
      `ref.reference_item (list_code, version, item_code)` (the V0006 `occupation_list`
      pattern) — plus one DEFERRABLE INITIALLY DEFERRED constraint trigger asserting the
      list's single parentless row carries that exact `item_code`. A plain CHECK is not an
      option: PostgreSQL does not support CHECK constraints referencing other rows
      [DOC postgresql.org/docs/18/ddl-constraints.html]. A trigger is restore-safe here
      because pg_dump emits triggers in the post-data section
      [DOC postgresql.org/docs/18/app-pgdump.html]. NOT YET BUILT.
- [ ] `'SD'` is asserted in FOUR independent places, not two: `admin_division`'s root row
      (V0016:35), the `country` list's Sudan row (V0022), `app.profile_customer_data`'s
      `sudan_uses_codes` CHECK (V0006:42-43) and `DataEntryService.SUDAN_CODE`
      (DataEntryService.java:84). AD-002f removes the last one (lookup via the manifest's
      `rootItemCode`) and leaves V0006's CHECK as a now-meaningless literal — a comment, not
      a migration, since rewriting it would need the cross-row reference CHECK forbids.
      [OBSERVED 2026-08-31]
```

Add, after the `search_en` item in the same list:

```markdown
- [ ] `content_hash` covers FOUR of the nine served columns. `extra` is documented as
      deliberately excluded (V0022:29-32), and `sort_ordinal`, `is_active`, `search_ar` and
      `search_en` are excluded too — none appears in any seed migration's `canon` CTE.
      `sort_ordinal` is the sharpest case: the device cannot recompute or detect a wrong
      value, because it cannot collate Arabic. AD-002f (2026-08-31) redefines `content_hash`
      as SHA-256 over the exact served document bytes, making the envelope the whole
      document. NOT YET BUILT. [OBSERVED 2026-08-31]
- [ ] `ref.profile_reference_version` is written by no application code. The standing
      constraint "the list version used for a submission is recorded on the profile" is
      therefore NOT currently met. `fru_app` already holds SELECT, INSERT (V0012:20) — only
      a writer in `SubmissionService`'s transaction is missing. [OBSERVED 2026-08-31]
```

### 10.2 New card — `docs/components/reference-data.md`

```markdown
# Component: Reference data delivery

Status: designed (AD-002f), not built · Last verified: 2026-08-31
Source: docs/sessions/2026-08-31-research-ad-002f-reference-data.md

Seven lists, 583 items, all at version 1: occupation 135, branch 25, admin_division 151,
income_source 9, education_level 7, rejection_reason 7, country 249. [OBSERVED V0014-V0019,
V0022]

## The one integrity primitive
`content_hash` = SHA-256 over the exact UTF-8 bytes of the document served at
`GET /api/v1/reference/lists/{listCode}/{version}`, before any transport content-coding.
Envelope = the whole document. The client hashes what it received; **no client-side
canonical JSON is needed anywhere**. Documents are stored (`ref.reference_list_document`),
not regenerated, so the served bytes exist before serving and cannot drift with a serialiser
upgrade — the same reasoning that produced `audit.domain.CanonicalJson`. [AD-002f]

Supersedes the SQL `string_agg` canonicalisation in the seed migrations, which V0014's own
header marked as "NOT a settled wire format" and pre-authorised AD-002f to replace.
[OBSERVED V0014:34-43]

## Endpoints
| Endpoint | Mutability | Caching |
|---|---|---|
| `GET /api/v1/reference/manifest` | mutable | `no-cache` + ETag (`catalogHash`). Not `no-store` — that forbids the 304. |
| `GET /api/v1/reference/lists/{listCode}/{version}` | **immutable** | `public, max-age=31536000, immutable` + strong ETag = `contentHash` |

`public` is safe on these two and **on nothing else in this system** — every other endpoint
carries PII and is `no-store`.

Do NOT use `ShallowEtagHeaderFilter`: it MD5-hashes the fully rendered body and "saves
network bandwidth but not CPU" [DOC Spring Framework 7.0.9, web/webmvc/filters]. The stored
`contentHash` is available before rendering; set the ETag explicitly and 304 without reading
the document.

There is no HTTP response cache in `dart:io HttpClient` — it caches connections and cookies
only [DOC api.flutter.dev/flutter/dart-io/HttpClient-class.html]. Conditional requests are
explicit client code. HTTP caching is a bandwidth optimisation on top of the drift cache,
never a substitute for it.

## Client rules
1. **Manifest, then whole lists.** Fetch only lists whose `(version, contentHash)` differ.
   List granularity, never item-level deltas — a merged list is one no server published and
   no hash can verify.
2. **Stage, verify, swap in one drift transaction.** A partial download simply never
   verifies. No `Range`/206 resumption.
3. **The catalogue gate is the stage 2 → stage 3 boundary.** The customer is necessarily
   online through 1a and 2; stages 3-6 are the offline ones. No list snapshot ships in the
   app binary.
4. **Versions are pinned per session at entry to stage 3.** A newly verified version stages
   and activates at the next session boundary. Back-navigation across 3-6 is free, so a
   mid-journey swap could invalidate an already-chosen code.
5. **Hash mismatch: discard, keep the last verified version, bounded backoff, poison the
   version after two failures, report it, block stage 3 if there is no prior cache.** Never
   log and continue; never adopt unverified data — the version is recorded on the profile,
   so adopting unverified data writes an unverifiable version onto a bank record.
6. The device folds only the query string. `search_ar`/`search_en` arrive server-computed
   and are inside the hash envelope. `sort_ordinal` likewise — the device never collates
   Arabic.
7. `rootItemCode` comes from the manifest. **Never hardcode `'SD'` in Dart.**

## Manifest fields
`catalogHash`, `generatedAt`, and per list: `listCode`, `version`, `itemCount`,
`contentHash`, `isHierarchical`, `rootItemCode`, `rootCountryVersion`, `publishedAt`,
`documentPath`. Plus `verifiableChannels` — R-042's half, reserving the field; the journey
decision R-042 asks for is NOT made by AD-002f.

Per item: `itemCode`, `parentCode`, `labelAr`, `labelEn`, `searchAr`, `searchEn`,
`sortOrdinal`, `isActive`, `extra` — exactly `ref.reference_item`'s shape, so no schema
change is needed to carry them. [OBSERVED V0011 + V0038]

## Back office
Same endpoints, no local persistence. Fetch at app load, hold in memory, rely on ETag
revalidation. Must fetch **historical** versions (a profile submitted under occupation v1
renders v1's label) — the URLs are version-addressed, so this works with no extra contract.
Renders rejection-reason customer-facing messages from `extra`, never hardcoded
(AD-002f (g)); those messages are now inside the hash envelope. Still does no client-side
sorting or filtering (AD-006).

## Backend consequences — none of these are built
- Version pinning on the data-entry/submission contract; `DataEntryService` stops using
  `currentVersion` at its four call sites.
- `ref.profile_reference_version` written at submission (nothing writes it today).
- `ref.reference_list_document`; one Java class owning hashing; an integration test asserting
  stored `content_hash` == recomputed.
- `root_item_code`/`root_country_version` + composite FK + deferred constraint trigger
  (R-045).
- A defined publication step (today: a hand-written Flyway migration, no publisher code).
- A client path to report a verification failure.
- `crypto` 3.0.7 on mobile — dart.dev, BSD-3, pure Dart, no native code, iOS listed
  [DOC pub.dev, 2026-08-31]. Not in AD-006's table; an addition, not a reversal.

## Open
- [ ] OQ-012's corrected dataset root code — decides whether the R-045 FK is satisfiable.
      **Ask before building the migration.**
- [ ] Serialised catalogue size — never measured; all size figures are arithmetic.
- [ ] Does `package:http` cache responses? Only `dart:io HttpClient` was verified.
- [ ] Is jsonb text-output key ordering a stable cross-version contract? No documented
      guarantee found. Moot under the hash-the-bytes design; recorded in case it is revisited.
```

---

## 11. Noticed in passing

Not expanded, not acted on:

- **`docs/components/persistence.md` line 3 says "Status: implemented … Last verified: 2026-08-30" while line 6 says "Decision (proposed, AD-005 — NOT YET SETTLED)".** AD-005 is in PROJECT_PLAN.md's Decisions log and is settled. The card's own decision header is stale.
- **`ref.reference_list`'s `list_code` comment enumerates six lists and omits `country`** [OBSERVED `V0011:11-12`], added later by V0022. Cosmetic.
- **`V0006`'s `occupation_fk` deferred-FK comment is still present** as an instruction to a future migration that has since been executed at V0013 [OBSERVED `V0006:36-41`, `V0013` filename]. Cosmetic.
- **BL-005** (rejection-reason Arabic text translated for the seed, not sourced from the bank) becomes marginally more urgent under this design, because those strings move inside the integrity envelope and correcting them then requires a republication rather than an invisible `extra` edit. That is the correct behaviour, not a regression — but it changes the cost profile of the BL-005 fix, and whoever schedules it should know.

---

## Files read

Absolute paths, for the calling session:

- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\PROJECT_PLAN.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\EXECUTION_PLAN.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\BACKLOG.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\RISKS.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\docs\components\persistence.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\docs\components\mobile-packages.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\docs\components\backoffice-components.md`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\docs\journeys\customer.md` (stages 3, 4, 5, 6, the "Reference data and field decisions — SETTLED" section, and stage 13 only)
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\src\main\resources\db\migration\V0011__ref_registry_and_fold.sql`
- `…\V0012__ref_grants.sql`, `…\V0014__seed_occupation.sql`, `…\V0016__seed_admin_division.sql`, `…\V0017__seed_income_source.sql`, `…\V0019__seed_rejection_reason.sql`, `…\V0022__seed_country.sql`, `…\V0006__app_customer_data.sql`, `…\V0038__ref_search_en_generated.sql`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\src\main\java\sd\gov\bank\fruserupdate\reference\domain\ReferenceCatalog.java`
- `…\reference\jdbc\JdbcReferenceCatalog.java`
- `…\dataentry\service\DataEntryService.java`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\pom.xml`
- `c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\mobile\pubspec.yaml`

## Sources

- [PostgreSQL 18 — Constraints (CHECK cannot reference other rows)](https://www.postgresql.org/docs/18/ddl-constraints.html)
- [PostgreSQL 18 — pg_dump (`--section` post-data contains triggers)](https://www.postgresql.org/docs/18/app-pgdump.html)
- [RFC 9110 — HTTP Semantics (ETag §8.8.3, If-None-Match §13.1.2, Range §14.2, 206 §15.3.7, 416 §15.5.17)](https://www.rfc-editor.org/rfc/rfc9110.html)
- [Spring Framework 7.0.9 reference — Filters / ShallowEtagHeaderFilter](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html)
- [Flutter API — dart:io HttpClient](https://api.flutter.dev/flutter/dart-io/HttpClient-class.html)
- [pub.dev — crypto 3.0.7](https://pub.dev/packages/crypto)

---

## Commit/push proof

```
$ git log --oneline -1
e0db36c docs: research AD-002f — reference-data delivery, caching, versioning

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed: `51c27a7..e0db36c main -> main`
