-- customer.md "Data and retention": "Abandoned-profile retention: 90 days from last activity,
-- then identity images and personal data deleted. Provisional. This is a data-protection
-- question and OQ-001 is unanswered." This function closes only the ARTIFACT half of that
-- rule -- nulling app.artifact_ref.body for a profile's images once it has been abandoned
-- (app.status_code, seeded V0005) for over 90 days since last_activity_at. Full profile PII
-- nulling (name, address, national number, etc. -- app.profile.pii_purged_at, V0005) stays
-- unwritten by any code, exactly as before this migration; that is the separate, broader,
-- still-provisional decision OQ-001 gates, not part of AD-004.
--
-- Mirrors audit.seal_create()'s own precedent exactly: a plain SQL function, no scheduler,
-- callable by whatever external mechanism (cron, Windows Task Scheduler, a future backend
-- scheduler) an operator wires up. NOTHING IN THIS CODEBASE CALLS THIS PERIODICALLY --
-- documented as an operational requirement in docs/components/persistence.md, same as the
-- seal.
--
-- Never touches audit.audit_artifact: a different schema, 7-year append-only retention, and
-- this function's body contains no reference to it whatsoever -- structurally incapable of
-- reaching it, not merely well-behaved.
CREATE FUNCTION app.purge_abandoned_artifacts(p_as_of timestamptz DEFAULT clock_timestamp())
RETURNS int
LANGUAGE plpgsql AS $$
DECLARE
  v_cutoff    timestamptz := p_as_of - interval '90 days';
  v_purged    int;
BEGIN
  UPDATE app.artifact_ref ar
     SET body = NULL, state = 'purged'
   WHERE ar.state <> 'purged'
     AND (
       EXISTS (
         SELECT 1
           FROM app.identity_cycle ic
           JOIN app.profile p ON p.profile_id = ic.profile_id
          WHERE ic.cycle_id = ar.cycle_id
            AND p.status = 'abandoned'
            AND p.last_activity_at < v_cutoff
       )
       OR EXISTS (
         SELECT 1
           FROM app.profile p
          WHERE p.profile_id = ar.profile_id
            AND p.status = 'abandoned'
            AND p.last_activity_at < v_cutoff
       )
     );
  GET DIAGNOSTICS v_purged = ROW_COUNT;
  RETURN v_purged;
END
$$;

-- PostgreSQL grants EXECUTE on a new function to PUBLIC by default -- explicit REVOKE first,
-- same gap @agent-reviewer caught at S3-06 for audit.ensure_profile_chain(). Deliberately NO
-- grant to fru_app. Note what this does and does not buy: fru_app already holds UPDATE on
-- app.artifact_ref (V0010) for its ordinary writes, so it could still null one row's body and
-- state directly -- withholding EXECUTE here does not make the application incapable of
-- touching an artifact, the way the missing grant on the audit seal tables makes fru_app
-- incapable of sealing at all. What it does enforce is that the BULK, time-based sweep across
-- every abandoned profile is an admin-only operation, invoked deliberately by whoever holds
-- fru_migrator (or a future dedicated ops role) -- the same operational-only posture
-- audit.seal_create() has via fru_sealer -- not something the application can trigger on its
-- own request path, by accident or otherwise.
REVOKE EXECUTE ON FUNCTION app.purge_abandoned_artifacts(timestamptz) FROM PUBLIC;
