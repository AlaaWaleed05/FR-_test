-- S3-06: the first migration that lets application code create an audit chain at runtime.
--
-- Every chain used so far was pre-seeded by a migration (V0034's system/account_check chain)
-- because its subject was fixed and known ahead of time. A profile's chain_id cannot be
-- pre-seeded: the profile_id does not exist until Stage 1b's endpoint creates it, and fru_app
-- holds SELECT only on audit.audit_chain (V0004) -- deliberate, "chain creation is a
-- schema-shaped act" (docs/components/persistence.md). Widening fru_app's grant to a bare
-- INSERT would let it create ANY chain_kind with any subject_id, which is a materially
-- different (and undesirable) privilege from "create the one profile chain this request just
-- created a profile for". A SECURITY DEFINER function narrows that back down to exactly the
-- one operation the application needs, the same pattern audit.chain_append() (V0003) already
-- uses for the write fru_app cannot make directly.
--
-- ON CONFLICT DO NOTHING makes this idempotent, so it is safe to call before every audit write
-- on a profile chain rather than only the first -- a caller does not need to track "have I
-- already created this chain" separately.
--
-- This migration issues DDL (CREATE FUNCTION, GRANT) in schema audit, so it must set the R-035
-- flag first (see V0031-V0033 and docs/components/persistence.md, "A deployment-ordering fact
-- confirmed live"): audit.block_audit_ddl() blocks unguarded DDL against this schema.
SET LOCAL fru.migration_in_progress = 'on';

CREATE FUNCTION audit.ensure_profile_chain(p_profile_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = audit, pg_catalog AS $$
BEGIN
  INSERT INTO audit.audit_chain (chain_kind, subject_id, head_hash)
  VALUES ('profile', p_profile_id::text, NULL)
  ON CONFLICT (chain_kind, subject_id) DO NOTHING;
END
$$;

-- PostgreSQL grants EXECUTE on a newly created function to PUBLIC by default -- REVOKE first, or
-- every role with USAGE on schema audit (fru_sealer included, V0030) could call this and insert
-- into audit_chain, which is exactly the privilege fru_sealer's SELECT-only grant (V0033) exists
-- to withhold from it.
REVOKE EXECUTE ON FUNCTION audit.ensure_profile_chain(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.ensure_profile_chain(uuid) TO fru_app;
