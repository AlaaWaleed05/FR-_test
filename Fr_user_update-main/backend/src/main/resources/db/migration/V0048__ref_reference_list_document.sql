-- AD-002f (docs/sessions/2026-08-31-research-ad-002f-reference-data.md §4.3) — redefines
-- content_hash from the seven seed migrations' field-enumeration `canon` CTEs to SHA-256 over
-- the exact UTF-8 bytes of the document actually served for a (list_code, version). This table
-- is where those bytes live, so "the served bytes" is a stable, storable thing rather than
-- something regenerated per request (the alternative rests on a serialiser's output never
-- changing across a Jackson/JVM upgrade -- rejected on the same grounds that produced
-- audit.domain.CanonicalJson, per the report's own citation of that precedent).
--
-- Schema only. No data is populated here: generating byte-identical documents deterministically
-- is a Java concern (one class, reference.domain.ReferenceDocumentGenerator, hand-rolled rather
-- than a general-purpose JSON library -- see that class's Javadoc), not a SQL one. The report
-- explicitly rejected canonicalising in SQL (§4.2 Option A) partly because jsonb's own text-output
-- key ordering has no documented cross-version stability guarantee (§8) -- a concern that does not
-- apply to Java code that owns its own serialisation end to end.
--
-- Population -- including the one-off in-place rewrite of the seven existing v1 content_hash
-- values -- happens by running the publication step (reference.jdbc.
-- ReferenceDocumentPublicationRunner, documented at db/post-migrate/02-publish-reference-
-- documents.md), not by this migration. That in-place rewrite is legitimate ONLY because
-- ref.profile_reference_version has zero rows today (nothing references the hashes being
-- replaced) -- confirmed by the report's own repo-wide grep, S4-03 session report. Once a real
-- submission writes that table, changing what content_hash means again would have to be a
-- republication (a new version), not an in-place update, because a submitted profile's recorded
-- version would otherwise become unverifiable after the fact.
--
-- No fru_app grant added. The two read endpoints that would serve this table to a client are
-- explicitly OUT OF SCOPE this session (AD-002f §6.1/§6.2/§6.6) -- adding an unused grant ahead
-- of its consumer is exactly the kind of speculative surface CLAUDE.md's "don't scaffold for
-- features that don't exist yet" rule warns against. The next session adds it alongside the
-- endpoints.
--
-- Touches schema ref only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed
-- (same reasoning V0038 already documents for itself).

CREATE TABLE ref.reference_list_document (
  list_code     text NOT NULL,
  version       int  NOT NULL,
  document_json text NOT NULL,   -- the exact bytes served, decoded to text for readability/psql
  sha256        bytea NOT NULL,  -- sha256(convert_to(document_json, 'UTF8')) -- the integrity primitive
  published_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (list_code, version),
  FOREIGN KEY (list_code, version) REFERENCES ref.reference_list_version (list_code, version)
);
