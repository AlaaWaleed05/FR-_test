-- ref schema seed -- income source list, per docs/journeys/customer.md stage 4. No
-- bank-supplied codes exist for this list (unlike occupation); item_code values below are
-- assigned here. 'OTHER' is not a free choice -- it must match the literal already used in
-- V0006's app.profile_income_source CHECK (other_needs_text). 'RATIB'/'REMITTANCE' echo
-- the example codes S2-02's own §7a constraint proof already used.
--
-- No FK exists from app.profile_income_source.source_code to this list (AD-005 report
-- §4.1: "Income source gets no FK, because أخرى is free text by design") -- this seed
-- still ships so the multi-select picker has real content, and so أخرى's extra can carry
-- a marker for the free-text box.
--
-- One combined statement -- see V0014's header comment for why.
--
-- content_hash canonicalisation matches V0014's exactly (item_code|parent_code|label_ar|
-- label_en, empty string for absent parent_code/label_en, ordered by item_code
-- COLLATE "C") -- see that file's header comment for why.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('income_source', 'مصدر الدخل', 'Income source', false);

WITH items(item_code, label_ar, label_en, extra) AS (
  VALUES
    ('RATIB', 'راتب / أجر', 'Salary / wage', NULL::jsonb),
    ('BUSINESS_PROFIT', 'أرباح عمل تجاري', 'Business profit', NULL::jsonb),
    ('PENSION', 'معاش تقاعدي', 'Pension', NULL::jsonb),
    ('RENTAL', 'إيجار عقار', 'Property rental', NULL::jsonb),
    ('REMITTANCE', 'تحويلات من الخارج', 'Remittances from abroad', NULL::jsonb),
    ('AGRICULTURE', 'زراعة أو ثروة حيوانية', 'Agriculture or livestock', NULL::jsonb),
    ('INVESTMENT', 'عوائد استثمار', 'Investment returns', NULL::jsonb),
    ('FAMILY_SUPPORT', 'مساعدة أسرية', 'Family support', NULL::jsonb),
    ('OTHER', 'أخرى', 'Other', '{"freeText": true}'::jsonb)
),
ordered AS (
  SELECT item_code, label_ar, label_en, extra,
         row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu") AS sort_ordinal
  FROM items
),
canon AS (
  SELECT string_agg(item_code || '|' || '' || '|' || label_ar || '|' || coalesce(label_en, ''),
                     E'\n' ORDER BY item_code COLLATE "C") AS c,
         count(*) AS n
  FROM items
),
ver AS (
  INSERT INTO ref.reference_list_version
    (list_code, version, content_hash, item_count, source_note, is_current)
  SELECT 'income_source', 1, sha256(convert_to(c, 'UTF8')), n,
         'docs/journeys/customer.md stage 4 -- fixed list, item_code values assigned in '
         || 'this migration (none supplied by the source)',
         true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item
  (list_code, version, item_code, label_ar, label_en, sort_ordinal, is_active, extra)
SELECT ver.list_code, ver.version, ordered.item_code, ordered.label_ar, ordered.label_en,
       ordered.sort_ordinal, true, ordered.extra
FROM ordered, ver;
