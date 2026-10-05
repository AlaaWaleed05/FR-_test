-- The one way application code reads artifact bytes back out of app.artifact_ref.body — an
-- invariant pushed into the database rather than trusted to every future Java caller,
-- matching this schema's existing discipline (ref.ar_fold(), audit.chain_append(),
-- audit.seal_create()). Absence (never stored, or purged by app.purge_abandoned_artifacts())
-- returns NULL -- a legitimate, expected outcome a caller can check for, e.g. a reactivated
-- abandoned profile whose artifacts were genuinely purged past 90 days and now needs a fresh
-- scan. A CHECKSUM MISMATCH is different in kind -- something is present and wrong -- and is
-- refused outright, never silently returned as bytes the caller would trust.
CREATE FUNCTION app.artifact_read(p_artifact_ref_id uuid) RETURNS bytea
LANGUAGE plpgsql AS $$
DECLARE
  v_body   bytea;
  v_sha256 bytea;
BEGIN
  SELECT body, sha256 INTO v_body, v_sha256
    FROM app.artifact_ref
   WHERE artifact_ref_id = p_artifact_ref_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'no artifact_ref row for id %', p_artifact_ref_id;
  END IF;

  IF v_body IS NULL THEN
    RETURN NULL;
  END IF;

  IF sha256(v_body) IS DISTINCT FROM v_sha256 THEN
    RAISE EXCEPTION 'artifact % failed checksum verification on read', p_artifact_ref_id;
  END IF;

  RETURN v_body;
END
$$;

-- PostgreSQL grants EXECUTE on a new function to PUBLIC by default -- the exact gap
-- @agent-reviewer caught at S3-06 for audit.ensure_profile_chain(). Explicit REVOKE first,
-- then GRANT only to the one role meant to call this (application readers).
REVOKE EXECUTE ON FUNCTION app.artifact_read(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.artifact_read(uuid) TO fru_app;
