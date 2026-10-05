-- ref schema seed -- rejection reason codes, REJ-01..REJ-07 per docs/journeys/operator.md.
-- item_code values are exactly 'REJ-01'..'REJ-07', matching the literal 'REJ-07' already
-- used in V0009's rej07_needs_detail CHECK constraint.
--
-- sort_ordinal follows the same row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu")
-- formula as every other list here except education_level (customer.md's one explicit
-- ordinal exception). No exemption for rejection_reason is stated anywhere in the task or
-- the AD-005 report, so none is taken here.
--
-- PROVENANCE GAP, flagged rather than silently patched: operator.md supplies the internal
-- reason and the customer-facing message in ENGLISH ONLY -- no Arabic text exists anywhere
-- in the repo for this list, unlike every other list seeded in this session. label_ar is
-- NOT NULL per the AD-005 report's own schema, so the Arabic text below is a translation
-- made for this seed, not sourced from the bank. Flagged in BACKLOG.md for a real
-- translation/compliance review before production -- this is customer-facing SMS copy.
--
-- extra carries the customer-facing message in both languages (label_ar/label_en hold the
-- INTERNAL reason, which operator.md keeps separate from the customer-facing text).
-- REJ-03/04/05/07 deliberately share one neutral message, per operator.md: naming which
-- control fired would tell a fraudster what to change.
--
-- One combined statement -- see V0014's header comment for why.
--
-- content_hash canonicalisation matches V0014's exactly (item_code|parent_code|label_ar|
-- label_en, empty string for absent parent_code/label_en, ordered by item_code
-- COLLATE "C") -- see that file's header comment for why.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('rejection_reason', 'سبب الرفض', 'Rejection reason', false);

WITH items(item_code, label_ar, label_en, extra) AS (
  VALUES
    ('REJ-01', 'صور المستندات غير واضحة أو رديئة الجودة',
     'Document images illegible or poor quality',
     jsonb_build_object(
       'customerMessageAr', 'لم تكن صور مستنداتك واضحة بما فيه الكفاية. يرجى زيارة أي فرع مع مستندك الأصلي.',
       'customerMessageEn', 'Your document images were not clear enough. Please visit any branch with your original document.')),
    ('REJ-02', 'تعارض تفاصيل المستند مع السجل المدني',
     'Document details conflict with Civil Registry',
     jsonb_build_object(
       'customerMessageAr', 'تعذر تأكيد بياناتك مقابل السجل المدني. يرجى زيارة أي فرع مع مستندك الأصلي.',
       'customerMessageEn', 'Your details could not be confirmed against the Civil Registry. Please visit any branch with your original document.')),
    ('REJ-03', 'فشل أو عدم وضوح مطابقة الوجه',
     'Face match failed or inconclusive',
     jsonb_build_object(
       'customerMessageAr', 'تعذر إكمال التحديث الخاص بك. يرجى زيارة أي فرع مع مستندك الأصلي.',
       'customerMessageEn', 'Your update could not be completed. Please visit any branch with your original document.')),
    ('REJ-04', 'شبهة تلاعب أو تزوير في المستند',
     'Suspected document tampering or forgery',
     jsonb_build_object(
       'customerMessageAr', 'تعذر إكمال التحديث الخاص بك. يرجى زيارة أي فرع مع مستندك الأصلي.',
       'customerMessageEn', 'Your update could not be completed. Please visit any branch with your original document.')),
    ('REJ-05', 'المستند لا يخص صاحب الحساب',
     'Document does not belong to the account holder',
     jsonb_build_object(
       'customerMessageAr', 'تعذر إكمال التحديث الخاص بك. يرجى زيارة أي فرع مع مستندك الأصلي.',
       'customerMessageEn', 'Your update could not be completed. Please visit any branch with your original document.')),
    ('REJ-06', 'بيانات مدخلة غير قابلة للاستخدام - نص حر غير منطقي أو متناقض',
     'Entered data not usable -- nonsense or contradictory free-text values',
     jsonb_build_object(
       'customerMessageAr', 'تعذر قبول بعض البيانات التي قدمتها. يرجى زيارة أي فرع لإكمال التحديث.',
       'customerMessageEn', 'Some of the details you provided could not be accepted. Please visit any branch to complete your update.')),
    ('REJ-07', 'أخرى - تفصيل داخلي إلزامي',
     'Other -- internal detail mandatory',
     jsonb_build_object(
       'customerMessageAr', 'تعذر إكمال التحديث الخاص بك. يرجى زيارة أي فرع مع مستندك الأصلي.',
       'customerMessageEn', 'Your update could not be completed. Please visit any branch with your original document.'))
),
ordered AS (
  SELECT item_code, label_ar, label_en, extra,
         row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu") AS sort_ordinal
  FROM items
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
  SELECT 'rejection_reason', 1, sha256(convert_to(c, 'UTF8')), n,
         'docs/journeys/operator.md REJ-01..REJ-07; Arabic text translated for this seed, '
         || 'not sourced from the bank -- see BACKLOG.md', true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item
  (list_code, version, item_code, label_ar, label_en, sort_ordinal, is_active, extra)
SELECT ver.list_code, ver.version, ordered.item_code, ordered.label_ar, ordered.label_en,
       ordered.sort_ordinal, true, ordered.extra
FROM ordered, ver;
