# db/post-migrate/02-publish-reference-documents

AD-002f (docs/sessions/2026-08-31-research-ad-002f-reference-data.md §6.7) — populates
`ref.reference_list_document` and (re)computes `ref.reference_list_version.content_hash` as the
SHA-256 of each current version's generated document bytes. Unlike
`01-audit-event-trigger.sql`, this step is Java, not SQL: deterministic document generation
needs one piece of code to own it end to end (`reference.domain.ReferenceDocumentGenerator`),
which is a JVM concern, not something the research report found a safe way to do in plain SQL
(see that class's Javadoc — jsonb's own text-output key ordering has no documented cross-version
stability guarantee).

**Not a Flyway migration and not an admin HTTP endpoint** — the same reasoning `db/post-migrate/`
already applies to Layer 3: an endpoint would drag in AD-002e (back-office authentication),
which this session does not settle. This step is invoked directly, by whoever publishes, from a
built jar.

## Running it

After `./mvnw package -DskipTests` (or any build that produces
`target/backend-0.0.1-SNAPSHOT.jar`):

```
DB_HOST=<host> DB_PORT=<port> DB_NAME=<db> DB_MIGRATOR_PASSWORD=<fru_migrator password> \
  java -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=publish-reference-documents
```

**This boots the full application context, not a stripped-down one** — `ReferenceDocumentPublicationRunner` is one bean among all the others (`CoreBankingClient`, `MessageSender`, `UqudoClient`, `CivilRegistryClient`), and every one of those has a no-default property that fails startup loudly if unset (the same "fail loudly, not quietly" design each of their own configuration classes documents). So a real invocation needs the **same environment the deployment already runs with** — `fru.core-banking.client`, `fru.messaging.{sms,whatsapp,email}.provider`, `fru.uqudo.client`, `fru.civil-registry.client` all still need a value. Proven live in the S4-03 session report by supplying `stub` for all of them against local `docker-compose` Postgres. A leaner, purpose-built context for this one command is a reasonable future refinement, not built here — this step already needed no new infrastructure, and reusing the same context the deployed app already configures is the smaller change.

This activates `application-publish-reference-documents.properties`, which overrides the
datasource to connect as `fru_migrator` (the only role with `INSERT`/`UPDATE` on
`ref.reference_list_document` and `ref.reference_list_version.content_hash` — the normal runtime
role, `fru_app`, holds neither) and disables the embedded web server
(`spring.main.web-application-type=none`), so no port is bound for what is a one-shot command.
`ReferenceDocumentPublicationRunner` (gated `@Profile("publish-reference-documents")`, inert in
every other profile including the deployed application's normal runtime) runs, logs which
`listCode/version` pairs it published, and exits the process.

## What it does, per current list version

1. `SELECT ref.assert_list_root(list_code, version)` — validates the cascade root (V0049) before
   writing anything. A loud failure here, not a silent bad publish.
2. Reads `ref.reference_list`/`ref.reference_list_version`/`ref.reference_item`, builds the
   document via `ReferenceDocumentGenerator`, computes its SHA-256.
3. Upserts `ref.reference_list_document (list_code, version, document_json, sha256)`.
4. Updates `ref.reference_list_version.content_hash` to that same SHA-256.

## Idempotent — safe to run twice

`ref.reference_item` rows for a published version are never rewritten (AD-005's
never-delete-a-version rule), so reading the same `(list_code, version)` twice and regenerating
always produces byte-identical output. A second run overwrites `document_json`/`sha256`/
`content_hash` with the same values (only `published_at` moves) — a clean no-op change, proven
live in the S4-03 session report and by
`ReferenceDocumentPublisherIntegrationTest#publishingTwiceIsIdempotent`.

## What happens if this step is skipped

**Asymmetric with Layer 3's own `db/post-migrate/` weakness (R-028).** The cascade-root
constraint trigger (V0049) fires on every future write to `ref.reference_item` regardless of
whether this step ever runs — skipping this step does not weaken that guard. What skipping this
step *does* leave silently stale is `content_hash`/`ref.reference_list_document`: a republished
list (a future version) would sit `is_current` with no matching document row and a
`content_hash` nobody generated, and nothing today fails loudly about that absence — it is
flagged as an open item on `docs/components/persistence.md`, the same way Layer 3's own skip
risk is tracked, rather than hidden.

## One-off note on the seven v1 hashes

The first run after V0048/V0049 rewrites all seven existing v1 `content_hash` values in place —
legitimate only because `ref.profile_reference_version` has zero rows today (nothing references
the hash being replaced). After the first real submission writes that table, a `content_hash`
redefinition would need to be a republication (a new version), not an in-place rewrite — see
V0048's own header comment.
