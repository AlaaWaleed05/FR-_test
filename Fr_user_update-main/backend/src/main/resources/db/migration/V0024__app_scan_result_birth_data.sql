-- app.scan_result additions per docs/journeys/field-provenance.md (S2-06/S2-08, R-032).

ALTER TABLE app.scan_result
  -- Field 23: Uqudo's placeOfBirth, confirmed to be the city (S2). The "else free text" S3
  -- fallback belongs on app.profile_customer_data instead -- see V0025's birth_city_text,
  -- alongside birth_state_text for the exact same reason.
  ADD COLUMN birth_city text,
  -- Field 22: field-provenance.md cites the SAME source -- "the MRZ issuer field" -- for both
  -- birth country (field 22) and issuing country (field 48, already app.scan_result.
  -- issuing_country since V0008). Rather than an independently-written second column that
  -- could silently drift from field 48's, this mirrors it exactly: always equal,
  -- self-documenting that they are the same underlying MRZ value.
  --
  -- NOTE the alphabet: MRZ `issuer` under ICAO 9303 is a three-letter code (e.g. 'SDN'), NOT
  -- the two-letter ISO 3166-1 alpha-2 `item_code` the country reference list (V0022) and
  -- country_of_residence_code/home_country_code/work_country_code (V0025) all use. This
  -- column is therefore NOT FK'd to ref.reference_item and cannot be compared against those
  -- other *_code columns with a plain '=' -- an app-layer comparison against birth_state_code
  -- (V0025) needing "is birth country Sudan" must test 'SDN', not 'SD'.
  ADD COLUMN birth_country_code text GENERATED ALWAYS AS (issuing_country) STORED;
