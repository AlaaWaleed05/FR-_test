-- Found by @agent-reviewer: app.artifact_ref's only relevant constraint is UNIQUE (cycle_id, kind)
-- (V0008), and 'signature' rows always have cycle_id NULL (V0026's own comment: a signature
-- belongs to the whole profile, not to an identity cycle) -- Postgres UNIQUE does not dedupe
-- NULLs, so customer.md Stage 11's deliberately-allowed re-submission ("the customer can redraw")
-- was silently producing N rows per profile with N different storage keys, each one another
-- retained PII artifact with no way to tell which is the one stage 12 actually reads.
--
-- A partial unique index scoped to kind='signature' closes this the same way the schema already
-- handles "one row that gets superseded by later attempts" elsewhere (app.face_result,
-- app.registry_result, both PK'd on cycle_id and upserted) -- here scoped to profile_id instead,
-- since a signature has no cycle_id to key on.
CREATE UNIQUE INDEX artifact_ref_one_signature_per_profile
  ON app.artifact_ref (profile_id)
  WHERE kind = 'signature';
