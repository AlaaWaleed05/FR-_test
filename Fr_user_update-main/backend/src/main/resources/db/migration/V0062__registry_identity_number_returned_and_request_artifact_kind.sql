-- BL-030 / AD-002b build: the real Civil Registry adapter's two schema needs.
--
-- Layer 3 (V0003/V0004's audit.block_audit_ddl(), installed post-migrate -- see
-- db/post-migrate/README.md and R-028) blocks DDL against schema audit outside a migration
-- session. This file alters audit.audit_artifact, so the flag is set first, exactly as V0031-V0033
-- and V0036 do (R-035: Testcontainers-based tests never install Layer 3 and cannot catch its
-- absence).
SET LOCAL fru.migration_in_progress = 'on';

-- 1. The raw Civil Registry REQUEST becomes an audit artifact kind. V0002 declared
--    'civil_registry_response' (the retained raw response PROJECT_PLAN.md's Architecture requires)
--    but no request kind, because the AD-005 design only foresaw the response; the core-banking
--    pair ('omni_check_request'/'omni_check_response') was declared complete there, which is why
--    S3-02 needed no widening. audit.audit_event.artifact_id is one FK, so the request and the
--    response need one event each (S3-02's two-events-one-chain pattern): registry_lookup_requested
--    carries civil_registry_request, registry_lookup_completed carries civil_registry_response.
--    The request body is {"NID": "<national number>"} -- PII, but an artifact BODY is purgeable
--    (body_purged_at), unlike the hash-chained payload_json, which never carries the number.
--
--    Re-declared under the same name (PostgreSQL's generated name for V0002's inline CHECK), the
--    V0026 precedent.
ALTER TABLE audit.audit_artifact DROP CONSTRAINT audit_artifact_kind_check;
ALTER TABLE audit.audit_artifact ADD CONSTRAINT audit_artifact_kind_check CHECK (
  kind IN (
    'uqudo_scan_jws',
    'uqudo_face_jws',
    'civil_registry_request',
    'civil_registry_response',
    'omni_check_request',
    'omni_check_response'
  )
);

-- 2. The IDENTITY_NUMBER the registry actually returned. V0023 deliberately gave field 7 no
--    column, reading IDENTITY_NUMBER as an echo of the request; the product owner answered on
--    2026-09-04 (AD-002b, Q2) that it is read from the FOUND record. The adapter's equality guard
--    is therefore real evidence, and its input must be kept: written on the 'ok' path (where it
--    equals app.scan_result.identity_number by construction) and, more importantly, on a
--    'not_found' produced by a populated record whose IDENTITY_NUMBER differed from the number
--    sent. NULL when nothing parseable came back (a 400 page, an empty body) or from the stub.
ALTER TABLE app.registry_result ADD COLUMN identity_number_returned text;

COMMENT ON COLUMN app.registry_result.identity_number_returned IS
  'The IDENTITY_NUMBER read from the record the Civil Registry returned (AD-002b: read from the found record, not echoed). Equals app.scan_result.identity_number when state = ok; on a not_found caused by a differing value it holds that value as the guard''s evidence (BL-030); NULL when no parseable record came back or the lookup was stubbed. V0062.';
