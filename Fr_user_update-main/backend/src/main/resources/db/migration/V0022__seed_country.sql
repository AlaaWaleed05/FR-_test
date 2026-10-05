-- ref schema seed -- ISO 3166-1 country list, per docs/journeys/field-provenance.md's
-- "Consequences for the rest of the specification" section (S2-06/S2-07): field 11 (country
-- of residence) and the country level of both address hierarchies need a country reference
-- list, which admin_division does not provide (it holds exactly one country row, Sudan
-- itself). No new registry/grants migration is needed -- ref.reference_list,
-- ref.reference_list_version, ref.reference_item, ref.ar_fold and fru_app's read grants on
-- all three already exist generically since V0011/V0012 (S2-03). This is purely a seed,
-- following V0017__seed_income_source.sql's exact combined-statement CTE shape (flat,
-- non-hierarchical list -- no parent_code, same as income_source/education_level/
-- rejection_reason/branch).
--
-- Sourced from CLDR data already on this machine, per the task's explicit instruction: no
-- network fetch for repo contents, and a reference list that ships in the product should not
-- depend on a URL that may move. Java 21 uses CLDR as its default locale provider.
-- java.util.Locale.getISOCountries() (249 codes) + getDisplayCountry(Locale.forLanguageTag
-- ("ar"))/getDisplayCountry(Locale.forLanguageTag("en")) for the Arabic/English names +
-- getISO3Country() for the alpha-3 code. Run against OpenJDK 21.0.8 (Android Studio JBR
-- build), locale provider adapter type CLDR, bundled CLDR v43 per
-- $JAVA_HOME/legal/java.base/cldr.md. Verified live before writing this migration, per the
-- task's explicit "stop and report if the JDK returns English fallbacks" instruction: SD ->
-- "السودان" (matches the admin_division country row S2-03 already seeded, byte-for-byte),
-- and 7 other spot-checked codes all returned real Arabic text, not English -- full detail
-- and every code's data in the S2-07 session report. All 249 alpha-3 lookups succeeded (0
-- failures). The 249-row VALUES list below was generated from that verified Java output by a
-- reviewed script, not hand-transcribed, and checked for stray `'` characters that would need
-- SQL escaping before being written here (none found -- the one apostrophe-like case, Côte
-- d’Ivoire, uses U+2019 RIGHT SINGLE QUOTATION MARK, not U+0027).
--
-- The stored code is ISO 3166-1 alpha-2 (item_code), per the task. Alpha-3 is kept in `extra`
-- (e.g. {"alpha3":"SDN"}), not the primary key, and -- matching every other list's
-- canonicalisation, including income_source's `extra` on OTHER -- deliberately excluded from
-- the content_hash input below.
--
-- sort_ordinal: row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu"), the general
-- alphabetical formula every list except education_level uses -- the task is explicit that
-- countries are alphabetical, not ordinal.
--
-- content_hash canonicalisation matches V0014's exactly (item_code|parent_code|label_ar|
-- label_en, empty string for absent parent_code/label_en, ordered by item_code COLLATE "C",
-- joined with the literal two-character escape E'\n', never a raw embedded newline -- see
-- V0014/V0016's header comment for why that distinction matters on this machine).
--
-- One combined statement -- see V0014's header comment for why (PostgreSQL CTEs do not
-- persist across separate top-level statements).
--
-- DEFERRED FK, per the task's explicit instruction not to guess column names: field 11
-- (country of residence) and the country level of both the work-address and home-address
-- hierarchies will reference ref.reference_item(list_code='country', version, item_code)
-- once S2-08 adds the corresponding columns to app.profile_customer_data / the address
-- tables. Those columns do not exist yet -- S2-08 is the schema amendment task that adds
-- them. Not added here, unlike V0006/V0009's deferred FKs, which could pre-write the exact
-- ALTER TABLE statement because their target columns already existed; these do not.

INSERT INTO ref.reference_list (list_code, name_ar, name_en, is_hierarchical)
VALUES ('country', 'الدولة', 'Country', false);

WITH items(item_code, label_ar, label_en, extra) AS (
  VALUES
    ('AD', 'أندورا', 'Andorra', '{"alpha3":"AND"}'::jsonb),
    ('AE', 'الإمارات العربية المتحدة', 'United Arab Emirates', '{"alpha3":"ARE"}'::jsonb),
    ('AF', 'أفغانستان', 'Afghanistan', '{"alpha3":"AFG"}'::jsonb),
    ('AG', 'أنتيغوا وبربودا', 'Antigua & Barbuda', '{"alpha3":"ATG"}'::jsonb),
    ('AI', 'أنغويلا', 'Anguilla', '{"alpha3":"AIA"}'::jsonb),
    ('AL', 'ألبانيا', 'Albania', '{"alpha3":"ALB"}'::jsonb),
    ('AM', 'أرمينيا', 'Armenia', '{"alpha3":"ARM"}'::jsonb),
    ('AO', 'أنغولا', 'Angola', '{"alpha3":"AGO"}'::jsonb),
    ('AQ', 'أنتاركتيكا', 'Antarctica', '{"alpha3":"ATA"}'::jsonb),
    ('AR', 'الأرجنتين', 'Argentina', '{"alpha3":"ARG"}'::jsonb),
    ('AS', 'ساموا الأمريكية', 'American Samoa', '{"alpha3":"ASM"}'::jsonb),
    ('AT', 'النمسا', 'Austria', '{"alpha3":"AUT"}'::jsonb),
    ('AU', 'أستراليا', 'Australia', '{"alpha3":"AUS"}'::jsonb),
    ('AW', 'أروبا', 'Aruba', '{"alpha3":"ABW"}'::jsonb),
    ('AX', 'جزر آلاند', 'Åland Islands', '{"alpha3":"ALA"}'::jsonb),
    ('AZ', 'أذربيجان', 'Azerbaijan', '{"alpha3":"AZE"}'::jsonb),
    ('BA', 'البوسنة والهرسك', 'Bosnia & Herzegovina', '{"alpha3":"BIH"}'::jsonb),
    ('BB', 'بربادوس', 'Barbados', '{"alpha3":"BRB"}'::jsonb),
    ('BD', 'بنغلاديش', 'Bangladesh', '{"alpha3":"BGD"}'::jsonb),
    ('BE', 'بلجيكا', 'Belgium', '{"alpha3":"BEL"}'::jsonb),
    ('BF', 'بوركينا فاسو', 'Burkina Faso', '{"alpha3":"BFA"}'::jsonb),
    ('BG', 'بلغاريا', 'Bulgaria', '{"alpha3":"BGR"}'::jsonb),
    ('BH', 'البحرين', 'Bahrain', '{"alpha3":"BHR"}'::jsonb),
    ('BI', 'بوروندي', 'Burundi', '{"alpha3":"BDI"}'::jsonb),
    ('BJ', 'بنين', 'Benin', '{"alpha3":"BEN"}'::jsonb),
    ('BL', 'سان بارتليمي', 'St. Barthélemy', '{"alpha3":"BLM"}'::jsonb),
    ('BM', 'برمودا', 'Bermuda', '{"alpha3":"BMU"}'::jsonb),
    ('BN', 'بروناي', 'Brunei', '{"alpha3":"BRN"}'::jsonb),
    ('BO', 'بوليفيا', 'Bolivia', '{"alpha3":"BOL"}'::jsonb),
    ('BQ', 'هولندا الكاريبية', 'Caribbean Netherlands', '{"alpha3":"BES"}'::jsonb),
    ('BR', 'البرازيل', 'Brazil', '{"alpha3":"BRA"}'::jsonb),
    ('BS', 'جزر البهاما', 'Bahamas', '{"alpha3":"BHS"}'::jsonb),
    ('BT', 'بوتان', 'Bhutan', '{"alpha3":"BTN"}'::jsonb),
    ('BV', 'جزيرة بوفيه', 'Bouvet Island', '{"alpha3":"BVT"}'::jsonb),
    ('BW', 'بوتسوانا', 'Botswana', '{"alpha3":"BWA"}'::jsonb),
    ('BY', 'بيلاروس', 'Belarus', '{"alpha3":"BLR"}'::jsonb),
    ('BZ', 'بليز', 'Belize', '{"alpha3":"BLZ"}'::jsonb),
    ('CA', 'كندا', 'Canada', '{"alpha3":"CAN"}'::jsonb),
    ('CC', 'جزر كوكوس (كيلينغ)', 'Cocos (Keeling) Islands', '{"alpha3":"CCK"}'::jsonb),
    ('CD', 'الكونغو - كينشاسا', 'Congo - Kinshasa', '{"alpha3":"COD"}'::jsonb),
    ('CF', 'جمهورية أفريقيا الوسطى', 'Central African Republic', '{"alpha3":"CAF"}'::jsonb),
    ('CG', 'الكونغو - برازافيل', 'Congo - Brazzaville', '{"alpha3":"COG"}'::jsonb),
    ('CH', 'سويسرا', 'Switzerland', '{"alpha3":"CHE"}'::jsonb),
    ('CI', 'ساحل العاج', 'Côte d’Ivoire', '{"alpha3":"CIV"}'::jsonb),
    ('CK', 'جزر كوك', 'Cook Islands', '{"alpha3":"COK"}'::jsonb),
    ('CL', 'تشيلي', 'Chile', '{"alpha3":"CHL"}'::jsonb),
    ('CM', 'الكاميرون', 'Cameroon', '{"alpha3":"CMR"}'::jsonb),
    ('CN', 'الصين', 'China', '{"alpha3":"CHN"}'::jsonb),
    ('CO', 'كولومبيا', 'Colombia', '{"alpha3":"COL"}'::jsonb),
    ('CR', 'كوستاريكا', 'Costa Rica', '{"alpha3":"CRI"}'::jsonb),
    ('CU', 'كوبا', 'Cuba', '{"alpha3":"CUB"}'::jsonb),
    ('CV', 'الرأس الأخضر', 'Cape Verde', '{"alpha3":"CPV"}'::jsonb),
    ('CW', 'كوراساو', 'Curaçao', '{"alpha3":"CUW"}'::jsonb),
    ('CX', 'جزيرة كريسماس', 'Christmas Island', '{"alpha3":"CXR"}'::jsonb),
    ('CY', 'قبرص', 'Cyprus', '{"alpha3":"CYP"}'::jsonb),
    ('CZ', 'التشيك', 'Czechia', '{"alpha3":"CZE"}'::jsonb),
    ('DE', 'ألمانيا', 'Germany', '{"alpha3":"DEU"}'::jsonb),
    ('DJ', 'جيبوتي', 'Djibouti', '{"alpha3":"DJI"}'::jsonb),
    ('DK', 'الدانمرك', 'Denmark', '{"alpha3":"DNK"}'::jsonb),
    ('DM', 'دومينيكا', 'Dominica', '{"alpha3":"DMA"}'::jsonb),
    ('DO', 'جمهورية الدومينيكان', 'Dominican Republic', '{"alpha3":"DOM"}'::jsonb),
    ('DZ', 'الجزائر', 'Algeria', '{"alpha3":"DZA"}'::jsonb),
    ('EC', 'الإكوادور', 'Ecuador', '{"alpha3":"ECU"}'::jsonb),
    ('EE', 'إستونيا', 'Estonia', '{"alpha3":"EST"}'::jsonb),
    ('EG', 'مصر', 'Egypt', '{"alpha3":"EGY"}'::jsonb),
    ('EH', 'الصحراء الغربية', 'Western Sahara', '{"alpha3":"ESH"}'::jsonb),
    ('ER', 'إريتريا', 'Eritrea', '{"alpha3":"ERI"}'::jsonb),
    ('ES', 'إسبانيا', 'Spain', '{"alpha3":"ESP"}'::jsonb),
    ('ET', 'إثيوبيا', 'Ethiopia', '{"alpha3":"ETH"}'::jsonb),
    ('FI', 'فنلندا', 'Finland', '{"alpha3":"FIN"}'::jsonb),
    ('FJ', 'فيجي', 'Fiji', '{"alpha3":"FJI"}'::jsonb),
    ('FK', 'جزر فوكلاند', 'Falkland Islands', '{"alpha3":"FLK"}'::jsonb),
    ('FM', 'ميكرونيزيا', 'Micronesia', '{"alpha3":"FSM"}'::jsonb),
    ('FO', 'جزر فارو', 'Faroe Islands', '{"alpha3":"FRO"}'::jsonb),
    ('FR', 'فرنسا', 'France', '{"alpha3":"FRA"}'::jsonb),
    ('GA', 'الغابون', 'Gabon', '{"alpha3":"GAB"}'::jsonb),
    ('GB', 'المملكة المتحدة', 'United Kingdom', '{"alpha3":"GBR"}'::jsonb),
    ('GD', 'غرينادا', 'Grenada', '{"alpha3":"GRD"}'::jsonb),
    ('GE', 'جورجيا', 'Georgia', '{"alpha3":"GEO"}'::jsonb),
    ('GF', 'غويانا الفرنسية', 'French Guiana', '{"alpha3":"GUF"}'::jsonb),
    ('GG', 'غيرنزي', 'Guernsey', '{"alpha3":"GGY"}'::jsonb),
    ('GH', 'غانا', 'Ghana', '{"alpha3":"GHA"}'::jsonb),
    ('GI', 'جبل طارق', 'Gibraltar', '{"alpha3":"GIB"}'::jsonb),
    ('GL', 'غرينلاند', 'Greenland', '{"alpha3":"GRL"}'::jsonb),
    ('GM', 'غامبيا', 'Gambia', '{"alpha3":"GMB"}'::jsonb),
    ('GN', 'غينيا', 'Guinea', '{"alpha3":"GIN"}'::jsonb),
    ('GP', 'غوادلوب', 'Guadeloupe', '{"alpha3":"GLP"}'::jsonb),
    ('GQ', 'غينيا الاستوائية', 'Equatorial Guinea', '{"alpha3":"GNQ"}'::jsonb),
    ('GR', 'اليونان', 'Greece', '{"alpha3":"GRC"}'::jsonb),
    ('GS', 'جورجيا الجنوبية وجزر ساندويتش الجنوبية', 'South Georgia & South Sandwich Islands', '{"alpha3":"SGS"}'::jsonb),
    ('GT', 'غواتيمالا', 'Guatemala', '{"alpha3":"GTM"}'::jsonb),
    ('GU', 'غوام', 'Guam', '{"alpha3":"GUM"}'::jsonb),
    ('GW', 'غينيا بيساو', 'Guinea-Bissau', '{"alpha3":"GNB"}'::jsonb),
    ('GY', 'غيانا', 'Guyana', '{"alpha3":"GUY"}'::jsonb),
    ('HK', 'هونغ كونغ الصينية (منطقة إدارية خاصة)', 'Hong Kong SAR China', '{"alpha3":"HKG"}'::jsonb),
    ('HM', 'جزيرة هيرد وجزر ماكدونالد', 'Heard & McDonald Islands', '{"alpha3":"HMD"}'::jsonb),
    ('HN', 'هندوراس', 'Honduras', '{"alpha3":"HND"}'::jsonb),
    ('HR', 'كرواتيا', 'Croatia', '{"alpha3":"HRV"}'::jsonb),
    ('HT', 'هايتي', 'Haiti', '{"alpha3":"HTI"}'::jsonb),
    ('HU', 'هنغاريا', 'Hungary', '{"alpha3":"HUN"}'::jsonb),
    ('ID', 'إندونيسيا', 'Indonesia', '{"alpha3":"IDN"}'::jsonb),
    ('IE', 'أيرلندا', 'Ireland', '{"alpha3":"IRL"}'::jsonb),
    ('IL', 'إسرائيل', 'Israel', '{"alpha3":"ISR"}'::jsonb),
    ('IM', 'جزيرة مان', 'Isle of Man', '{"alpha3":"IMN"}'::jsonb),
    ('IN', 'الهند', 'India', '{"alpha3":"IND"}'::jsonb),
    ('IO', 'الإقليم البريطاني في المحيط الهندي', 'British Indian Ocean Territory', '{"alpha3":"IOT"}'::jsonb),
    ('IQ', 'العراق', 'Iraq', '{"alpha3":"IRQ"}'::jsonb),
    ('IR', 'إيران', 'Iran', '{"alpha3":"IRN"}'::jsonb),
    ('IS', 'آيسلندا', 'Iceland', '{"alpha3":"ISL"}'::jsonb),
    ('IT', 'إيطاليا', 'Italy', '{"alpha3":"ITA"}'::jsonb),
    ('JE', 'جيرسي', 'Jersey', '{"alpha3":"JEY"}'::jsonb),
    ('JM', 'جامايكا', 'Jamaica', '{"alpha3":"JAM"}'::jsonb),
    ('JO', 'الأردن', 'Jordan', '{"alpha3":"JOR"}'::jsonb),
    ('JP', 'اليابان', 'Japan', '{"alpha3":"JPN"}'::jsonb),
    ('KE', 'كينيا', 'Kenya', '{"alpha3":"KEN"}'::jsonb),
    ('KG', 'قيرغيزستان', 'Kyrgyzstan', '{"alpha3":"KGZ"}'::jsonb),
    ('KH', 'كمبوديا', 'Cambodia', '{"alpha3":"KHM"}'::jsonb),
    ('KI', 'كيريباتي', 'Kiribati', '{"alpha3":"KIR"}'::jsonb),
    ('KM', 'جزر القمر', 'Comoros', '{"alpha3":"COM"}'::jsonb),
    ('KN', 'سانت كيتس ونيفيس', 'St. Kitts & Nevis', '{"alpha3":"KNA"}'::jsonb),
    ('KP', 'كوريا الشمالية', 'North Korea', '{"alpha3":"PRK"}'::jsonb),
    ('KR', 'كوريا الجنوبية', 'South Korea', '{"alpha3":"KOR"}'::jsonb),
    ('KW', 'الكويت', 'Kuwait', '{"alpha3":"KWT"}'::jsonb),
    ('KY', 'جزر كايمان', 'Cayman Islands', '{"alpha3":"CYM"}'::jsonb),
    ('KZ', 'كازاخستان', 'Kazakhstan', '{"alpha3":"KAZ"}'::jsonb),
    ('LA', 'لاوس', 'Laos', '{"alpha3":"LAO"}'::jsonb),
    ('LB', 'لبنان', 'Lebanon', '{"alpha3":"LBN"}'::jsonb),
    ('LC', 'سانت لوسيا', 'St. Lucia', '{"alpha3":"LCA"}'::jsonb),
    ('LI', 'ليختنشتاين', 'Liechtenstein', '{"alpha3":"LIE"}'::jsonb),
    ('LK', 'سريلانكا', 'Sri Lanka', '{"alpha3":"LKA"}'::jsonb),
    ('LR', 'ليبيريا', 'Liberia', '{"alpha3":"LBR"}'::jsonb),
    ('LS', 'ليسوتو', 'Lesotho', '{"alpha3":"LSO"}'::jsonb),
    ('LT', 'ليتوانيا', 'Lithuania', '{"alpha3":"LTU"}'::jsonb),
    ('LU', 'لوكسمبورغ', 'Luxembourg', '{"alpha3":"LUX"}'::jsonb),
    ('LV', 'لاتفيا', 'Latvia', '{"alpha3":"LVA"}'::jsonb),
    ('LY', 'ليبيا', 'Libya', '{"alpha3":"LBY"}'::jsonb),
    ('MA', 'المغرب', 'Morocco', '{"alpha3":"MAR"}'::jsonb),
    ('MC', 'موناكو', 'Monaco', '{"alpha3":"MCO"}'::jsonb),
    ('MD', 'مولدوفا', 'Moldova', '{"alpha3":"MDA"}'::jsonb),
    ('ME', 'الجبل الأسود', 'Montenegro', '{"alpha3":"MNE"}'::jsonb),
    ('MF', 'سان مارتن', 'St. Martin', '{"alpha3":"MAF"}'::jsonb),
    ('MG', 'مدغشقر', 'Madagascar', '{"alpha3":"MDG"}'::jsonb),
    ('MH', 'جزر مارشال', 'Marshall Islands', '{"alpha3":"MHL"}'::jsonb),
    ('MK', 'مقدونيا الشمالية', 'North Macedonia', '{"alpha3":"MKD"}'::jsonb),
    ('ML', 'مالي', 'Mali', '{"alpha3":"MLI"}'::jsonb),
    ('MM', 'ميانمار (بورما)', 'Myanmar (Burma)', '{"alpha3":"MMR"}'::jsonb),
    ('MN', 'منغوليا', 'Mongolia', '{"alpha3":"MNG"}'::jsonb),
    ('MO', 'منطقة ماكاو الإدارية الخاصة', 'Macao SAR China', '{"alpha3":"MAC"}'::jsonb),
    ('MP', 'جزر ماريانا الشمالية', 'Northern Mariana Islands', '{"alpha3":"MNP"}'::jsonb),
    ('MQ', 'جزر المارتينيك', 'Martinique', '{"alpha3":"MTQ"}'::jsonb),
    ('MR', 'موريتانيا', 'Mauritania', '{"alpha3":"MRT"}'::jsonb),
    ('MS', 'مونتسرات', 'Montserrat', '{"alpha3":"MSR"}'::jsonb),
    ('MT', 'مالطا', 'Malta', '{"alpha3":"MLT"}'::jsonb),
    ('MU', 'موريشيوس', 'Mauritius', '{"alpha3":"MUS"}'::jsonb),
    ('MV', 'جزر المالديف', 'Maldives', '{"alpha3":"MDV"}'::jsonb),
    ('MW', 'ملاوي', 'Malawi', '{"alpha3":"MWI"}'::jsonb),
    ('MX', 'المكسيك', 'Mexico', '{"alpha3":"MEX"}'::jsonb),
    ('MY', 'ماليزيا', 'Malaysia', '{"alpha3":"MYS"}'::jsonb),
    ('MZ', 'موزمبيق', 'Mozambique', '{"alpha3":"MOZ"}'::jsonb),
    ('NA', 'ناميبيا', 'Namibia', '{"alpha3":"NAM"}'::jsonb),
    ('NC', 'كاليدونيا الجديدة', 'New Caledonia', '{"alpha3":"NCL"}'::jsonb),
    ('NE', 'النيجر', 'Niger', '{"alpha3":"NER"}'::jsonb),
    ('NF', 'جزيرة نورفولك', 'Norfolk Island', '{"alpha3":"NFK"}'::jsonb),
    ('NG', 'نيجيريا', 'Nigeria', '{"alpha3":"NGA"}'::jsonb),
    ('NI', 'نيكاراغوا', 'Nicaragua', '{"alpha3":"NIC"}'::jsonb),
    ('NL', 'هولندا', 'Netherlands', '{"alpha3":"NLD"}'::jsonb),
    ('NO', 'النرويج', 'Norway', '{"alpha3":"NOR"}'::jsonb),
    ('NP', 'نيبال', 'Nepal', '{"alpha3":"NPL"}'::jsonb),
    ('NR', 'ناورو', 'Nauru', '{"alpha3":"NRU"}'::jsonb),
    ('NU', 'نيوي', 'Niue', '{"alpha3":"NIU"}'::jsonb),
    ('NZ', 'نيوزيلندا', 'New Zealand', '{"alpha3":"NZL"}'::jsonb),
    ('OM', 'عُمان', 'Oman', '{"alpha3":"OMN"}'::jsonb),
    ('PA', 'بنما', 'Panama', '{"alpha3":"PAN"}'::jsonb),
    ('PE', 'بيرو', 'Peru', '{"alpha3":"PER"}'::jsonb),
    ('PF', 'بولينيزيا الفرنسية', 'French Polynesia', '{"alpha3":"PYF"}'::jsonb),
    ('PG', 'بابوا غينيا الجديدة', 'Papua New Guinea', '{"alpha3":"PNG"}'::jsonb),
    ('PH', 'الفلبين', 'Philippines', '{"alpha3":"PHL"}'::jsonb),
    ('PK', 'باكستان', 'Pakistan', '{"alpha3":"PAK"}'::jsonb),
    ('PL', 'بولندا', 'Poland', '{"alpha3":"POL"}'::jsonb),
    ('PM', 'سان بيير ومكويلون', 'St. Pierre & Miquelon', '{"alpha3":"SPM"}'::jsonb),
    ('PN', 'جزر بيتكيرن', 'Pitcairn Islands', '{"alpha3":"PCN"}'::jsonb),
    ('PR', 'بورتوريكو', 'Puerto Rico', '{"alpha3":"PRI"}'::jsonb),
    ('PS', 'الأراضي الفلسطينية', 'Palestinian Territories', '{"alpha3":"PSE"}'::jsonb),
    ('PT', 'البرتغال', 'Portugal', '{"alpha3":"PRT"}'::jsonb),
    ('PW', 'بالاو', 'Palau', '{"alpha3":"PLW"}'::jsonb),
    ('PY', 'باراغواي', 'Paraguay', '{"alpha3":"PRY"}'::jsonb),
    ('QA', 'قطر', 'Qatar', '{"alpha3":"QAT"}'::jsonb),
    ('RE', 'روينيون', 'Réunion', '{"alpha3":"REU"}'::jsonb),
    ('RO', 'رومانيا', 'Romania', '{"alpha3":"ROU"}'::jsonb),
    ('RS', 'صربيا', 'Serbia', '{"alpha3":"SRB"}'::jsonb),
    ('RU', 'روسيا', 'Russia', '{"alpha3":"RUS"}'::jsonb),
    ('RW', 'رواندا', 'Rwanda', '{"alpha3":"RWA"}'::jsonb),
    ('SA', 'المملكة العربية السعودية', 'Saudi Arabia', '{"alpha3":"SAU"}'::jsonb),
    ('SB', 'جزر سليمان', 'Solomon Islands', '{"alpha3":"SLB"}'::jsonb),
    ('SC', 'سيشل', 'Seychelles', '{"alpha3":"SYC"}'::jsonb),
    ('SD', 'السودان', 'Sudan', '{"alpha3":"SDN"}'::jsonb),
    ('SE', 'السويد', 'Sweden', '{"alpha3":"SWE"}'::jsonb),
    ('SG', 'سنغافورة', 'Singapore', '{"alpha3":"SGP"}'::jsonb),
    ('SH', 'سانت هيلينا', 'St. Helena', '{"alpha3":"SHN"}'::jsonb),
    ('SI', 'سلوفينيا', 'Slovenia', '{"alpha3":"SVN"}'::jsonb),
    ('SJ', 'سفالبارد وجان ماين', 'Svalbard & Jan Mayen', '{"alpha3":"SJM"}'::jsonb),
    ('SK', 'سلوفاكيا', 'Slovakia', '{"alpha3":"SVK"}'::jsonb),
    ('SL', 'سيراليون', 'Sierra Leone', '{"alpha3":"SLE"}'::jsonb),
    ('SM', 'سان مارينو', 'San Marino', '{"alpha3":"SMR"}'::jsonb),
    ('SN', 'السنغال', 'Senegal', '{"alpha3":"SEN"}'::jsonb),
    ('SO', 'الصومال', 'Somalia', '{"alpha3":"SOM"}'::jsonb),
    ('SR', 'سورينام', 'Suriname', '{"alpha3":"SUR"}'::jsonb),
    ('SS', 'جنوب السودان', 'South Sudan', '{"alpha3":"SSD"}'::jsonb),
    ('ST', 'ساو تومي وبرينسيبي', 'São Tomé & Príncipe', '{"alpha3":"STP"}'::jsonb),
    ('SV', 'السلفادور', 'El Salvador', '{"alpha3":"SLV"}'::jsonb),
    ('SX', 'سانت مارتن', 'Sint Maarten', '{"alpha3":"SXM"}'::jsonb),
    ('SY', 'سوريا', 'Syria', '{"alpha3":"SYR"}'::jsonb),
    ('SZ', 'إسواتيني', 'Eswatini', '{"alpha3":"SWZ"}'::jsonb),
    ('TC', 'جزر توركس وكايكوس', 'Turks & Caicos Islands', '{"alpha3":"TCA"}'::jsonb),
    ('TD', 'تشاد', 'Chad', '{"alpha3":"TCD"}'::jsonb),
    ('TF', 'الأقاليم الجنوبية الفرنسية', 'French Southern Territories', '{"alpha3":"ATF"}'::jsonb),
    ('TG', 'توغو', 'Togo', '{"alpha3":"TGO"}'::jsonb),
    ('TH', 'تايلاند', 'Thailand', '{"alpha3":"THA"}'::jsonb),
    ('TJ', 'طاجيكستان', 'Tajikistan', '{"alpha3":"TJK"}'::jsonb),
    ('TK', 'توكيلو', 'Tokelau', '{"alpha3":"TKL"}'::jsonb),
    ('TL', 'تيمور - ليشتي', 'Timor-Leste', '{"alpha3":"TLS"}'::jsonb),
    ('TM', 'تركمانستان', 'Turkmenistan', '{"alpha3":"TKM"}'::jsonb),
    ('TN', 'تونس', 'Tunisia', '{"alpha3":"TUN"}'::jsonb),
    ('TO', 'تونغا', 'Tonga', '{"alpha3":"TON"}'::jsonb),
    ('TR', 'تركيا', 'Türkiye', '{"alpha3":"TUR"}'::jsonb),
    ('TT', 'ترينيداد وتوباغو', 'Trinidad & Tobago', '{"alpha3":"TTO"}'::jsonb),
    ('TV', 'توفالو', 'Tuvalu', '{"alpha3":"TUV"}'::jsonb),
    ('TW', 'تايوان', 'Taiwan', '{"alpha3":"TWN"}'::jsonb),
    ('TZ', 'تنزانيا', 'Tanzania', '{"alpha3":"TZA"}'::jsonb),
    ('UA', 'أوكرانيا', 'Ukraine', '{"alpha3":"UKR"}'::jsonb),
    ('UG', 'أوغندا', 'Uganda', '{"alpha3":"UGA"}'::jsonb),
    ('UM', 'جزر الولايات المتحدة النائية', 'U.S. Outlying Islands', '{"alpha3":"UMI"}'::jsonb),
    ('US', 'الولايات المتحدة', 'United States', '{"alpha3":"USA"}'::jsonb),
    ('UY', 'أورغواي', 'Uruguay', '{"alpha3":"URY"}'::jsonb),
    ('UZ', 'أوزبكستان', 'Uzbekistan', '{"alpha3":"UZB"}'::jsonb),
    ('VA', 'الفاتيكان', 'Vatican City', '{"alpha3":"VAT"}'::jsonb),
    ('VC', 'سانت فنسنت وجزر غرينادين', 'St. Vincent & Grenadines', '{"alpha3":"VCT"}'::jsonb),
    ('VE', 'فنزويلا', 'Venezuela', '{"alpha3":"VEN"}'::jsonb),
    ('VG', 'جزر فيرجن البريطانية', 'British Virgin Islands', '{"alpha3":"VGB"}'::jsonb),
    ('VI', 'جزر فيرجن التابعة للولايات المتحدة', 'U.S. Virgin Islands', '{"alpha3":"VIR"}'::jsonb),
    ('VN', 'فيتنام', 'Vietnam', '{"alpha3":"VNM"}'::jsonb),
    ('VU', 'فانواتو', 'Vanuatu', '{"alpha3":"VUT"}'::jsonb),
    ('WF', 'جزر والس وفوتونا', 'Wallis & Futuna', '{"alpha3":"WLF"}'::jsonb),
    ('WS', 'ساموا', 'Samoa', '{"alpha3":"WSM"}'::jsonb),
    ('YE', 'اليمن', 'Yemen', '{"alpha3":"YEM"}'::jsonb),
    ('YT', 'مايوت', 'Mayotte', '{"alpha3":"MYT"}'::jsonb),
    ('ZA', 'جنوب أفريقيا', 'South Africa', '{"alpha3":"ZAF"}'::jsonb),
    ('ZM', 'زامبيا', 'Zambia', '{"alpha3":"ZMB"}'::jsonb),
    ('ZW', 'زيمبابوي', 'Zimbabwe', '{"alpha3":"ZWE"}'::jsonb)
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
  SELECT 'country', 1, sha256(convert_to(c, 'UTF8')), n,
         'ISO 3166-1 alpha-2 codes and names from OpenJDK 21.0.8 (Android Studio JBR build), '
         || 'CLDR v43 locale data -- java.util.Locale.getISOCountries() + '
         || 'getDisplayCountry(Locale.forLanguageTag(...)) for ar/en, getISO3Country() for '
         || 'alpha-3 (stored in extra, not the primary key). Verified live: real Arabic '
         || 'names, not English fallback -- see the S2-07 session report',
         true
  FROM canon
  RETURNING list_code, version
)
INSERT INTO ref.reference_item
  (list_code, version, item_code, label_ar, label_en, sort_ordinal, is_active, extra)
SELECT ver.list_code, ver.version, ordered.item_code, ordered.label_ar, ordered.label_en,
       ordered.sort_ordinal, true, ordered.extra
FROM ordered, ver;
