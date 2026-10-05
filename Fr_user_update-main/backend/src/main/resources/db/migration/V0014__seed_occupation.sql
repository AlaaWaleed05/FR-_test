-- ref schema seed -- occupation list, per AD-005 report §9 and this task's source:
-- docs/reference/كود_المهنة.xlsx, JOBCODE column. 138 rows in the workbook; three JOBCODEs
-- are duplicate labels and the task requires the smaller code win, the larger EXCLUDED
-- entirely: مبرمج -> 86 (133 dropped), ممرض -> 43 (139 dropped), موظف حكومة -> 9 (135
-- dropped). 138 - 3 = 135 seeded rows.
--
-- DEVIATION FROM AD-005 REPORT §9, recorded deliberately: the report says duplicates
-- should be carried as is_active = false rows, not omitted ("so a profile that somehow
-- references one still resolves to a label"). This task's own instructions are explicit
-- and more specific -- "the three larger codes are excluded from the seed entirely" -- and
-- its required proof count is 135, not 138. Followed the task; flagging the contradiction
-- here rather than silently overriding either source. See the S2-03 session report.
--
-- Arabic and English names reproduced exactly as supplied, including the awkward English
-- translations (خياط = "Needle", محاسب/مدقق حساب = "Amenable", ربة بيت = "HOUSEWIFELY").
-- Only incidental leading/trailing ASCII whitespace present on a few source rows was
-- trimmed -- not a content correction.
--
-- Per AD-005 report §11: "reference-data publication is not a schema migration. New list
-- versions are data, inserted through an authenticated admin endpoint ... Only the initial
-- seed is a repeatable migration." Implemented here as an ordinary versioned V____
-- migration, not Flyway's R__ repeatable-migration mechanism: an R__ script re-runs
-- whenever its checksum changes, which would attempt these INSERTs again and fail on the
-- existing primary keys. A one-time versioned migration is the correct mechanism for data
-- that must be inserted exactly once; "repeatable" in the report reads as "reproducible
-- from the repository," matching how V0002's roles-and-grants migration is described
-- (§11: "reproducible from the repository rather than being something someone once typed
-- into psql"), not literally Flyway's R__ syntax.
--
-- One combined statement: PostgreSQL CTEs do not persist across separate statements, so
-- the reference_list_version insert and the reference_item insert are chained via a single
-- data-modifying CTE (ver) rather than two top-level statements sharing "items"/"ordered".
--
-- content_hash canonicalisation: item_code|parent_code|label_ar|label_en per row (empty
-- string for an absent parent_code or label_en), newline-joined, ordered by
-- item_code COLLATE "C" -- identical format and ordering in all six seed migrations this
-- session, found inconsistent across lists in review and unified here. This is the
-- internal canonicalisation this session's own contentHash proof needs (task §6); it is
-- NOT a settled wire format -- the actual client/server contentHash contract belongs to
-- AD-002f (still open, PROJECT_PLAN.md), and CLAUDE.md is explicit that open architecture
-- decisions are not to be settled in passing. A future AD-002f session may need to change
-- this and republish every list's version -- that is fine, because reference_list_version
-- rows are never deleted (report §4.4) and existing profiles keep the version they used.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('occupation', 'المهنة', 'Occupation', false);

WITH items(item_code, label_ar, label_en) AS (
  VALUES
    ('1','طبيب','Doctor'),
    ('2','مهندس','Engineer'),
    ('3','صيدلي','Pharmaceutical'),
    ('4','متعهد بناء','Contractor'),
    ('5','نجار','Carpenter'),
    ('6','حداد','Blacksmith'),
    ('7','عامل مصنع/شركة','LABOURER'),
    ('8','عامل يومية','LABOURER'),
    ('9','موظف حكومة','Public Servant'),
    ('10','موظف مؤسسة عامة','Public Office Servant'),
    ('11','موظف شركة عامة','Public Utility Servant'),
    ('12','موظف شركة خاصة','Company Employee'),
    ('13','موظف بنك','BANKER'),
    ('15','طالب','STUDENT'),
    ('16','ربة بيت','HOUSEWIFELY'),
    ('17','موظف شركة مالي','EMPLOYEE'),
    ('18','ميكانيكي','MECHANIC'),
    ('19','كهربائي','ELECTRICAL'),
    ('20','مزارع','FARMER'),
    ('21','سائق','DRIVER'),
    ('22','متقاعد','SUPERANNUATED'),
    ('24','طيار','AIRMAN'),
    ('25','خياط','Needle'),
    ('26','محاسب/مدقق حساب','Amenable'),
    ('27','فنان','Artist'),
    ('28','تاجر','Commereialize'),
    ('30','معلم','Teacher'),
    ('31','فنادق','Caravansaries'),
    ('33','اخرى','Other'),
    ('34','محامي','Lawyer'),
    ('36','مدير','Administrator'),
    ('38','دعاية واعلان','Propaganda'),
    ('39','شركات خاصة','Company insurance'),
    ('40','مختبرات','Laboratories'),
    ('41','عسكري','Martial'),
    ('42','تجارة عامة','Affairs'),
    ('43','ممرض','Infirmary'),
    ('44','نوادي','Clubs'),
    ('45','مقصف','Buffet'),
    ('46','دبلوماسي','Diplomatic'),
    ('48','موظف سياحة وسفر','Tourism'),
    ('51','موظف مكتبات','Bookstores'),
    ('52','موظف سفارة','Attache'),
    ('53','موظف ملاحة','Mariner'),
    ('54','موظف تخليص','Extrication'),
    ('55','قاضي','Judge'),
    ('56','بحار','sailar'),
    ('57','رئيس دولة','President'),
    ('58','وزير','Minister'),
    ('59','تعهدات ومقاولات','Commitments'),
    ('74','موظف شركة اتصالات','telecomcompany emplayee'),
    ('75','خبير اقتصادي','Ecenomist'),
    ('76','خبير مصرفي','Banking Expert'),
    ('77','خبير زراعي','Agricultural'),
    ('78','خبير صناعي','Industrial Expert'),
    ('79','خبير اعلامي','Madia Expert'),
    ('80','خبير استراتجي','Strategic Expert'),
    ('81','مذيع','Announcer'),
    ('82','مندوب مبيعات','Sales Representative'),
    ('83','رئيس قسم','Head of Department'),
    ('84','رجل اعمال','Businessman'),
    ('85','سيدة اعمال','Businesswoman'),
    ('86','مبرمج','Programmer'),
    ('87','مدير عام','Director General'),
    ('88','اخصائي','Specialist'),
    ('89','موظف منظمات','Organizations Employee'),
    ('90','ضابط اداري','Admnstrative officer'),
    ('91','ضابط جوي','Admnstrative Air'),
    ('92','كايتن جوي','Captain Joy'),
    ('93','كابتن بحري','Nautical Captain'),
    ('94','كابتن بري','Captain Brie'),
    ('95','مضيف جوي','Air Host'),
    ('96','مضيف بري','Wild Host'),
    ('97','استاذ جامعي','University Professor'),
    ('98','استاذ مساعد','Assistant Professor'),
    ('99','مستشار اقتصادي','Economic Consaltant'),
    ('100','مستشار مصرفي','Banking Advisor'),
    ('101','مستشار زراعي','Agricultural Advisor'),
    ('102','مستشار صناعي','Idustrial Consultant'),
    ('103','مستشار اعلامي','Media Consultant'),
    ('104','مستشار استراتجي','Strategic Advisor'),
    ('105','خفير','sentry'),
    ('106','صحفي','journalist'),
    ('107','ضابط','officer'),
    ('108','ضابط أمن','security officer'),
    ('109','فنان تشكيلي','Fineartist'),
    ('110','جزار','Butcher'),
    ('111','اعلامي','Informational'),
    ('112','مخرج','Director'),
    ('113','مصور','Photographer'),
    ('114','مدير مبيعات','Sales manager'),
    ('115','بائع','Seller'),
    ('116','موظف استقبال','Receptionis'),
    ('117','بروفسير','Professor'),
    ('118','سفرجي','Ssfruji'),
    ('119','شيف','Chef'),
    ('120','قبطان','Captain'),
    ('121','مشرف اداري','Administrative Supervisor'),
    ('122','اعمال حرة','Free Businees'),
    ('123','لاعب رياضي','Lizarder'),
    ('124','مدرب','Coach'),
    ('125','منقب','Prospector'),
    ('126','صائغ','Jeweler'),
    ('127','مترجم','Translator'),
    ('128','منسق حدائق','Ganden Coordinator'),
    ('129','علاقات عامة','Public Relations'),
    ('130','مراسم','Ceremony'),
    ('131','مؤذن','Muezzin'),
    ('132','امام جامع','Imam of Mosque'),
    ('134','مستشار قانوني','Counsel'),
    ('136','مدير مكتب','Office Boss'),
    ('137','قاصر','Minor'),
    ('138','مدير طبي','Medical Director'),
    ('140','اختصاصي تسويق','Marketing Specialist'),
    ('141','مرحل','Marahal'),
    ('142','والئ ولاية','Waly Walayh'),
    ('143','برلماني','Parliamentary'),
    ('144','فني','Technical'),
    ('145','مصمم','Designer'),
    ('146','مهندس طيران','Flight Engineer'),
    ('147','مطران','Bishop'),
    ('148','وكيل نيابة','Prosecutor'),
    ('149','كبار الضباط','Senior Officers'),
    ('150','روساء الاحزاب','Party Leaders'),
    ('151','مدراء الموسسات الحكومية','Directors Of Government Istitutions'),
    ('152','كبار الشخصيات الدينية','Senior Religious Figures'),
    ('163','السفراء','Ambassadors'),
    ('164','القنصل','Consul'),
    ('165','مفوض سامي','High Commissioner'),
    ('166','كبار مسئولي الاحزاب الساسية','Political Parties'),
    ('167','روساء ومديرين الولايات','Senier Management And Boarel Of state'),
    ('168','مدراء المنظمات','Senior Offical Ingos'),
    ('169','روساء البلديات واعضاء المحلية','Mayors And Members Of Local Country'),
    ('170','روساء الهيئات العسكرية القانونية','Head Of Military Judiciary Law Enporceme'),
    ('171','وكيل وزارة','Undersecretary of the Ministry')
),
ordered AS (
  SELECT item_code, label_ar, label_en,
         row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu") AS sort_ordinal
  FROM items
),
canon AS (
  SELECT string_agg(
           item_code || '|' || '' || '|' || label_ar || '|' || coalesce(label_en, ''),
           E'\n' ORDER BY item_code COLLATE "C"
         ) AS c,
         count(*) AS n
  FROM items
),
ver AS (
  INSERT INTO ref.reference_list_version
    (list_code, version, content_hash, item_count, source_note, is_current)
  SELECT 'occupation', 1, sha256(convert_to(c, 'UTF8')), n,
         'docs/reference/كود_المهنة.xlsx, JOBCODE column; duplicates 133/139/135 excluded, '
         || 'smaller code (86/43/9) kept',
         true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item
  (list_code, version, item_code, label_ar, label_en, sort_ordinal, is_active)
SELECT ver.list_code, ver.version, ordered.item_code, ordered.label_ar, ordered.label_en,
       ordered.sort_ordinal, true
FROM ordered, ver;
