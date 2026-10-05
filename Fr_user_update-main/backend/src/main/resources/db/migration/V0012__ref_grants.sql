-- Grants for the ref schema, extending the same role-separation principle V0010 applied to
-- app to ref, which the AD-005 report doesn't spell out explicitly either. fru_app reads
-- published reference data (delivered to the app via a future endpoint, AD-002f) but never
-- writes it: publication is an admin-only action, out of scope for S2-03 (AD-005 report
-- §11 -- "reference-data publication is not a schema migration"). The one exception is
-- profile_reference_version, which the app itself writes at stage 11 submission (report
-- §9: "the backend writes ref.profile_reference_version rows").

GRANT USAGE ON SCHEMA ref TO fru_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fru_migrator IN SCHEMA ref
  REVOKE ALL ON TABLES FROM PUBLIC;

-- reference_list / reference_list_version / reference_item: migration-seeded now, and
-- admin-endpoint-published later. Read-only to the application.
GRANT SELECT ON ref.reference_list         TO fru_app;
GRANT SELECT ON ref.reference_list_version TO fru_app;
GRANT SELECT ON ref.reference_item         TO fru_app;

-- profile_reference_version: written once per list at stage 11 submission, never updated.
GRANT SELECT, INSERT ON ref.profile_reference_version TO fru_app;
