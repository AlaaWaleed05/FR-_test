-- ref schema seed -- education level list, per docs/journeys/customer.md stage 3. Ordinal,
-- not Arabic-alphabetical: sort_ordinal is assigned directly as list position (1-7), per
-- customer.md's explicit instruction ("displays in this order, not alphabetically" --
-- unlike occupation). item_code = '1'..'7', matching the values already stored in V0006's
-- app.profile_customer_data.education_level smallint column (a bare CHECK(BETWEEN 1 AND
-- 7), no FK to this list -- the versioned list still exists for the app's offline picker
-- and its content_hash).
--
-- One combined statement -- see V0014's header comment for why.
--
-- content_hash canonicalisation matches V0014's exactly (item_code|parent_code|label_ar|
-- label_en, empty string for absent parent_code/label_en, ordered by item_code
-- COLLATE "C") -- see that file's header comment for why. This ordering is for the hash
-- only; sort_ordinal itself stays the explicit 1-7 list position above, per customer.md.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('education_level', 'مستوى التعليم', 'Education level', false);

WITH items(item_code, label_ar, label_en, sort_ordinal) AS (
  VALUES
    ('1', 'أمي', 'Illiterate', 1),
    ('2', 'يقرأ ويكتب', 'Reads and writes', 2),
    ('3', 'أساس', 'Basic education', 3),
    ('4', 'ثانوي', 'Secondary', 4),
    ('5', 'دبلوم / معهد فني', 'Diploma / technical institute', 5),
    ('6', 'جامعي', 'University degree', 6),
    ('7', 'دراسات عليا', 'Postgraduate', 7)
),
canon AS (
  SELECT string_agg(item_code || '|' || '' || '|' || label_ar || '|' || label_en, E'\n'
                     ORDER BY item_code COLLATE "C") AS c,
         count(*) AS n
  FROM items
),
ver AS (
  INSERT INTO ref.reference_list_version
    (list_code, version, content_hash, item_count, source_note, is_current)
  SELECT 'education_level', 1, sha256(convert_to(c, 'UTF8')), n,
         'docs/journeys/customer.md stage 3 -- fixed 7-value ordinal list', true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item
  (list_code, version, item_code, label_ar, label_en, sort_ordinal, is_active)
SELECT ver.list_code, ver.version, items.item_code, items.label_ar, items.label_en,
       items.sort_ordinal, true
FROM items, ver;
