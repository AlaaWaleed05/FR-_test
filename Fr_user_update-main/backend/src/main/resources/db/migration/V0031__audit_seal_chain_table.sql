-- Layer 3 (V0003/V0004's audit.block_audit_ddl(), installed post-migrate — see
-- db/post-migrate/README.md and R-028) blocks DDL against schema audit outside a migration
-- session. V0003's own comment states the contract for any later migration that legitimately
-- needs to alter schema audit: set this flag first. Every migration in this file's group
-- (V0031-V0033) that issues DDL in schema audit does so. Found live in review: without this,
-- V0031 fails on any database where Layer 3 has already been installed — i.e. every real
-- deployment past S2-01, since Testcontainers-based tests never run db/post-migrate/ and so
-- never exercised this path.
SET LOCAL fru.migration_in_progress = 'on';

-- audit.audit_seal (V0002) stores one AGGREGATE row per seal run — a single seal_hash over
-- every chain combined. That is not enough to verify one specific chain against a seal: there
-- is no per-chain breakdown to compare against. audit_seal_chain closes that gap: one row per
-- (seal_id, chain_id), capturing exactly what AD-005 report §5 Layer 4 asks the seal to record
-- for each chain — its head_seq and head_hash at seal time — plus row_count, a second,
-- independently-scanned signal (COUNT(*) of audit_event rows for that chain, not copied from
-- head_seq) that would catch a row deleted without head_seq being adjusted even if the hash
-- chain math still lined up. See docs/sessions/2026-08-27-s2-04-audit-seal.md.
CREATE TABLE audit.audit_seal_chain (
  seal_id   bigint NOT NULL REFERENCES audit.audit_seal,
  chain_id  uuid   NOT NULL REFERENCES audit.audit_chain,
  head_seq  bigint NOT NULL,
  head_hash bytea  NOT NULL,
  row_count bigint NOT NULL,
  PRIMARY KEY (seal_id, chain_id)
);
CREATE INDEX ON audit.audit_seal_chain (chain_id, seal_id);
