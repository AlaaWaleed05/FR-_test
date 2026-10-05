-- S4-01: the first operator-side application code. `audit.audit_chain.chain_kind` already
-- accepts 'operator' (V0002's CHECK: 'profile', 'operator', 'system'), anticipating exactly this
-- use, but nothing has ever created one -- every write so far has been 'profile' (Stage 1b
-- onward, via audit.ensure_profile_chain, V0036) or the pre-seeded 'system'/'account_check'
-- singleton (V0034).
--
-- An operator search touches zero or many profiles depending on the filter, so it has no single
-- profile chain to append to, and it is not a fixed, pre-known subject the way 'account_check' is
-- -- operator ids come from AD-002e's (not yet built) directory, unknown at migration time. This
-- mirrors V0036's exact reasoning for why a profile chain needs a runtime-created function rather
-- than a migration-time seed: one chain per operator, created on first use, so operators'
-- searches serialise only against themselves (chain_append() takes SELECT ... FOR UPDATE on the
-- chain row) rather than against every other operator's searches on one shared row -- the R-038
-- bottleneck V0034's own comment accepts for account_check, avoided here since it costs nothing
-- extra to avoid.
--
-- Same shape as V0036's audit.ensure_profile_chain: SECURITY DEFINER (fru_app holds SELECT only
-- on audit.audit_chain, V0004 -- chain creation is a schema-shaped act), idempotent ON CONFLICT
-- DO NOTHING (safe to call before every operator-attributed audit write, not only the first), and
-- EXECUTE explicitly REVOKEd from PUBLIC before being GRANTed to fru_app only -- PostgreSQL grants
-- EXECUTE on a new function to PUBLIC by default, and without the REVOKE, fru_sealer (held to
-- SELECT-only on audit_chain by V0033, precisely so it cannot write what it later seals) could
-- call this function and insert chain rows. Found by @agent-reviewer at S3-06 for the profile
-- case; applied here from the start rather than left to be found again.
--
-- This migration issues DDL (CREATE FUNCTION, GRANT) in schema audit, so it must set the R-035
-- flag first (see V0031-V0033/V0036 and docs/components/persistence.md, "A deployment-ordering
-- fact confirmed live"): audit.block_audit_ddl() blocks unguarded DDL against this schema.
SET LOCAL fru.migration_in_progress = 'on';

CREATE FUNCTION audit.ensure_operator_chain(p_operator_id text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = audit, pg_catalog AS $$
BEGIN
  INSERT INTO audit.audit_chain (chain_kind, subject_id, head_hash)
  VALUES ('operator', p_operator_id, NULL)
  ON CONFLICT (chain_kind, subject_id) DO NOTHING;
END
$$;

REVOKE EXECUTE ON FUNCTION audit.ensure_operator_chain(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.ensure_operator_chain(text) TO fru_app;
