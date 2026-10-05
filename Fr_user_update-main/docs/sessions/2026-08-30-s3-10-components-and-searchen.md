# S3-10 — Close AD-006, fix search_en, record §8 implications, trim CLAUDE.md

Two outcomes, one session. Full plan approved before implementation; `@agent-reviewer` ran
against the complete diff afterward — findings and dispositions are in §3 below.

## Outcome 1 — Close AD-006 (client component decision)

**Decisions log**: added an `AD-006` row to PROJECT_PLAN.md — mobile assembles `signature`,
`phone_numbers_parser` (validation core only), `image_picker`, `file_picker`, `image`;
rejects `pinput`, `country_picker`, `dropdown_search`, `flutter_form_builder`,
`flutter_image_compress`, each for the named reason. Back office maps onto Ant Design
6.6.1 with all `Table` filtering/sorting server-side; export is Apache POI SXSSF in
`backend/`, never the browser. Reasoning copied from the research report's §2/§6/§7, not
re-derived. Removed the corresponding paragraph from PROJECT_PLAN's "Open architecture
decisions" — decided, not open.

**New component cards**, verbatim from the research report's §11.1/§11.2 drafts (byte-for-
byte, confirmed by `@agent-reviewer`'s `diff`):
- `docs/components/mobile-packages.md`
- `docs/components/backoffice-components.md`

**`docs/components/persistence.md`** (§11.3): added the three-implementations-of-the-fold
note and the decomposed-hamza gap under "Arabic — the rule" (worded as *already fixed* for
`search_en`, not still-open); added the `admin_division`/`country` root-equality item under
"Open verification items", cross-referenced to the new R-045; marked the `search_en` open
item `[x]` with the V0038 fix.

**AD-002f** (§11.4): appended five additive items (d)–(h) — manifest column shape,
`search_en` now done, the address-cascade invariant, the REJ-message/`content_hash` tie-in,
and the E.164 phone format — none of them changes AD-002f's own still-OPEN status.

## Outcome 2 — Fix `search_en`, record §8, trim CLAUDE.md

### The defect and the fix

`ref.reference_item.search_en` (V0011) was a plain column no seed migration ever
populated — confirmed by `grep -rn search_en backend/src/main/resources/db/migration/`
returning exactly one hit, the declaration itself. Stage 4 of `docs/journeys/customer.md`
("Reference data and field decisions — SETTLED") requires substring matching across Arabic
**and English** simultaneously; with `search_en` NULL on every row, the English half had
nothing to match.

**Migration `V0038__ref_search_en_generated.sql`**: drops and re-adds `search_en` as
`GENERATED ALWAYS AS (ref.ar_fold(label_en)) STORED`, matching `search_ar`, plus a
`gin_trgm_ops` trigram index. Drop-then-add is required because PostgreSQL has no `ALTER
COLUMN ... ADD GENERATED` on an existing plain column; safe here because the column has
held only NULL since V0011. No `reference_list_version` bump: every seed migration's
`content_hash` canonicalises `item_code|parent_code|label_ar|label_en`, never
`search_ar`/`search_en` (confirmed against V0014's own `canon` CTE).

**Live proof**, against the local `docker-compose.yml` Postgres via the `flyway-maven-plugin`
(fresh volume, `flyway.target=37`, then unbounded `flyway:migrate`):

```
-- BEFORE (at V0037)
 is_generated
--------------
 NEVER
(1 row)

 item_code | search_en
-----------+-----------
(0 rows)

-- AFTER (V0038 applied)
 is_generated |  generation_expression
--------------+-------------------------
 ALWAYS       | ref.ar_fold(label_en)
(1 row)

 item_code | label_ar | label_en | search_en
-----------+----------+----------+-----------
 25        | خياط     | Needle   | needle
(1 row)
```

**Flyway from scratch**: fresh volume, `flyway:migrate` applied all 38 migrations in one run
("Successfully applied 38 migrations ... now at version v0038"). **Idempotent re-run**:
"Successfully validated 38 migrations ... Schema \"public\" is up to date. No migration
necessary." Run twice (once mid-sequence at V0037→38, once from a second from-scratch pass)
with the same result both times.

**Regression-guard test** added to `AppSchemaConnectivityIntegrationTest`: asserts
`is_generated = 'ALWAYS'` and the expression contains `ar_fold`, asserts the trigram index
exists (`pg_indexes`, added after `@agent-reviewer` noted the first version only guarded the
column), and asserts `search_en ILIKE '%needle%'` resolves to item_code `25`. This is a
regression guard, not the proof — CLAUDE.md's own live-proof-vs-test rule applies here since
a generated-column guarantee is exactly the case it names.

### §8 backend implications recorded

- **E.164 phone format** — new Architecture bullet in PROJECT_PLAN.md: decision only, not
  implemented. `@agent-reviewer` found two defects in the first version of this note: it
  cited `ProfileRepository.findExisting` as the comparison mechanism (it holds no phone
  field at all) when the real exposure is `ContactChannelsService` writing
  `previousPhoneNumber` into the `session_reentered` audit payload — fixed to cite the
  correct mechanism; and it deferred the validator to "whoever builds the phone-entry
  endpoint," but `/api/v1/contact-channels` (S3-06/S3-07/S3-08) already ships with an
  unvalidated `phoneNumber` field, so the deferral named nobody — fixed by filing **BL-016**
  as an explicit retrofit.
- **RISKS.md**: R-045 (address-cascade code-equality between `admin_division` and `country`,
  unenforced) and R-046 (identity-document image URLs need a signed-URL-vs-blob-fetch
  decision, owned by AD-004) — both 🔴 Live.
- **AD-004**: appended that it now also owns the signed-URL-vs-blob-fetch decision.
- **BACKLOG.md**: BL-013 (`canApprove` field), BL-014 (shared Arabic-fold golden-vector
  fixture, three implementations), BL-015 (profile-list endpoint's server-side filter/sort/
  search/`total` contract), BL-016 (above).

### CLAUDE.md: 199 → 199, three rules added, net zero growth

Every cut preserved the underlying rule; content removed was restatement, historical
narrative, or a now-stale claim:

| Removed | Reason |
|---|---|
| Architecture's mobile bullet restating "forwards raw JWS untouched" | Full rule with its FIB warning stays intact in Hard rules |
| Package-layout bullet's second statement of the same testability criterion ("no container, no database, no socket, no real clock") | Identical to the first statement two sentences earlier; permissive half (`@Service` allowed) kept |
| S3-01's specific branch-exception history in the Session-end rule | The incident is in the S3-01/S3-03 session reports; the generalised forward rule stays word-for-word |
| Commands' stale "not `ref` — `fru_app` is never granted USAGE... since it has no tables yet" | **Factually wrong**, not just verbose: V0012 grants USAGE and SELECT on `ref`, which has had tables since V0011 |
| Coverage's restatement of Architecture's own S1-08/business-logic-tier status | Replaced with a one-line cross-reference; the facts live in one place now |

`@agent-reviewer` found one of these went further than intended: the Commands intro's
"State the requirement first, resolve the local path second" was a real directive, not a
restatement, and nothing else in the file preserved it — restored (merged into the same
paragraph, no net line growth). Everything else it checked (7 of 8 edits) confirmed as
restatement, narrative, or a stale-claim correction, not a lost rule.

Added verbatim, in Hard rules: the "session reports carry proof, not narrative" rule
(target 150–250 lines — this report is held to it), "name sections, not whole documents",
and "phone numbers are E.164 everywhere." One tension the reviewer flagged and left as-is
(both texts are task-specified verbatim, not mine to reword): the new "name sections" rule
uses `docs/journeys/customer.md` as its example of a document too long to name whole, while
the unchanged session-start rule twelve lines earlier requires reading that same document in
full for customer-facing work. Both are correct in their own scope (a business-logic slice
needs two stages; a customer-facing session needs the whole journey) but a future session
should not assume one supersedes the other.

## 3. `@agent-reviewer` findings and dispositions

| Finding | Disposition |
|---|---|
| SHOULD FIX: E.164 rule added with no filed remediation for the endpoint that already violates it | Fixed — BL-016 filed, PROJECT_PLAN note reworded to name it |
| NOTE: E.164 note cited the wrong mechanism (`findExisting`, which holds no phone field) | Fixed — recited to `ContactChannelsService`'s `session_reentered` payload |
| NOTE: AD-002f item (h) said "Architecture note below"; it is above | Fixed |
| NOTE: Commands intro lost a real directive ("state requirement first, resolve path second") | Fixed — restored, no net line growth |
| NOTE: "Per-machine notes, observed/not authoritative" scoping caveat lost | Not fixed — reviewer itself called this cosmetic-adjacent; the bold `**Windows**`/`**Linux**` labels carry the operative meaning |
| NOTE: "Name sections" rule vs. session-start's "read customer.md in full" pull in different directions | Not fixed — both are task-verbatim; flagged above for a future session, not mine to reword |
| NOTE: new regression test guarded the generated column but not the trigram index | Fixed — added a `pg_indexes` assertion |
| Informational: EXECUTION_PLAN/session-report not yet filed at review time | Expected — review ran mid-task; both are complete as of this report |

No re-review requested: fixes were narrow (three text corrections, one filed backlog item,
one added assertion), and the full gate suite was re-run clean after them (below).

## 4. Gates

`./mvnw verify -Pdb-integration-test` (backend — the only tier touched):

```
Tests run: 293, Failures: 0, Errors: 0, Skipped: 0
...
Spotless.Java is keeping 111 files clean - 0 needs changes to be clean, 1 were already clean, 110 were skipped because caching determined they were already clean
...
All coverage checks have been met.
BUILD SUCCESS
```

293 vs. the prior 292 (one new regression test). `mobile/`/`backoffice/` gates not run —
nothing in either tier changed this session.

## 5. iOS support

No Flutter package was added, changed, or considered for addition this session — nothing to
verify per CLAUDE.md's per-package iOS rule.

## Commit/push proof

```
$ git log --oneline -1
34a6aae feat: S3-10 — close AD-006, fix search_en, record §8 implications, trim CLAUDE.md

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
