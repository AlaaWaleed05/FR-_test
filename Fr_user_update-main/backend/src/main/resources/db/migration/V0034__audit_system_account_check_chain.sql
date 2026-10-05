-- S3-01: the audit chain that journey Stage 1a's account checks append to.
--
-- Why a pre-seeded chain at all: fru_app holds SELECT only on audit.audit_chain (V0004) and
-- therefore cannot create one. That is deliberate -- chain creation is a schema-shaped act, not
-- an application one -- but it means any event the application writes must have a chain waiting
-- for it. Stage 1a creates no profile (docs/journeys/customer.md: "No session exists yet. Nothing
-- is persisted at this stage"), so there is no profile chain to use, and the journey's audit
-- section still requires the attempt recorded "even when no profile is created", because invalid
-- and inactive attempts are what a misuse investigation reads.
--
-- Hence one 'system' chain with a fixed subject_id. subject_id is deliberately NOT NULL here:
-- audit_chain's UNIQUE (chain_kind, subject_id) does not deduplicate NULLs in PostgreSQL, so a
-- NULL-subject system chain could be seeded twice by accident and the application would then pick
-- an arbitrary one. A named subject makes the uniqueness real.
--
-- Consequence, recorded as R-038: audit.chain_append() takes SELECT ... FOR UPDATE on the chain
-- row, so every concurrent account check in the system queues behind this one row. That is
-- acceptable at the stated scale (~100,000 accounts over 12-18 months) and is inherent to a hash
-- chain, but it is a fact about this chain, not a detail.
--
-- On Layer 3 and the R-035 trap: this migration touches schema audit but issues DML only, not
-- DDL. audit.block_audit_ddl() is registered on ddl_command_end and sql_drop, neither of which an
-- INSERT fires, so SET LOCAL fru.migration_in_progress = 'on' is NOT required here. It IS required
-- by any future migration in this schema that issues DDL -- see V0031-V0033 and R-035.

-- head_hash is passed as an explicit NULL: it is NOT NULL with no default, and the
-- audit_chain_genesis BEFORE INSERT trigger (V0004) computes it. BEFORE ROW triggers run ahead of
-- the NOT NULL check, so the row is complete by the time the constraint is evaluated.
INSERT INTO audit.audit_chain (chain_kind, subject_id, head_hash)
VALUES ('system', 'account_check', NULL);
