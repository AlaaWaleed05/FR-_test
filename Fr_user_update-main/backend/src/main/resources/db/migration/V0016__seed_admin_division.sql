-- ref schema seed -- administrative divisions, per docs/reference/NSudan_Admin_Hierarchy.xlsx
-- and this task's explicit instructions. Hierarchical: country -> state -> locality,
-- linked via parent_code.
--
-- Keyed on locality_id (item_code), never on the Arabic name: confirmed via the workbook
-- that locality_name_ar is not unique -- "السلام"/As Salam appears in three different
-- states (White Nile 4207, South Kordofan 5205, South Darfur 6306) in this seed's own 135
-- rows. A UNIQUE-by-name approach would have collided.
--
-- The state list is driven from the states that actually appear in the Localities sheet
-- (15 distinct state_code values), never from the workbook's own States sheet, which lists
-- all 25 states including ten South-Sudan states with zero localities (pre-2011 source;
-- see the workbook's own README and OQ-012/R-014). The supplied Localities sheet already
-- contains exactly 135 real data rows, all within regions 1-6 (Sudan proper) -- confirmed
-- by reading the workbook directly; no filtering was needed beyond that.
--
-- sort_ordinal is one flat row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu") across
-- country+state+locality rows together, per the report's exact §9 formula (no PARTITION BY
-- given there). This still sorts correctly when the device queries a single parent's
-- children (WHERE parent_code = :x ORDER BY sort_ordinal) -- relative order among rows
-- sharing a parent is preserved by a global sort exactly as it would be by a partitioned
-- one.
--
-- One combined statement -- see V0014's header comment for why.
--
-- content_hash canonicalisation matches V0014's exactly (item_code|parent_code|label_ar|
-- label_en, empty string for absent parent_code/label_en, ordered by item_code
-- COLLATE "C") -- see that file's header comment for why.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('admin_division', 'التقسيم الإداري', 'Administrative division', true);

WITH items(item_code, parent_code, label_ar, label_en) AS (
  VALUES
    ('SD', NULL, 'السودان', 'Sudan'),
    ('11','SD','الشمالية','Northern'),
    ('12','SD','نهر النيل','River Nile'),
    ('21','SD','البحر الأحمر','Red Sea'),
    ('22','SD','كسلا','Kassala'),
    ('23','SD','القضارف','Gedaref'),
    ('31','SD','الخرطوم','Khartoum'),
    ('41','SD','الجزيرة','Gezira'),
    ('42','SD','النيل الأبيض','White Nile'),
    ('43','SD','سنار','Sennar'),
    ('44','SD','النيل الأزرق','Blue Nile'),
    ('51','SD','شمال كردفان','North Kordofan'),
    ('52','SD','جنوب كردفان','South Kordofan'),
    ('61','SD','شمال دارفور','North Darfur'),
    ('62','SD','غرب دارفور','West Darfur'),
    ('63','SD','جنوب دارفور','South Darfur'),
    ('1101','11','حلفا','Halfa'),
    ('1102','11','دلقو','Delgo'),
    ('1103','11','البرقيق','Al Burgaig'),
    ('1104','11','دنقلا','Dongola'),
    ('1105','11','القولد','Al Golid'),
    ('1106','11','الدبة','Ad Dabbah'),
    ('1107','11','مروي','Merowe'),
    ('1201','12','أبو حمد','Abu Hamad'),
    ('1202','12','بربر','Berber'),
    ('1203','12','عطبرة','Atbara'),
    ('1204','12','الدامر','Ad Damer'),
    ('1205','12','شندي','Shendi'),
    ('1206','12','المتمة','Al Matama'),
    ('2101','21','حلايب','Halaib'),
    ('2102','21','القنب','Al Ganab'),
    ('2103','21','بورتسودان','Port Sudan'),
    ('2104','21','سواكن','Suakin'),
    ('2105','21','سنكات','Sinkat'),
    ('2106','21','هيا','Haya'),
    ('2107','21','طوكر','Tokar'),
    ('2108','21','عقيق','Agig'),
    ('2201','22','شمال الدلتا','North Delta'),
    ('2202','22','همشكوريب','Hamashkoreib'),
    ('2203','22','تلكوك','Telkuk'),
    ('2204','22','ريفي أروما','Rural Aroma'),
    ('2205','22','غرب كسلا','West Kassala'),
    ('2206','22','مدينة كسلا','Kassala Town'),
    ('2207','22','ريفي كسلا','Rural Kassala'),
    ('2208','22','حلفا الجديدة','New Halfa'),
    ('2209','22','نهر عطبرة','Atbara River'),
    ('2210','22','ستيت','Setit'),
    ('2211','22','ود الحليو','Wad al Helew'),
    ('2301','23','البطانة','Al Butana'),
    ('2302','23','الفشقة','Al Fashaga'),
    ('2303','23','وسط القضارف','Central Gedaref'),
    ('2304','23','مدينة القضارف','Gedaref Town'),
    ('2305','23','الفاو','Al Fao'),
    ('2306','23','الرهد','Ar Rahad'),
    ('2307','23','قلع النحل','Galaa al Nahal'),
    ('2308','23','القلابات الغربية','West Galabat'),
    ('2309','23','القريشة','Al Gureisha'),
    ('2310','23','القلابات الشرقية','East Galabat'),
    ('2311','23','باسندة','Basundah'),
    ('3101','31','كرري','Karari'),
    ('3102','31','أم بدة','Umbada'),
    ('3103','31','أم درمان','Omdurman'),
    ('3104','31','بحري','Bahri'),
    ('3105','31','شرق النيل','East Nile'),
    ('3106','31','الخرطوم','Khartoum'),
    ('3107','31','جبل أولياء','Jebel Awlia'),
    ('4101','41','شرق الجزيرة','East Gezira'),
    ('4102','41','الكاملين','Al Kamlin'),
    ('4103','41','الحصاحيصا','Al Hasahisa'),
    ('4104','41','أم القرى','Umm al Qura'),
    ('4105','41','ود مدني الكبرى','Greater Wad Madani'),
    ('4106','41','جنوب الجزيرة','South Gezira'),
    ('4107','41','المناقل','Al Managil'),
    ('4201','42','القطينة','Al Getaina'),
    ('4202','42','أم رمتة','Umm Rimta'),
    ('4203','42','الدويم','Ad Duwaim'),
    ('4204','42','ربك','Rabak'),
    ('4205','42','الجبلين','Al Jabalain'),
    ('4206','42','كوستي','Kosti'),
    ('4207','42','السلام','As Salam'),
    ('4208','42','تندلتي','Tendelti'),
    ('4301','43','شرق سنار','East Sennar'),
    ('4302','43','سنار','Sennar'),
    ('4303','43','الدندر','Ad Dinder'),
    ('4304','43','السوكي','As Suki'),
    ('4305','43','سنجة','Singa'),
    ('4306','43','أبو حجار','Abu Hujar'),
    ('4307','43','الدالي','Ad Dali'),
    ('4401','44','الرصيرص','Ar Roseires'),
    ('4402','44','الدمازين','Ad Damazin'),
    ('4403','44','التضامن','At Tadamon'),
    ('4404','44','باو','Bau'),
    ('4405','44','قيسان','Geissan'),
    ('4406','44','الكرمك','Kurmuk'),
    ('5101','51','جبرة الشيخ','Jabrat ash Sheikh'),
    ('5102','51','سودري','Sodari'),
    ('5103','51','بارا','Bara'),
    ('5104','51','أم روابة','Umm Ruwaba'),
    ('5105','51','النهود','An Nuhud'),
    ('5106','51','شيكان','Sheikan'),
    ('5107','51','أبو زبد','Abu Zabad'),
    ('5108','51','ود بندة','Wad Banda'),
    ('5109','51','غبيش','Ghubaish'),
    ('5201','52','الرشاد','Ar Rashad'),
    ('5202','52','أبو جبيهة','Abu Jubaiha'),
    ('5203','52','الدلنج','Ad Dilling'),
    ('5204','52','كادقلي','Kadugli'),
    ('5205','52','السلام','As Salam'),
    ('5206','52','تلودي','Talodi'),
    ('5207','52','القوز','Al Guoz'),
    ('5208','52','كيلك','Keilak'),
    ('5209','52','أبيي','Abyei'),
    ('6101','61','المالحة','Al Malha'),
    ('6102','61','مليط','Mellit'),
    ('6103','61','الطينة','At Tina'),
    ('6104','61','سرف عمرة','Saraf Omra'),
    ('6105','61','السريف','As Serief'),
    ('6106','61','كبكابية','Kebkabiya'),
    ('6107','61','كتم','Kutum'),
    ('6108','61','الكومة','Al Koma'),
    ('6109','61','الفاشر','Al Fasher'),
    ('6110','61','أم كدادة','Umm Keddada'),
    ('6111','61','كلمندو','Kelemendo'),
    ('6112','61','الطويشة/اللعيت','At Tawisha / Al Laait'),
    ('6113','61','دار السلام','Dar as Salam'),
    ('6114','61','الواحة','Al Waha'),
    ('6201','62','كلبس','Kulbus'),
    ('6202','62','سربا','Sirba'),
    ('6203','62','كرينك','Kereneik'),
    ('6204','62','الجنينة','Al Geneina'),
    ('6205','62','بيضا','Beida'),
    ('6206','62','هبيلا','Habila'),
    ('6207','62','أزوم','Azum'),
    ('6208','62','زالنجي','Zalingei'),
    ('6209','62','نيرتتي','Nertiti'),
    ('6210','62','فوربرنقا','Foro Baranga'),
    ('6211','62','وادي صالح','Wadi Salih'),
    ('6212','62','مكجر','Mukjar'),
    ('6213','62','أم دخن','Umm Dukhun'),
    ('6301','63','شعيرية','Sheiria'),
    ('6302','63','نيالا','Nyala'),
    ('6303','63','شرق جبل مرة','East Jebel Marra'),
    ('6304','63','كاس','Kass'),
    ('6305','63','عد الفرسان','Ed al Fursan'),
    ('6306','63','السلام','As Salam'),
    ('6307','63','الضعين','Ed Daein'),
    ('6308','63','عديلة','Adila'),
    ('6309','63','تلس','Tullus'),
    ('6310','63','رهيد البردي','Rehed al Birdi'),
    ('6311','63','برام','Buram'),
    ('6312','63','بحر العرب','Bahr al Arab')
),
ordered AS (
  SELECT item_code, parent_code, label_ar, label_en,
         row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu") AS sort_ordinal
  FROM items
),
canon AS (
  SELECT string_agg(
           item_code || '|' || coalesce(parent_code, '') || '|' || label_ar || '|'
             || coalesce(label_en, ''),
           E'\n' ORDER BY item_code COLLATE "C"
         ) AS c,
         count(*) AS n
  FROM items
),
ver AS (
  INSERT INTO ref.reference_list_version
    (list_code, version, content_hash, item_count, source_note, is_current)
  SELECT 'admin_division', 1, sha256(convert_to(c, 'UTF8')), n,
         'docs/reference/NSudan_Admin_Hierarchy.xlsx, Localities sheet (135 rows, regions '
         || '1-6 only); 1 country row + 15 states derived from the localities actually '
         || 'present, not the workbook''s States sheet',
         true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item
  (list_code, version, item_code, parent_code, label_ar, label_en, sort_ordinal, is_active)
SELECT ver.list_code, ver.version, ordered.item_code, ordered.parent_code, ordered.label_ar,
       ordered.label_en, ordered.sort_ordinal, true
FROM ordered, ver;
