-- AD-002f (docs/sessions/2026-08-31-research-ad-002f-reference-data.md §3.3) — R-045: the
-- address cascade depends on admin_division's hierarchy root equalling the country list's Sudan
-- alpha-2 code, with nothing in the schema enforcing it. A plain CHECK cannot express this --
-- PostgreSQL does not support CHECK constraints that reference other rows or other tables
-- [DOC postgresql.org/docs/18/ddl-constraints.html] -- so the root becomes DECLARED DATA (a
-- composite FK, C1) plus a DEFERRABLE constraint trigger asserting the declared root actually
-- matches the list's one parentless row (C2). A trigger is safe here despite that CHECK warning:
-- pg_dump emits triggers in the post-data section, after COPY, so a restore cannot fail on
-- partial data the way a CHECK enforced during data load could
-- [DOC postgresql.org/docs/18/app-pgdump.html].
--
-- C3 (validate every current version, live, in this same migration) is done in the DO block at
-- the end. C4 (stop hardcoding 'SD') is NOT this migration's job for the Java constant --
-- DataEntryService.SUDAN_CODE is replaced in the same S4-03 diff by a
-- ReferenceCatalog.currentRootItemCode(listCode) lookup against the columns this migration adds.
-- app.profile_customer_data's sudan_uses_codes CHECK is commented, not rewritten, in the
-- following migration (V0050) -- rewriting it would need the cross-row reference this
-- migration's own opening paragraph says PostgreSQL forbids.
--
-- Touches schema ref only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed
-- (same reasoning V0038 already documents for itself).

-- Step 1 (of 6): the assertion itself, callable directly (used by the trigger wrapper below, by
-- this migration's own C3 validation, and by an integration test recomputing it against the live
-- schema -- "one implementation, three call sites", per the report).
CREATE FUNCTION ref.assert_list_root(p_list_code text, p_version int) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  v_is_hierarchical boolean;
  v_root_item_code  text;
  v_parentless_count int;
  v_parentless_code  text;
  v_bad_parent_count int;
BEGIN
  SELECT l.is_hierarchical, v.root_item_code
    INTO v_is_hierarchical, v_root_item_code
    FROM ref.reference_list_version v
    JOIN ref.reference_list l ON l.list_code = v.list_code
   WHERE v.list_code = p_list_code AND v.version = p_version;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'ref.assert_list_root: no such list version %/%', p_list_code, p_version;
  END IF;

  IF v_is_hierarchical THEN
    SELECT count(*), min(item_code)
      INTO v_parentless_count, v_parentless_code
      FROM ref.reference_item
     WHERE list_code = p_list_code AND version = p_version AND parent_code IS NULL;

    IF v_parentless_count <> 1 THEN
      RAISE EXCEPTION
        'ref.assert_list_root: hierarchical list %/% must have exactly one parentless row, found %',
        p_list_code, p_version, v_parentless_count;
    END IF;
    IF v_parentless_code IS DISTINCT FROM v_root_item_code THEN
      RAISE EXCEPTION
        'ref.assert_list_root: hierarchical list %/% parentless row % does not match declared root_item_code %',
        p_list_code, p_version, v_parentless_code, v_root_item_code;
    END IF;
  ELSE
    IF v_root_item_code IS NOT NULL THEN
      RAISE EXCEPTION
        'ref.assert_list_root: flat list %/% must have root_item_code NULL, found %',
        p_list_code, p_version, v_root_item_code;
    END IF;
    SELECT count(*) INTO v_bad_parent_count
      FROM ref.reference_item
     WHERE list_code = p_list_code AND version = p_version AND parent_code IS NOT NULL;
    IF v_bad_parent_count <> 0 THEN
      RAISE EXCEPTION
        'ref.assert_list_root: flat list %/% has % row(s) with a non-null parent_code',
        p_list_code, p_version, v_bad_parent_count;
    END IF;
  END IF;
END;
$$;

-- Step 2: declare the root as data. root_country_list is the same generated-constant pattern
-- app.profile_customer_data.occupation_list already uses (V0006, closed at V0013) to hang a
-- composite FK onto a versioned reference item -- here always 'country', because the cascade
-- this project has is rooted in a country code, not a general "any list" root.
ALTER TABLE ref.reference_list_version
  ADD COLUMN root_item_code text,
  ADD COLUMN root_country_version int,
  ADD COLUMN root_country_list text GENERATED ALWAYS AS ('country') STORED;

ALTER TABLE ref.reference_list_version
  ADD CONSTRAINT root_country_fk FOREIGN KEY (root_country_list, root_country_version, root_item_code)
    REFERENCES ref.reference_item (list_code, version, item_code);

-- MATCH SIMPLE (PostgreSQL's only option for a composite FK here) lets a row with root_item_code
-- set but root_country_version NULL satisfy the FK trivially -- any NULL column skips the check.
-- V0013 found and closed the identical gap for occupation_fk/reason_code_fk; closed the same way
-- here rather than rediscovering it under review.
ALTER TABLE ref.reference_list_version
  ADD CONSTRAINT root_code_version_paired
    CHECK ((root_item_code IS NULL) = (root_country_version IS NULL));

-- Step 3: set the one hierarchical list's declared root. admin_division's root row is 'SD'
-- (V0016); the country list's Sudan row is alpha-2 'SD' at version 1 (V0022) -- the FK above
-- accepts this because that row already exists (committed at V0022, long before this migration).
UPDATE ref.reference_list_version
   SET root_item_code = 'SD', root_country_version = 1
 WHERE list_code = 'admin_division' AND version = 1;

-- Step 4: the constraint trigger's wrapper function -- CREATE CONSTRAINT TRIGGER requires a
-- RETURNS trigger function; the actual assertion logic lives in ref.assert_list_root (step 1) so
-- it stays independently callable.
CREATE FUNCTION ref.trg_assert_list_root() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM ref.assert_list_root(OLD.list_code, OLD.version);
  ELSE
    PERFORM ref.assert_list_root(NEW.list_code, NEW.version);
  END IF;
  RETURN NULL; -- ignored for an AFTER trigger; a value is still required
END;
$$;

-- Step 5: DEFERRABLE INITIALLY DEFERRED, not an immediate row-level check -- the seed migrations
-- insert an entire list's rows via one data-modifying CTE statement (V0014's header explains
-- why), so an immediate trigger would fire mid-list against an incomplete hierarchy. Deferred to
-- commit, it instead re-validates the whole (list_code, version) once every row exists, once per
-- row touched in the transaction -- redundant but cheap at this data's scale (at most 249 rows).
CREATE CONSTRAINT TRIGGER reference_item_root_check
  AFTER INSERT OR UPDATE OR DELETE ON ref.reference_item
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ref.trg_assert_list_root();

-- Step 5b: the item-side trigger alone leaves a gap -- it fires when ref.reference_item changes,
-- but says nothing about a later UPDATE that only touches root_item_code/root_country_version on
-- ref.reference_list_version itself (e.g. a correction migration fixing a typo'd root), which the
-- composite FK alone cannot catch either: the FK only proves the new value is SOME published
-- country code, not that it still matches this list's own parentless row. Same function, same
-- deferred-to-commit reasoning, the other direction.
CREATE CONSTRAINT TRIGGER reference_list_version_root_check
  AFTER UPDATE OF root_item_code, root_country_version ON ref.reference_list_version
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ref.trg_assert_list_root();

-- Step 6 (C3): validate every list version marked current today, live, in this migration --
-- catches a bad existing state before the trigger (which only guards future writes) would ever
-- see one. Trivially passes for the six flat lists (no parentless-row requirement beyond "no row
-- has a parent") and for admin_division against the root just declared in step 3.
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT list_code, version FROM ref.reference_list_version WHERE is_current LOOP
    PERFORM ref.assert_list_root(r.list_code, r.version);
  END LOOP;
END;
$$;

-- Known residual gap, found under review and deliberately not closed by a third trigger:
-- ref.assert_list_root also reads ref.reference_list.is_hierarchical, and nothing guards an
-- UPDATE to THAT column the way the two triggers above guard reference_item/reference_list_version
-- -- e.g. `UPDATE ref.reference_list SET is_hierarchical = false WHERE list_code = 'admin_division'`
-- commits silently today. Not built: no code path in this codebase, past or present, ever updates
-- is_hierarchical after a list's INSERT-time seed (grep the whole db/migration/ tree), unlike
-- root_item_code (a real, expected correction path once OQ-012's dataset lands) and reference_item
-- rows (written on every publish). ref.trg_assert_list_root() also cannot be reused as-is for a
-- third trigger on ref.reference_list: that table has no `version` column, so NEW.version would
-- fail at runtime -- a real trigger here needs its own wrapper, not a one-line addition. Recorded
-- in docs/components/reference-data.md's Open section rather than built speculatively against a
-- write path nothing exercises.
