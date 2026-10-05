-- S2-09: field-provenance.md Version 2 (2026-08-27) seeded as provenance matrix version 2.
-- Version 1 (seeded at V0027) covered the document as filed at S2-06/S2-08. This bump records
-- the one difference: field 22 (birth country) moved from S2 (MRZ issuer) to S3 (customer
-- entry, ISO 3166 list) -- the MRZ issuer is the document's issuing country, which reads 'SDN'
-- for every customer including those born abroad, so it never was a true birth country.

INSERT INTO app.provenance_matrix_version (version, effective_date, note) VALUES
  (2, '2026-08-27',
   'docs/journeys/field-provenance.md Version 2 -- field 22 (birth country) corrected from S2 '
   || '(MRZ issuer) to S3 (customer entry, ISO 3166 list): the MRZ issuer is the document''s '
   || 'issuing country, which reads SDN for every customer including those born abroad. See '
   || 'S2-09.');
