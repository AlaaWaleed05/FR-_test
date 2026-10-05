-- ref schema seed -- branch list, per docs/reference/branches.md. Definitive per the
-- product owner: 25 branches, numbered 2 to 26 -- there is no branch 1. No English label
-- is supplied by the source; label_en is left NULL.
--
-- One combined statement -- see V0014's header comment for why (CTEs don't persist across
-- separate statements; chained here via a data-modifying CTE instead).
--
-- content_hash canonicalisation matches V0014's exactly (item_code|parent_code|label_ar|
-- label_en, empty string for absent parent_code/label_en, ordered by item_code
-- COLLATE "C") -- see that file's header comment for why.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('branch', 'الفرع', 'Branch', false);

WITH items(item_code, label_ar) AS (
  VALUES
    ('2','بورتسودان'),
    ('3','القضارف'),
    ('4','الابيض'),
    ('5','مدني'),
    ('6','ام درمان'),
    ('7','الدمازين'),
    ('8','المناقل'),
    ('9','سنار'),
    ('10','نيالا'),
    ('11','حلفا الجديده'),
    ('12','السجانة'),
    ('13','ربك'),
    ('14','الحصاحيصا'),
    ('15','سوق ليبيا'),
    ('16','الخرطوم'),
    ('17','الخرطوم ٢'),
    ('18','الخرطوم بحري'),
    ('19','الجمهوريه'),
    ('20','السوق المحلي'),
    ('21','قاردن ستي'),
    ('22','الرياض'),
    ('23','عطبرة'),
    ('24','المعمورة'),
    ('25','الجنيد'),
    ('26','الكدرو')
),
ordered AS (
  SELECT item_code, label_ar,
         row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu") AS sort_ordinal
  FROM items
),
canon AS (
  SELECT string_agg(item_code || '|' || '' || '|' || label_ar || '|' || '',
                     E'\n' ORDER BY item_code COLLATE "C") AS c,
         count(*) AS n
  FROM items
),
ver AS (
  INSERT INTO ref.reference_list_version
    (list_code, version, content_hash, item_count, source_note, is_current)
  SELECT 'branch', 1, sha256(convert_to(c, 'UTF8')), n,
         'docs/reference/branches.md, supplied by the product owner 2026-08-19', true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item (list_code, version, item_code, label_ar, sort_ordinal, is_active)
SELECT ver.list_code, ver.version, ordered.item_code, ordered.label_ar, ordered.sort_ordinal, true
FROM ordered, ver;
