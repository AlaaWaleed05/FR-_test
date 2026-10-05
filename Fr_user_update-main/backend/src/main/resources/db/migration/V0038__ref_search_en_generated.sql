-- Fixes a live defect found by the 2026-08-30 client-component research (S3-10,
-- docs/sessions/2026-08-30-research-client-components.md §8.2): search_en was declared
-- (V0011) as a plain column, unlike search_ar which is GENERATED ALWAYS AS
-- (ref.ar_fold(label_ar)) STORED, and no seed migration (V0014-V0019, V0022) ever wrote to
-- it. docs/journeys/customer.md's "Reference data and field decisions -- SETTLED" requires
-- substring matching across Arabic AND English simultaneously for the occupation picker;
-- with search_en NULL on every row, the English half has nothing to match against.
--
-- Made GENERATED like search_ar so it cannot drift from label_en independently. PostgreSQL
-- cannot ALTER an existing plain column into a generated one (no ALTER COLUMN ... ADD
-- GENERATED), so the column is dropped and re-added -- safe here because search_en has held
-- only NULL since V0011 (confirmed: grep for search_en across every migration file returns
-- exactly one hit, the V0011 declaration itself), so no real data is lost. ar_fold() is
-- STRICT (V0011), so ar_fold(NULL) = NULL -- label_en is nullable (V0011); some list items
-- may lack an English label, and the generated column inherits that same nullability with
-- no NOT NULL to violate.
--
-- No reference_list_version bump or republication: every seed migration's content_hash
-- canonicalises item_code|parent_code|label_ar|label_en (confirmed by reading V0014's own
-- canon CTE) -- never search_ar/search_en -- so this is a pure schema fix, not a change to
-- published reference-list content. This migration touches schema ref only, not audit, so
-- R-035's SET LOCAL fru.migration_in_progress flag does not apply (that flag guards DDL in
-- schema audit specifically -- see V0003's comment and docs/components/persistence.md).

ALTER TABLE ref.reference_item DROP COLUMN search_en;
ALTER TABLE ref.reference_item
  ADD COLUMN search_en text GENERATED ALWAYS AS (ref.ar_fold(label_en)) STORED;

-- Gives search_en the same substring-search support search_ar already has (V0011) --
-- needed for stage 4's English-side matching over the occupation and country lists.
CREATE INDEX reference_item_search_en_trgm
  ON ref.reference_item USING gin (search_en gin_trgm_ops);
