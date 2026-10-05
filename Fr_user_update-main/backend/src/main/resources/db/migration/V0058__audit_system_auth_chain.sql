-- S4-05 (AD-002e): the audit chain a FAILED sign-in appends to. A successful sign-in, sign-out or
-- password change audits to the operator's own chain via the already-existing
-- audit.ensure_operator_chain(user_id) (V0047) -- this migration is for the case where no known
-- operator identity exists yet.
--
-- Same reasoning as V0034 (account_check), not V0047 (ensure_operator_chain): a failed sign-in's
-- "actor" may be nonexistent (an unknown username) or, even when the username matches a real
-- account, is not yet an authenticated identity worth giving its own chain -- creating one keyed by
-- attacker-supplied input would let unauthenticated traffic insert unbounded rows into an
-- append-only, uncleanable schema (R1/R5 in docs/sessions/2026-09-02-research-ad-002e-auth.md).
-- One pre-seeded 'system'/'auth' chain, fixed at migration time, closes that off entirely: fru_app
-- cannot create a chain (V0004 grants it SELECT only on audit.audit_chain), so there is no
-- attacker-reachable path to a second one.
--
-- DML only (an INSERT), not DDL, in schema audit -- audit.block_audit_ddl() fires on
-- ddl_command_end/sql_drop, neither of which an INSERT triggers, so SET LOCAL
-- fru.migration_in_progress = 'on' is NOT required here. V0034 is the exact precedent and states
-- this same reasoning for itself.

-- head_hash is passed as an explicit NULL: it is NOT NULL with no default, and the
-- audit_chain_genesis BEFORE INSERT trigger (V0004) computes it before the constraint is checked.
INSERT INTO audit.audit_chain (chain_kind, subject_id, head_hash)
VALUES ('system', 'auth', NULL);
