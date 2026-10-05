-- S4-04 -- V0048's own comment deferred this grant to "the next session, alongside the [reference
-- read] endpoints" (AD-002f §6.1/§6.2/§6.6 were all out of scope at S4-03). This is that session:
-- GET /api/v1/reference/lists/{listCode}/{version} serves ref.reference_list_document's stored
-- bytes directly, reading as fru_app (the normal runtime role), so fru_app needs SELECT here.
--
-- Write access stays fru_migrator-only (unchanged) -- ReferenceDocumentPublisher, run only via the
-- publish-reference-documents profile, is still the sole writer.
--
-- Touches schema ref only, not audit -- no R-035 SET LOCAL fru.migration_in_progress flag needed.

GRANT SELECT ON ref.reference_list_document TO fru_app;
