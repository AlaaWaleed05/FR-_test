-- R-046 is CLOSED (2026-09-13, wayfinder ticket 01) and app.artifact_ref.storage_key is now DEAD
-- rather than reserved. V0053 narrowed the column's meaning to "reserved for R-046's still-open
-- addressing scheme"; that scheme turned out to need no stored key at all -- the back office's
-- image endpoint addresses an artifact by its own artifact_ref_id
-- (operator.web.ProfileImageController, BL-075). Nothing reads this column, and nothing will.
--
-- The column is deliberately NOT dropped here. Dropping it is its own migration with its own
-- review, and rushing it into a session that is building an endpoint buys nothing. Recording it as
-- dead is the point: without this, the next reader infers a meaning from its presence, which is
-- exactly the mistake V0053's own comment set up.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT TO V0053.
-- V0053 has been applied to every environment, and Flyway's validateOnMigrate defaults to true
-- (nothing in application*.properties, pom.xml or a configuration customizer turns it off). Editing
-- its bytes changes its checksum and breaks migration on every already-migrated database. The trap
-- is that no gate would catch it: Testcontainers starts from an empty database and re-applies the
-- edited file, so its checksum always matches in test and only the deployed database refuses.
--
-- A consequence worth stating plainly, because BL-075's own row lists three V0053 lines as though
-- all three were correctable: only ONE was. V0053:33 is the live COMMENT ON COLUMN this migration
-- replaces. V0053:20 (the derivatives note, which assigns the derivative writer to "whoever builds
-- the backoffice image-viewing endpoint (R-046, still open)") and V0053:28 (the storage_key
-- preamble) are plain SQL `--` file comments inside an applied migration. They are historical text
-- and are SUPERSEDED BY THIS FILE, not edited. Read them as what was true in September 2026.
--
-- On V0053:20's substance, since it named a successor and this is that successor: derivative rows
-- (app.artifact_ref.derived_from_artifact_ref_id) are still NOT populated by any code. BL-075 serves
-- originals. Whether the operator's contact sheet ever needs downscaled derivatives is an open
-- question, not an owed deliverable.
COMMENT ON COLUMN app.artifact_ref.storage_key IS
  'DEAD -- no reader, and no future one planned. History: AD-004 owned its meaning while storage '
  'technology was undecided; V0053 narrowed it to "reserved for R-046''s addressing scheme" when '
  'AD-004 closed (bytes live in this row''s own body column); R-046 then closed at BL-075 with a '
  'scheme that needs no stored key, since artifact_ref_id addresses the artifact by itself. Still '
  'NOT NULL, so writers must supply a value; retained rather than dropped because dropping a '
  'column is its own migration and its own review.';

-- No DDL, no grant, no data change: a column comment is metadata only, so this migration is safe
-- to apply to a running system and nothing downstream depends on it having run.
